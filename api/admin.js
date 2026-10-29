// Admin API router: /api/admin/* -> server/admin/firestore-admin.js (ADMIN_EMAILS enforced on every call).
import {handleAdmin} from '../server/admin/firestore-admin.js';
import {recordError} from '../server/shared/error-log.js';
import {applyHttpHeaders, handleOptions, parseRoute, publicError} from '../server/shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, POST, DELETE, OPTIONS');
  res.setHeader('Cache-Control', 'no-store');
  if (handleOptions(req, res)) return;
  const path = parseRoute(req, '/api/admin').replace(/\/+$/, '') || '/';
  try {
    return res.status(200).json(await handleAdmin(req, path));
  } catch (error) {
    if (!error.status || error.status >= 500) {
      console.error('Admin API failed:', error);
      await recordError({source: 'admin', message: error.message, stack: error.stack, context: path});
    }
    return res.status(error.status || 500).json({error: publicError(error, 'Admin request failed')});
  }
}
