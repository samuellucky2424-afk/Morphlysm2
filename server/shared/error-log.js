// Lightweight error log stored in Firestore errors_sm. Used by the app (POST /api/log-error) and the server.
import {firebaseRuntime} from './firebase-runtime.js';
import {bearerToken} from './firebase.js';
import {cleanString} from './http.js';

export async function recordError(entry) {
  try {
    const {db} = firebaseRuntime();
    await db.collection('errors_sm').add({
      source: cleanString(entry.source, 20) || 'server', level: cleanString(entry.level, 10) || 'error',
      message: cleanString(entry.message, 1000) || 'Unknown error', code: cleanString(entry.code, 80) || null,
      context: cleanString(entry.context, 200) || null, stack: cleanString(entry.stack, 4000) || null,
      user_id: entry.user_id || null, device: cleanString(entry.device, 200) || null,
      app_version: cleanString(entry.app_version, 40) || null, created_at: new Date().toISOString(), createdAt: Date.now(),
    });
  } catch (error) { console.error('errors_sm write failed:', error?.message); }
}

export async function logErrorRoute(req, res) {
  if (req.method !== 'POST') return res.status(405).json({error: 'Method not allowed'});
  const body = req.body || {};
  let uid = null;
  const token = bearerToken(req);
  if (token) { try { uid = (await firebaseRuntime().auth.verifyIdToken(token)).uid; } catch { /* anonymous */ } }
  const items = (Array.isArray(body.errors) ? body.errors : [body]).slice(0, 20);
  await Promise.all(items.map(e => recordError({...e, source: e.source || 'app', user_id: uid,
    app_version: e.appVersion || e.app_version, device: e.device || e.deviceModel})));
  return res.status(200).json({logged: items.length});
}
