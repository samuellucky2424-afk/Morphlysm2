import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {isAdminClaims} from '../server/admin/firestore-admin.js';
import {handleAdminPage, pageDecision, renderAdminDocument} from '../server/admin/pages.js';

const deny = (status, message) => async () => {
  throw Object.assign(new Error(message), {status});
};

function mockRes() {
  return {
    code: 0,
    body: undefined,
    headers: {},
    setHeader(key, value) { this.headers[key] = value; },
    status(code) { this.code = code; return this; },
    json(body) { this.body = body; return this; },
    end(body) { this.body = body; return this; },
  };
}

test('admin claims require a verified email, the admin claim, and ADMIN_EMAILS', () => {
  const previous = process.env.ADMIN_EMAILS;
  process.env.ADMIN_EMAILS = 'Owner@Example.com, other@x.com';
  try {
    assert.equal(isAdminClaims({email: 'owner@example.com', email_verified: true, admin: true}), true);
    assert.equal(isAdminClaims({email: 'owner@example.com', email_verified: false, admin: true}), false);
    assert.equal(isAdminClaims({email: 'owner@example.com', email_verified: true, admin: false}), false);
    assert.equal(isAdminClaims({email: 'stranger@example.com', email_verified: true, admin: true}), false);
    assert.equal(isAdminClaims({email: '', email_verified: true, admin: true}), false);
  } finally {
    if (previous === undefined) delete process.env.ADMIN_EMAILS;
    else process.env.ADMIN_EMAILS = previous;
  }
});

test('an empty ADMIN_EMAILS list denies every account', () => {
  const previous = process.env.ADMIN_EMAILS;
  delete process.env.ADMIN_EMAILS;
  try {
    assert.equal(isAdminClaims({email: 'owner@example.com', email_verified: true, admin: true}), false);
  } finally {
    if (previous === undefined) delete process.env.ADMIN_EMAILS;
    else process.env.ADMIN_EMAILS = previous;
  }
});

test('page decisions keep the dashboard behind the existing admin check', () => {
  assert.deepEqual(pageDecision('dashboard', null), {status: 200});
  assert.deepEqual(pageDecision('dashboard', Object.assign(new Error('Authentication required'), {status: 401})), {status: 302, location: '/admin/login'});
  assert.deepEqual(pageDecision('dashboard', Object.assign(new Error('Administrator access required'), {status: 403})), {status: 403});
  assert.deepEqual(pageDecision('dashboard', new Error('Firebase is down')), {status: 503});
  assert.deepEqual(pageDecision('login', null), {status: 302, location: '/admin'});
  assert.deepEqual(pageDecision('login', Object.assign(new Error('Authentication required'), {status: 401})), {status: 200});
  assert.deepEqual(pageDecision('other', null), {status: 404});
});

test('admin documents do not embed credentials', () => {
  for (const page of ['login', 'dashboard']) {
    const html = renderAdminDocument(page);
    assert.match(html, new RegExp(`data-page="${page}"`));
    assert.match(html, /src="\/lucky\/privatelife\/logs\/app\.js"/);
    assert.doesNotMatch(html, /private_key|service.account|ADMIN_EMAILS|password123/i);
  }
});

test('dashboard HTML is withheld without an admin session', async () => {
  const missing = mockRes();
  await handleAdminPage('dashboard', {method: 'GET', headers: {}}, missing, deny(401, 'Authentication required'));
  assert.equal(missing.code, 302);
  assert.equal(missing.headers.Location, '/admin/login');
  assert.equal(missing.body, '');
  assert.equal(missing.headers['Cache-Control'], 'private, no-store');

  const stranger = mockRes();
  await handleAdminPage('dashboard', {method: 'GET', headers: {}}, stranger, deny(403, 'Administrator access required'));
  assert.equal(stranger.code, 403);
  assert.match(stranger.body, /Administrator access required/);
  assert.doesNotMatch(stranger.body, /app\.js|Adjust credits|user_sm/);

  const admin = mockRes();
  await handleAdminPage('dashboard', {method: 'GET', headers: {}}, admin, async () => ({uid: 'abc', email: 'owner@example.com'}));
  assert.equal(admin.code, 200);
  assert.match(admin.body, /data-page="dashboard"/);

  const head = mockRes();
  await handleAdminPage('dashboard', {method: 'HEAD', headers: {}}, head, async () => ({uid: 'abc', email: 'owner@example.com'}));
  assert.equal(head.code, 200);
  assert.equal(head.body, undefined);
});

test('login page is public and sends an existing admin session to the dashboard', async () => {
  const anon = mockRes();
  await handleAdminPage('login', {method: 'GET', headers: {}}, anon, deny(401, 'Authentication required'));
  assert.equal(anon.code, 200);
  assert.match(anon.body, /data-page="login"/);

  const admin = mockRes();
  await handleAdminPage('login', {method: 'GET', headers: {}}, admin, async () => ({uid: 'abc', email: 'owner@example.com'}));
  assert.equal(admin.code, 302);
  assert.equal(admin.headers.Location, '/admin');
  assert.equal(admin.body, '');

  const posted = mockRes();
  await handleAdminPage('login', {method: 'POST', headers: {}}, posted, deny(401, 'Authentication required'));
  assert.equal(posted.code, 405);
  assert.deepEqual(posted.body, {error: 'Method not allowed'});
});

test('vercel rewrites expose /admin without moving the admin API', () => {
  const vercel = JSON.parse(readFileSync(new URL('../vercel.json', import.meta.url), 'utf8'));
  const bySource = Object.fromEntries(vercel.rewrites.map(rule => [rule.source, rule.destination]));
  assert.equal(bySource['/admin/login'], '/api/admin-login-page.js');
  assert.equal(bySource['/admin'], '/api/admin-dashboard-page.js');
  assert.equal(bySource['/api/admin/:path*'], '/api/admin.js?path=:path*');
  assert.equal(bySource['/api/login'], '/api/auth.js?route=login');
});
