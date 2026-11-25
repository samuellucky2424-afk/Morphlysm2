// Dashboard shell. requireAdmin runs before any HTML is sent.
import {handleAdminPage} from '../server/admin/pages.js';

export default function handler(req, res) {
  return handleAdminPage('dashboard', req, res);
}
