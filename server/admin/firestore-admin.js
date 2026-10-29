// Morphly admin API (Firestore).
// Auth: POST /login checks the password with Firebase first, then requires email_verified, an email in
// ADMIN_EMAILS and the custom claim admin=true. It answers with an 8h Firebase *session cookie*
// (__Host-, HttpOnly, Secure, SameSite=Strict); no ID/refresh token ever reaches the browser.
// Every other route re-verifies the cookie (with revocation check) and the same three conditions.
// State-changing calls also need the X-Morphly-Admin header and a same-origin Origin (CSRF).
import {createHash} from 'node:crypto';
import {firebaseRuntime, firebaseAuthRequest} from '../shared/firebase-runtime.js';
import {ensureFirebaseDefaults} from '../shared/firebase.js';
import {cleanString, clientIp} from '../shared/http.js';
import {recordError} from '../shared/error-log.js';
import {hit, peek, clear, tooMany} from '../shared/rate-limit.js';

const fail = (message, status = 400) => {throw Object.assign(new Error(message), {status, expose: true});};
const DAY = 86400000;
const iso = () => new Date().toISOString();
const ms = v => (typeof v === 'number' ? v : v?.toMillis ? v.toMillis() : Date.parse(v || '')) || 0;
const dayKey = t => new Date(t).toISOString().slice(0, 10);
export const adminEmails = () => String(process.env.ADMIN_EMAILS || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
export const COOKIE = '__Host-morphly_admin';
const SESSION_MS = 8 * 3600 * 1000;
const MIN_PACKAGE_PRICE = () => Math.max(1, Number(process.env.MIN_PACKAGE_PRICE || 100));
const GENERIC_LOGIN = 'Invalid email or password';

export function isAdminClaims(claims) {
  const email = String(claims?.email || '').toLowerCase();
  return Boolean(email && claims.email_verified === true && claims.admin === true && adminEmails().includes(email));
}
function readCookie(req, name) {
  for (const part of String(req.headers?.cookie || '').split(';')) {
    const i = part.indexOf('='); if (i > 0 && part.slice(0, i).trim() === name) return decodeURIComponent(part.slice(i + 1).trim());
  }
  return '';
}
const setCookie = (res, value, maxAgeSeconds) => res.setHeader('Set-Cookie',
  `${COOKIE}=${value}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAgeSeconds}`);

export async function requireAdmin(req) {
  const cookie = readCookie(req, COOKIE);
  if (!cookie) fail('Authentication required', 401);
  let claims;
  try {claims = await firebaseRuntime().auth.verifySessionCookie(cookie, true);}
  catch {fail('Session is invalid or expired', 401);}
  if (!isAdminClaims(claims)) fail('Administrator access required', 403);
  return {uid: claims.uid, email: String(claims.email).toLowerCase()};
}

export function checkCsrf(req) {
  if (['GET', 'HEAD', 'OPTIONS'].includes(req.method)) return;
  if (req.headers?.['x-morphly-admin'] !== '1') fail('Request blocked', 403);
  const origin = req.headers?.origin, host = req.headers?.['x-forwarded-host'] || req.headers?.host;
  if (origin && origin !== `https://${host}`) fail('Request blocked', 403);
  const site = req.headers?.['sec-fetch-site'];
  if (site && !['same-origin', 'none'].includes(site)) fail('Request blocked', 403);
}

async function all(name) {return (await firebaseRuntime().db.collection(name).get()).docs.map(d => ({id: d.id, ...d.data()}));}
function series(days, rows, timeOf, valueOf = () => 1) {
  const start = Date.now() - (days - 1) * DAY, map = new Map();
  for (let i = 0; i < days; i++) map.set(dayKey(start + i * DAY), 0);
  for (const r of rows) {const k = dayKey(timeOf(r) || 0); if (map.has(k)) map.set(k, map.get(k) + valueOf(r));}
  return [...map].map(([date, value]) => ({date, value}));
}
// Audit writes are awaited. A failed write is logged to errors_sm and reported back as audit_logged:false.
async function audit(admin, action, entity, data, req) {
  try {
    await firebaseRuntime().db.collection('admin_audit_sm').add({actor_email: admin?.email || null, actor_uid: admin?.uid || null,
      action, entity: entity || null, data: data || null, ip: req ? clientIp(req) : null,
      user_agent: req ? cleanString(req.headers?.['user-agent'], 200) : null, created_at: iso(), createdAt: Date.now()});
    return true;
  } catch (error) {
    console.error('admin audit write failed:', action, error?.message);
    await recordError({source: 'admin', level: 'error', message: `Audit log write failed for ${action}: ${error?.message}`, context: 'admin_audit_sm'});
    return false;
  }
}
const verified = p => p.status === 'verified';
const sessionSeconds = s => {const a = ms(s.createdAt || s.created_at), b = ms(s.ended_at || s.heartbeat_at); return a && b > a ? Math.round((b - a) / 1000) : 0;};

async function login(req, res) {
  const email = cleanString(req.body?.email, 320).toLowerCase(), password = String(req.body?.password || '');
  if (!email || !password || password.length > 256) fail('Email and password are required');
  const {db, auth} = firebaseRuntime(), ip = clientIp(req);
  const lock = await peek(db, `admfail:${email}`);
  if (lock.count >= 10) {await audit({email}, 'login.locked', email, null, req); throw tooMany(lock.retryAfterSeconds);}
  const [pair, perIp] = await Promise.all([hit(db, `admlogin:${ip}:${email}`, {limit: 5, windowMs: 15 * 60000}),
    hit(db, `admlogin:${ip}`, {limit: 20, windowMs: 3600000})]);
  if (!pair.allowed || !perIp.allowed) {
    await audit({email}, 'login.rate_limited', email, null, req);
    throw tooMany(Math.max(pair.retryAfterSeconds, perIp.retryAfterSeconds));
  }
  const denied = async reason => {
    await hit(db, `admfail:${email}`, {limit: 10, windowMs: 3600000});
    await audit({email}, 'login.failed', email, {reason}, req);
    fail(GENERIC_LOGIN, 401);
  };
  let r;
  try {r = await firebaseAuthRequest('signInWithPassword', {email, password, returnSecureToken: true});}
  catch {return denied('bad_credentials');}
  const claims = await auth.verifyIdToken(r.idToken, true).catch(() => null);
  if (!claims || !isAdminClaims(claims)) return denied(!claims ? 'token' : 'not_admin');
  const cookie = await auth.createSessionCookie(r.idToken, {expiresIn: SESSION_MS});
  setCookie(res, cookie, SESSION_MS / 1000);
  await clear(db, `admlogin:${ip}:${email}`);
  const logged = await audit({email, uid: claims.uid}, 'login.success', claims.uid, null, req);
  return {email, expiresAt: new Date(Date.now() + SESSION_MS).toISOString(), audit_logged: logged};
}

async function logout(req, res) {
  setCookie(res, '', 0);
  let admin = null;
  try {admin = await requireAdmin(req);} catch {return {success: true};}
  await firebaseRuntime().auth.revokeRefreshTokens(admin.uid);
  const logged = await audit(admin, 'logout', admin.uid, null, req);
  return {success: true, audit_logged: logged};
}

function buyerStats(payments) {
  const byUser = new Map();
  for (const p of payments.filter(verified).sort((a, b) => ms(a.verified_at || a.created_at) - ms(b.verified_at || b.created_at))) {
    const list = byUser.get(p.user_id) || []; list.push(p); byUser.set(p.user_id, list);
  }
  const first = [], repeat = [];
  for (const [uid, list] of byUser) {
    const row = {user_id: uid, email: list[0].user_email_snapshot, purchases: list.length,
      total_spent: list.reduce((n, p) => n + Number(p.expected_amount || 0), 0), currency: list[0].currency,
      first_package: list[0].package_id, first_purchase_at: list[0].verified_at || list[0].created_at,
      last_purchase_at: list.at(-1).verified_at || list.at(-1).created_at, packages: [...new Set(list.map(p => p.package_id))]};
    (list.length > 1 ? repeat : first).push(row);
  }
  return {byUser, first, repeat};
}

async function overview(req) {
  const days = Math.min(Math.max(Number(req.query?.days) || 30, 7), 90);
  const [users, wallets, payments, sessions, ledger] = await Promise.all(
    ['user_sm', 'wallet_sm', 'payment_transaction_sm', 'stream_session_sm', 'wallet_ledger_sm'].map(all));
  const errors24h = (await firebaseRuntime().db.collection('errors_sm').where('createdAt', '>', Date.now() - DAY).count().get()).data().count;
  const now = Date.now(), today = dayKey(now), paid = payments.filter(verified);
  const usage = ledger.filter(l => l.entry_type === 'usage');
  const {first, repeat} = buyerStats(payments);
  const activeIds = new Set(sessions.filter(s => now - ms(s.createdAt || s.created_at) < 7 * DAY).map(s => s.user_id));
  const prevWindow = r => {const t = ms(r); return t > now - 2 * days * DAY && t <= now - days * DAY;};
  const inWindow = r => ms(r) > now - days * DAY;
  return {
    kpis: {
      total_users: users.length,
      new_signups: users.filter(u => inWindow(u.created_at)).length,
      new_signups_prev: users.filter(u => prevWindow(u.created_at)).length,
      active_users: activeIds.size,
      revenue: paid.filter(p => inWindow(p.verified_at || p.created_at)).reduce((n, p) => n + Number(p.expected_amount || 0), 0),
      revenue_prev: paid.filter(p => prevWindow(p.verified_at || p.created_at)).reduce((n, p) => n + Number(p.expected_amount || 0), 0),
      revenue_all_time: paid.reduce((n, p) => n + Number(p.expected_amount || 0), 0),
      credits_sold: paid.reduce((n, p) => n + Number(p.credits || 0), 0),
      credits_used: wallets.reduce((n, w) => n + Number(w.used || 0), 0),
      credits_outstanding: wallets.reduce((n, w) => n + Number(w.balance || 0), 0),
      streams_today: sessions.filter(s => dayKey(ms(s.createdAt || s.created_at)) === today).length,
      live_streams: sessions.filter(s => ['starting', 'running'].includes(s.status) && now - ms(s.heartbeat_at || s.createdAt) < 120000).length,
      first_time_buyers: first.length + repeat.length, repeat_buyers: repeat.length,
      errors_24h: errors24h,
      currency: paid[0]?.currency || 'NGN',
    },
    series: {
      signups: series(days, users, u => ms(u.created_at)),
      revenue: series(days, paid, p => ms(p.verified_at || p.created_at), p => Number(p.expected_amount || 0)),
      credits_used: series(days, usage, l => ms(l.created_at), l => -Number(l.delta || 0)),
      streams: series(days, sessions, s => ms(s.createdAt || s.created_at)),
    },
    recent_signups: users.sort((a, b) => ms(b.created_at) - ms(a.created_at)).slice(0, 6)
      .map(u => ({id: u.id, email: u.email, name: u.name, created_at: u.created_at})),
    recent_purchases: paid.sort((a, b) => ms(b.verified_at) - ms(a.verified_at)).slice(0, 6),
  };
}

async function users(req) {
  const [list, wallets, sessions, payments, referrals] = await Promise.all(['user_sm', 'wallet_sm', 'stream_session_sm', 'payment_transaction_sm', 'referral_sm'].map(all));
  const w = new Map(wallets.map(x => [x.id, x])), last = new Map(), plan = new Map(), refBy = new Map(referrals.map(r => [r.referred_user_id, r.referrer_user_id]));
  for (const s of sessions) last.set(s.user_id, Math.max(last.get(s.user_id) || 0, ms(s.heartbeat_at || s.createdAt || s.created_at)));
  for (const p of payments.filter(verified).sort((a, b) => ms(a.verified_at) - ms(b.verified_at))) plan.set(p.user_id, p.package_id);
  const q = cleanString(req.query?.q, 200).toLowerCase();
  const emailById = new Map(list.map(u => [u.id, u.email]));
  return {users: list.filter(u => !q || `${u.email} ${u.name} ${u.id} ${u.referral_code}`.toLowerCase().includes(q))
    .sort((a, b) => ms(b.created_at) - ms(a.created_at))
    .map(u => ({id: u.id, email: u.email, name: u.name || '', status: u.status || 'active', role: u.role,
      balance: Number(w.get(u.id)?.balance || 0), used: Number(w.get(u.id)?.used || 0), plan: plan.get(u.id) || null,
      referral_code: u.referral_code || '', referred_by: emailById.get(refBy.get(u.id)) || null, device_id: u.device_id || '',
      created_at: u.created_at, last_active: last.get(u.id) ? new Date(last.get(u.id)).toISOString() : (u.updated_at || null)}))};
}

async function adjustCredits(req, admin) {
  const uid = cleanString(req.body?.uid, 128), delta = Number(req.body?.delta), reason = cleanString(req.body?.reason, 200);
  if (!uid || !Number.isSafeInteger(delta) || delta === 0 || Math.abs(delta) > 10000000) fail('A user and a non-zero whole credit amount are required');
  const {db} = firebaseRuntime();
  const key = cleanString(req.body?.idempotencyKey, 80) || `${Date.now()}`;
  const receipt = db.collection('wallet_ledger_sm').doc(createHash('sha256').update(`admin:${uid}:${key}`).digest('hex'));
  const balance = await db.runTransaction(async tx => {
    const ref = db.collection('wallet_sm').doc(uid), [wallet, existing] = await Promise.all([tx.get(ref), tx.get(receipt)]);
    if (existing.exists) return wallet.data()?.balance;
    const next = Number(wallet.data()?.balance || 0) + delta;
    if (next < 0) fail('Balance cannot go below zero');
    if (wallet.exists) tx.update(ref, {balance: next, updated_at: iso()});
    else tx.set(ref, {id: uid, user_id: uid, balance: next, used: 0, created_at: iso(), updated_at: iso()});
    tx.create(receipt, {user_id: uid, delta, balance_after: next, entry_type: 'admin_adjustment', reason, actor_email: admin.email, created_at: iso()});
    return next;
  });
  const logged = await audit(admin, 'credits.adjust', uid, {delta, reason}, req);
  return {success: true, balance, audit_logged: logged};
}

async function setStatus(req, admin) {
  const uid = cleanString(req.body?.uid, 128), status = cleanString(req.body?.status, 20);
  if (!uid || !['active', 'suspended'].includes(status)) fail('uid and status (active|suspended) are required');
  if (uid === admin.uid) fail('You cannot disable your own account');
  const {db, auth} = firebaseRuntime();
  await db.collection('user_sm').doc(uid).update({status, updated_at: iso()});
  await auth.updateUser(uid, {disabled: status !== 'active'});
  if (status !== 'active') await auth.revokeRefreshTokens(uid);
  const logged = await audit(admin, 'user.status', uid, {status}, req);
  return {success: true, status, audit_logged: logged};
}

async function purchases() {
  const payments = await all('payment_transaction_sm');
  const {first, repeat} = buyerStats(payments), paid = payments.filter(verified);
  const byPackage = {};
  for (const p of paid) byPackage[p.package_id] = (byPackage[p.package_id] || 0) + Number(p.expected_amount || 0);
  return {
    purchases: payments.sort((a, b) => ms(b.created_at) - ms(a.created_at)).slice(0, 500).map(p => ({id: p.id, email: p.user_email_snapshot,
      user_id: p.user_id, package_id: p.package_id, credits: p.credits, amount: p.expected_amount, currency: p.currency, status: p.status,
      provider: p.provider, tx_ref: p.tx_ref, created_at: p.created_at, verified_at: p.verified_at || null})),
    first_time_buyers: first.concat(repeat).map(r => ({...r, purchases: 1})).sort((a, b) => ms(b.first_purchase_at) - ms(a.first_purchase_at)),
    only_once_buyers: first, repeat_buyers: repeat.sort((a, b) => b.purchases - a.purchases),
    revenue_series: series(30, paid, p => ms(p.verified_at || p.created_at), p => Number(p.expected_amount || 0)),
    revenue_by_package: Object.entries(byPackage).map(([id, value]) => ({id, value})),
    totals: {verified: paid.length, pending: payments.filter(p => p.status === 'pending').length,
      failed: payments.filter(p => p.status === 'failed').length, revenue: paid.reduce((n, p) => n + Number(p.expected_amount || 0), 0)},
  };
}

async function performance() {
  const [sessions, users] = await Promise.all([all('stream_session_sm'), all('user_sm')]);
  const email = new Map(users.map(u => [u.id, u.email])), done = sessions.filter(s => s.status === 'completed');
  const durations = done.map(sessionSeconds).filter(Boolean);
  const failed = sessions.filter(s => s.status === 'failed');
  return {
    totals: {sessions: sessions.length, completed: done.length, failed: failed.length,
      failure_rate: sessions.length ? failed.length / sessions.length : 0,
      avg_duration_seconds: durations.length ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length) : 0,
      total_stream_seconds: durations.reduce((a, b) => a + b, 0),
      credits_consumed: sessions.reduce((n, s) => n + Number(s.consumed_credits || 0), 0)},
    sessions_series: series(14, sessions, s => ms(s.createdAt || s.created_at)),
    failures_series: series(14, failed, s => ms(s.createdAt || s.created_at)),
    sessions: sessions.sort((a, b) => ms(b.createdAt || b.created_at) - ms(a.createdAt || a.created_at)).slice(0, 200)
      .map(s => ({id: s.id, email: email.get(s.user_id) || s.user_id, status: s.status, provider: s.provider, model: s.model, tier: s.tier || (s.provider === 'vidu' ? 'pro' : 'plus'), rate: Number(s.rate || 0),
        started_at: s.created_at, ended_at: s.ended_at || null, duration_seconds: sessionSeconds(s), credits: Number(s.consumed_credits || 0),
        device_id: s.device_id || ''})),
  };
}

async function errors(req, admin) {
  const {db} = firebaseRuntime();
  if (req.method === 'DELETE') {
    const id = cleanString(req.query?.id, 128);
    let deleted = 0;
    if (id === 'all') {
      if (cleanString(req.query?.confirm, 40) !== 'DELETE-ALL-ERRORS') fail('Type DELETE-ALL-ERRORS to confirm');
      for (;;) {
        const s = await db.collection('errors_sm').limit(400).get();
        if (s.empty) break;
        const batch = db.batch(); s.docs.forEach(d => batch.delete(d.ref)); await batch.commit(); deleted += s.size;
      }
    } else if (id) {await db.collection('errors_sm').doc(id).delete(); deleted = 1;} else fail('id is required');
    const logged = await audit(admin, id === 'all' ? 'errors.delete_all' : 'errors.delete', id, {deleted}, req);
    return {success: true, deleted, audit_logged: logged};
  }
  const rows = (await db.collection('errors_sm').orderBy('createdAt', 'desc').limit(300).get()).docs.map(d => ({id: d.id, ...d.data()}));
  return {errors: rows, series: series(14, rows, e => ms(e.createdAt)),
    by_source: rows.reduce((m, e) => ({...m, [e.source]: (m[e.source] || 0) + 1}), {})};
}

async function referrals() {
  const [refs, users, settings] = await Promise.all([all('referral_sm'), all('user_sm'),
    firebaseRuntime().db.collection('app_setting_sm').doc('referral_program').get()]);
  const u = new Map(users.map(x => [x.id, x])), counts = new Map();
  for (const r of refs) counts.set(r.referrer_user_id, (counts.get(r.referrer_user_id) || 0) + 1);
  return {program: settings.data()?.value || {},
    referrals: refs.sort((a, b) => ms(b.created_at) - ms(a.created_at)).map(r => ({id: r.id, referred: u.get(r.referred_user_id)?.email || r.referred_user_id,
      referrer: u.get(r.referrer_user_id)?.email || r.referrer_user_id, code: r.referral_code, status: r.status, created_at: r.created_at, converted_at: r.converted_at || null})),
    top_referrers: [...counts].sort((a, b) => b[1] - a[1]).slice(0, 20).map(([id, n]) => ({email: u.get(id)?.email || id, code: u.get(id)?.referral_code, referrals: n,
      converted: refs.filter(r => r.referrer_user_id === id && r.status === 'converted').length})),
    totals: {referrals: refs.length, converted: refs.filter(r => r.status === 'converted').length}};
}

async function packages(req, admin) {
  const {db} = firebaseRuntime(), res = {};
  if (req.method === 'POST') {
    const list = Array.isArray(req.body?.packages) ? req.body.packages : fail('packages array is required');
    const batch = db.batch();
    list.forEach((p, i) => {
      const code = cleanString(p.code || p.id, 64).toLowerCase().replace(/[^a-z0-9_-]/g, '');
      const credits = Number(p.credits), price = Number(p.price);
      if (!code || !Number.isSafeInteger(credits) || credits <= 0 || credits > 10000000) fail(`Package ${i + 1} needs a code and whole credits (1 to 10,000,000)`);
      if (!Number.isFinite(price) || price < MIN_PACKAGE_PRICE() || price > 100000000) fail(`Package ${i + 1}: price must be at least ${MIN_PACKAGE_PRICE()}`);
      batch.set(db.collection('credit_package_sm').doc(code), {id: code, code, name: cleanString(p.name, 120) || code, credits, price,
        currency: cleanString(p.currency, 8).toUpperCase() || 'NGN', time_label: cleanString(p.time_label, 60) || `~${Math.floor(credits / 2 / 60)}m ${Math.round(credits / 2) % 60}s`,
        sort_order: (i + 1) * 10, is_active: p.is_active !== false, updated_at: iso()}, {merge: true});
    });
    await batch.commit();
    res.audit_logged = await audit(admin, 'packages.save', null, {packages: list.map(p => ({code: p.code || p.id, credits: p.credits, price: p.price, is_active: p.is_active !== false}))}, req);
  }
  return {...res, packages: (await all('credit_package_sm')).sort((a, b) => (a.sort_order || 0) - (b.sort_order || 0))};
}

const SETTING_KEYS = ['global_config', 'streaming_availability', 'referral_program'];
async function settings(req, admin) {
  const {db} = firebaseRuntime();
  if (req.method === 'POST') {
    const v = req.body || {};
    const values = {global_config: {notification: cleanString(v.notification, 1000)},
      streaming_availability: {enabled: v.streamingEnabled !== false},
      referral_program: {enabled: v.referralEnabled !== false, signup_credits: Math.min(10000, Math.max(0, Math.floor(Number(v.signupCredits) || 0))),
        purchase_reward: Math.min(100000, Math.max(0, Math.floor(Number(v.purchaseReward) || 0)))}};
    await Promise.all(SETTING_KEYS.map(k => db.collection('app_setting_sm').doc(k).set({id: k, key: k, value: values[k], is_public: true}, {merge: true})));
    var logged = await audit(admin, 'settings.save', null, values, req);
  }
  const rows = await Promise.all(SETTING_KEYS.map(k => db.collection('app_setting_sm').doc(k).get()));
  const s = Object.fromEntries(rows.map(r => [r.id, r.data()?.value || {}]));
  return {...(logged === undefined ? {} : {audit_logged: logged}), notification: s.global_config.notification || '', streamingEnabled: s.streaming_availability.enabled !== false,
    referralEnabled: s.referral_program.enabled !== false, signupCredits: Number(s.referral_program.signup_credits ?? 50),
    purchaseReward: Number(s.referral_program.purchase_reward ?? 250),
    flutterwaveConfigured: Boolean(process.env.FLW_SECRET_KEY || process.env.FLUTTERWAVE_SECRET_KEY), creditRatePerSecond: 2};
}

async function auditLog(req) {
  const rows = (await firebaseRuntime().db.collection('admin_audit_sm').orderBy('created_at', 'desc').limit(300).get()).docs;
  return {entries: rows.map(d => ({id: d.id, ...d.data()}))};
}

const ROUTES = {
  'GET /overview': overview, 'GET /users': users, 'POST /users/credits': adjustCredits, 'POST /users/status': setStatus,
  'GET /purchases': purchases, 'GET /performance': performance, 'GET /errors': errors, 'DELETE /errors': errors,
  'GET /referrals': referrals, 'GET /packages': packages, 'POST /packages': packages, 'GET /settings': settings, 'POST /settings': settings,
  'GET /audit': auditLog,
  'GET /me': async (req, admin) => ({email: admin.email, uid: admin.uid}),
};

export async function handleAdmin(req, res, path) {
  checkCsrf(req);
  if (req.method === 'POST' && path === '/login') return login(req, res);
  if (req.method === 'POST' && path === '/logout') return logout(req, res);
  if (path === '/refresh') fail('Sign in again', 410);
  const admin = await requireAdmin(req);
  const fn = ROUTES[`${req.method} ${path}`];
  if (!fn) fail('Not found', 404);
  await ensureFirebaseDefaults().catch(() => {});
  return fn(req, admin);
}
