import { createClient } from '@supabase/supabase-js';

function requiredEnv(...names) {
  for (const name of names) {
    const value = String(process.env[name] || '').trim();
    if (value) return value;
  }
  throw new Error(`Missing required server configuration: ${names.join(' or ')}`);
}

export function supabaseUrl() {
  return requiredEnv('SUPABASE_URL');
}

export function publicSupabaseKey() {
  return requiredEnv('SUPABASE_PUBLISHABLE_KEY', 'SUPABASE_ANON_KEY');
}

export function secretSupabaseKey() {
  return requiredEnv('SUPABASE_SECRET_KEY', 'SUPABASE_SERVICE_ROLE_KEY');
}

const authOptions = {
  persistSession: false,
  autoRefreshToken: false,
  detectSessionInUrl: false,
};

export function createPublicClient() {
  return createClient(supabaseUrl(), publicSupabaseKey(), { auth: authOptions });
}

export function createServiceClient() {
  return createClient(supabaseUrl(), secretSupabaseKey(), { auth: authOptions });
}

export function bearerToken(req) {
  const header = String(req.headers?.authorization || '');
  const match = /^Bearer\s+(.+)$/i.exec(header);
  return match?.[1]?.trim() || '';
}

export function createUserClient(token) {
  return createClient(supabaseUrl(), publicSupabaseKey(), {
    auth: authOptions,
    global: { headers: { Authorization: `Bearer ${token}` } },
  });
}

export async function requireUser(req) {
  const token = bearerToken(req);
  if (!token) {
    const error = new Error('Authentication required');
    error.status = 401;
    throw error;
  }

  const client = createUserClient(token);
  const { data: authData, error: authError } = await client.auth.getUser(token);
  if (authError || !authData?.user) {
    const error = new Error('Session is invalid or expired');
    error.status = 401;
    throw error;
  }

  const service = createServiceClient();
  const { data: appUser, error: userError } = await service
    .from('user_sm')
    .select('id,email,phone,role,status,referral_code,is_activated,license_expires_at')
    .eq('id', authData.user.id)
    .single();
  if (userError || !appUser) {
    const error = new Error('Application account was not found');
    error.status = 403;
    throw error;
  }
  if (appUser.status !== 'active') {
    const error = new Error(`Account is ${appUser.status}`);
    error.status = 403;
    throw error;
  }

  return { token, client, service, authUser: authData.user, appUser };
}

export async function requireAdmin(req) {
  const context = await requireUser(req);
  if (!['admin', 'moderator'].includes(context.appUser.role)) {
    const error = new Error('Administrator access required');
    error.status = 403;
    throw error;
  }
  return context;
}

export async function getAccountSummary(client, userId) {
  const { data, error } = await client
    .from('account_summary_sm')
    .select('*')
    .eq('user_id', userId)
    .single();
  if (error) throw error;
  return data;
}

export function formatAccount(summary) {
  return {
    id: summary.user_id,
    email: summary.email,
    name: summary.display_name || summary.email?.split('@')[0] || 'User',
    phone: summary.phone || '',
    role: summary.role || 'user',
    status: summary.status || 'active',
    balance: Number(summary.balance || 0),
    creditsUsed: Number(summary.credits_used || 0),
    referralCode: summary.referral_code || '',
    deviceId: summary.device_id || '',
    is_activated: summary.is_activated === true || summary.role === 'admin',
    licenseExpiresAt: summary.license_expires_at || null,
    createdAt: summary.created_at || null,
  };
}

export function throwIfError(error) {
  if (error) throw error;
}
