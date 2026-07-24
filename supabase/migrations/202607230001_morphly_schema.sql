-- Morphly Firebase -> Supabase schema
-- Run once in a new Supabase project before importing Firebase Auth users.
-- This migration is intentionally fail-fast and wrapped in one transaction.

begin;

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
create extension if not exists supabase_vault with schema vault;

create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Core application tables
-- ---------------------------------------------------------------------------

create table public.app_setting_sm (
  key text primary key check (key ~ '^[a-z][a-z0-9_]{1,63}$'),
  value jsonb not null default '{}'::jsonb,
  is_public boolean not null default false,
  description text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.user_sm (
  id uuid primary key references auth.users(id) on delete cascade,
  email text not null check (email = lower(btrim(email))),
  phone text,
  role text not null default 'user'
    check (role in ('user', 'creator', 'moderator', 'admin')),
  status text not null default 'active'
    check (status in ('active', 'suspended', 'banned', 'deleted')),
  device_id text,
  referral_code text not null,
  referred_by uuid references public.user_sm(id) on delete set null,
  is_activated boolean not null default false,
  license_expires_at timestamptz,
  access_key text,
  last_login_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index user_sm_email_lower_uidx on public.user_sm (lower(email));
create unique index user_sm_referral_code_upper_uidx on public.user_sm (upper(referral_code));
create index user_sm_referred_by_idx on public.user_sm (referred_by);
create index user_sm_status_role_idx on public.user_sm (status, role);
create index user_sm_created_at_idx on public.user_sm (created_at desc);

create table public.profile_sm (
  id uuid primary key references public.user_sm(id) on delete cascade,
  display_name text not null default '',
  avatar_url text,
  bio text,
  country text check (country is null or country ~ '^[A-Z]{2}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.wallet_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references public.user_sm(id) on delete cascade,
  balance bigint not null default 0 check (balance >= 0),
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3,10}$'),
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.credit_package_sm (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code ~ '^[a-z0-9][a-z0-9_-]{1,63}$'),
  name text not null,
  description text,
  credits integer not null check (credits > 0),
  price numeric(18,2) not null check (price >= 0),
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3,10}$'),
  time_label text,
  sort_order integer not null default 0,
  is_active boolean not null default true,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index credit_package_sm_active_sort_idx
  on public.credit_package_sm (is_active, sort_order, created_at);

create table public.payment_transaction_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  user_email_snapshot text not null,
  package_id uuid references public.credit_package_sm(id) on delete set null,
  provider text not null check (provider in ('flutterwave', 'nowpayments', 'manual')),
  tx_ref text not null unique,
  provider_transaction_id text,
  credits integer not null check (credits > 0),
  expected_amount numeric(18,2) not null check (expected_amount >= 0),
  currency text not null check (currency ~ '^[A-Z]{3,10}$'),
  status text not null default 'pending'
    check (status in ('pending', 'verified', 'failed', 'cancelled', 'refunded')),
  provider_payload jsonb,
  verification_payload jsonb,
  failure_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  verified_at timestamptz,
  refunded_at timestamptz
);

create unique index payment_transaction_sm_provider_id_uidx
  on public.payment_transaction_sm (provider, provider_transaction_id)
  where provider_transaction_id is not null;
create index payment_transaction_sm_user_created_idx
  on public.payment_transaction_sm (user_id, created_at desc);
create index payment_transaction_sm_status_created_idx
  on public.payment_transaction_sm (status, created_at desc);

create table public.stream_session_sm (
  id uuid primary key default gen_random_uuid(),
  external_session_id text not null unique
    default ('morphly_' || replace(gen_random_uuid()::text, '-', '')),
  user_id uuid references public.user_sm(id) on delete set null,
  user_email_snapshot text not null,
  device_id text not null,
  mode text not null default 'style' check (mode in ('style', 'face_swap')),
  prompt text not null default '',
  preset text,
  enhance boolean not null default false,
  quality text not null default 'medium' check (quality in ('low', 'medium', 'high')),
  fps smallint not null default 20 check (fps between 1 and 60),
  model text not null default 'lucy-2.1',
  status text not null default 'starting'
    check (status in ('starting', 'running', 'stopping', 'completed', 'failed', 'expired')),
  reserved_credits integer not null default 0 check (reserved_credits >= 0),
  consumed_credits integer not null default 0 check (consumed_credits >= 0),
  has_face_image boolean not null default false,
  face_image_mime_type text,
  error_message text,
  started_at timestamptz,
  heartbeat_at timestamptz,
  ended_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index stream_session_sm_user_created_idx
  on public.stream_session_sm (user_id, created_at desc);
create index stream_session_sm_active_idx
  on public.stream_session_sm (status, heartbeat_at desc)
  where status in ('starting', 'running', 'stopping');

create table public.credit_ledger_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  user_email_snapshot text not null,
  delta bigint not null check (delta <> 0),
  balance_after bigint not null check (balance_after >= 0),
  reason text not null check (
    reason in (
      'payment', 'crypto_payment', 'usage', 'referral_bonus',
      'signup_bonus', 'admin_adjustment', 'refund', 'migration'
    )
  ),
  payment_transaction_id uuid
    references public.payment_transaction_sm(id) on delete set null,
  stream_session_id uuid references public.stream_session_sm(id) on delete set null,
  idempotency_key text,
  metadata jsonb not null default '{}'::jsonb,
  created_by uuid references public.user_sm(id) on delete set null,
  created_at timestamptz not null default now()
);

create unique index credit_ledger_sm_idempotency_uidx
  on public.credit_ledger_sm (idempotency_key)
  where idempotency_key is not null;
create index credit_ledger_sm_user_created_idx
  on public.credit_ledger_sm (user_id, created_at desc);
create index credit_ledger_sm_payment_idx
  on public.credit_ledger_sm (payment_transaction_id)
  where payment_transaction_id is not null;

create table public.usage_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  user_email_snapshot text not null,
  stream_session_id uuid references public.stream_session_sm(id) on delete set null,
  ledger_id uuid references public.credit_ledger_sm(id) on delete set null,
  feature_name text not null,
  credits_used integer not null check (credits_used >= 0),
  status text not null default 'success'
    check (status in ('success', 'failed', 'in_progress')),
  idempotency_key text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create unique index usage_sm_idempotency_uidx
  on public.usage_sm (idempotency_key)
  where idempotency_key is not null;
create index usage_sm_user_status_created_idx
  on public.usage_sm (user_id, status, created_at desc);
create index usage_sm_stream_idx
  on public.usage_sm (stream_session_id, created_at);

create table public.applogs_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  device_id text,
  log_level text not null check (log_level in ('debug', 'info', 'warn', 'error')),
  message text not null check (char_length(message) between 1 and 4000),
  context jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index applogs_sm_user_created_idx
  on public.applogs_sm (user_id, created_at desc);
create index applogs_sm_level_created_idx
  on public.applogs_sm (log_level, created_at desc);

create table public.key_log_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  email_snapshot text not null,
  access_key_hash text not null,
  access_key_last4 text,
  device_id text,
  activated_at timestamptz not null default now(),
  expires_at timestamptz,
  status text not null default 'active'
    check (status in ('active', 'expired', 'revoked')),
  created_at timestamptz not null default now()
);

create index key_log_sm_user_created_idx
  on public.key_log_sm (user_id, created_at desc);
create index key_log_sm_status_expires_idx
  on public.key_log_sm (status, expires_at);

-- API keys are encrypted in Supabase Vault. This table stores metadata only.
create table public.engine_key_sm (
  id uuid primary key default gen_random_uuid(),
  label text not null unique,
  vault_secret_id uuid not null unique,
  sort_order integer not null default 0,
  is_active boolean not null default true,
  is_exhausted boolean not null default false,
  last_selected_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index engine_key_sm_rotation_idx
  on public.engine_key_sm (is_active, is_exhausted, sort_order, last_selected_at);

-- ---------------------------------------------------------------------------
-- Tables required by currently visible admin-dashboard sections
-- ---------------------------------------------------------------------------

create table public.background_preset_sm (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  label text not null,
  prompt text not null,
  preview_url text,
  sort_order integer not null default 0,
  is_active boolean not null default true,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.support_ticket_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  email_snapshot text not null,
  category text not null,
  subject text not null,
  message text not null,
  status text not null default 'open'
    check (status in ('open', 'in_progress', 'resolved', 'closed')),
  priority text not null default 'normal'
    check (priority in ('low', 'normal', 'high', 'urgent')),
  assigned_to uuid references public.user_sm(id) on delete set null,
  resolution text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  resolved_at timestamptz
);

create index support_ticket_sm_status_created_idx
  on public.support_ticket_sm (status, created_at desc);
create index support_ticket_sm_user_created_idx
  on public.support_ticket_sm (user_id, created_at desc);

create table public.email_campaign_sm (
  id uuid primary key default gen_random_uuid(),
  subject text not null,
  body_text text not null,
  status text not null default 'draft'
    check (status in ('draft', 'queued', 'sending', 'sent', 'failed', 'cancelled')),
  audience_filter jsonb not null default '{}'::jsonb,
  total_recipients integer not null default 0 check (total_recipients >= 0),
  sent_count integer not null default 0 check (sent_count >= 0),
  failed_count integer not null default 0 check (failed_count >= 0),
  created_by uuid references public.user_sm(id) on delete set null,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  completed_at timestamptz
);

create table public.email_delivery_sm (
  id uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references public.email_campaign_sm(id) on delete cascade,
  user_id uuid references public.user_sm(id) on delete set null,
  email_snapshot text not null,
  status text not null default 'queued'
    check (status in ('queued', 'sent', 'failed', 'bounced', 'complained')),
  provider_message_id text,
  error_message text,
  created_at timestamptz not null default now(),
  sent_at timestamptz
);

create unique index email_delivery_sm_campaign_email_uidx
  on public.email_delivery_sm (campaign_id, lower(email_snapshot));

create table public.creator_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid unique references public.user_sm(id) on delete set null,
  email text not null,
  display_name text not null,
  referral_code text not null unique,
  commission_rate numeric(5,2) not null default 30
    check (commission_rate between 0 and 100),
  referral_bonus_enabled boolean not null default true,
  referral_bonus_credits integer not null default 250 check (referral_bonus_credits >= 0),
  status text not null default 'active'
    check (status in ('active', 'paused', 'disabled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index creator_sm_email_lower_uidx on public.creator_sm (lower(email));
create unique index creator_sm_referral_code_upper_uidx
  on public.creator_sm (upper(referral_code));

create table public.referral_sm (
  id uuid primary key default gen_random_uuid(),
  creator_id uuid references public.creator_sm(id) on delete set null,
  referrer_user_id uuid references public.user_sm(id) on delete set null,
  referred_user_id uuid unique references public.user_sm(id) on delete set null,
  referral_code text not null,
  status text not null default 'signed_up'
    check (status in ('signed_up', 'converted', 'rejected', 'reversed')),
  signup_ip_hash text,
  device_hash text,
  signed_up_at timestamptz not null default now(),
  converted_at timestamptz,
  metadata jsonb not null default '{}'::jsonb
);

create index referral_sm_creator_status_idx
  on public.referral_sm (creator_id, status, signed_up_at desc);
create index referral_sm_ip_idx
  on public.referral_sm (signup_ip_hash)
  where signup_ip_hash is not null;

create table public.commission_sm (
  id uuid primary key default gen_random_uuid(),
  creator_id uuid not null references public.creator_sm(id) on delete restrict,
  referral_id uuid references public.referral_sm(id) on delete set null,
  payment_transaction_id uuid not null unique
    references public.payment_transaction_sm(id) on delete restrict,
  gross_amount numeric(18,2) not null check (gross_amount >= 0),
  currency text not null check (currency ~ '^[A-Z]{3,10}$'),
  commission_rate numeric(5,2) not null check (commission_rate between 0 and 100),
  commission_amount numeric(18,2) not null check (commission_amount >= 0),
  status text not null default 'pending'
    check (status in ('pending', 'approved', 'held', 'paid', 'reversed')),
  created_at timestamptz not null default now(),
  approved_at timestamptz,
  paid_at timestamptz
);

create index commission_sm_creator_status_idx
  on public.commission_sm (creator_id, status, created_at desc);

create table public.payout_batch_sm (
  id uuid primary key default gen_random_uuid(),
  period_start date not null,
  period_end date not null check (period_end >= period_start),
  status text not null default 'draft'
    check (status in ('draft', 'processing', 'completed', 'failed', 'cancelled')),
  total_amount numeric(18,2) not null default 0 check (total_amount >= 0),
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3,10}$'),
  created_by uuid references public.user_sm(id) on delete set null,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);

create table public.payout_sm (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid references public.payout_batch_sm(id) on delete set null,
  creator_id uuid not null references public.creator_sm(id) on delete restrict,
  amount numeric(18,2) not null check (amount >= 0),
  currency text not null default 'NGN' check (currency ~ '^[A-Z]{3,10}$'),
  status text not null default 'pending'
    check (status in ('pending', 'processing', 'paid', 'failed', 'cancelled')),
  period_start date not null,
  period_end date not null check (period_end >= period_start),
  provider_reference text,
  failure_reason text,
  created_at timestamptz not null default now(),
  paid_at timestamptz
);

create index payout_sm_creator_status_idx
  on public.payout_sm (creator_id, status, created_at desc);

create table public.fraud_event_sm (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.user_sm(id) on delete set null,
  creator_id uuid references public.creator_sm(id) on delete set null,
  referral_id uuid references public.referral_sm(id) on delete set null,
  event_type text not null,
  severity text not null default 'medium'
    check (severity in ('low', 'medium', 'high', 'critical')),
  status text not null default 'open'
    check (status in ('open', 'reviewing', 'dismissed', 'confirmed')),
  evidence jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  reviewed_at timestamptz,
  reviewed_by uuid references public.user_sm(id) on delete set null
);

create index fraud_event_sm_status_severity_idx
  on public.fraud_event_sm (status, severity, created_at desc);

create table public.admin_audit_sm (
  id uuid primary key default gen_random_uuid(),
  actor_user_id uuid references public.user_sm(id) on delete set null,
  action text not null,
  entity_type text not null,
  entity_id text,
  old_data jsonb,
  new_data jsonb,
  request_id text,
  created_at timestamptz not null default now()
);

create index admin_audit_sm_actor_created_idx
  on public.admin_audit_sm (actor_user_id, created_at desc);
create index admin_audit_sm_entity_idx
  on public.admin_audit_sm (entity_type, entity_id, created_at desc);

-- ---------------------------------------------------------------------------
-- Seed configuration and current Android credit packages
-- ---------------------------------------------------------------------------

insert into public.app_setting_sm (key, value, is_public, description) values
  ('global_config', '{"notification":""}'::jsonb, true, 'Public Android configuration'),
  ('referral_program', '{"enabled":true,"signup_credits":50,"purchase_reward":250}'::jsonb, true, 'Signup and first-purchase referral rewards'),
  ('free_credits', '{"enabled":false,"amount":1500}'::jsonb, false, 'Activation bonus settings'),
  ('streaming_availability', '{"enabled":true}'::jsonb, true, 'Global live streaming switch'),
  ('commission_settings', '{"default_rate":30,"minimum_payout":5000}'::jsonb, false, 'Creator commission defaults')
on conflict (key) do update
set value = excluded.value,
    is_public = excluded.is_public,
    description = excluded.description,
    updated_at = now();

insert into public.credit_package_sm
  (code, name, credits, price, currency, time_label, sort_order, is_active)
values
  ('basic', 'Basic', 1000, 29000, 'NGN', '~8m 20s', 10, true),
  ('pro', 'Pro', 2000, 58000, 'NGN', '~16m 40s', 20, true),
  ('enterprise', 'Enterprise', 5000, 145000, 'NGN', '~41m 40s', 30, true),
  ('vip', 'VIP plan', 10000, 290000, 'NGN', '~83m 20s', 40, true)
on conflict (code) do update
set name = excluded.name,
    credits = excluded.credits,
    price = excluded.price,
    currency = excluded.currency,
    time_label = excluded.time_label,
    sort_order = excluded.sort_order,
    is_active = excluded.is_active,
    updated_at = now();

-- ---------------------------------------------------------------------------
-- Shared helper functions and automatic Auth profile provisioning
-- ---------------------------------------------------------------------------

create or replace function app_private.set_updated_at_sm()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'app_setting_sm', 'user_sm', 'profile_sm', 'wallet_sm',
    'credit_package_sm', 'payment_transaction_sm', 'stream_session_sm',
    'engine_key_sm', 'background_preset_sm', 'support_ticket_sm',
    'creator_sm'
  ]
  loop
    execute format(
      'create trigger %I before update on public.%I for each row execute function app_private.set_updated_at_sm()',
      table_name || '_set_updated_at',
      table_name
    );
  end loop;
end;
$$;

create or replace function app_private.generate_referral_code_sm()
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  candidate text;
begin
  loop
    candidate := 'REF-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8));
    exit when not exists (
      select 1 from public.user_sm u where upper(u.referral_code) = candidate
    ) and not exists (
      select 1 from public.creator_sm c where upper(c.referral_code) = candidate
    );
  end loop;
  return candidate;
end;
$$;

create or replace function app_private.is_admin_sm()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.user_sm u
    where u.id = (select auth.uid())
      and u.role in ('admin', 'moderator')
      and u.status = 'active'
  );
$$;

create or replace function app_private.is_service_role_sm()
returns boolean
language sql
stable
set search_path = ''
as $$
  select coalesce((select auth.role()), '') = 'service_role';
$$;

create or replace function app_private.apply_wallet_delta_sm(
  p_user_id uuid,
  p_delta bigint,
  p_reason text,
  p_idempotency_key text default null,
  p_payment_transaction_id uuid default null,
  p_stream_session_id uuid default null,
  p_metadata jsonb default '{}'::jsonb,
  p_created_by uuid default null
)
returns table (ledger_id uuid, balance_after bigint)
language plpgsql
security definer
set search_path = ''
as $$
declare
  wallet_row public.wallet_sm%rowtype;
  existing_ledger public.credit_ledger_sm%rowtype;
  user_email text;
  new_balance bigint;
  new_ledger_id uuid;
begin
  if p_delta = 0 then
    raise exception 'Credit delta must not be zero' using errcode = '22023';
  end if;

  if p_idempotency_key is not null then
    perform pg_advisory_xact_lock(hashtextextended(p_idempotency_key, 0));
    select *
      into existing_ledger
      from public.credit_ledger_sm l
      where l.idempotency_key = p_idempotency_key;
    if found then
      return query select existing_ledger.id, existing_ledger.balance_after;
      return;
    end if;
  end if;

  select lower(u.email)
    into user_email
    from public.user_sm u
    where u.id = p_user_id
      and u.status <> 'deleted';
  if user_email is null then
    raise exception 'User not found' using errcode = 'P0002';
  end if;

  insert into public.wallet_sm (user_id, balance, currency)
  values (p_user_id, 0, 'NGN')
  on conflict (user_id) do nothing;

  select *
    into wallet_row
    from public.wallet_sm w
    where w.user_id = p_user_id
    for update;

  new_balance := wallet_row.balance + p_delta;
  if new_balance < 0 then
    raise exception 'Insufficient credits' using errcode = 'P0001';
  end if;

  update public.wallet_sm
  set balance = new_balance,
      version = version + 1,
      updated_at = now()
  where user_id = p_user_id;

  insert into public.credit_ledger_sm (
    user_id, user_email_snapshot, delta, balance_after, reason,
    payment_transaction_id, stream_session_id, idempotency_key,
    metadata, created_by
  )
  values (
    p_user_id, user_email, p_delta, new_balance, p_reason,
    p_payment_transaction_id, p_stream_session_id, p_idempotency_key,
    coalesce(p_metadata, '{}'::jsonb), p_created_by
  )
  returning id into new_ledger_id;

  return query select new_ledger_id, new_balance;
end;
$$;

create or replace function app_private.handle_new_auth_user_sm()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  metadata jsonb := coalesce(new.raw_user_meta_data, '{}'::jsonb);
  is_migration_import boolean :=
    lower(coalesce(new.raw_user_meta_data ->> 'migration_import', 'false'))
      in ('true', '1', 'yes');
  referral_input text;
  referrer_id uuid;
  referrer_creator public.creator_sm%rowtype;
begin
  referral_input := nullif(
    upper(btrim(coalesce(metadata ->> 'referredByCode', metadata ->> 'referred_by_code', ''))),
    ''
  );

  if referral_input is not null then
    select u.id
      into referrer_id
      from public.user_sm u
      where upper(u.referral_code) = referral_input
        and u.status = 'active'
      limit 1;

    if referrer_id is null then
      select c.*
        into referrer_creator
        from public.creator_sm c
        where upper(c.referral_code) = referral_input
          and c.status = 'active'
        limit 1;
      referrer_id := referrer_creator.user_id;
    end if;
  end if;

  insert into public.user_sm (
    id, email, phone, role, status, referral_code, referred_by,
    last_login_at, created_at, updated_at
  )
  values (
    new.id,
    lower(coalesce(new.email, new.id::text || '@missing.local')),
    coalesce(nullif(new.phone, ''), nullif(metadata ->> 'phone', '')),
    'user',
    'active',
    app_private.generate_referral_code_sm(),
    referrer_id,
    new.last_sign_in_at,
    coalesce(new.created_at, now()),
    now()
  )
  on conflict (id) do update
  set email = excluded.email,
      phone = coalesce(excluded.phone, public.user_sm.phone),
      last_login_at = excluded.last_login_at,
      updated_at = now();

  insert into public.profile_sm (id, display_name)
  values (
    new.id,
    coalesce(
      nullif(metadata ->> 'display_name', ''),
      nullif(metadata ->> 'name', ''),
      split_part(coalesce(new.email, 'User'), '@', 1)
    )
  )
  on conflict (id) do nothing;

  insert into public.wallet_sm (user_id, balance, currency)
  values (new.id, 0, 'NGN')
  on conflict (user_id) do nothing;

  -- Imported Firebase accounts are existing users, not new signups. Mark
  -- imports with raw_user_meta_data.migration_import = true so they keep their
  -- migrated wallet balance without receiving a second opening bonus.
  if not is_migration_import then
    -- Every genuinely new account receives exactly 50 credits. The unique
    -- idempotency key prevents a trigger retry from issuing the bonus twice.
    perform *
    from app_private.apply_wallet_delta_sm(
      new.id,
      50,
      'signup_bonus',
      'signup-bonus:' || new.id::text,
      null,
      null,
      '{}'::jsonb,
      null
    );

    -- A valid code only creates the relationship at signup. The referrer
    -- reward is issued after the referred account's first verified purchase.
    if referrer_id is not null or referrer_creator.id is not null then
      insert into public.referral_sm (
        creator_id, referrer_user_id, referred_user_id, referral_code, status
      )
      values (
        referrer_creator.id, referrer_id, new.id, referral_input, 'signed_up'
      )
      on conflict (referred_user_id) do nothing;
    end if;
  end if;

  return new;
end;
$$;

create or replace function app_private.sync_auth_user_sm()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.user_sm
  set email = lower(coalesce(new.email, public.user_sm.email)),
      phone = coalesce(nullif(new.phone, ''), public.user_sm.phone),
      last_login_at = coalesce(new.last_sign_in_at, public.user_sm.last_login_at),
      updated_at = now()
  where id = new.id;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created_sm on auth.users;
create trigger on_auth_user_created_sm
  after insert on auth.users
  for each row execute function app_private.handle_new_auth_user_sm();

drop trigger if exists on_auth_user_updated_sm on auth.users;
create trigger on_auth_user_updated_sm
  after update of email, phone, last_sign_in_at on auth.users
  for each row execute function app_private.sync_auth_user_sm();

-- Backfill profiles for any Supabase users created before this migration.
insert into public.user_sm (
  id, email, phone, role, status, referral_code, last_login_at, created_at, updated_at
)
select
  au.id,
  lower(coalesce(au.email, au.id::text || '@missing.local')),
  au.phone,
  'user',
  'active',
  'REF-' || upper(substr(replace(au.id::text, '-', ''), 1, 8)),
  au.last_sign_in_at,
  coalesce(au.created_at, now()),
  now()
from auth.users au
on conflict (id) do nothing;

insert into public.profile_sm (id, display_name)
select
  au.id,
  coalesce(
    nullif(au.raw_user_meta_data ->> 'display_name', ''),
    nullif(au.raw_user_meta_data ->> 'name', ''),
    split_part(coalesce(au.email, 'User'), '@', 1)
  )
from auth.users au
on conflict (id) do nothing;

insert into public.wallet_sm (user_id, balance, currency)
select au.id, 0, 'NGN'
from auth.users au
on conflict (user_id) do nothing;

-- ---------------------------------------------------------------------------
-- Atomic RPC functions used by the Android/API backend
-- ---------------------------------------------------------------------------

create or replace function public.begin_payment_sm(
  p_package_code text,
  p_provider text,
  p_tx_ref text
)
returns public.payment_transaction_sm
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := (select auth.uid());
  caller public.user_sm%rowtype;
  package_row public.credit_package_sm%rowtype;
  result_row public.payment_transaction_sm%rowtype;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_provider not in ('flutterwave', 'nowpayments') then
    raise exception 'Unsupported payment provider' using errcode = '22023';
  end if;
  if nullif(btrim(p_tx_ref), '') is null then
    raise exception 'tx_ref is required' using errcode = '22023';
  end if;

  select * into caller from public.user_sm where id = caller_id;
  if caller.status <> 'active' then
    raise exception 'Account is not active' using errcode = '28000';
  end if;

  select *
    into package_row
    from public.credit_package_sm p
    where p.code = lower(btrim(p_package_code))
      and p.is_active;
  if not found then
    raise exception 'Unknown or inactive credit package' using errcode = 'P0002';
  end if;

  insert into public.payment_transaction_sm (
    user_id, user_email_snapshot, package_id, provider, tx_ref,
    credits, expected_amount, currency, status
  )
  values (
    caller.id, caller.email, package_row.id, p_provider, btrim(p_tx_ref),
    package_row.credits, package_row.price, package_row.currency, 'pending'
  )
  returning * into result_row;

  return result_row;
end;
$$;

create or replace function public.consume_credits_sm(
  p_amount integer,
  p_feature_name text default 'live_stream',
  p_stream_session_id uuid default null,
  p_idempotency_key text default null,
  p_metadata jsonb default '{}'::jsonb
)
returns table (balance bigint, used bigint, usage_id uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := (select auth.uid());
  caller_email text;
  ledger_result record;
  new_usage_id uuid;
  existing_usage_id uuid;
  existing_balance bigint;
  total_used bigint;
  effective_idempotency_key text;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_amount <= 0 or p_amount > 100000 then
    raise exception 'amount must be between 1 and 100000' using errcode = '22023';
  end if;

  select u.email into caller_email
  from public.user_sm u
  where u.id = caller_id and u.status = 'active';
  if caller_email is null then
    raise exception 'Account is not active' using errcode = '28000';
  end if;

  effective_idempotency_key := case
    when p_idempotency_key is null then null
    else 'usage:' || caller_id::text || ':' || p_idempotency_key
  end;

  if effective_idempotency_key is not null then
    perform pg_advisory_xact_lock(hashtextextended(effective_idempotency_key, 0));
    select u.id
      into existing_usage_id
      from public.usage_sm u
      where u.idempotency_key = effective_idempotency_key
        and u.user_id = caller_id;
    if found then
      select w.balance
        into existing_balance
        from public.wallet_sm w
        where w.user_id = caller_id;
      select coalesce(sum(u.credits_used), 0)
        into total_used
        from public.usage_sm u
        where u.user_id = caller_id
          and u.status = 'success';
      return query select existing_balance, total_used, existing_usage_id;
      return;
    end if;
  end if;

  if p_stream_session_id is not null and not exists (
    select 1 from public.stream_session_sm s
    where s.id = p_stream_session_id and s.user_id = caller_id
  ) then
    raise exception 'Stream session not found' using errcode = 'P0002';
  end if;

  select * into ledger_result
  from app_private.apply_wallet_delta_sm(
    caller_id,
    -p_amount,
    'usage',
    effective_idempotency_key,
    null,
    p_stream_session_id,
    p_metadata,
    caller_id
  );

  insert into public.usage_sm (
    user_id, user_email_snapshot, stream_session_id, ledger_id,
    feature_name, credits_used, status, idempotency_key, metadata
  )
  values (
    caller_id, caller_email, p_stream_session_id, ledger_result.ledger_id,
    coalesce(nullif(btrim(p_feature_name), ''), 'live_stream'),
    p_amount, 'success', effective_idempotency_key, coalesce(p_metadata, '{}'::jsonb)
  )
  on conflict (idempotency_key) where idempotency_key is not null
  do update set idempotency_key = excluded.idempotency_key
  returning id into new_usage_id;

  if p_stream_session_id is not null then
    update public.stream_session_sm
    set consumed_credits = consumed_credits + p_amount,
        heartbeat_at = now(),
        updated_at = now()
    where id = p_stream_session_id and user_id = caller_id;
  end if;

  select coalesce(sum(u.credits_used), 0)
    into total_used
    from public.usage_sm u
    where u.user_id = caller_id
      and u.status = 'success';

  return query select ledger_result.balance_after, total_used, new_usage_id;
end;
$$;

create or replace function public.create_stream_session_sm(
  p_device_id text,
  p_mode text default 'style',
  p_prompt text default '',
  p_preset text default null,
  p_enhance boolean default false,
  p_quality text default 'medium',
  p_fps integer default 20,
  p_model text default 'lucy-2.1',
  p_has_face_image boolean default false,
  p_face_image_mime_type text default null
)
returns public.stream_session_sm
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := (select auth.uid());
  caller_email text;
  current_balance bigint;
  streaming_enabled boolean := true;
  result_row public.stream_session_sm%rowtype;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if nullif(btrim(p_device_id), '') is null then
    raise exception 'deviceId is required' using errcode = '22023';
  end if;

  select
    u.email,
    w.balance
  into caller_email, current_balance
  from public.user_sm u
  join public.wallet_sm w on w.user_id = u.id
  where u.id = caller_id
    and u.status = 'active';

  if caller_email is null then
    raise exception 'Account is not active' using errcode = '28000';
  end if;
  if current_balance <= 0 then
    raise exception 'Insufficient credits' using errcode = 'P0001';
  end if;

  select coalesce((s.value ->> 'enabled')::boolean, true)
  into streaming_enabled
  from public.app_setting_sm s
  where s.key = 'streaming_availability';
  if not streaming_enabled then
    raise exception 'Live streaming is currently disabled' using errcode = '55000';
  end if;

  update public.user_sm
  set device_id = btrim(p_device_id), updated_at = now()
  where id = caller_id;

  insert into public.stream_session_sm (
    user_id, user_email_snapshot, device_id, mode, prompt, preset,
    enhance, quality, fps, model, status, has_face_image,
    face_image_mime_type, heartbeat_at
  )
  values (
    caller_id, caller_email, btrim(p_device_id), p_mode, coalesce(p_prompt, ''),
    p_preset, p_enhance, p_quality, p_fps, p_model, 'starting',
    p_has_face_image, p_face_image_mime_type, now()
  )
  returning * into result_row;

  return result_row;
end;
$$;

create or replace function public.update_stream_session_sm(
  p_session_id uuid,
  p_status text,
  p_error_message text default null
)
returns public.stream_session_sm
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := (select auth.uid());
  result_row public.stream_session_sm%rowtype;
begin
  if caller_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if p_status not in ('running', 'stopping', 'completed', 'failed', 'expired') then
    raise exception 'Invalid stream status' using errcode = '22023';
  end if;

  update public.stream_session_sm s
  set status = p_status,
      error_message = p_error_message,
      heartbeat_at = now(),
      started_at = case
        when p_status = 'running' then coalesce(s.started_at, now())
        else s.started_at
      end,
      ended_at = case
        when p_status in ('completed', 'failed', 'expired') then now()
        else s.ended_at
      end,
      updated_at = now()
  where s.id = p_session_id
    and s.user_id = caller_id
  returning * into result_row;

  if not found then
    raise exception 'Stream session not found' using errcode = 'P0002';
  end if;
  return result_row;
end;
$$;

create or replace function public.admin_adjust_credits_sm(
  p_email text,
  p_delta bigint,
  p_reference text default null,
  p_note text default null,
  p_idempotency_key text default null
)
returns table (user_id uuid, balance bigint, ledger_id uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_user public.user_sm%rowtype;
  ledger_result record;
  actor_id uuid := (select auth.uid());
begin
  if not (
    app_private.is_service_role_sm() or app_private.is_admin_sm()
  ) then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if p_delta = 0 then
    raise exception 'Credit delta must not be zero' using errcode = '22023';
  end if;

  select * into target_user
  from public.user_sm u
  where lower(u.email) = lower(btrim(p_email));
  if not found then
    raise exception 'User not found' using errcode = 'P0002';
  end if;

  select * into ledger_result
  from app_private.apply_wallet_delta_sm(
    target_user.id,
    p_delta,
    'admin_adjustment',
    coalesce(p_idempotency_key, 'admin:' || gen_random_uuid()::text),
    null,
    null,
    jsonb_build_object('reference', p_reference, 'note', p_note),
    actor_id
  );

  insert into public.admin_audit_sm (
    actor_user_id, action, entity_type, entity_id, new_data
  )
  values (
    actor_id,
    'wallet.adjust',
    'wallet_sm',
    target_user.id::text,
    jsonb_build_object(
      'delta', p_delta,
      'balance_after', ledger_result.balance_after,
      'reference', p_reference,
      'note', p_note
    )
  );

  return query select target_user.id, ledger_result.balance_after, ledger_result.ledger_id;
end;
$$;

create or replace function public.fulfill_payment_sm(
  p_tx_ref text,
  p_provider text,
  p_provider_transaction_id text,
  p_verified_amount numeric,
  p_verified_currency text,
  p_verification_payload jsonb default '{}'::jsonb
)
returns table (
  payment_id uuid,
  payment_status text,
  wallet_balance bigint,
  was_already_fulfilled boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  payment_row public.payment_transaction_sm%rowtype;
  ledger_result record;
  referral_row public.referral_sm%rowtype;
  creator_row public.creator_sm%rowtype;
  commission_value numeric(18,2);
  referral_enabled boolean := true;
  referral_reward integer := 250;
  is_first_purchase boolean := false;
  already_done boolean := false;
begin
  if not app_private.is_service_role_sm() then
    raise exception 'Service role required' using errcode = '42501';
  end if;
  if nullif(btrim(p_tx_ref), '') is null
    or nullif(btrim(p_provider_transaction_id), '') is null
    or nullif(btrim(p_verified_currency), '') is null
    or p_verified_amount is null
    or p_verified_amount < 0
  then
    raise exception 'Invalid payment verification data' using errcode = '22023';
  end if;
  if p_provider not in ('flutterwave', 'nowpayments') then
    raise exception 'Unsupported payment provider' using errcode = '22023';
  end if;

  select * into payment_row
  from public.payment_transaction_sm p
  where p.tx_ref = p_tx_ref
    and p.provider = p_provider
  for update;
  if not found then
    raise exception 'Unknown payment reference' using errcode = 'P0002';
  end if;
  if payment_row.provider_transaction_id is not null
    and payment_row.provider_transaction_id is distinct from p_provider_transaction_id
  then
    raise exception 'Payment provider transaction mismatch' using errcode = '23505';
  end if;

  if payment_row.status = 'verified' then
    if payment_row.provider_transaction_id is distinct from p_provider_transaction_id then
      raise exception 'Payment was fulfilled by a different provider transaction'
        using errcode = '23505';
    end if;
    already_done := true;
  elsif payment_row.status <> 'pending' then
    raise exception 'Payment is not pending' using errcode = '55000';
  end if;

  if upper(payment_row.currency) <> upper(btrim(p_verified_currency)) then
    raise exception 'Payment currency mismatch' using errcode = '22023';
  end if;
  if payment_row.expected_amount is distinct from p_verified_amount then
    raise exception 'Payment amount mismatch' using errcode = '22023';
  end if;
  if payment_row.user_id is null then
    raise exception 'Payment user no longer exists' using errcode = 'P0002';
  end if;

  if not already_done then
    update public.payment_transaction_sm
    set status = 'verified',
        provider_transaction_id = p_provider_transaction_id,
        verification_payload = coalesce(p_verification_payload, '{}'::jsonb),
        verified_at = now(),
        updated_at = now()
    where id = payment_row.id;
  end if;

  select * into ledger_result
  from app_private.apply_wallet_delta_sm(
    payment_row.user_id,
    payment_row.credits,
    case when payment_row.provider = 'nowpayments' then 'crypto_payment' else 'payment' end,
    'payment:' || payment_row.provider || ':' || p_provider_transaction_id,
    payment_row.id,
    null,
    jsonb_build_object('provider', payment_row.provider, 'tx_ref', payment_row.tx_ref),
    null
  );

  if not already_done then
    select * into referral_row
    from public.referral_sm r
    where r.referred_user_id = payment_row.user_id
    limit 1
    for update;

    is_first_purchase := not exists (
      select 1
      from public.payment_transaction_sm older
      where older.user_id = payment_row.user_id
        and older.status = 'verified'
        and older.id <> payment_row.id
    );

    if referral_row.id is not null
      and referral_row.status = 'signed_up'
      and is_first_purchase
    then
      select coalesce((s.value ->> 'enabled')::boolean, true)
      into referral_enabled
      from public.app_setting_sm s
      where s.key = 'referral_program';

      if referral_enabled then
        update public.referral_sm
        set status = 'converted', converted_at = now()
        where id = referral_row.id and status = 'signed_up';

        if referral_row.referrer_user_id is not null
          and referral_row.referrer_user_id <> payment_row.user_id
          and referral_reward > 0
        then
          perform *
          from app_private.apply_wallet_delta_sm(
            referral_row.referrer_user_id,
            referral_reward,
            'referral_bonus',
            'referral-conversion:' || referral_row.id::text,
            payment_row.id,
            null,
            jsonb_build_object(
              'referral_id', referral_row.id,
              'referred_user_id', payment_row.user_id,
              'first_purchase_tx_ref', payment_row.tx_ref
            ),
            null
          );
        end if;
      end if;

      if referral_row.creator_id is not null then
        select * into creator_row
        from public.creator_sm c
        where c.id = referral_row.creator_id and c.status = 'active';
      end if;

      if creator_row.id is not null and referral_enabled then
        commission_value := round(
          payment_row.expected_amount * creator_row.commission_rate / 100.0,
          2
        );

        insert into public.commission_sm (
          creator_id, referral_id, payment_transaction_id, gross_amount,
          currency, commission_rate, commission_amount, status
        )
        values (
          creator_row.id, referral_row.id, payment_row.id,
          payment_row.expected_amount, payment_row.currency,
          creator_row.commission_rate, commission_value, 'pending'
        )
        on conflict (payment_transaction_id) do nothing;
      end if;
    end if;
  end if;

  return query
    select payment_row.id, 'verified'::text, ledger_result.balance_after, already_done;
end;
$$;

create or replace function public.activate_license_sm(
  p_user_id uuid,
  p_access_key_hash text,
  p_access_key_last4 text,
  p_expires_at timestamptz default null,
  p_device_id text default null
)
returns public.user_sm
language plpgsql
security definer
set search_path = ''
as $$
declare
  actor_id uuid := (select auth.uid());
  user_row public.user_sm%rowtype;
  free_enabled boolean := false;
  free_amount integer := 1500;
begin
  if not (
    app_private.is_service_role_sm() or app_private.is_admin_sm()
  ) then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;

  update public.user_sm
  set is_activated = true,
      license_expires_at = p_expires_at,
      device_id = coalesce(nullif(p_device_id, ''), device_id),
      updated_at = now()
  where id = p_user_id
  returning * into user_row;
  if not found then
    raise exception 'User not found' using errcode = 'P0002';
  end if;

  insert into public.key_log_sm (
    user_id, email_snapshot, access_key_hash, access_key_last4,
    device_id, expires_at, status
  )
  values (
    user_row.id, user_row.email, p_access_key_hash, p_access_key_last4,
    p_device_id, p_expires_at, 'active'
  );

  select
    coalesce((s.value ->> 'enabled')::boolean, false),
    coalesce((s.value ->> 'amount')::integer, 1500)
  into free_enabled, free_amount
  from public.app_setting_sm s
  where s.key = 'free_credits';

  if free_enabled and free_amount > 0 then
    perform *
    from app_private.apply_wallet_delta_sm(
      user_row.id,
      free_amount,
      'signup_bonus',
      'activation-bonus:' || user_row.id::text,
      null,
      null,
      '{}'::jsonb,
      actor_id
    );
  end if;

  return user_row;
end;
$$;

create or replace function public.admin_overview_sm()
returns table (
  users bigint,
  active_licenses bigint,
  credits_issued bigint,
  credits_used bigint,
  active_streams bigint
)
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not (
    app_private.is_service_role_sm() or app_private.is_admin_sm()
  ) then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;

  return query
  select
    (select count(*) from public.user_sm u where u.status <> 'deleted'),
    (
      select count(*)
      from public.user_sm u
      where u.is_activated
        and (u.license_expires_at is null or u.license_expires_at > now())
    ),
    (
      select coalesce(sum(greatest(l.delta, 0)), 0)
      from public.credit_ledger_sm l
    ),
    (
      select coalesce(sum(us.credits_used), 0)
      from public.usage_sm us
      where us.status = 'success'
    ),
    (
      select count(*)
      from public.stream_session_sm s
      where s.status in ('starting', 'running', 'stopping')
        and coalesce(s.heartbeat_at, s.created_at) > now() - interval '2 minutes'
    );
end;
$$;

create or replace function public.admin_list_users_sm(
  p_search text default '',
  p_limit integer default 50,
  p_offset integer default 0
)
returns table (
  id uuid,
  name text,
  email text,
  phone text,
  device_id text,
  account_status text,
  license_status text,
  credits_remaining bigint,
  credits_used bigint,
  created_at timestamptz,
  last_login_at timestamptz,
  total_count bigint
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  search_text text := btrim(coalesce(p_search, ''));
  page_size integer := least(greatest(coalesce(p_limit, 50), 1), 100);
  page_offset integer := greatest(coalesce(p_offset, 0), 0);
begin
  if not (
    app_private.is_service_role_sm() or app_private.is_admin_sm()
  ) then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;

  return query
  with usage_totals as (
    select us.user_id, coalesce(sum(us.credits_used), 0)::bigint as used
    from public.usage_sm us
    where us.status = 'success'
    group by us.user_id
  )
  select
    u.id,
    p.display_name,
    u.email,
    coalesce(u.phone, ''),
    coalesce(u.device_id, ''),
    u.status,
    case
      when u.is_activated
        and (u.license_expires_at is null or u.license_expires_at > now())
        then 'active'
      else 'none'
    end,
    w.balance,
    coalesce(ut.used, 0),
    u.created_at,
    u.last_login_at,
    count(*) over ()
  from public.user_sm u
  join public.profile_sm p on p.id = u.id
  join public.wallet_sm w on w.user_id = u.id
  left join usage_totals ut on ut.user_id = u.id
  where u.status <> 'deleted'
    and (
      search_text = ''
      or u.email ilike '%' || search_text || '%'
      or p.display_name ilike '%' || search_text || '%'
      or coalesce(u.phone, '') ilike '%' || search_text || '%'
      or coalesce(u.device_id, '') ilike '%' || search_text || '%'
    )
  order by u.created_at desc, u.id
  limit page_size
  offset page_offset;
end;
$$;

-- Stores/updates a live-engine key in Vault. It never returns the secret.
create or replace function public.save_engine_key_sm(
  p_label text,
  p_secret text,
  p_sort_order integer default 0,
  p_is_active boolean default true
)
returns public.engine_key_sm
language plpgsql
security definer
set search_path = ''
as $$
declare
  key_row public.engine_key_sm%rowtype;
  new_secret_id uuid;
begin
  if not (
    app_private.is_service_role_sm() or app_private.is_admin_sm()
  ) then
    raise exception 'Administrator access required' using errcode = '42501';
  end if;
  if nullif(btrim(p_label), '') is null or nullif(btrim(p_secret), '') is null then
    raise exception 'label and secret are required' using errcode = '22023';
  end if;

  select * into key_row
  from public.engine_key_sm k
  where k.label = btrim(p_label)
  for update;

  if found then
    perform vault.update_secret(
      key_row.vault_secret_id,
      p_secret,
      'morphly_engine_' || replace(lower(btrim(p_label)), ' ', '_'),
      'Morphly live-engine API key'
    );
    update public.engine_key_sm
    set sort_order = p_sort_order,
        is_active = p_is_active,
        is_exhausted = false,
        updated_at = now()
    where id = key_row.id
    returning * into key_row;
  else
    select vault.create_secret(
      p_secret,
      'morphly_engine_' || replace(lower(btrim(p_label)), ' ', '_'),
      'Morphly live-engine API key'
    )
    into new_secret_id;

    insert into public.engine_key_sm (
      label, vault_secret_id, sort_order, is_active, is_exhausted
    )
    values (
      btrim(p_label), new_secret_id, p_sort_order, p_is_active, false
    )
    returning * into key_row;
  end if;

  return key_row;
end;
$$;

create or replace function app_private.next_engine_key_sm()
returns table (engine_key_id uuid, api_key text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  key_row public.engine_key_sm%rowtype;
  secret_value text;
begin
  if not app_private.is_service_role_sm() then
    raise exception 'Service role required' using errcode = '42501';
  end if;

  select *
  into key_row
  from public.engine_key_sm k
  where k.is_active and not k.is_exhausted
  order by k.last_selected_at nulls first, k.sort_order, k.id
  limit 1
  for update skip locked;

  if not found then
    raise exception 'No active live-engine key is available' using errcode = 'P0002';
  end if;

  select d.decrypted_secret
  into secret_value
  from vault.decrypted_secrets d
  where d.id = key_row.vault_secret_id;

  if secret_value is null then
    raise exception 'Live-engine Vault secret is missing' using errcode = 'P0002';
  end if;

  update public.engine_key_sm
  set last_selected_at = now(), updated_at = now()
  where id = key_row.id;

  return query select key_row.id, secret_value;
end;
$$;

-- PostgREST exposes only the public schema by default. This narrow wrapper
-- lets the Vercel service role rotate Vault-backed keys without exposing the
-- private schema or granting the function to Android/browser clients.
create or replace function public.next_engine_key_for_service_sm()
returns table (engine_key_id uuid, api_key text)
language sql
security definer
set search_path = ''
as $$
  select * from app_private.next_engine_key_sm();
$$;

-- ---------------------------------------------------------------------------
-- Read models
-- ---------------------------------------------------------------------------

create or replace view public.account_summary_sm
with (security_invoker = true)
as
select
  u.id as user_id,
  u.email,
  p.display_name,
  u.phone,
  u.device_id,
  u.referral_code,
  u.role,
  u.status,
  u.is_activated,
  u.license_expires_at,
  u.created_at,
  w.balance,
  w.currency,
  coalesce(sum(us.credits_used) filter (where us.status = 'success'), 0)::bigint
    as credits_used
from public.user_sm u
join public.profile_sm p on p.id = u.id
join public.wallet_sm w on w.user_id = u.id
left join public.usage_sm us on us.user_id = u.id
group by u.id, p.id, w.id;

create or replace view public.active_stream_monitor_sm
with (security_invoker = true)
as
select
  s.id,
  s.external_session_id,
  s.user_id,
  s.user_email_snapshot as email,
  s.device_id,
  s.model,
  s.status,
  s.consumed_credits,
  s.created_at,
  s.started_at,
  s.heartbeat_at,
  extract(epoch from (now() - coalesce(s.started_at, s.created_at)))::bigint
    as duration_seconds
from public.stream_session_sm s
where s.status in ('starting', 'running', 'stopping')
  and coalesce(s.heartbeat_at, s.created_at) > now() - interval '2 minutes';

-- ---------------------------------------------------------------------------
-- Row-level security and grants
-- ---------------------------------------------------------------------------

do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'app_setting_sm', 'user_sm', 'profile_sm', 'wallet_sm',
    'credit_package_sm', 'payment_transaction_sm', 'stream_session_sm',
    'credit_ledger_sm', 'usage_sm', 'applogs_sm', 'key_log_sm',
    'engine_key_sm', 'background_preset_sm', 'support_ticket_sm',
    'email_campaign_sm', 'email_delivery_sm', 'creator_sm', 'referral_sm',
    'commission_sm', 'payout_batch_sm', 'payout_sm', 'fraud_event_sm',
    'admin_audit_sm'
  ]
  loop
    execute format('alter table public.%I enable row level security', table_name);
    execute format('revoke all on public.%I from anon, authenticated', table_name);
    execute format('grant all on public.%I to service_role', table_name);
    execute format(
      'create policy %I on public.%I for all to authenticated using ((select app_private.is_admin_sm())) with check ((select app_private.is_admin_sm()))',
      table_name || '_admin_all',
      table_name
    );
  end loop;
end;
$$;

grant usage on schema app_private to authenticated, service_role;
grant execute on function app_private.is_admin_sm() to authenticated, service_role;
grant execute on function app_private.is_service_role_sm() to authenticated, service_role;
revoke execute on all functions in schema app_private from public, anon;

grant select on public.app_setting_sm to anon, authenticated;
create policy app_setting_sm_public_read
  on public.app_setting_sm for select to anon, authenticated
  using (is_public);

grant select on public.credit_package_sm to anon, authenticated;
create policy credit_package_sm_public_read
  on public.credit_package_sm for select to anon, authenticated
  using (is_active);

grant select on public.background_preset_sm to anon, authenticated;
create policy background_preset_sm_public_read
  on public.background_preset_sm for select to anon, authenticated
  using (is_active);

grant select on public.user_sm to authenticated;
create policy user_sm_owner_read
  on public.user_sm for select to authenticated
  using ((select auth.uid()) = id);

grant select on public.profile_sm to authenticated;
grant update (display_name, avatar_url, bio, country) on public.profile_sm
  to authenticated;
create policy profile_sm_owner_read
  on public.profile_sm for select to authenticated
  using ((select auth.uid()) = id);
create policy profile_sm_owner_update
  on public.profile_sm for update to authenticated
  using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

grant select on public.wallet_sm to authenticated;
create policy wallet_sm_owner_read
  on public.wallet_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.payment_transaction_sm to authenticated;
create policy payment_transaction_sm_owner_read
  on public.payment_transaction_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.stream_session_sm to authenticated;
create policy stream_session_sm_owner_read
  on public.stream_session_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.credit_ledger_sm to authenticated;
create policy credit_ledger_sm_owner_read
  on public.credit_ledger_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.usage_sm to authenticated;
create policy usage_sm_owner_read
  on public.usage_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select, insert on public.applogs_sm to authenticated;
create policy applogs_sm_owner_read
  on public.applogs_sm for select to authenticated
  using ((select auth.uid()) = user_id);
create policy applogs_sm_owner_insert
  on public.applogs_sm for insert to authenticated
  with check ((select auth.uid()) = user_id);

grant select on public.key_log_sm to authenticated;
create policy key_log_sm_owner_read
  on public.key_log_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select, insert on public.support_ticket_sm to authenticated;
create policy support_ticket_sm_owner_read
  on public.support_ticket_sm for select to authenticated
  using ((select auth.uid()) = user_id);
create policy support_ticket_sm_owner_insert
  on public.support_ticket_sm for insert to authenticated
  with check ((select auth.uid()) = user_id);

grant select on public.creator_sm to authenticated;
create policy creator_sm_owner_read
  on public.creator_sm for select to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.referral_sm to authenticated;
create policy referral_sm_participant_read
  on public.referral_sm for select to authenticated
  using (
    (select auth.uid()) = referred_user_id
    or (select auth.uid()) = referrer_user_id
    or creator_id in (
      select c.id from public.creator_sm c where c.user_id = (select auth.uid())
    )
  );

grant select on public.commission_sm to authenticated;
create policy commission_sm_creator_read
  on public.commission_sm for select to authenticated
  using (
    creator_id in (
      select c.id from public.creator_sm c where c.user_id = (select auth.uid())
    )
  );

grant select on public.payout_sm to authenticated;
create policy payout_sm_creator_read
  on public.payout_sm for select to authenticated
  using (
    creator_id in (
      select c.id from public.creator_sm c where c.user_id = (select auth.uid())
    )
  );

grant select on public.account_summary_sm to authenticated, service_role;
grant select on public.active_stream_monitor_sm to authenticated, service_role;

revoke all on function public.begin_payment_sm(text, text, text) from public, anon;
grant execute on function public.begin_payment_sm(text, text, text)
  to authenticated, service_role;

revoke all on function public.consume_credits_sm(integer, text, uuid, text, jsonb)
  from public, anon;
grant execute on function public.consume_credits_sm(integer, text, uuid, text, jsonb)
  to authenticated, service_role;

revoke all on function public.create_stream_session_sm(
  text, text, text, text, boolean, text, integer, text, boolean, text
) from public, anon;
grant execute on function public.create_stream_session_sm(
  text, text, text, text, boolean, text, integer, text, boolean, text
) to authenticated, service_role;

revoke all on function public.update_stream_session_sm(uuid, text, text)
  from public, anon;
grant execute on function public.update_stream_session_sm(uuid, text, text)
  to authenticated, service_role;

revoke all on function public.admin_adjust_credits_sm(text, bigint, text, text, text)
  from public, anon;
grant execute on function public.admin_adjust_credits_sm(text, bigint, text, text, text)
  to authenticated, service_role;

revoke all on function public.fulfill_payment_sm(text, text, text, numeric, text, jsonb)
  from public, anon, authenticated;
grant execute on function public.fulfill_payment_sm(text, text, text, numeric, text, jsonb)
  to service_role;

revoke all on function public.activate_license_sm(uuid, text, text, timestamptz, text)
  from public, anon;
grant execute on function public.activate_license_sm(uuid, text, text, timestamptz, text)
  to authenticated, service_role;

revoke all on function public.admin_overview_sm() from public, anon;
grant execute on function public.admin_overview_sm()
  to authenticated, service_role;

revoke all on function public.admin_list_users_sm(text, integer, integer)
  from public, anon;
grant execute on function public.admin_list_users_sm(text, integer, integer)
  to authenticated, service_role;

revoke all on function public.save_engine_key_sm(text, text, integer, boolean)
  from public, anon;
grant execute on function public.save_engine_key_sm(text, text, integer, boolean)
  to authenticated, service_role;

revoke all on function app_private.next_engine_key_sm()
  from public, anon, authenticated;
grant execute on function app_private.next_engine_key_sm() to service_role;

revoke all on function public.next_engine_key_for_service_sm()
  from public, anon, authenticated;
grant execute on function public.next_engine_key_for_service_sm()
  to service_role;

-- Ensure future objects do not become public by accident.
alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke execute on functions from public, anon;

commit;

-- First admin bootstrap (run only after that email exists in Supabase Auth):
-- update public.user_sm
-- set role = 'admin', status = 'active', updated_at = now()
-- where email = lower('admin@example.com');
