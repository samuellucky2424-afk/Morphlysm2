import {
  bearerToken,
  createUserClient,
  publicSupabaseKey,
  supabaseUrl,
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

  const token = bearerToken(req);
  const password = String(req.body?.password || '');
  if (!token) return res.status(401).json({ error: 'Recovery session is missing or expired.' });
  if (password.length < 8 || password.length > 128) {
    return res.status(400).json({ error: 'Password must be between 8 and 128 characters.' });
  }

  try {
    const client = createUserClient(token);
    const { data: userData, error: userError } = await client.auth.getUser(token);
    if (userError || !userData?.user) {
      return res.status(401).json({ error: 'Recovery session is invalid or expired.' });
    }
    const updateResponse = await fetch(`${supabaseUrl()}/auth/v1/user`, {
      method: 'PUT',
      headers: {
        apikey: publicSupabaseKey(),
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ password }),
    });
    const updateData = await updateResponse.json().catch(() => ({}));
    if (!updateResponse.ok) {
      throw new Error(updateData.msg || updateData.message || 'Password update failed.');
    }
    return res.status(200).json({ message: 'Password updated. You can return to Morphly and sign in.' });
  } catch (error) {
    console.error('Password update failed:', error);
    return res.status(error.status || 400).json({
      error: publicError(error, 'Could not update the password. Request a new recovery link.'),
    });
  }
}
