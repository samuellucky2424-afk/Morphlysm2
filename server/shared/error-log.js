// Error log stored in Firestore errors_sm (30-day TTL via expireAt). The server writes directly;
// the public POST /api/log-error requires a signed-in user and is rate limited and size capped.
import {Timestamp} from 'firebase-admin/firestore';
import {firebaseRuntime} from './firebase-runtime.js';
import {bearerToken} from './firebase.js';
import {cleanString, clientIp} from './http.js';
import {hit} from './rate-limit.js';

const TTL_MS = 30 * 86400000;
const MAX_BODY_BYTES = 32 * 1024, MAX_ITEMS = 10;

export async function recordError(entry) {
  try {
    const {db} = firebaseRuntime();
    await db.collection('errors_sm').add({
      source: cleanString(entry.source, 20) || 'server', level: cleanString(entry.level, 10) || 'error',
      message: cleanString(entry.message, 1000) || 'Unknown error', code: cleanString(entry.code, 80) || null,
      context: cleanString(entry.context, 200) || null, stack: cleanString(entry.stack, 4000) || null,
      ref: cleanString(entry.ref, 20) || null,
      user_id: entry.user_id || null, device: cleanString(entry.device, 200) || null,
      app_version: cleanString(entry.app_version, 40) || null, created_at: new Date().toISOString(), createdAt: Date.now(),
      expireAt: Timestamp.fromMillis(Date.now() + TTL_MS),
    });
    return true;
  } catch (error) { console.error('errors_sm write failed:', error?.message); return false; }
}

export async function logErrorRoute(req, res) {
  if (req.method !== 'POST') return res.status(405).json({error: 'Method not allowed'});
  const raw = typeof req.body === 'string' ? req.body : JSON.stringify(req.body || {});
  if (Buffer.byteLength(raw) > MAX_BODY_BYTES) return res.status(413).json({error: 'Report too large'});
  const token = bearerToken(req);
  if (!token) return res.status(401).json({error: 'Authentication required'});
  const {auth, db} = firebaseRuntime();
  let uid;
  try { uid = (await auth.verifyIdToken(token)).uid; } catch { return res.status(401).json({error: 'Session is invalid or expired'}); }
  const [perUser, perIp] = await Promise.all([
    hit(db, `logerr:u:${uid}`, {limit: 30, windowMs: 600000}),
    hit(db, `logerr:ip:${clientIp(req)}`, {limit: 60, windowMs: 600000}),
  ]);
  if (!perUser.allowed || !perIp.allowed) {
    res.setHeader('Retry-After', String(Math.max(perUser.retryAfterSeconds, perIp.retryAfterSeconds)));
    return res.status(429).json({error: 'Too many reports'});
  }
  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  const items = (Array.isArray(body.errors) ? body.errors : [body]).slice(0, MAX_ITEMS).filter(e => e && typeof e === 'object');
  await Promise.all(items.map(e => recordError({...e, source: 'app', user_id: uid,
    app_version: e.appVersion || e.app_version, device: e.device || e.deviceModel})));
  return res.status(200).json({logged: items.length});
}
