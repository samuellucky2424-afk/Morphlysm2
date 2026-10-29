// Shared by the routed Vercel functions; this file is not an API entry point.
const DEFAULT_ALLOWED_METHODS = 'GET, POST, PATCH, PUT, DELETE, OPTIONS';

function configuredOrigins() {
  const values = [
    process.env.PUBLIC_APP_URL,
    ...(process.env.ALLOWED_ORIGINS || '').split(','),
  ];
  return new Set(
    values
      .map((value) => String(value || '').trim())
      .filter(Boolean)
      .map((value) => {
        try {
          return new URL(value).origin;
        } catch {
          return value;
        }
      }),
  );
}

export function applyHttpHeaders(req, res, methods = DEFAULT_ALLOWED_METHODS) {
  const origin = String(req.headers?.origin || '').trim();
  if (origin && configuredOrigins().has(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
  }
  res.setHeader('Access-Control-Allow-Methods', methods);
  res.setHeader(
    'Access-Control-Allow-Headers',
    'Authorization, Content-Type, Idempotency-Key, X-Request-Id',
  );
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
}

export function handleOptions(req, res) {
  if (req.method !== 'OPTIONS') return false;
  const origin = String(req.headers?.origin || '').trim();
  if (origin && !configuredOrigins().has(origin)) {
    res.status(403).json({ error: 'Origin is not allowed' });
  } else {
    res.status(204).end();
  }
  return true;
}

export function requireMethod(req, res, ...methods) {
  if (methods.includes(req.method)) return true;
  res.setHeader('Allow', methods.join(', '));
  res.status(405).json({ error: 'Method not allowed' });
  return false;
}

export function cleanString(value, maxLength = 500) {
  return String(value ?? '').trim().slice(0, maxLength);
}

export function positiveInteger(value, { min = 1, max = 100000 } = {}) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) return null;
  return parsed;
}

// Client-facing errors: intentional 4xx messages pass through; anything else becomes a
// generic message plus a short reference id that is logged server-side for support.
export function errorRef() {
  return Math.random().toString(36).slice(2, 8).toUpperCase() + Date.now().toString(36).slice(-4).toUpperCase();
}
const SECRETISH = /(private key|bearer |authorization|service.?role|api key|secret|password=|token=)/i;
export function publicError(error, fallback = 'Something went wrong. Please try again.') {
  const status = Number(error?.status || 0);
  const message = String(error?.message || '');
  if (((status >= 400 && status < 500) || error?.expose) && message && !SECRETISH.test(message)) return message;
  const ref = error?.ref || errorRef();
  if (error && typeof error === 'object') error.ref = ref;
  console.error(`[error ref ${ref}]`, error?.stack || message);
  return `${fallback} (ref ${ref})`;
}

export function clientIp(req) {
  return String(req.headers?.['x-real-ip'] || String(req.headers?.['x-forwarded-for'] || '').split(',')[0] || '').trim() || 'unknown';
}

export function safeRedirectUrl(value) {
  const fallback = 'morphly://payment-return';
  if (typeof value !== 'string' || value.length === 0) return fallback;
  try {
    const url = new URL(value);
    if (url.protocol === 'morphly:' && url.hostname === 'payment-return') {
      return url.toString();
    }
    const allowed = new Set(
      (process.env.ALLOWED_PAYMENT_REDIRECT_ORIGINS || '')
        .split(',')
        .map((entry) => entry.trim())
        .filter(Boolean),
    );
    if (url.protocol === 'https:' && allowed.has(url.origin)) return url.toString();
  } catch {
    // Use the application deep link for malformed or untrusted URLs.
  }
  return fallback;
}

export function parseRoute(req, prefix) {
  const url = new URL(req.url, `https://${req.headers.host || 'localhost'}`);
  const catchAll = req.query?.path;
  if (catchAll) {
    const segments = Array.isArray(catchAll) ? catchAll : [catchAll];
    return `/${segments.map((segment) => String(segment).replace(/^\/+|\/+$/g, '')).join('/')}`;
  }
  const parsed = url.pathname.replace(new RegExp(`^${prefix}`), '');
  return parsed === '.js' || parsed === '/.js' ? '/' : (parsed || '/');
}
