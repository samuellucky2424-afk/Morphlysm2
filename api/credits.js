import { requireAdmin } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  parseRoute,
  publicError,
} from './_shared/http.js';

async function lookup(service, email) {
  const { data: user, error: userError } = await service
    .from('user_sm')
    .select('id,email,role,status,is_activated')
    .eq('email', email)
    .single();
  if (userError || !user) {
    const error = new Error('User not found');
    error.status = 404;
    throw error;
  }
  const [{ data: wallet, error: walletError }, { data: usage, error: usageError }] =
    await Promise.all([
      service.from('wallet_sm').select('balance').eq('user_id', user.id).single(),
      service.from('usage_sm').select('credits_used').eq('user_id', user.id).eq('status', 'success'),
    ]);
  if (walletError) throw walletError;
  if (usageError) throw usageError;
  const used = (usage || []).reduce((sum, row) => sum + Number(row.credits_used || 0), 0);
  return { user, balance: Number(wallet.balance || 0), used };
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, POST, OPTIONS');
  if (handleOptions(req, res)) return;

  try {
    const context = await requireAdmin(req);
    const path = parseRoute(req, '/api/credits');
    if (req.method === 'POST' && path === '/add') {
      const email = cleanString(req.body?.user_email, 320).toLowerCase();
      const delta = Number.parseInt(req.body?.credits, 10);
      if (!email || !Number.isSafeInteger(delta) || delta === 0) {
        return res.status(400).json({ error: 'user_email and a non-zero integer credits value are required' });
      }
      const { data, error } = await context.client.rpc('admin_adjust_credits_sm', {
        p_email: email,
        p_delta: delta,
        p_reference: cleanString(req.body?.reference, 160) || null,
        p_note: req.body?.package_id
          ? `Dashboard package: ${cleanString(req.body.package_id, 64)}`
          : 'Dashboard manual adjustment',
        p_idempotency_key: cleanString(req.headers['idempotency-key'], 160) || null,
      });
      if (error) throw error;
      const result = Array.isArray(data) ? data[0] : data;
      return res.status(200).json({
        email,
        new_total: Number(result.balance),
        remaining: Number(result.balance),
      });
    }

    if (req.method === 'GET' && path.startsWith('/email/')) {
      const email = decodeURIComponent(path.slice('/email/'.length)).trim().toLowerCase();
      const result = await lookup(context.service, email);
      return res.status(200).json({
        email,
        plan: result.user.role === 'admin'
          ? 'Admin Plan'
          : (result.user.is_activated ? 'Pro Activation' : 'Free Tier'),
        total: result.balance + result.used,
        used: result.used,
        remaining: result.balance,
      });
    }
    return res.status(404).json({ error: 'Endpoint not found' });
  } catch (error) {
    console.error('Credits API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
