// Firestore-backed fixed-window counters (rate_limit_sm). Keys are hashed so emails/IPs are not stored in clear.
// Docs carry expireAt so a Firestore TTL policy can clean them up.
import {createHash} from 'node:crypto';
import {Timestamp} from 'firebase-admin/firestore';

export const hashKey = k => createHash('sha256').update(String(k)).digest('hex').slice(0, 40);

// Counts one hit. Returns {allowed, count, retryAfterSeconds}. Fails open only when told to (non-security paths).
export async function hit(db, key, {limit, windowMs}, {failOpen = false} = {}) {
  const ref = db.collection('rate_limit_sm').doc(hashKey(key));
  try {
    return await db.runTransaction(async tx => {
      const snap = await tx.get(ref), now = Date.now(), row = snap.data();
      const fresh = !row || row.resetAt <= now;
      const count = fresh ? 1 : Number(row.count || 0) + 1, resetAt = fresh ? now + windowMs : row.resetAt;
      tx.set(ref, {count, resetAt, expireAt: Timestamp.fromMillis(resetAt + 86400000)});
      return {allowed: count <= limit, count, retryAfterSeconds: Math.max(1, Math.ceil((resetAt - now) / 1000))};
    });
  } catch (error) {
    if (failOpen) return {allowed: true, count: 0, retryAfterSeconds: 0};
    throw error;
  }
}

// Read-only check (does not count).
export async function peek(db, key) {
  const row = (await db.collection('rate_limit_sm').doc(hashKey(key)).get()).data();
  if (!row || row.resetAt <= Date.now()) return {count: 0, retryAfterSeconds: 0};
  return {count: Number(row.count || 0), retryAfterSeconds: Math.ceil((row.resetAt - Date.now()) / 1000)};
}

export async function clear(db, key) {
  await db.collection('rate_limit_sm').doc(hashKey(key)).delete().catch(() => {});
}

export function tooMany(retryAfterSeconds, message = 'Too many attempts. Try again later.') {
  return Object.assign(new Error(message), {status: 429, expose: true, retryAfter: retryAfterSeconds});
}
