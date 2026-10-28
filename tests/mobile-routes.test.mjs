import test from 'node:test';
import assert from 'node:assert/strict';
import {publicConfig, startSession, recordUsage} from '../server/shared/mobile-routes.js';
import {createXmaxAndroidKey} from '../server/shared/xmax.js';

function fakeRes() { const r = {code: 0, body: null, headers: {}, setHeader(k, v) { this.headers[k] = v; },
  status(c) { this.code = c; return this; }, json(b) { this.body = b; return this; }, end() { return this; }}; return r; }
function fakeDb(data = {}) {
  let n = 0;
  const doc = (col, id = `auto${++n}`) => ({id, col, key: `${col}/${id}`,
    async get() { const d = data[`${col}/${id}`]; return {exists: !!d, data: () => d, id}; },
    async set(v) { data[`${col}/${id}`] = {...v}; }});
  const db = {data, collection: col => ({doc: id => doc(col, id)}),
    async runTransaction(fn) { return fn({get: r => r.get(), update: (r, v) => { data[r.key] = {...data[r.key], ...v}; },
      create: (r, v) => { data[r.key] = v; }}); }};
  return db;
}

test('public-config has the shape FirebaseBackend.kt expects', async () => {
  const res = fakeRes();
  await publicConfig({method: 'GET'}, res, {runtime: {db: fakeDb()}, env: {FIREBASE_API_KEY: 'web-key'}});
  assert.equal(res.code, 200);
  assert.equal(res.body.backend, 'firebase');
  assert.equal(res.body.firebaseConfig.projectId, 'luckyweb-f546e');
  assert.equal(res.body.firebaseConfig.apiKey, 'web-key');
  assert.equal(typeof res.body.notification, 'string');
});

test('start-session issues an xmax session and never leaks the permanent key', async () => {
  const db = fakeDb(), res = fakeRes();
  let seen;
  await startSession({method: 'POST', body: {provider: 'xmax', platform: 'android', installationId: 'dev1'}}, res, {db,
    env: {XMAX_ANDROID_API_KEY: 'perm'}, requireUser: async () => ({appUser: {id: 'u1', balance: 100}}),
    createKey: async a => { seen = a; return {token: 'temp', sessionLimit: a.maxSeconds, expiresAt: 1}; }});
  assert.equal(res.code, 200);
  assert.equal(seen.maxSeconds, 50);
  assert.equal(res.body.provider, 'xmax'); assert.equal(res.body.allowed, true);
  assert.equal(res.body.token, 'temp'); assert.equal(res.body.model, 'x2.1');
  assert.ok(!JSON.stringify(res.body).includes('perm'));
  assert.equal(db.data[`stream_session_sm/${res.body.sessionId}`].status, 'active');
});

test('start-session rejects non-xmax providers and empty wallets', async () => {
  const r1 = fakeRes();
  await startSession({method: 'POST', body: {provider: 'vidu'}}, r1, {db: fakeDb(), requireUser: async () => ({appUser: {id: 'u', balance: 9}})});
  assert.equal(r1.code, 400);
  const r2 = fakeRes();
  await startSession({method: 'POST', body: {provider: 'xmax'}}, r2, {db: fakeDb(), requireUser: async () => ({appUser: {id: 'u', balance: 1}})});
  assert.equal(r2.code, 402);
});

test('usage bills each second once and stops when credits run out', async () => {
  const now = Math.floor(Date.now() / 1000);
  const db = fakeDb({'stream_session_sm/s1': {user_id: 'u1', status: 'active', started_epoch: now - 10, max_seconds: 1800, billed_epochs: []},
    'wallet_sm/u1': {balance: 7, used: 0}});
  const a = await recordUsage(db, 'u1', {sessionId: 's1', epochSeconds: [now - 3, now - 2, now - 2]});
  assert.equal(a.creditsCharged, 4); assert.equal(a.remainingCredits, 3); assert.equal(a.shouldStop, false);
  const b = await recordUsage(db, 'u1', {sessionId: 's1', epochSeconds: [now - 3, now - 1, now]});
  assert.equal(b.recordedSeconds, 1); assert.equal(b.remainingCredits, 1); assert.equal(b.shouldStop, true);
  const c = await recordUsage(db, 'u2', {sessionId: 's1', epochSeconds: [now]});
  assert.equal(c.reason, 'session_not_found');
});

test('xmax key helper reports misconfiguration and provider failures', async () => {
  assert.equal((await createXmaxAndroidKey({apiKey: ''})).error.error, 'XMAX_NOT_CONFIGURED');
  const bad = await createXmaxAndroidKey({apiKey: 'k', maxSeconds: 10, fetchImpl: async () => ({ok: false, status: 401, json: async () => ({})})});
  assert.equal(bad.error.error, 'XMAX_SESSION_FAILED');
  const ok = await createXmaxAndroidKey({apiKey: 'k', maxSeconds: 5000, fetchImpl: async (u, o) => {
    assert.equal(JSON.parse(o.body).pointsLimit, 1800);
    return {ok: true, status: 200, json: async () => ({success: true, data: {temporaryApiKey: 't', expireTimestamp: 9}})}; }});
  assert.equal(ok.token, 't');
});
