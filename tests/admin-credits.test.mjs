import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import handler from '../api/admin.js';
import {applyCreditAdjustment, parseCreditAdjustment} from '../server/admin/firestore-admin.js';

const admin = {email: 'owner@example.com', uid: 'admin-1'};

function mockRes() {
  return {
    code: 0, body: null, headers: {},
    setHeader(key, value) { this.headers[key] = value; },
    status(code) { this.code = code; return this; },
    json(body) { this.body = body; return this; },
  };
}

function memoryDb(seed) {
  const committed = new Map(Object.entries(seed).map(([key, value]) => [key, structuredClone(value)]));
  const ref = (name, id) => {
    const docId = id || `auto_${Math.random().toString(36).slice(2, 10)}`;
    return {_k: `${name}/${docId}`, id: docId};
  };
  return {
    collection(name) { return {doc: id => ref(name, id)}; },
    async runTransaction(fn) {
      const staged = new Map();
      const read = key => staged.has(key) ? structuredClone(staged.get(key)) : committed.has(key) ? structuredClone(committed.get(key)) : undefined;
      const result = await fn({
        async get(target) {
          const data = read(target._k);
          return {exists: data !== undefined, data: () => (data === undefined ? undefined : structuredClone(data))};
        },
        update(target, patch) {
          const current = read(target._k);
          if (!current) throw new Error(`missing ${target._k}`);
          staged.set(target._k, {...current, ...patch});
        },
        set(target, data) { staged.set(target._k, {...data}); },
        create(target, data) {
          if (read(target._k) !== undefined) throw new Error(`exists ${target._k}`);
          staged.set(target._k, {...data});
        },
      });
      for (const [key, value] of staged) committed.set(key, value);
      return result;
    },
    get(path) { return committed.get(path); },
    docs(name) { return [...committed.entries()].filter(([key]) => key.startsWith(`${name}/`)).map(([, value]) => value); },
  };
}

function account(balance = 1000) {
  return memoryDb({
    'user_sm/user-1': {email: 'ada@example.com', name: 'Ada'},
    'wallet_sm/user-1': {id: 'user-1', user_id: 'user-1', balance, used: 4},
  });
}

test('credit amounts must be a non-zero whole number within the cap, with a reason', () => {
  assert.throws(() => parseCreditAdjustment({uid: '', delta: 10, reason: 'gift'}), error => error.status === 400);
  assert.throws(() => parseCreditAdjustment({uid: 'user-1', delta: 1.5, reason: 'gift'}), error => error.status === 400);
  assert.throws(() => parseCreditAdjustment({uid: 'user-1', delta: 0, reason: 'gift'}), error => error.status === 400);
  assert.throws(() => parseCreditAdjustment({uid: 'user-1', delta: 10000001, reason: 'gift'}), error => error.status === 400);
  assert.throws(() => parseCreditAdjustment({uid: 'user-1', delta: -10000001, reason: 'gift'}), error => error.status === 400);
  assert.throws(() => parseCreditAdjustment({uid: 'user-1', delta: 10, reason: '   '}), error => error.status === 400);
  assert.deepEqual(parseCreditAdjustment({uid: ' user-1 ', delta: -25, reason: ' fix ', idempotencyKey: 'k1'}), {
    uid: 'user-1', delta: -25, reason: 'fix', key: 'k1',
  });
});

test('adding credits updates the wallet and writes one audit entry with before and after', async () => {
  const db = account();
  const result = await applyCreditAdjustment(db, admin, {
    uid: 'user-1', delta: 250, reason: 'Support top-up', idempotencyKey: 'add-1',
  }, {headers: {'user-agent': 'test'}});
  assert.equal(result.audit_logged, true);
  assert.equal(result.balance_before, 1000);
  assert.equal(result.balance_after, 1250);
  assert.equal(db.get('wallet_sm/user-1').balance, 1250);
  assert.equal(db.get('wallet_sm/user-1').used, 4);
  const audits = db.docs('admin_audit_sm');
  assert.equal(audits.length, 1);
  assert.equal(audits[0].actor_email, 'owner@example.com');
  assert.equal(audits[0].actor_uid, 'admin-1');
  assert.equal(audits[0].action, 'credits.adjust');
  assert.equal(audits[0].entity, 'user-1');
  assert.deepEqual(audits[0].data, {
    delta: 250, reason: 'Support top-up', balance_before: 1000, balance_after: 1250, user_email: 'ada@example.com',
  });
  const ledger = db.docs('wallet_ledger_sm');
  assert.equal(ledger.length, 1);
  assert.equal(ledger[0].entry_type, 'admin_adjustment');
  assert.equal(ledger[0].balance_before, 1000);
  assert.equal(ledger[0].balance_after, 1250);
});

test('the same idempotency key does not credit twice or write a second audit entry', async () => {
  const db = account();
  const body = {uid: 'user-1', delta: 100, reason: 'Retry', idempotencyKey: 'same'};
  await applyCreditAdjustment(db, admin, body);
  const second = await applyCreditAdjustment(db, admin, body);
  assert.equal(second.idempotent, true);
  assert.equal(second.balance_before, 1000);
  assert.equal(second.balance_after, 1100);
  assert.equal(db.get('wallet_sm/user-1').balance, 1100);
  assert.equal(db.docs('admin_audit_sm').length, 1);
});

test('removing credits is atomic and cannot drive the balance below zero', async () => {
  const db = account(300);
  const removed = await applyCreditAdjustment(db, admin, {uid: 'user-1', delta: -80, reason: 'Correction', idempotencyKey: 'rem'});
  assert.equal(removed.balance_before, 300);
  assert.equal(removed.balance_after, 220);
  await assert.rejects(
    () => applyCreditAdjustment(db, admin, {uid: 'user-1', delta: -1000, reason: 'Too much', idempotencyKey: 'over'}),
    error => error.status === 400 && error.message === 'Balance cannot go below zero',
  );
  assert.equal(db.get('wallet_sm/user-1').balance, 220);
  assert.equal(db.docs('admin_audit_sm').length, 1);
  assert.equal(db.docs('wallet_ledger_sm').length, 1);
});

test('unknown users are not given a wallet', async () => {
  const db = account();
  await assert.rejects(
    () => applyCreditAdjustment(db, admin, {uid: 'missing', delta: 50, reason: 'Oops', idempotencyKey: 'miss'}),
    error => error.status === 404,
  );
  assert.equal(db.docs('wallet_sm').length, 1);
  assert.equal(db.docs('admin_audit_sm').length, 0);
});

test('a user without a wallet receives one at the credited balance', async () => {
  const db = memoryDb({'user_sm/user-2': {email: 'new@example.com'}});
  const result = await applyCreditAdjustment(db, admin, {uid: 'user-2', delta: 40, reason: 'Opening balance', idempotencyKey: 'new'});
  assert.equal(result.balance_before, 0);
  assert.equal(result.balance, 40);
  assert.equal(db.get('wallet_sm/user-2').balance, 40);
});

test('the credit endpoint rejects a caller with no admin session', async () => {
  const res = mockRes();
  await handler({
    method: 'POST',
    url: '/api/admin/users/credits',
    headers: {'x-morphly-admin': '1', host: 'morphlysm2.vercel.app'},
    body: {uid: 'user-1', delta: 100, reason: 'should not apply'},
  }, res);
  assert.equal(res.code, 401);
  assert.deepEqual(res.body, {error: 'Authentication required'});
});

test('the users screen posts adjustments through the existing credit endpoint', () => {
  const source = readFileSync(new URL('../server/admin-ui/app.js', import.meta.url), 'utf8');
  assert.match(source, /Adjust credits/);
  assert.match(source, /Add credits/);
  assert.match(source, /Remove credits/);
  assert.match(source, /\/users\/credits/);
  assert.match(source, /data-credit/);
});
