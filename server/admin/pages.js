// HTML shells for /admin/login and /admin. The dashboard document is sent only after
// requireAdmin accepts the session cookie. Account data stays in /api/admin.
import {requireAdmin} from './firestore-admin.js';

const PAGE_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'";

export function pageDecision(kind, error) {
  if (kind !== 'login' && kind !== 'dashboard') return {status: 404};
  if (kind === 'login') {
    if (!error) return {status: 302, location: '/admin'};
    return {status: 200};
  }
  if (!error) return {status: 200};
  if (error.status === 401) return {status: 302, location: '/admin/login'};
  if (error.status === 403) return {status: 403};
  return {status: 503};
}

export function renderAdminDocument(page) {
  if (page !== 'login' && page !== 'dashboard') throw new Error('Unknown admin page');
  const title = page === 'login' ? 'Sign in · Morphly Admin' : 'Morphly Admin';
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><meta name="robots" content="noindex,nofollow"><link rel="stylesheet" href="/lucky/privatelife/logs/styles.css">
<link rel="preconnect" href="https://fonts.googleapis.com"><link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
</head><body data-page="${page}"><div id="root"></div><noscript>Sign-in requires JavaScript. Account data is not included in this page.</noscript>
<script src="/lucky/privatelife/logs/app.js"></script></body></html>`;
}

function forbiddenHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="robots" content="noindex,nofollow"><title>Not an administrator</title></head>
<body style="font-family:sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem"><h1>Administrator access required</h1>
<p>This account is signed in, but it is not an administrator.</p>
<p><a href="/admin/login">Sign in with an administrator account</a></p></body></html>`;
}

function unavailableHtml() {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="robots" content="noindex,nofollow"><title>Admin unavailable</title></head>
<body style="font-family:sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem"><h1>Admin dashboard unavailable</h1>
<p>Sign-in cannot be checked right now. Try again shortly.</p></body></html>`;
}

function applyDocumentHeaders(res) {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.setHeader('Cache-Control', 'private, no-store');
  res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Content-Security-Policy', PAGE_CSP);
}

export async function handleAdminPage(kind, req, res, requireAdminFn = requireAdmin) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.setHeader('Allow', 'GET, HEAD');
    res.setHeader('Cache-Control', 'private, no-store');
    res.status(405).json({error: 'Method not allowed'});
    return;
  }
  applyDocumentHeaders(res);
  let error = null;
  try {
    await requireAdminFn(req);
  } catch (caught) {
    error = caught instanceof Error ? caught : Object.assign(new Error('Admin request failed'), {status: 500});
  }
  const decision = pageDecision(kind, error);
  if (decision.status === 503) console.error('admin page unavailable:', error?.message || 'unknown');
  if (decision.location) res.setHeader('Location', decision.location);
  const body = decision.status === 403 ? forbiddenHtml()
    : decision.status === 503 ? unavailableHtml()
    : decision.status === 200 ? renderAdminDocument(kind)
    : '';
  res.status(decision.status).end(req.method === 'HEAD' ? undefined : body);
}
