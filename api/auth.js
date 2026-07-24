import login from '../server/auth/login.js';
import signup from '../server/auth/signup.js';
import refresh from '../server/auth/refresh.js';
import requestPasswordReset from '../server/auth/request-password-reset.js';
import updatePassword from '../server/auth/update-password.js';
import { cleanString } from '../server/shared/http.js';

const handlers = new Map([
  ['login', login],
  ['signup', signup],
  ['refresh', refresh],
  ['request-password-reset', requestPasswordReset],
  ['update-password', updatePassword],
]);

export default async function handler(req, res) {
  const route = cleanString(req.query?.route, 64).toLowerCase();
  const routeHandler = handlers.get(route);
  if (!routeHandler) {
    res.setHeader('Cache-Control', 'no-store');
    return res.status(404).json({ error: 'Authentication route not found' });
  }
  return routeHandler(req, res);
}
