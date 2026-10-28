import {getApps, initializeApp, cert} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore} from 'firebase-admin/firestore';

export const FIREBASE_PROJECT = 'nova-8fb74';
let runtime;
export function firebaseRuntime() {
  if (runtime) return runtime;
  let credentials = process.env.FIREBASE_SERVICE_ACCOUNT_JSON || process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (credentials) {
    try { credentials = JSON.parse(credentials); }
    catch { throw new Error('Firebase server credential is invalid.'); }
  } else {
    credentials = {project_id: process.env.FIREBASE_PROJECT_ID,
      client_email: process.env.FIREBASE_CLIENT_EMAIL,
      private_key: process.env.FIREBASE_PRIVATE_KEY?.replace(/\\n/g, '\n')};
  }
  if (credentials.project_id !== FIREBASE_PROJECT || !credentials.private_key || !credentials.client_email) {
    throw new Error('Configure this backend with a Nova Firebase service account.');
  }
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
      OPERATION_NOT_ALLOWED: 'Email/password authentication must be enabled in Nova Firebase.',
      USER_DISABLED: 'This account is disabled.'};
    const error = new Error(messages[code] || 'Invalid or expired credentials. Sign in again.');
    error.status = code === 'EMAIL_EXISTS' ? 409 : 401;
    throw error;
  }
  return refresh ? {idToken: result.id_token, refreshToken: result.refresh_token, expiresIn: Number(result.expires_in)} : result;
}
