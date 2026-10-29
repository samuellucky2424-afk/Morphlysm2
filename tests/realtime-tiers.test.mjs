import test from 'node:test';
import assert from 'node:assert/strict';
import {androidRealtime, normalizeTier, tierRates} from '../server/shared/firebase-realtime.js';

function memDb(seed) {
  const data = new Map(Object.entries(seed));
  const ref = (c, id) => ({id, path: `${c}/${id}`,
    get: async () => snap(`${c}/${id}`), set: async v => data.set(`${c}/${id}`, {...v}),
    delete: async () => data.delete(`${c}/${id}`)});
  const snap = p => ({exists: data.has(p), id: p.split('/')[1], data: () => data.get(p), ref: ref(...p.split('/'))});
  const db = {data, collection: c => ({doc: id => ref(c, id)}),
    runTransaction: async fn => fn({get: async r => snap(r.path),
      update: (r, v) => data.set(r.path, {...data.get(r.path), ...v}), set: (r, v) => data.set(r.path, {...v}),
      create: (r, v) => { if (data.has(r.path)) throw new Error('exists'); data.set(r.path, {...v}); }})};
  return db;
}
const seed = () => ({'user_sm/u': {status: 'active'}, 'wallet_sm/u': {balance: 1000, reserved: 0}});

test('tier parsing and rates', () => {
  assert.equal(normalizeTier({tier: 'pro'}), 'pro');
  assert.equal(normalizeTier({provider: 'xmax'}), 'plus');
  assert.equal(normalizeTier({tier: 'bogus'}), null);
  assert.deepEqual(tierRates({}), {plus: 2, pro: 2});
  assert.deepEqual(tierRates({STREAM_RATE_PRO: '3'}), {plus: 2, pro: 3});
});

test('plus start keeps old xmax behaviour', async () => {
  const db = memDb(seed());
  const rt = androidRealtime(db, async () => ({token: 'tmp', sessionLimit: 500}), async () => ({ok: true, consumed: null}), Date.now, {env: {}});
  const r = await rt.start({id: 'u'}, {provider: 'xmax', platform: 'android'}, 'perm');
  assert.equal(r.provider, 'xmax'); assert.equal(r.tier, 'plus'); assert.equal(r.maxSeconds, 500);
  assert.equal(db.data.get(`stream_session_sm/${r.sessionId}`).provider, 'xmax');
});

test('pro start uses vidu with configured rate and never returns the permanent key', async () => {
  const db = memDb(seed());
  let sent;
  const rt = androidRealtime(db, null, null, Date.now, {env: {VIDU_API_KEY: 'permvidu', STREAM_RATE_PRO: '4'},
    issueVidu: async a => { sent = a; return {token: 'secret', sessionLimit: 250, provider: {liveId: 'L', renderUid: 'R', rtc: {userId: 'x', token: 't'}}}; },
    revokeVidu: async () => ({ok: false, consumed: null})});
  const r = await rt.start({id: 'u'}, {tier: 'pro', platform: 'android', referenceImage: 'data:image/jpeg;base64,' + 'A'.repeat(64)}, 'xmaxperm');
  assert.equal(r.provider, 'vidu'); assert.equal(r.rate, 4); assert.equal(r.maxSeconds, 250);
  assert.equal(sent.apiKey, 'permvidu');
  assert.ok(!JSON.stringify(r).includes('permvidu'));
  assert.equal(db.data.get('wallet_sm/u').balance, 0); // 250s * 4 held
  const end = await rt.meter({id: 'u'}, {sessionId: r.sessionId}, true, 'xmaxperm');
  assert.equal(end.shouldStop, true);
  assert.equal(db.data.get(`stream_session_sm/${r.sessionId}`).status, 'completed');
});

test('pro without key or photo is refused before charging', async () => {
  const db = memDb(seed());
  const rt = androidRealtime(db, null, null, Date.now, {env: {}});
  await assert.rejects(rt.start({id: 'u'}, {tier: 'pro', platform: 'android', referenceImage: 'x'.repeat(40)}, 'k'), /not available/);
  assert.equal(db.data.get('wallet_sm/u').balance, 1000);
});
