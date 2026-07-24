# Morphly Firebase-to-Supabase migration status

## Implemented

- All active Vercel API routes now use Supabase Auth/Postgres; the
  `firebase-admin` dependency and shared Firebase server module were removed.
- User routes derive identity from a verified bearer token. Admin routes
  require a Supabase user with an active `admin` or `moderator` role.
- Android access and refresh tokens are encrypted with Android Keystore, access
  tokens refresh automatically, and credit use has no offline/local fallback.
- The previously fake password-reset toast now sends a Supabase recovery email
  and completes the password change through a protected Vercel page.
- Credit consumption and payment fulfillment use atomic, idempotent Postgres
  functions.
- Flutterwave and NOWPayments webhooks validate signatures, provider,
  transaction ID, exact expected amount, currency, and replay state.
- Every genuinely new user gets 50 credits exactly once.
- A valid referral code creates a pending link; the referrer gets 250 credits
  exactly once after the referred user's first verified credit purchase.
- Firebase imports marked with `migration_import = true` do not receive a
  duplicate new-signup bonus.
- The admin dashboard uses Supabase sessions and implements the visible user,
  wallet, package, configuration, payment, referral, creator, payout, support,
  background, engine-key, monitoring, fraud, and email operations.
- Secrets are server-only. Engine keys can be encrypted in Supabase Vault.
- Android now compiles/targets API 36, requires API 24+, disables app backups,
  uses HTTPS-only app networking, and opens payment checkout in the external
  browser.

## SQL delivered

Run `supabase/migrations/202607230001_morphly_schema.sql` once in a new Supabase
project. It creates:

- Auth-linked users/profiles and automatic provisioning triggers
- Wallets, append-only-by-API credit ledger, packages, payments, usage, and stream
  sessions
- Referral, creator, commission, payout, fraud, support, email, background,
  settings, license, key-log, and admin-audit records
- Atomic payment, consumption, stream, wallet adjustment, activation, and
  Vault key functions
- Row Level Security policies and least-privilege grants
- Current package/config seed data and the 50/250 referral settings

Existing Firebase Auth password hashes must be imported with Supabase's
Firebase migration tooling. Follow `supabase/FIREBASE_IMPORT.md`, import wallet
opening balances as ledger entries, and require its reconciliation query to
return zero rows before cutover.

## Validation completed

- All 18 API/dashboard JavaScript files pass `node --check`.
- `npm audit --omit=dev` reports zero vulnerabilities.
- The complete SQL migration passes PostgreSQL parsing (166 statements).
- `package.json`, `package-lock.json`, and `vercel.json` parse successfully.
- Android manifest conflicts and Kotlin compile errors found during rebuild
  were corrected.
- The universal debug APK verifies as API 24/target 36, ARMv7+ARM64 only,
  v2-signed, and 16 KB ZIP/ARM64 ELF aligned.

## External cutover checks still required

- Run the SQL against the actual Supabase project and execute role/RLS tests
  with real anon, user, moderator, admin, and service credentials.
- Configure Vercel variables, deploy, and test signup/login/refresh against the
  production URL.
- Configure provider webhook URLs and run test-mode Flutterwave/NOWPayments
  purchases, including replay, wrong amount, wrong currency, and first-purchase
  referral cases.
- Import/reconcile production Firebase data before disabling Firebase writes.
- Run camera, virtual-app, microphone, payment-return, and long-stream tests on
  physical Android 7, 10, 12, 14, and 16 devices.
- Supply the private release keystore values before producing a Play/release
  artifact. A debug-signed APK is for direct testing only.

## Existing repository security cleanup

- The tracked `.env` contains old Firebase credentials. Rotate/revoke those
  credentials and remove that file from Git history before sharing the source.
  New `.env` files are now ignored.
- The tracked `logcat.txt` may contain user/device data. Remove it from the
  repository and Git history before sharing the source.

## Performance priorities

1. Replace repeated `HttpURLConnection` calls with one pooled OkHttp/Retrofit
   client using coroutines and lifecycle cancellation.
2. Split the large `StreamActivity` into ViewModels/services for auth, wallet,
   checkout, camera, and streaming.
3. Batch usage heartbeats instead of making a credit request every few seconds.
4. Stream selected face images from a URI instead of materializing full Base64
   strings; retain the new resize/compression cap.
5. Add Baseline Profiles and Macrobenchmark tests, then tune R8 rules and
   remove unused resources.
6. Paginate admin histories and mass email, and cache public packages/config
   with a short TTL/ETag.
7. Clean duplicate legacy permissions in the virtualization library and profile
   cold start, memory, thermal load, and native 16 KB page-size compatibility.
