import {
  createPublicClient,
  createServiceClient,
  formatAccount,
  getAccountSummary,
} from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  const email = cleanString(req.body?.email, 320).toLowerCase();
  const password = String(req.body?.password || '');
  if (!email || !password) {
    return res.status(400).json({ error: 'email and password are required' });
  }

  try {
    const authClient = createPublicClient();
    const { data, error } = await authClient.auth.signInWithPassword({ email, password });
    if (error || !data?.session || !data.user) {
      return res.status(401).json({ error: 'Invalid email or password' });
    }

    const summary = await getAccountSummary(createServiceClient(), data.user.id);
    if (summary.status !== 'active') {
      await authClient.auth.signOut();
      return res.status(403).json({ error: `Your account is ${summary.status}. Please contact support.` });
    }

    return res.status(200).json({
      token: data.session.access_token,
      refreshToken: data.session.refresh_token,
      expiresIn: data.session.expires_in,
      expiresAt: data.session.expires_at,
      user: formatAccount(summary),
    });
  } catch (error) {
    console.error('Login API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
