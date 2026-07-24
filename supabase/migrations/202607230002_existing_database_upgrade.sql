-- Morphly upgrade for an EXISTING Supabase database.
--
-- Use this when 202607230001_morphly_schema.sql reports that a relation such
-- as app_setting_sm already exists. This migration preserves existing users,
-- wallets, payments, and credits. It is transactional and safe to rerun.
--
-- It installs the exact business rules required by the Android app:
--   * every genuine new signup receives exactly 50 credits once;
--   * a valid referral is recorded at signup;
--   * the referrer receives exactly 250 credits once, only after the referred
--     user completes their first verified purchase;
--   * payment fulfillment is service-role-only and idempotent.

begin;

create schema if not exists app_private;
revoke all on schema app_private from public, anon, authenticated;

-- Older Morphly schemas did not expose these app-setting fields.
alter table public.app_setting_sm
  add column if not exists is_public boolean not null default false,
  add column if not exists description text,
  add column if not exists created_at timestamptz not null default now(),
  add column if not exists updated_at timestamptz not null default now();

-- Fail before changing business logic if the existing schema is incompatible.
do $upgrade_preflight$
declare
  missing_tables text;
  missing_columns text;
  conflicting_signup_triggers text;
begin
  select string_agg(required_name, ', ' order by required_name)
    into missing_tables
  from (
    values
      ('public.app_setting_sm'),
      ('public.user_sm'),
      ('public.profile_sm'),
      ('public.wallet_sm'),
      ('public.credit_ledger_sm'),
      ('public.payment_transaction_sm'),
      ('public.creator_sm'),
      ('public.referral_sm'),
      ('public.commission_sm')
  ) required(required_name)
  where to_regclass(required_name) is null;

  if missing_tables is not null then
    raise exception 'Existing Morphly schema is missing required table(s): %',
      missing_tables
      using hint = 'Do not drop existing tables. Add the missing tables before rerunning this upgrade.';
  end if;

  with required(table_name, column_name) as (
    values
      ('app_setting_sm', 'key'),
      ('app_setting_sm', 'value'),
      ('user_sm', 'id'),
      ('user_sm', 'email'),
      ('user_sm', 'phone'),
      ('user_sm', 'role'),
      ('user_sm', 'status'),
      ('user_sm', 'referral_code'),
      ('user_sm', 'referred_by'),
      ('user_sm', 'last_login_at'),
      ('user_sm', 'created_at'),
      ('user_sm', 'updated_at'),
      ('profile_sm', 'id'),
      ('profile_sm', 'display_name'),
      ('wallet_sm', 'user_id'),
      ('wallet_sm', 'balance'),
      ('wallet_sm', 'currency'),
      ('wallet_sm', 'version'),
      ('wallet_sm', 'updated_at'),
      ('credit_ledger_sm', 'id'),
      ('credit_ledger_sm', 'user_id'),
      ('credit_ledger_sm', 'user_email_snapshot'),
      ('credit_ledger_sm', 'delta'),
      ('credit_ledger_sm', 'balance_after'),
      ('credit_ledger_sm', 'reason'),
      ('credit_ledger_sm', 'payment_transaction_id'),
      ('credit_ledger_sm', 'stream_session_id'),
      ('credit_ledger_sm', 'idempotency_key'),
      ('credit_ledger_sm', 'metadata'),
      ('credit_ledger_sm', 'created_by'),
      ('payment_transaction_sm', 'id'),
      ('payment_transaction_sm', 'user_id'),
      ('payment_transaction_sm', 'provider'),
      ('payment_transaction_sm', 'tx_ref'),
      ('payment_transaction_sm', 'provider_transaction_id'),
      ('payment_transaction_sm', 'credits'),
      ('payment_transaction_sm', 'expected_amount'),
      ('payment_transaction_sm', 'currency'),
      ('payment_transaction_sm', 'status'),
      ('payment_transaction_sm', 'verification_payload'),
      ('payment_transaction_sm', 'verified_at'),
      ('payment_transaction_sm', 'updated_at'),
      ('creator_sm', 'id'),
      ('creator_sm', 'user_id'),
      ('creator_sm', 'referral_code'),
      ('creator_sm', 'status'),
      ('creator_sm', 'commission_rate'),
      ('referral_sm', 'id'),
      ('referral_sm', 'creator_id'),
      ('referral_sm', 'referrer_user_id'),
      ('referral_sm', 'referred_user_id'),
      ('referral_sm', 'referral_code'),
      ('referral_sm', 'status'),
      ('referral_sm', 'converted_at'),
      ('commission_sm', 'creator_id'),
      ('commission_sm', 'referral_id'),
      ('commission_sm', 'payment_transaction_id'),
      ('commission_sm', 'gross_amount'),
      ('commission_sm', 'currency'),
      ('commission_sm', 'commission_rate'),
      ('commission_sm', 'commission_amount'),
      ('commission_sm', 'status')
  )
  select string_agg(
    format('public.%I.%I', required.table_name, required.column_name),
    ', ' order by required.table_name, required.column_name
  )
    into missing_columns
  from required
  left join information_schema.columns actual
    on actual.table_schema = 'public'
   and actual.table_name = required.table_name
   and actual.column_name = required.column_name
  where actual.column_name is null;

  if missing_columns is not null then
    raise exception 'Existing Morphly schema is missing required column(s): %',
      missing_columns
      using hint = 'Do not drop data. Apply an additive column repair before rerunning this upgrade.';
  end if;

  -- A second INSERT trigger could grant an old signup/referral bonus in
  -- addition to the exact rewards installed below.
  select string_agg(t.tgname, ', ' order by t.tgname)
    into conflicting_signup_triggers
  from pg_trigger t
  where t.tgrelid = 'auth.users'::regclass
    and not t.tgisinternal
    and (t.tgtype::integer & 4) = 4
    and t.tgname <> 'on_auth_user_created_sm';

  if conflicting_signup_triggers is not null then
    raise exception 'Conflicting auth.users signup trigger(s): %',
      conflicting_signup_triggers
      using hint = 'Remove only the obsolete Morphly signup trigger, then rerun this upgrade.';
  end if;
end;
$upgrade_preflight$;

-- These unique indexes make every credit-changing action retry-safe.
create unique index if not exists credit_ledger_sm_idempotency_uidx
  on public.credit_ledger_sm (idempotency_key)
  where idempotency_key is not null;

create unique index if not exists wallet_sm_user_id_uidx
  on public.wallet_sm (user_id);

create unique index if not exists referral_sm_referred_user_uidx
  on public.referral_sm (referred_user_id);

create unique index if not exists payment_transaction_sm_provider_id_uidx
  on public.payment_transaction_sm (provider, provider_transaction_id)
  where provider_transaction_id is not null;

-- Replace the old {"enabled":false,"reward_amount":100} document. Reward
-- amounts are deliberately exact and are not admin-configurable.
insert into public.app_setting_sm (key, value, is_public, description)
values (
  'referral_program',
  '{"enabled":true,"signup_credits":50,"purchase_reward":250}'::jsonb,
  true,
  'Signup and first-purchase referral rewards'
)
on conflict (key) do update
set value = excluded.value,
    is_public = true,
    description = excluded.description,
    updated_at = now();

insert into public.app_setting_sm (key, value, is_public, description)
values
  ('global_config', '{"notification":""}'::jsonb, true, 'Public Android configuration'),
  ('streaming_availability', '{"enabled":true}'::jsonb, true, 'Global live streaming switch')
on conflict (key) do update
set is_public = true,
    updated_at = now();

create or replace function app_private.set_updated_at_sm()
returns trigger
language plpgsql
set search_path = ''
as $function$
begin
  new.updated_at := now();
  return new;
end;
$function$;

drop trigger if exists app_setting_sm_set_updated_at on public.app_setting_sm;
create trigger app_setting_sm_set_updated_at
  before update on public.app_setting_sm
  for each row execute function app_private.set_updated_at_sm();

create or replace function app_private.generate_referral_code_sm()
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
  candidate text;
begin
  loop
    candidate := 'REF-' ||
      upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8));
    exit when not exists (
      select 1
      from public.user_sm u
      where upper(u.referral_code) = candidate
    ) and not exists (
      select 1
      from public.creator_sm c
      where upper(c.referral_code) = candidate
    );
  end loop;
  return candidate;
end;
$function$;

create or replace function app_private.is_service_role_sm()
returns boolean
language sql
stable
set search_path = ''
as $function$
  select coalesce((select auth.role()), '') = 'service_role';
$function$;

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
as $function$
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
      return query
        select existing_ledger.id, existing_ledger.balance_after;
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
    user_id,
    user_email_snapshot,
    delta,
    balance_after,
    reason,
    payment_transaction_id,
    stream_session_id,
    idempotency_key,
    metadata,
    created_by
  )
  values (
    p_user_id,
    user_email,
    p_delta,
    new_balance,
    p_reason,
    p_payment_transaction_id,
    p_stream_session_id,
    p_idempotency_key,
    coalesce(p_metadata, '{}'::jsonb),
    p_created_by
  )
  returning id into new_ledger_id;

  return query select new_ledger_id, new_balance;
end;
$function$;

create or replace function app_private.handle_new_auth_user_sm()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
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
    upper(btrim(coalesce(
      metadata ->> 'referredByCode',
      metadata ->> 'referred_by_code',
      ''
    ))),
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
    id,
    email,
    phone,
    role,
    status,
    referral_code,
    referred_by,
    last_login_at,
    created_at,
    updated_at
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

  -- Existing Firebase imports must set migration_import=true so that they do
  -- not receive a second opening bonus.
  if not is_migration_import then
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

    if referrer_id is not null or referrer_creator.id is not null then
      insert into public.referral_sm (
        creator_id,
        referrer_user_id,
        referred_user_id,
        referral_code,
        status
      )
      values (
        referrer_creator.id,
        referrer_id,
        new.id,
        referral_input,
        'signed_up'
      )
      on conflict (referred_user_id) do nothing;
    end if;
  end if;

  return new;
end;
$function$;

create or replace function app_private.sync_auth_user_sm()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
  update public.user_sm
  set email = lower(coalesce(new.email, public.user_sm.email)),
      phone = coalesce(nullif(new.phone, ''), public.user_sm.phone),
      last_login_at = coalesce(
        new.last_sign_in_at,
        public.user_sm.last_login_at
      ),
      updated_at = now()
  where id = new.id;
  return new;
end;
$function$;

drop trigger if exists on_auth_user_created_sm on auth.users;
create trigger on_auth_user_created_sm
  after insert on auth.users
  for each row execute function app_private.handle_new_auth_user_sm();

drop trigger if exists on_auth_user_updated_sm on auth.users;
create trigger on_auth_user_updated_sm
  after update of email, phone, last_sign_in_at on auth.users
  for each row execute function app_private.sync_auth_user_sm();

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
as $function$
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
    raise exception 'Invalid payment verification data'
      using errcode = '22023';
  end if;
  if p_provider not in ('flutterwave', 'nowpayments') then
    raise exception 'Unsupported payment provider' using errcode = '22023';
  end if;

  select *
    into payment_row
    from public.payment_transaction_sm p
    where p.tx_ref = p_tx_ref
      and p.provider = p_provider
    for update;
  if not found then
    raise exception 'Unknown payment reference' using errcode = 'P0002';
  end if;

  if payment_row.provider_transaction_id is not null
    and payment_row.provider_transaction_id
      is distinct from p_provider_transaction_id
  then
    raise exception 'Payment provider transaction mismatch'
      using errcode = '23505';
  end if;

  if payment_row.status = 'verified' then
    if payment_row.provider_transaction_id
      is distinct from p_provider_transaction_id
    then
      raise exception 'Payment was fulfilled by a different provider transaction'
        using errcode = '23505';
    end if;
    already_done := true;
  elsif payment_row.status <> 'pending' then
    raise exception 'Payment is not pending' using errcode = '55000';
  end if;

  if upper(payment_row.currency) <>
    upper(btrim(p_verified_currency))
  then
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
        verification_payload = coalesce(
          p_verification_payload,
          '{}'::jsonb
        ),
        verified_at = now(),
        updated_at = now()
    where id = payment_row.id;
  end if;

  select *
    into ledger_result
    from app_private.apply_wallet_delta_sm(
      payment_row.user_id,
      payment_row.credits,
      case
        when payment_row.provider = 'nowpayments' then 'crypto_payment'
        else 'payment'
      end,
      'payment:' || payment_row.provider || ':' ||
        p_provider_transaction_id,
      payment_row.id,
      null,
      jsonb_build_object(
        'provider', payment_row.provider,
        'tx_ref', payment_row.tx_ref
      ),
      null
    );

  if not already_done then
    -- Lock the referral row so two simultaneous first-purchase webhooks cannot
    -- both create a reward or creator commission.
    select *
      into referral_row
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
        set status = 'converted',
            converted_at = now()
        where id = referral_row.id
          and status = 'signed_up';

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
        select *
          into creator_row
          from public.creator_sm c
          where c.id = referral_row.creator_id
            and c.status = 'active';
      end if;

      if creator_row.id is not null and referral_enabled then
        commission_value := round(
          payment_row.expected_amount *
            creator_row.commission_rate / 100.0,
          2
        );

        insert into public.commission_sm (
          creator_id,
          referral_id,
          payment_transaction_id,
          gross_amount,
          currency,
          commission_rate,
          commission_amount,
          status
        )
        values (
          creator_row.id,
          referral_row.id,
          payment_row.id,
          payment_row.expected_amount,
          payment_row.currency,
          creator_row.commission_rate,
          commission_value,
          'pending'
        )
        on conflict (payment_transaction_id) do nothing;
      end if;
    end if;
  end if;

  return query
    select
      payment_row.id,
      'verified'::text,
      ledger_result.balance_after,
      already_done;
end;
$function$;

-- Private credit functions cannot be called directly from Android/browser
-- sessions. The payment RPC is callable only by the Vercel service client.
grant usage on schema app_private to service_role;
revoke all on function app_private.apply_wallet_delta_sm(
  uuid, bigint, text, text, uuid, uuid, jsonb, uuid
) from public, anon, authenticated;
grant execute on function app_private.apply_wallet_delta_sm(
  uuid, bigint, text, text, uuid, uuid, jsonb, uuid
) to service_role;

revoke all on function app_private.handle_new_auth_user_sm()
  from public, anon, authenticated;
revoke all on function app_private.sync_auth_user_sm()
  from public, anon, authenticated;
revoke all on function app_private.generate_referral_code_sm()
  from public, anon, authenticated;
revoke all on function app_private.is_service_role_sm()
  from public, anon, authenticated;
grant execute on function app_private.is_service_role_sm()
  to service_role;

revoke all on function public.fulfill_payment_sm(
  text, text, text, numeric, text, jsonb
) from public, anon, authenticated;
grant execute on function public.fulfill_payment_sm(
  text, text, text, numeric, text, jsonb
) to service_role;

commit;

-- The SQL editor should return one row with every boolean true and the exact
-- referral JSON shown below.
select
  to_regprocedure(
    'public.fulfill_payment_sm(text,text,text,numeric,text,jsonb)'
  ) is not null as payment_function_exists,
  exists (
    select 1
    from pg_trigger
    where tgrelid = 'auth.users'::regclass
      and tgname = 'on_auth_user_created_sm'
      and not tgisinternal
  ) as signup_trigger_exists,
  exists (
    select 1
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'app_private'
      and p.proname = 'apply_wallet_delta_sm'
  ) as wallet_function_exists,
  (
    select value
    from public.app_setting_sm
    where key = 'referral_program'
  ) as referral_settings;
