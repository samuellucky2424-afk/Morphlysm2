import crypto from 'crypto';
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

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  const name = cleanString(req.body?.name, 120);
  const email = cleanString(req.body?.email, 320).toLowerCase();
  const phone = cleanString(req.body?.phone, 40);
  const password = String(req.body?.password || '');
  const referredByCode = cleanString(req.body?.referredByCode, 64).toUpperCase();

  if (!name || !email || !password) {
    return res.status(400).json({ error: 'name, email, and password are required' });
  }
  if (!EMAIL_PATTERN.test(email)) {
    return res.status(400).json({ error: 'Enter a valid email address' });
  }
  if (password.length < 8 || password.length > 128) {
    return res.status(400).json({ error: 'Password must be between 8 and 128 characters' });
  }

  let service;
  let createdUserId = null;
  try {
    service = createServiceClient();
    // Server-created users are email-confirmed so the Android app can sign in
    // immediately. The database Auth trigger atomically provisions the profile,
    // 50-credit signup bonus, wallet, and optional referral link.
    const { data: created, error: createError } = await service.auth.admin.createUser({
      email,
      password,
      email_confirm: true,
      user_metadata: {
        name,
        display_name: name,
        phone: phone || null,
        referredByCode: referredByCode || null,
      },
    });
    if (createError || !created?.user) {
      const message = String(createError?.message || '');
      if (message.toLowerCase().includes('already')) {
        return res.status(409).json({ error: 'The email address is already registered.' });
      }
      throw createError || new Error('Could not create account');
    }
    createdUserId = created.user.id;

    const referralHashSecret = String(process.env.REFERRAL_HASH_SECRET || '').trim();
    const forwardedFor = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
    const deviceId = cleanString(req.body?.deviceId, 160);
    if (referredByCode && referralHashSecret) {
      const hash = (value) => value
        ? crypto.createHmac('sha256', referralHashSecret).update(value).digest('hex')
        : null;
      const { error: referralUpdateError } = await service
        .from('referral_sm')
        .update({
          signup_ip_hash: hash(forwardedFor),
          device_hash: hash(deviceId),
        })
        .eq('referred_user_id', createdUserId);
      if (referralUpdateError) throw referralUpdateError;
    }

    const authClient = createPublicClient();
    const { data: sessionData, error: signInError } = await authClient.auth.signInWithPassword({
      email,
      password,
    });
    if (signInError || !sessionData?.session) {
      throw signInError || new Error('Account created, but automatic sign-in failed');
    }

    const summary = await getAccountSummary(service, createdUserId);
    return res.status(200).json({
      message: 'Registration successful',
      token: sessionData.session.access_token,
      refreshToken: sessionData.session.refresh_token,
      expiresIn: sessionData.session.expires_in,
      expiresAt: sessionData.session.expires_at,
      user: formatAccount(summary),
    });
  } catch (error) {
    if (createdUserId && service) {
      const { error: cleanupError } = await service.auth.admin.deleteUser(createdUserId);
      if (cleanupError) console.error('Signup rollback failed:', cleanupError);
    }
    console.error('Signup API failed:', error);
    return res.status(error.status || 400).json({
      error: publicError(error, 'Registration failed. Please try again.'),
    });
  }
}
