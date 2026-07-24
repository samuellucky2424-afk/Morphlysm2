# Vercel environment variables

Add these in **Vercel project -> Settings -> Environment Variables** for
Production, Preview, and Development as appropriate. Redeploy after changing
them.

## Required

| Variable | Value |
|---|---|
| `SUPABASE_URL` | Supabase project URL |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase publishable key (`sb_publishable_...`) |
| `SUPABASE_SECRET_KEY` | Supabase server secret (`sb_secret_...`); never put it in Android or browser code |
| `PUBLIC_APP_URL` | Production Vercel origin, for example `https://morphlysm2.vercel.app` |
| `ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call the API |
| `ALLOWED_PAYMENT_REDIRECT_ORIGINS` | Comma-separated HTTPS payment return origins |
| `REFERRAL_HASH_SECRET` | A random value of at least 32 bytes used to HMAC referral IP/device identifiers |

Use `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` only when the project
still exposes Supabase's legacy key format. The backend accepts those names as
fallbacks, but the newer publishable/secret key names are preferred.

## Live engine

Set `DECART_API_KEY` for one engine key. Alternatively, leave it unset and add
one or more encrypted keys through the admin portal after running the SQL
migration. `LIVE_ENGINE_API_KEY` is accepted only as a legacy fallback.

## Flutterwave

| Variable | Value |
|---|---|
| `FLUTTERWAVE_SECRET_KEY` | Live or test Flutterwave secret key |
| `FLUTTERWAVE_WEBHOOK_HASH` | The webhook secret/hash configured in Flutterwave |

Webhook URL: `https://YOUR_DOMAIN/api/flutterwave-webhook`

## NOWPayments

| Variable | Value |
|---|---|
| `NOWPAYMENTS_API_KEY` | NOWPayments API key |
| `NOWPAYMENTS_IPN_SECRET_KEY` | NOWPayments IPN secret |
| `NOWPAYMENTS_PAY_CURRENCY` | Optional; defaults to `USDTTRC20` |

Webhook URL: `https://YOUR_DOMAIN/api/nowpayments-webhook`

## Optional mass email

Set `RESEND_API_KEY` and `EMAIL_FROM` only if the admin mass-email feature is
needed. `EMAIL_FROM` must use a sender/domain verified in Resend.

## Supabase dashboard steps that are not Vercel variables

1. Run `supabase/migrations/202607230001_morphly_schema.sql` in the Supabase SQL editor.
2. Create the first admin Auth user, then run the commented admin bootstrap
   statement at the bottom of the migration with that email.
3. Keep Row Level Security enabled.
4. Add both payment webhook URLs in their provider dashboards.
5. In Supabase Auth URL Configuration, add
   `https://YOUR_DOMAIN/reset-password.html` to the redirect allow list.
6. Migrate existing Firebase Auth users before switching production traffic;
   see `supabase/FIREBASE_IMPORT.md`.

Do not add Firebase variables, `ADMIN_SECRET`, a Supabase server key, or a
payment-provider secret to the APK.
