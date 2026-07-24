# Firebase data import order

Run the schema migration first, then migrate in this order:

1. Export and import Firebase Authentication users with Supabase's
   `firebase-to-supabase` Auth tools. Do not insert password rows manually.
   Set `raw_user_meta_data.migration_import = true` on every imported account
   before it is inserted. The provisioning trigger uses this flag to avoid
   giving existing Firebase users the 50-credit new-signup bonus.
2. Export Firestore collections as JSON/CSV.
3. Load user fields into `user_sm` and `profile_sm`, matching rows by the
   imported Auth UUID.
4. Import each wallet's opening balance through `credit_ledger_sm` with
   `reason = 'migration'`, then set `wallet_sm.balance` to the same value.
5. Import packages, payments, usage, logs, key logs, settings, and stream
   sessions using the mapping in `SUPABASE_MIGRATION_AUDIT.md`.
6. Reconcile every wallet:

```sql
select
  w.user_id,
  w.balance as wallet_balance,
  coalesce(sum(l.delta), 0) as ledger_balance
from public.wallet_sm w
left join public.credit_ledger_sm l on l.user_id = w.user_id
group by w.user_id, w.balance
having w.balance <> coalesce(sum(l.delta), 0);
```

The query must return zero rows before the production cutover.
