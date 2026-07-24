import {
  formatAccount,
  getAccountSummary,
  requireUser,
} from './_shared/supabase.js';
import {
  applyHttpHeaders,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'GET')) return;

  try {
    const { service, appUser } = await requireUser(req);
    const summary = await getAccountSummary(service, appUser.id);
    const user = formatAccount(summary);
    return res.status(200).json({
      balance: user.balance,
      used: user.creditsUsed,
      user,
      license: {
        status: user.is_activated ? 'active' : 'inactive',
        expiresAt: user.licenseExpiresAt || '-',
      },
    });
  } catch (error) {
    console.error('Balance API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
