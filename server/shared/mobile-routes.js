// Extra routes used by the Morphly Android app (FirebaseBackend.kt / StreamAuthManager.kt / StreamActivity.kt).
import {firebaseRuntime, FIREBASE_PROJECT} from './firebase-runtime.js';
import {ensureFirebaseDefaults, requireUser, bearerToken, formatAccount} from './firebase.js';
import {createOperations} from './firebase-operations.js';
import {cleanString, requireMethod} from './http.js';

const err = (message, status) => Object.assign(new Error(message), {status});

export function firebaseWebConfig(env = process.env) {
  const projectId = String(env.FIREBASE_PROJECT_ID || FIREBASE_PROJECT).trim();
  return {projectId, apiKey: String(env.FIREBASE_API_KEY || '').trim(),
    authDomain: String(env.FIREBASE_AUTH_DOMAIN || `${projectId}.firebaseapp.com`).trim()};
}

export async function publicConfig(req, res, deps = {}) {
  if (!requireMethod(req, res, 'GET')) return;
  const firebaseConfig = firebaseWebConfig(deps.env);
  let values = {};
  try {
    const db = deps.db || (await ensureFirebaseDefaults(), firebaseRuntime().db);
    const rows = await Promise.all(['global_config', 'streaming_availability', 'referral_program']
      .map(k => db.collection('app_setting_sm').doc(k).get()));
    values = Object.fromEntries(rows.map(r => [r.id, r.data()?.value || {}]));
  } catch (error) { console.error('public-config settings unavailable:', error?.message); }
  return res.status(200).json({
    backend: 'firebase', firebaseProjectId: firebaseConfig.projectId, firebaseConfig, realtimeProvider: 'xmax',
    notification: values.global_config?.notification || '',
    streamingEnabled: values.streaming_availability?.enabled !== false,
    referralEnabled: values.referral_program?.enabled !== false,
    signupCredits: Number(values.referral_program?.signup_credits ?? 50),
    referralPurchaseReward: Number(values.referral_program?.purchase_reward ?? 250),
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
  await ops.provision(record, {name: cleanString(req.body?.name, 120) || record.displayName || '',
    phone: cleanString(req.body?.phone, 40), referredByCode: cleanString(req.body?.referralCode, 40)});
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

export const MOBILE_ROUTES = {'public-config': publicConfig, 'firebase-register': firebaseRegister,
  wallet, 'credit-packages': creditPackages};
