# Android backend: Nova

Android calls `https://morphlysm2.vercel.app/api`. The desktop backend is independent.

Server authentication and Firestore storage use project `nova-8fb74`. Existing Android endpoints keep their response contracts. Android account, wallet, payment, referral and stream records live in `_sm` collections in the configured Firestore database. Firebase security rules are not deployed or changed by this repository.

Required server settings:

- `FIREBASE_SERVICE_ACCOUNT_JSON`: Nova service account, stored as a sensitive Vercel setting; never committed or included in an APK.
- `FIREBASE_API_KEY`: Nova public web API key for authentication REST calls.
- `FIREBASE_DATABASE_ID`: optional; defaults to `(default)`.
- Existing Flutterwave and NOWPayments settings stay on this backend.
- `XMAX_API_KEY`: server-only permanent key; Android receives a bounded temporary key.

Enable email/password sign-in and Cloud Firestore in Nova before end-to-end testing. Initial missing Android settings/packages use the existing SQL schema defaults; existing Firestore records are never overwritten by initialization. Existing Supabase data is retained; it is not silently copied into a different Firebase project. Existing identities and balances must be migrated explicitly before using old paid accounts.

Payment callbacks retain signature verification and provider verification. Fulfillment, wallet changes, duplicate receipts, referral rewards and creator commissions use Firestore transactions. XMax output billing uses deduplicated seconds, a server-controlled rate, account ownership and bounded session duration.

Run `FIRESTORE_EMULATOR_HOST=127.0.0.1:8787 node --test tests/firebase-payments.test.mjs` with a local Firestore emulator. The tests use a `demo-` project, never production.

The latest emulator compatibility guard is preserved. It skips BlackBox's native engine on emulators/x86, so emulator sign-in and camera-preview checks do not establish that app virtualization works. A physical ARM device is still required for that check.

Some legacy admin key-pool operations are not ported; they fail closed rather than falling back to the desktop backend. Password recovery uses Firebase action codes. A pasted service-account private key must be rotated before production use.

## Admin dashboard

Sign-in: `https://morphlysm2.vercel.app/admin/login`

Dashboard: `https://morphlysm2.vercel.app/admin`

`POST /api/admin/login` is unchanged. A session cookie is issued only when every check below passes:

1. The email and password match a Firebase Auth user in project `nova-8fb74`.
2. That user's email is verified.
3. The Firebase ID token includes the custom claim `admin: true`.
4. The email is listed in the existing `ADMIN_EMAILS` environment variable (comma-separated, compared in lowercase).

The browser gets an HttpOnly `__Host-morphly_admin` cookie for 8 hours. Every later `/api/admin` call verifies that cookie again, including revocation. `/admin` runs the same check before it sends the dashboard page. Login attempts are limited to 5 per IP and email every 15 minutes, 20 per IP every hour, and locked out after 10 failures for the same email within an hour.

No new environment variables are required. `ADMIN_EMAILS` is already the allowlist; leave it empty and nobody can sign in. `FIREBASE_SERVICE_ACCOUNT_JSON` and `FIREBASE_API_KEY` stay as they are.

### Grant an administrator

1. In the Firebase console for project `nova-8fb74`, open Authentication → Users → Add user. Choose the email and a password. Do not put that password in the repo or in Vercel.
2. The email must be verified. If you created the user yourself and have confirmed they own the address, the script in the next step can mark it verified.
3. Set the custom claim. The Firebase console cannot edit custom claims. From a trusted machine, with the Nova service account in the environment and not on the command line of a shared shell history if you can avoid it:

   ```bash
   FIREBASE_SERVICE_ACCOUNT_JSON='…' node scripts/grant-admin.mjs owner@example.com --verify-email
   ```

   Drop `--verify-email` when the address is already verified. To remove the claim and revoke existing sessions:

   ```bash
   FIREBASE_SERVICE_ACCOUNT_JSON='…' node scripts/grant-admin.mjs owner@example.com --revoke
   ```

4. In the Vercel project `morphlysm2` → Settings → Environment Variables, set `ADMIN_EMAILS` to that email (comma-separate more than one). Apply it to Production, and to Preview if preview sign-in should work. Redeploy so the function picks up the value.
5. Open `/admin/login` and sign in with that email and password. A normal app user, a wrong password, a missing claim, an unverified email, or an email that is not in `ADMIN_EMAILS` is rejected and does not receive a session. Sign out from the dashboard when finished.
