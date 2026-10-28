// Routes used by the Morphly Android app (FirebaseBackend.kt / StreamActivity.kt).
import {firebaseRuntime, FIREBASE_PROJECT} from './firebase-runtime.js';
import {ensureFirebaseDefaults, requireUser, bearerToken, formatAccount} from './firebase.js';
import {createOperations} from './firebase-operations.js';
import {createXmaxAndroidKey} from './xmax.js';
import {cleanString, requireMethod} from './http.js';

export const CREDITS_PER_SECOND = 2;
export const XMAX_MODEL = 'x2.1';
const MAX_SESSION_SECONDS = 1800;
const err = (message, status) => Object.assign(new Error(message), {status});
const sessions = db => db.collection('stream_session_sm');

async function settings(db, key) {
  return (await db.collection('app_setting_sm').doc(key).get()).data()?.value || {};
}

export async function publicConfig(req, res, deps = {}) {
  if (!requireMethod(req, res, 'GET')) return;
  const {db} = deps.runtime || firebaseRuntime();
  if (!deps.runtime) await ensureFirebaseDefaults();
  const [global, streaming, referral] = await Promise.all(
    ['global_config', 'streaming_availability', 'referral_program'].map(k => settings(db, k)));
  const env = deps.env || process.env;
  return res.status(200).json({
    backend: 'firebase', firebaseProjectId: FIREBASE_PROJECT,
    firebaseConfig: {projectId: FIREBASE_PROJECT, apiKey: String(env.FIREBASE_API_KEY || ''),
      authDomain: `${FIREBASE_PROJECT}.firebaseapp.com`},
    realtimeProvider: 'xmax',
    notification: global.notification || '',
    streamingEnabled: streaming.enabled !== false,
    referralEnabled: referral.enabled !== false,
    signupCredits: Number(referral.signup_credits ?? 50),
    referralPurchaseReward: Number(referral.purchase_reward ?? 250),
  });
}

export async function firebaseRegister(req, res) {
  if (!requireMethod(req, res, 'POST')) return;
  const token = bearerToken(req);
  if (!token) throw err('Authentication required', 401);
  const {auth, db} = firebaseRuntime();
  let record;
  try { const claims = await auth.verifyIdToken(token, true); record = await auth.getUser(claims.uid); }
  catch { throw err('Session is invalid or expired', 401); }
  await ensureFirebaseDefaults();
  const ops = createOperations(db);
  await ops.provision(record, {name: cleanString(req.body?.name, 120), phone: cleanString(req.body?.phone, 40),
    referredByCode: cleanString(req.body?.referralCode, 40)});
  return res.status(200).json({success: true, user: formatAccount(await ops.summary(record.uid))});
}

export async function wallet(req, res) {
  if (!requireMethod(req, res, 'GET', 'POST')) return;
  const {appUser} = await requireUser(req);
  const account = formatAccount(appUser);
  return res.status(200).json({...account, credits: account.balance, used: account.creditsUsed, user: account});
}

export async function creditPackages(req, res) {
  if (!requireMethod(req, res, 'GET')) return;
  await ensureFirebaseDefaults();
  const rows = await firebaseRuntime().db.collection('credit_package_sm').where('is_active', '==', true).get();
  const packages = rows.docs.map(d => d.data()).sort((a, b) => Number(a.sort_order || 0) - Number(b.sort_order || 0))
    .map(p => ({id: p.code || p.id, name: p.name, description: p.description || '', credits: Number(p.credits),
      price: Number(p.price), priceNGN: Number(p.price), currency: p.currency || 'NGN', timeLabel: p.time_label || ''}));
  return res.status(200).json({packages});
}

export async function startSession(req, res, deps = {}) {
  if (!requireMethod(req, res, 'POST')) return;
  const provider = String(req.body?.provider || 'xmax');
  if (provider !== 'xmax') return res.status(400).json({error: 'UNSUPPORTED_PROVIDER', details: 'Only XMax is available.'});
  const {appUser} = await (deps.requireUser || requireUser)(req);
  const db = deps.db || firebaseRuntime().db;
  if ((await settings(db, 'streaming_availability')).enabled === false) {
    return res.status(503).json({error: 'STREAMING_DISABLED', details: 'Live streaming is currently disabled.'});
  }
  const balance = Number(appUser.balance || 0);
  const affordable = Math.floor(balance / CREDITS_PER_SECOND);
  if (affordable < 1) return res.status(402).json({error: 'INSUFFICIENT_CREDITS', details: 'Insufficient credits'});
  const maxSeconds = Math.min(MAX_SESSION_SECONDS, affordable);
  const env = deps.env || process.env;
  const key = await (deps.createKey || createXmaxAndroidKey)({apiKey: String(env.XMAX_ANDROID_API_KEY || '').trim(), maxSeconds});
  if (key.error) return res.status(key.error.error === 'XMAX_NOT_CONFIGURED' ? 503 : 502).json(key.error);
  const nowMs = Date.now(), ref = sessions(db).doc();
  await ref.set({id: ref.id, user_id: appUser.id, provider: 'xmax', model: XMAX_MODEL, status: 'active',
    platform: cleanString(req.body?.platform, 20) || 'android', installation_id: cleanString(req.body?.installationId, 120) || null,
    max_seconds: key.sessionLimit || maxSeconds, started_epoch: Math.floor(nowMs / 1000), billed_epochs: [],
    billed_seconds: 0, credits_used: 0, created_at: new Date(nowMs).toISOString(), updated_at: new Date(nowMs).toISOString()});
  return res.status(200).json({allowed: true, provider: 'xmax', token: key.token, sessionId: ref.id, model: XMAX_MODEL,
    maxSeconds: key.sessionLimit || maxSeconds, expiresAt: key.expiresAt, serverNow: nowMs, remainingCredits: balance});
}

// Bills each distinct second of video once (2 credits/second), atomically with the wallet.
export async function recordUsage(db, userId, body, close = false) {
  const epochs = body?.epochSeconds ?? [];
  if (!Array.isArray(epochs) || epochs.length > 60 || epochs.some(v => !Number.isSafeInteger(v))) {
    throw err('Invalid video usage timestamps.', 400);
  }
  const sessionId = cleanString(body?.sessionId, 120);
  if (!sessionId) throw err('sessionId is required', 400);
  const sRef = sessions(db).doc(sessionId), wRef = db.collection('wallet_sm').doc(String(userId));
  return db.runTransaction(async tx => {
    const [s, w] = await Promise.all([tx.get(sRef), tx.get(wRef)]);
    const balance = Number(w.data()?.balance || 0);
    if (!s.exists || s.data().user_id !== userId) return {shouldStop: true, reason: 'session_not_found', remainingCredits: balance};
    const session = s.data();
    if (session.status !== 'active') return {shouldStop: true, reason: 'session_closed', remainingCredits: balance,
      totalBillableSeconds: session.billed_seconds || 0};
    const nowEpoch = Math.floor(Date.now() / 1000), billed = new Set(session.billed_epochs || []);
    const fresh = [...new Set(epochs)].filter(e => e >= session.started_epoch - 5 && e <= nowEpoch + 5 && !billed.has(e))
      .slice(0, Math.max(0, session.max_seconds - billed.size));
    const payable = Math.min(fresh.length, Math.floor(balance / CREDITS_PER_SECOND));
    const charge = payable * CREDITS_PER_SECOND, remaining = balance - charge, stamp = new Date().toISOString();
    const accepted = fresh.slice(0, payable), totalSeconds = billed.size + accepted.length;
    const outOfCredits = remaining < CREDITS_PER_SECOND || payable < fresh.length;
    const done = close || outOfCredits || totalSeconds >= session.max_seconds;
    if (charge > 0) {
      tx.update(wRef, {balance: remaining, used: Number(w.data()?.used || 0) + charge, updated_at: stamp});
      tx.create(db.collection('wallet_ledger_sm').doc(), {user_id: userId, delta: -charge, balance_after: remaining,
        entry_type: 'stream_usage', session_id: sessionId, seconds: accepted.length, created_at: stamp});
    }
    tx.update(sRef, {billed_epochs: [...billed, ...accepted], billed_seconds: totalSeconds,
      credits_used: Number(session.credits_used || 0) + charge, updated_at: stamp,
      ...(done ? {status: 'ended', ended_at: stamp, end_reason: close ? 'client' : outOfCredits ? 'no_credits' : 'max_duration'} : {})});
    return {recordedSeconds: accepted.length, totalBillableSeconds: totalSeconds, creditsCharged: charge,
      remainingCredits: remaining, shouldStop: done, ...(outOfCredits ? {reason: 'no_credits'} : {})};
  });
}

export async function heartbeat(req, res, close = false) {
  if (!requireMethod(req, res, 'POST')) return;
  const {appUser} = await requireUser(req);
  return res.status(200).json(await recordUsage(firebaseRuntime().db, appUser.id, req.body || {}, close));
}

export async function sessionStatus(req, res) {
  if (!requireMethod(req, res, 'GET', 'POST')) return;
  const {appUser} = await requireUser(req);
  const url = new URL(req.url, 'https://localhost');
  const id = cleanString(req.body?.sessionId || url.searchParams.get('sessionId'), 120);
  if (!id) return res.status(400).json({error: 'sessionId is required'});
  const s = await sessions(firebaseRuntime().db).doc(id).get();
  if (!s.exists || s.data().user_id !== appUser.id) return res.status(404).json({error: 'Session not found'});
  const d = s.data();
  return res.status(200).json({sessionId: id, provider: d.provider, status: d.status, active: d.status === 'active',
    billedSeconds: d.billed_seconds || 0, creditsUsed: d.credits_used || 0, maxSeconds: d.max_seconds,
    remainingCredits: Number(appUser.balance || 0), serverNow: Date.now()});
}

// Preview-only read-only sanity check: counts of existing accounts (no document contents).
export async function diag(req, res) {
  if ((process.env.VERCEL_ENV || '') === 'production') return res.status(404).json({error: 'Not found'});
  const {db} = firebaseRuntime();
  const count = async n => (await db.collection(n).count().get()).data().count;
  const [users, wallets, funded] = await Promise.all([count('user_sm'), count('wallet_sm'),
    db.collection('wallet_sm').where('balance', '>', 0).count().get().then(r => r.data().count)]);
  const {auth} = firebaseRuntime();
  const authUsers = (await auth.listUsers(1000)).users.length;
  return res.status(200).json({project: FIREBASE_PROJECT, user_sm: users, wallet_sm: wallets, wallets_with_balance: funded, auth_users_first_page: authUsers});
}
