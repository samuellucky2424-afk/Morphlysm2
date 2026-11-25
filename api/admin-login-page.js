// Public sign-in page. A valid admin session is redirected to /admin.
import {handleAdminPage} from '../server/admin/pages.js';

export default function handler(req, res) {
  return handleAdminPage('login', req, res);
}
