import test from 'node:test';
import assert from 'node:assert/strict';
import {publicConfig, firebaseWebConfig} from '../server/shared/mobile-routes.js';

const res = () => ({code: 0, body: null, setHeader() {}, status(c) { this.code = c; return this; }, json(b) { this.body = b; return this; }, end() { return this; }});
const db = {collection: () => ({doc: id => ({get: async () => ({id, data: () => (id === 'global_config' ? {value: {notification: 'hi'}} : undefined)})})})};

test('public-config returns the nova firebaseConfig FirebaseBackend.kt expects', async () => {
  const r = res();
  await publicConfig({method: 'GET'}, r, {db, env: {FIREBASE_PROJECT_ID: 'nova-8fb74', FIREBASE_API_KEY: 'web'}});
  assert.equal(r.code, 200);
  assert.equal(r.body.backend, 'firebase');
  assert.deepEqual(r.body.firebaseConfig, {projectId: 'nova-8fb74', apiKey: 'web', authDomain: 'nova-8fb74.firebaseapp.com'});
  assert.equal(r.body.notification, 'hi');
  assert.equal(r.body.streamingEnabled, true);
});

test('public-config still answers when settings cannot be read', async () => {
  const r = res();
  await publicConfig({method: 'GET'}, r, {db: {collection: () => { throw new Error('down'); }}, env: {FIREBASE_API_KEY: 'k'}});
  assert.equal(r.code, 200);
  assert.equal(r.body.firebaseConfig.projectId, 'nova-8fb74');
});

test('web config defaults to the nova project', () => {
  assert.equal(firebaseWebConfig({}).authDomain, 'nova-8fb74.firebaseapp.com');
});
