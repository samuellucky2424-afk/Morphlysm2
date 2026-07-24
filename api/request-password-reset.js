import { createPublicClient } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function resetRedirectUrl() {
  const configured = String(process.env.PUBLIC_APP_URL || '').trim();
  try {
    const url = new URL(configured);
    if (url.protocol !== 'https:') throw new Error();
    return `${url.origin}/reset-password.html`;
  } catch {
    throw new Error('PUBLIC_APP_URL must be a valid HTTPS origin.');
  }
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  const email = cleanString(req.body?.email, 320).toLowerCase();
  if (!EMAIL_PATTERN.test(email)) {
    return res.status(400).json({ error: 'Enter a valid email address' });
  }

  try {
    const { error } = await createPublicClient().auth.resetPasswordForEmail(email, {
      redirectTo: resetRedirectUrl(),
    });
    if (error) throw error;
    // Keep the response generic so this endpoint cannot enumerate accounts.
    return res.status(200).json({
      message: 'If that account exists, a password reset link has been sent.',
    });
  } catch (error) {
    console.error('Password reset request failed:', error);
    return res.status(error.status || 500).json({
      error: publicError(error, 'Could not send a reset link. Please try again.'),
    });
  }
}
