// Admin API router: /api/admin/* -> server/admin/firestore-admin.js (session cookie + admin claim on every call).
import {handleAdmin} from '../server/admin/firestore-admin.js';
import {recordError} from '../server/shared/error-log.js';
import {parseRoute, publicError} from '../server/shared/http.js';

export default async function handler(req, res) {
  // Same-origin only: no CORS headers are ever sent for the admin API.
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method === 'OPTIONS') return res.status(403).json({error: 'Origin is not allowed'});
  const path = parseRoute(req, '/api/admin').replace(/\/+$/, '') || '/';
  try {
    return res.status(200).json(await handleAdmin(req, res, path));
  } catch (error) {
    if (error.retryAfter) res.setHeader('Retry-After', String(error.retryAfter));
    const message = publicError(error, 'Admin request failed');
    if (!error.status || error.status >= 500) {
      await recordError({source: 'admin', message: error.message, stack: error.stack, context: path, ref: error.ref});
    }
    return res.status(error.status || 500).json({error: message});
  }
}
