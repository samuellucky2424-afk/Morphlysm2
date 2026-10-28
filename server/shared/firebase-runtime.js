import {getApps, initializeApp, cert} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore} from 'firebase-admin/firestore';

export const FIREBASE_PROJECT = 'luckyweb-f546e';
function parseServiceAccount(raw) {
  const text = String(raw || '').trim();
  if (!text) return null;
  for (const candidate of [text, Buffer.from(text, 'base64').toString('utf8')]) {
    try { const value = JSON.parse(candidate); if (value && typeof value === 'object') return value; } catch {}
  }
  return {invalid: true};
}
function normalize(value, source) {
  if (!value) return null;
  return {...value, source, private_key: typeof value.private_key === 'string' ? value.private_key.replace(/\\n/g, '\n') : value.private_key};
}
function credentialCandidates(env = process.env) {
  return [
    normalize(parseServiceAccount(env.FIREBASE_SERVICE_ACCOUNT_JSON), 'FIREBASE_SERVICE_ACCOUNT_JSON'),
    normalize(parseServiceAccount(env.FIREBASE_SERVICE_ACCOUNT_KEY), 'FIREBASE_SERVICE_ACCOUNT_KEY'),
    env.FIREBASE_PRIVATE_KEY || env.FIREBASE_CLIENT_EMAIL ? normalize({project_id: env.FIREBASE_PROJECT_ID,
      client_email: env.FIREBASE_CLIENT_EMAIL, private_key: env.FIREBASE_PRIVATE_KEY}, 'FIREBASE_PROJECT_ID/CLIENT_EMAIL/PRIVATE_KEY') : null,
  ].filter(Boolean);
}
const usable = c => !c.invalid && c.project_id === FIREBASE_PROJECT && Boolean(c.private_key) && Boolean(c.client_email);
// Secret-free summary of which credential sources are configured (project IDs only).
export function credentialReport(env = process.env) {
  return credentialCandidates(env).map(c => ({source: c.source, parsed: !c.invalid, projectId: c.project_id || null,
    hasClientEmail: Boolean(c.client_email), hasPrivateKey: Boolean(c.private_key), usable: usable(c)}));
}
let runtime;
export function firebaseRuntime() {
  if (runtime) return runtime;
  const credentials = credentialCandidates().find(usable);
  if (!credentials) throw new Error('Configure this backend with a Luckyweb Firebase service account.');
  const app = getApps().find(a => a.name === 'morphly-android') || initializeApp({
    credential: cert(credentials), projectId: FIREBASE_PROJECT,
  }, 'morphly-android');
  const databaseId = process.env.FIREBASE_DATABASE_ID || '(default)';
  runtime = {auth: getAuth(app), db: getFirestore(app, databaseId), databaseId};
  return runtime;
}

export async function firebaseAuthRequest(operation, body) {
  const key = process.env.FIREBASE_API_KEY;
  if (!key) throw new Error('Firebase authentication is not configured.');
  const refresh = operation === 'refresh';
  const url = refresh ? `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(key)}`
    : `https://identitytoolkit.googleapis.com/v1/accounts:${operation}?key=${encodeURIComponent(key)}`;
  const response = await fetch(url, {method: 'POST',
    headers: {'Content-Type': refresh ? 'application/x-www-form-urlencoded' : 'application/json'},
    body: refresh ? new URLSearchParams({grant_type: 'refresh_token', refresh_token: body.refreshToken}) : JSON.stringify(body),
    signal: AbortSignal.timeout(15000)});
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    const code = result.error?.message || 'AUTHENTICATION_FAILED';
    const messages = {EMAIL_EXISTS: 'This email is already registered.', TOO_MANY_ATTEMPTS_TRY_LATER: 'Too many attempts. Try again later.',
      OPERATION_NOT_ALLOWED: 'Email/password authentication must be enabled in Luckyweb Firebase.',
      USER_DISABLED: 'This account is disabled.'};
    const error = new Error(messages[code] || 'Invalid or expired credentials. Sign in again.');
    error.status = code === 'EMAIL_EXISTS' ? 409 : 401;
    throw error;
  }
  return refresh ? {idToken: result.id_token, refreshToken: result.refresh_token, expiresIn: Number(result.expires_in)} : result;
}

// Diagnostic: try each usable credential independently (read-only counts; no secrets returned).
export async function probeCredentials() {
  const out = [];
  for (const [i, c] of credentialCandidates().entries()) {
    if (!usable(c)) { out.push({source: c.source, usable: false}); continue; }
    const name = `probe-${i}`;
    const app = getApps().find(a => a.name === name) || initializeApp({credential: cert(c), projectId: FIREBASE_PROJECT}, name);
    const row = {source: c.source, usable: true, serviceAccountDomain: String(c.client_email).split('@')[1] || ''};
    try { row.authUsers = (await getAuth(app).listUsers(1000)).users.length; } catch (e) { row.authError = String(e.code || e.message).slice(0, 120); }
    try {
      const db = getFirestore(app, process.env.FIREBASE_DATABASE_ID || '(default)');
      row.user_sm = (await db.collection('user_sm').count().get()).data().count;
      row.wallet_sm = (await db.collection('wallet_sm').count().get()).data().count;
      row.walletsWithBalance = (await db.collection('wallet_sm').where('balance', '>', 0).count().get()).data().count;
      row.topCollections = (await db.listCollections()).map(x => x.id).slice(0, 40);
    } catch (e) { row.firestoreError = String(e.code || e.message).slice(0, 160); }
    out.push(row);
  }
  return out;
}