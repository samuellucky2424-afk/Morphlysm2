import crypto from 'crypto';
import { requireAdmin } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  parseRoute,
  positiveInteger,
  publicError,
} from './_shared/http.js';

const USER_STATUSES = new Set(['active', 'suspended', 'banned', 'deleted']);

function asArray(data) {
  if (!data) return [];
  return Array.isArray(data) ? data : [data];
}

function packageDto(row) {
  return {
    id: row.code,
    name: row.name,
    description: row.description || '',
    credits: Number(row.credits),
    price: Number(row.price),
    currency: row.currency,
    timeLabel: row.time_label || '',
    active: row.is_active,
  };
}

async function setting(service, key, fallback = {}) {
  const { data, error } = await service
    .from('app_setting_sm')
    .select('value')
    .eq('key', key)
    .maybeSingle();
  if (error) throw error;
  return data?.value || fallback;
}

async function saveSetting(service, key, value, isPublic, description) {
  const { error } = await service.from('app_setting_sm').upsert({
    key,
    value,
    is_public: isPublic,
    description,
  });
  if (error) throw error;
}

async function audit(context, action, entityType, entityId, newData = null, oldData = null) {
  const { error } = await context.service.from('admin_audit_sm').insert({
    actor_user_id: context.appUser.id,
    action,
    entity_type: entityType,
    entity_id: entityId ? String(entityId) : null,
    old_data: oldData,
    new_data: newData,
  });
  if (error) console.error('Admin audit insert failed:', error);
}

async function overview(context, res) {
  const { data, error } = await context.service.rpc('admin_overview_sm');
  if (error) throw error;
  return res.status(200).json(asArray(data)[0] || {});
}

async function packages(context, req, res) {
  if (req.method === 'GET') {
    const { data, error } = await context.service
      .from('credit_package_sm')
      .select('*')
      .order('sort_order');
    if (error) throw error;
    return res.status(200).json({ packages: (data || []).map(packageDto) });
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  if (!Array.isArray(req.body?.packages) || !req.body.packages.length) {
    return res.status(400).json({ error: 'packages array is required' });
  }
  const rows = req.body.packages.map((item, index) => {
    const code = cleanString(item.id || item.code, 64).toLowerCase();
    const credits = positiveInteger(item.credits, { max: 100000000 });
    const price = Number(item.price);
    if (!code || !credits || !Number.isFinite(price) || price < 0) {
      throw new Error('Each package requires a valid id, credits, and non-negative price');
    }
    return {
      code,
      name: cleanString(item.name, 120) || code,
      credits,
      price,
      currency: cleanString(item.currency, 10).toUpperCase() || 'NGN',
      time_label: cleanString(item.timeLabel, 80) || null,
      sort_order: (index + 1) * 10,
      is_active: item.active !== false,
    };
  });
  const { error } = await context.service.from('credit_package_sm').upsert(rows, { onConflict: 'code' });
  if (error) throw error;
  await audit(context, 'packages.save', 'credit_package_sm', null, { count: rows.length });
  return res.status(200).json({ success: true, packages: rows.map(packageDto) });
}

async function globalConfig(context, req, res) {
  if (req.method === 'GET') {
    return res.status(200).json(await setting(context.service, 'global_config', { notification: '' }));
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  const value = { notification: cleanString(req.body?.notification, 1000) };
  await saveSetting(context.service, 'global_config', value, true, 'Public Android configuration');
  await audit(context, 'config.save', 'app_setting_sm', 'global_config', value);
  return res.status(200).json({ success: true });
}

async function listUsers(context, req, res) {
  const page = Math.max(Number.parseInt(req.query?.page, 10) || 1, 1);
  const limit = Math.min(Math.max(Number.parseInt(req.query?.limit, 10) || 50, 1), 100);
  const { data, error } = await context.service.rpc('admin_list_users_sm', {
    p_search: cleanString(req.query?.q, 200),
    p_limit: limit,
    p_offset: (page - 1) * limit,
  });
  if (error) throw error;
  const rows = data || [];
  return res.status(200).json({
    users: rows.map((row) => ({
      ...row,
      last_login: row.last_login_at,
      credits_remaining: Number(row.credits_remaining || 0),
      credits_used: Number(row.credits_used || 0),
    })),
    page,
    pageSize: limit,
    total: Number(rows[0]?.total_count || 0),
    hasNext: page * limit < Number(rows[0]?.total_count || 0),
  });
}

async function userByEmail(service, rawEmail, includeDeleted = true) {
  const email = cleanString(rawEmail, 320).toLowerCase();
  if (!email) {
    const error = new Error('email is required');
    error.status = 400;
    throw error;
  }
  let query = service
    .from('account_summary_sm')
    .select('*')
    .eq('email', email);
  if (!includeDeleted) query = query.neq('status', 'deleted');
  const { data, error } = await query.maybeSingle();
  if (error) throw error;
  return data;
}

async function lookupUser(context, req, res) {
  const row = await userByEmail(context.service, req.query?.email);
  if (!row) return res.status(200).json({ user_exists: false });
  return res.status(200).json({
    user_exists: true,
    email: row.email,
    user: {
      id: row.user_id,
      name: row.display_name,
      device_id: row.device_id || '',
      created_at: row.created_at,
      phone: row.phone || '',
      last_login: row.last_login_at,
      status: row.status,
    },
    credits: {
      total: Number(row.balance || 0) + Number(row.credits_used || 0),
      used: Number(row.credits_used || 0),
      remaining: Number(row.balance || 0),
    },
    license: {
      status: row.is_activated ? 'active' : 'none',
      expires_at: row.license_expires_at,
    },
  });
}

async function setUserStatus(context, req, res, forcedStatus = null) {
  const email = cleanString(req.body?.email || req.query?.email, 320).toLowerCase();
  const status = forcedStatus || cleanString(req.body?.status, 20).toLowerCase();
  if (!email || !USER_STATUSES.has(status)) {
    return res.status(400).json({ error: 'A valid email and account status are required' });
  }
  const { data: user, error: findError } = await context.service
    .from('user_sm')
    .select('id,email,status,role')
    .eq('email', email)
    .single();
  if (findError || !user) return res.status(404).json({ error: 'User not found' });
  if (user.id === context.appUser.id && status !== 'active') {
    return res.status(400).json({ error: 'You cannot disable your own administrator account' });
  }
  const { error } = await context.service.from('user_sm').update({ status }).eq('id', user.id);
  if (error) throw error;
  const { error: authError } = await context.service.auth.admin.updateUserById(user.id, {
    ban_duration: status === 'active' ? 'none' : '876000h',
  });
  if (authError) throw authError;
  await audit(context, 'user.status', 'user_sm', user.id, { status }, { status: user.status });
  return res.status(200).json({ success: true, message: `User status updated to ${status}` });
}

async function engineKeys(context, req, res) {
  if (req.method === 'GET') {
    const { data, error } = await context.service
      .from('engine_key_sm')
      .select('id,label,sort_order,is_active,is_exhausted,last_selected_at,updated_at')
      .order('sort_order');
    if (error) throw error;
    const active = (data || []).filter((row) => row.is_active);
    return res.status(200).json({
      mode: active.length > 1 ? 'multi' : 'single',
      configured: active.length > 0,
      keys: (data || []).map((row) => ({
        ...row,
        display: `${row.label} (secret stored in Vault)`,
      })),
    });
  }
  if (req.method === 'DELETE') {
    const { error } = await context.service
      .from('engine_key_sm')
      .update({ is_active: false })
      .eq('is_active', true);
    if (error) throw error;
    await audit(context, 'engine_keys.clear', 'engine_key_sm');
    return res.status(200).json({ success: true });
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  const mode = req.body?.mode === 'multi' ? 'multi' : 'single';
  const secrets = mode === 'multi'
    ? (Array.isArray(req.body?.keys) ? req.body.keys : [])
    : [req.body?.single_key];
  const cleaned = secrets.map((value) => cleanString(value, 1000)).filter(Boolean);
  if (!cleaned.length) return res.status(400).json({ error: 'At least one engine key is required' });
  if (cleaned.length > 20) return res.status(400).json({ error: 'At most 20 engine keys are allowed' });

  const { error: disableError } = await context.service
    .from('engine_key_sm')
    .update({ is_active: false })
    .eq('is_active', true);
  if (disableError) throw disableError;
  for (let index = 0; index < cleaned.length; index += 1) {
    const { error } = await context.client.rpc('save_engine_key_sm', {
      p_label: mode === 'single' ? 'primary' : `rotation-${index + 1}`,
      p_secret: cleaned[index],
      p_sort_order: index,
      p_is_active: true,
    });
    if (error) throw error;
  }
  await audit(context, 'engine_keys.save', 'engine_key_sm', null, {
    mode,
    count: cleaned.length,
  });
  return res.status(200).json({ success: true, mode, count: cleaned.length });
}

async function resetEngineKeys(context, res) {
  const { error } = await context.service
    .from('engine_key_sm')
    .update({ is_exhausted: false })
    .eq('is_active', true);
  if (error) throw error;
  await audit(context, 'engine_keys.reset', 'engine_key_sm');
  return res.status(200).json({ success: true });
}

async function keyLogs(context, req, res) {
  if (req.method === 'DELETE') {
    const id = cleanString(req.query?.id, 64);
    if (!id) return res.status(400).json({ error: 'id is required' });
    const { error } = await context.service.from('key_log_sm').delete().eq('id', id);
    if (error) throw error;
    await audit(context, 'key_log.delete', 'key_log_sm', id);
    return res.status(200).json({ success: true });
  }
  const { data, error } = await context.service
    .from('key_log_sm')
    .select('id,email_snapshot,access_key_last4,device_id,activated_at,expires_at,status')
    .order('activated_at', { ascending: false })
    .limit(200);
  if (error) throw error;
  return res.status(200).json({
    logs: (data || []).map((row) => ({
      id: row.id,
      email: row.email_snapshot,
      access_key: row.access_key_last4 ? `••••${row.access_key_last4}` : '••••',
      device_id: row.device_id,
      activated_at: row.activated_at,
      expires_at: row.expires_at,
      status: row.status,
    })),
  });
}

async function jsonSetting(context, req, res, config) {
  if (req.method === 'GET') {
    const value = await setting(context.service, config.key, config.fallback);
    return res.status(200).json(config.output ? config.output(value) : value);
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  const value = config.input(req.body || {});
  await saveSetting(context.service, config.key, value, config.isPublic, config.description);
  await audit(context, 'setting.save', 'app_setting_sm', config.key, value);
  return res.status(200).json({ success: true, ...value });
}

async function streamingMonitor(context, res) {
  const [{ data: sessions, error }, { count: withCredits }, { count: withoutCredits }] =
    await Promise.all([
      context.service
        .from('active_stream_monitor_sm')
        .select('*')
        .order('heartbeat_at', { ascending: false }),
      context.service.from('wallet_sm').select('*', { count: 'exact', head: true }).gt('balance', 0),
      context.service.from('wallet_sm').select('*', { count: 'exact', head: true }).eq('balance', 0),
    ]);
  if (error) throw error;
  return res.status(200).json({
    active_sessions: sessions || [],
    total_streaming: sessions?.length || 0,
    users_with_credits: withCredits || 0,
    users_without_credits: withoutCredits || 0,
    timestamp: new Date().toISOString(),
  });
}

async function payments(context, req, res, provider) {
  const limit = Math.min(Math.max(Number.parseInt(req.query?.limit, 10) || 100, 1), 500);
  let query = context.service
    .from('payment_transaction_sm')
    .select('id,user_email_snapshot,provider,tx_ref,provider_transaction_id,credits,expected_amount,currency,status,created_at,verified_at,failure_reason')
    .eq('provider', provider)
    .order('created_at', { ascending: false })
    .limit(limit);
  const status = cleanString(req.query?.status, 20);
  if (status) query = query.eq('status', status);
  const { data, error } = await query;
  if (error) throw error;
  return res.status(200).json({ payments: data || [] });
}

async function backgrounds(context, req, res) {
  if (req.method === 'GET') {
    const { data, error } = await context.service
      .from('background_preset_sm')
      .select('*')
      .order('sort_order');
    if (error) throw error;
    return res.status(200).json({ presets: data || [] });
  }
  if (req.method !== 'POST' || !Array.isArray(req.body?.presets)) {
    return res.status(400).json({ error: 'presets array is required' });
  }
  const rows = req.body.presets.slice(0, 100).map((item, index) => ({
    code: cleanString(item.code || item.id, 64).toLowerCase(),
    label: cleanString(item.label || item.name, 120),
    prompt: cleanString(item.prompt, 2000),
    preview_url: cleanString(item.preview_url || item.previewUrl, 1000) || null,
    sort_order: index * 10,
    is_active: item.is_active !== false,
  }));
  if (rows.some((row) => !row.code || !row.label || !row.prompt)) {
    return res.status(400).json({ error: 'Every preset requires code, label, and prompt' });
  }
  const { error } = await context.service.from('background_preset_sm').upsert(rows, { onConflict: 'code' });
  if (error) throw error;
  await audit(context, 'backgrounds.save', 'background_preset_sm', null, { count: rows.length });
  return res.status(200).json({ success: true });
}

async function creatorOverview(context, res) {
  const [
    { count: creators },
    { count: referrals },
    { count: conversions },
    { data: commissions, error },
  ] = await Promise.all([
    context.service.from('creator_sm').select('*', { count: 'exact', head: true }).eq('status', 'active'),
    context.service.from('referral_sm').select('*', { count: 'exact', head: true }),
    context.service.from('referral_sm').select('*', { count: 'exact', head: true }).eq('status', 'converted'),
    context.service.from('commission_sm').select('commission_amount,status'),
  ]);
  if (error) throw error;
  return res.status(200).json({
    active_creators: creators || 0,
    referrals: referrals || 0,
    conversions: conversions || 0,
    pending_commission: (commissions || [])
      .filter((row) => ['pending', 'approved', 'held'].includes(row.status))
      .reduce((sum, row) => sum + Number(row.commission_amount || 0), 0),
  });
}

async function creators(context, req, res) {
  if (req.method === 'GET') {
    const { data, error } = await context.service
      .from('creator_sm')
      .select('*')
      .order('created_at', { ascending: false });
    if (error) throw error;
    return res.status(200).json({ creators: data || [] });
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  const email = cleanString(req.body?.email, 320).toLowerCase();
  const displayName = cleanString(req.body?.displayName || req.body?.name, 120);
  if (!email || !displayName) return res.status(400).json({ error: 'email and displayName are required' });
  const { data: user } = await context.service.from('user_sm').select('id').eq('email', email).maybeSingle();
  const referralCode = cleanString(req.body?.referralCode, 64).toUpperCase()
    || `CREATOR-${crypto.randomBytes(4).toString('hex').toUpperCase()}`;
  const row = {
    user_id: user?.id || null,
    email,
    display_name: displayName,
    referral_code: referralCode,
    commission_rate: Math.min(Math.max(Number(req.body?.commissionRate) || 30, 0), 100),
    referral_bonus_enabled: true,
    referral_bonus_credits: 250,
    status: 'active',
  };
  const { data, error } = await context.service
    .from('creator_sm')
    .insert(row)
    .select()
    .single();
  if (error) throw error;
  if (user?.id) {
    await context.service.from('user_sm').update({ role: 'creator' }).eq('id', user.id);
  }
  await audit(context, 'creator.create', 'creator_sm', data.id, { email, referralCode });
  return res.status(201).json({ creator: data });
}

async function listTable(context, res, table, key) {
  const orderColumn = table === 'referral_sm' ? 'signed_up_at' : 'created_at';
  const { data, error } = await context.service
    .from(table)
    .select('*')
    .order(orderColumn, { ascending: false })
    .limit(500);
  if (error) throw error;
  return res.status(200).json({ [key]: data || [] });
}

async function supportTickets(context, req, res) {
  if (req.method === 'GET') {
    const { data, error } = await context.service
      .from('support_ticket_sm')
      .select('*')
      .order('created_at', { ascending: false })
      .limit(500);
    if (error) throw error;
    return res.status(200).json({ tickets: data || [] });
  }
  if (req.method !== 'PATCH') return res.status(405).json({ error: 'Method not allowed' });
  const id = cleanString(req.body?.id, 64);
  const status = cleanString(req.body?.status, 30);
  if (!id || !['open', 'in_progress', 'resolved', 'closed'].includes(status)) {
    return res.status(400).json({ error: 'A valid ticket id and status are required' });
  }
  const changes = {
    status,
    resolution: cleanString(req.body?.resolution, 4000) || null,
    assigned_to: context.appUser.id,
    resolved_at: ['resolved', 'closed'].includes(status) ? new Date().toISOString() : null,
  };
  const { error } = await context.service.from('support_ticket_sm').update(changes).eq('id', id);
  if (error) throw error;
  await audit(context, 'support.status', 'support_ticket_sm', id, changes);
  return res.status(200).json({ success: true });
}

async function sendMassEmail(context, req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  const subject = cleanString(req.body?.subject, 200);
  const bodyText = cleanString(req.body?.body, 10000);
  if (!subject || !bodyText) return res.status(400).json({ error: 'subject and body are required' });
  const resendKey = String(process.env.RESEND_API_KEY || '').trim();
  const from = String(process.env.EMAIL_FROM || '').trim();
  if (!resendKey || !from) {
    return res.status(503).json({ error: 'Mass email requires RESEND_API_KEY and EMAIL_FROM' });
  }
  const { data: users, error: usersError } = await context.service
    .from('user_sm')
    .select('id,email')
    .eq('status', 'active')
    .order('created_at')
    .limit(5000);
  if (usersError) throw usersError;
  if (!users?.length) return res.status(400).json({ error: 'No active recipients found' });

  const { data: campaign, error: campaignError } = await context.service
    .from('email_campaign_sm')
    .insert({
      subject,
      body_text: bodyText,
      status: 'sending',
      audience_filter: { status: 'active' },
      total_recipients: users.length,
      created_by: context.appUser.id,
      started_at: new Date().toISOString(),
    })
    .select()
    .single();
  if (campaignError) throw campaignError;

  const deliveries = users.map((user) => ({
    campaign_id: campaign.id,
    user_id: user.id,
    email_snapshot: user.email,
  }));
  const { error: deliveryError } = await context.service.from('email_delivery_sm').insert(deliveries);
  if (deliveryError) throw deliveryError;

  let sent = 0;
  let failed = 0;
  for (let offset = 0; offset < users.length; offset += 100) {
    const batch = users.slice(offset, offset + 100);
    const response = await fetch('https://api.resend.com/emails/batch', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${resendKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(batch.map((user) => ({
        from,
        to: [user.email],
        subject,
        text: bodyText,
      }))),
    });
    if (response.ok) {
      await context.service
        .from('email_delivery_sm')
        .update({ status: 'sent', sent_at: new Date().toISOString() })
        .eq('campaign_id', campaign.id)
        .in('user_id', batch.map((user) => user.id));
      sent += batch.length;
    } else {
      const providerError = await response.text();
      await context.service
        .from('email_delivery_sm')
        .update({ status: 'failed', error_message: providerError.slice(0, 1000) })
        .eq('campaign_id', campaign.id)
        .in('user_id', batch.map((user) => user.id));
      failed += batch.length;
    }
  }
  await context.service
    .from('email_campaign_sm')
    .update({
      status: failed ? (sent ? 'sent' : 'failed') : 'sent',
      sent_count: sent,
      failed_count: failed,
      completed_at: new Date().toISOString(),
    })
    .eq('id', campaign.id);
  await audit(context, 'email_campaign.send', 'email_campaign_sm', campaign.id, { sent, failed });
  return res.status(200).json({ success: true, campaignId: campaign.id, sent, failed });
}

async function approveCommissions(context, res) {
  const { data, error } = await context.service
    .from('commission_sm')
    .update({ status: 'approved', approved_at: new Date().toISOString() })
    .eq('status', 'pending')
    .select('id');
  if (error) throw error;
  await audit(context, 'commissions.approve_all', 'commission_sm', null, { count: data?.length || 0 });
  return res.status(200).json({ success: true, count: data?.length || 0 });
}

async function createPayoutBatch(context, req, res) {
  const periodEnd = cleanString(req.body?.periodEnd, 10) || new Date().toISOString().slice(0, 10);
  const periodStart = cleanString(req.body?.periodStart, 10)
    || new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
  const { data: commissions, error } = await context.service
    .from('commission_sm')
    .select('creator_id,commission_amount,currency')
    .eq('status', 'approved')
    .gte('created_at', `${periodStart}T00:00:00Z`)
    .lte('created_at', `${periodEnd}T23:59:59Z`);
  if (error) throw error;
  const totals = new Map();
  for (const row of commissions || []) {
    const current = totals.get(row.creator_id) || { amount: 0, currency: row.currency };
    current.amount += Number(row.commission_amount || 0);
    totals.set(row.creator_id, current);
  }
  if (!totals.size) return res.status(400).json({ error: 'No approved commissions in this period' });
  const currency = [...totals.values()][0].currency || 'NGN';
  const total = [...totals.values()].reduce((sum, value) => sum + value.amount, 0);
  const { data: batch, error: batchError } = await context.service
    .from('payout_batch_sm')
    .insert({
      period_start: periodStart,
      period_end: periodEnd,
      total_amount: total,
      currency,
      created_by: context.appUser.id,
    })
    .select()
    .single();
  if (batchError) throw batchError;
  const payoutRows = [...totals.entries()].map(([creatorId, value]) => ({
    batch_id: batch.id,
    creator_id: creatorId,
    amount: value.amount,
    currency: value.currency,
    period_start: periodStart,
    period_end: periodEnd,
  }));
  const { error: payoutError } = await context.service.from('payout_sm').insert(payoutRows);
  if (payoutError) throw payoutError;
  await audit(context, 'payout_batch.create', 'payout_batch_sm', batch.id, { total, count: payoutRows.length });
  return res.status(201).json({ batch, payouts: payoutRows });
}

async function fraudScan(context, res) {
  const { data: referrals, error } = await context.service
    .from('referral_sm')
    .select('id,creator_id,referred_user_id,signup_ip_hash,device_hash,status');
  if (error) throw error;
  const groups = new Map();
  for (const row of referrals || []) {
    for (const [type, hash] of [['duplicate_signup_ip', row.signup_ip_hash], ['duplicate_device', row.device_hash]]) {
      if (!hash) continue;
      const key = `${type}:${hash}`;
      if (!groups.has(key)) groups.set(key, { type, hash, rows: [] });
      groups.get(key).rows.push(row);
    }
  }
  const suspicious = [...groups.values()].filter((group) => group.rows.length > 1);
  for (const group of suspicious) {
    const first = group.rows[0];
    const evidence = { hash: group.hash, referralIds: group.rows.map((row) => row.id) };
    const { data: existing } = await context.service
      .from('fraud_event_sm')
      .select('id')
      .eq('event_type', group.type)
      .contains('evidence', { hash: group.hash })
      .maybeSingle();
    if (!existing) {
      await context.service.from('fraud_event_sm').insert({
        user_id: first.referred_user_id,
        creator_id: first.creator_id,
        referral_id: first.id,
        event_type: group.type,
        severity: group.rows.length >= 5 ? 'high' : 'medium',
        evidence,
      });
    }
  }
  const { data: events, error: eventsError } = await context.service
    .from('fraud_event_sm')
    .select('*')
    .order('created_at', { ascending: false })
    .limit(500);
  if (eventsError) throw eventsError;
  return res.status(200).json({ events: events || [], detected: suspicious.length });
}

async function route(context, path, req, res) {
  if (path === '/overview') return overview(context, res);
  if (path === '/packages') return packages(context, req, res);
  if (path === '/config') return globalConfig(context, req, res);
  if (path === '/users' && req.method === 'GET') return listUsers(context, req, res);
  if (path === '/users' && req.method === 'DELETE') return setUserStatus(context, req, res, 'deleted');
  if (path === '/users/lookup') return lookupUser(context, req, res);
  if (path === '/users/status') return setUserStatus(context, req, res);
  if (path === '/users/restore') return setUserStatus(context, req, res, 'active');
  if (path === '/key-logs') return keyLogs(context, req, res);
  if (path === '/engine-key') return engineKeys(context, req, res);
  if (path === '/engine-key/reset') return resetEngineKeys(context, res);
  if (path === '/free-credits-settings') {
    return jsonSetting(context, req, res, {
      key: 'free_credits',
      fallback: { enabled: false, amount: 1500 },
      input: (body) => ({
        enabled: body.enabled === true,
        amount: positiveInteger(body.amount, { max: 1000000 }) || 1500,
      }),
      isPublic: false,
      description: 'Activation bonus settings',
    });
  }
  if (path === '/streaming-availability') {
    return jsonSetting(context, req, res, {
      key: 'streaming_availability',
      fallback: { enabled: true },
      input: (body) => ({ enabled: body.enabled !== false }),
      isPublic: true,
      description: 'Global live streaming switch',
    });
  }
  if (path === '/referral-settings') {
    return jsonSetting(context, req, res, {
      key: 'referral_program',
      fallback: { enabled: true, signup_credits: 50, purchase_reward: 250 },
      input: (body) => ({
        enabled: body.enabled !== false,
        signup_credits: 50,
        purchase_reward: 250,
      }),
      output: (value) => ({
        enabled: value.enabled !== false,
        signupCredits: 50,
        rewardAmount: 250,
      }),
      isPublic: true,
      description: 'Signup and first-purchase referral rewards',
    });
  }
  if (path === '/streaming-monitor') return streamingMonitor(context, res);
  if (path === '/payments') return payments(context, req, res, 'flutterwave');
  if (path === '/crypto') return payments(context, req, res, 'nowpayments');
  if (path === '/backgrounds') return backgrounds(context, req, res);
  if (path === '/support') return supportTickets(context, req, res);
  if (path === '/mass-email') return sendMassEmail(context, req, res);
  if (path === '/creator/overview') return creatorOverview(context, res);
  if (path === '/creator/settings') {
    return jsonSetting(context, req, res, {
      key: 'commission_settings',
      fallback: { default_rate: 30, minimum_payout: 5000 },
      input: (body) => ({
        default_rate: Math.min(Math.max(Number(body.default_rate ?? body.defaultRate) || 30, 0), 100),
        minimum_payout: Math.max(Number(body.minimum_payout ?? body.minimumPayout) || 5000, 0),
      }),
      isPublic: false,
      description: 'Creator commission defaults',
    });
  }
  if (path === '/creator/creators') return creators(context, req, res);
  if (path === '/creator/referrals') return listTable(context, res, 'referral_sm', 'referrals');
  if (path === '/creator/commissions') return listTable(context, res, 'commission_sm', 'commissions');
  if (path === '/creator/commissions/approve-all') return approveCommissions(context, res);
  if (path === '/creator/payouts') return listTable(context, res, 'payout_sm', 'payouts');
  if (path === '/creator/payout-batches' && req.method === 'POST') return createPayoutBatch(context, req, res);
  if (path === '/creator/fraud-scan') return fraudScan(context, res);
  return res.status(404).json({ error: 'Admin endpoint not found' });
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res);
  if (handleOptions(req, res)) return;
  try {
    const context = await requireAdmin(req);
    return await route(context, parseRoute(req, '/api/admin'), req, res);
  } catch (error) {
    console.error('Admin API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
