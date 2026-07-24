import {
  createPublicClient,
  createServiceClient,
  formatAccount,
  getAccountSummary,
} from './_shared/supabase.js';
import {
  applyHttpHeaders,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  const refreshToken = String(req.body?.refreshToken || '').trim();
  if (!refreshToken) return res.status(400).json({ error: 'refreshToken is required' });

  try {
    const client = createPublicClient();
    const { data, error } = await client.auth.refreshSession({ refresh_token: refreshToken });
    if (error || !data?.session || !data.user) {
      return res.status(401).json({ error: 'Session expired. Sign in again.' });
    }
    const summary = await getAccountSummary(createServiceClient(), data.user.id);
    if (summary.status !== 'active') {
      return res.status(403).json({ error: `Account is ${summary.status}` });
    }
    return res.status(200).json({
      token: data.session.access_token,
      refreshToken: data.session.refresh_token,
      expiresIn: data.session.expires_in,
      expiresAt: data.session.expires_at,
      user: formatAccount(summary),
    });
  } catch (error) {
    console.error('Refresh API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
