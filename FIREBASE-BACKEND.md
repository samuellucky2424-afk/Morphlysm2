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
