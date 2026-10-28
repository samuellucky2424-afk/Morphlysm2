import {randomUUID, createHash} from 'node:crypto';
const hash = s => createHash('sha256').update(String(s)).digest('hex');
const now = () => new Date().toISOString();
const fail = (message, status = 400) => {throw Object.assign(new Error(message), {status});};
const safeAmount = n => Number.isSafeInteger(n) && n > 0;

export function createOperations(db) {
  const col = name => db.collection(name);
  const ref = (table, id) => col(table).doc(String(id));
  async function summary(uid) {
    const [profile, wallet] = await Promise.all([ref('user_sm', uid).get(), ref('wallet_sm', uid).get()]);
    if (!profile.exists) fail('Application account was not found', 403);
    return {...profile.data(), user_id: uid, display_name: profile.data().name,
      balance: Number(wallet.data()?.balance || 0), credits_used: Number(wallet.data()?.used || 0)};
  }
  async function provision(user, input = {}) {
    return db.runTransaction(async tx => {
      const p = ref('user_sm', user.uid), w = ref('wallet_sm', user.uid);
      const [profile, wallet, settings] = await Promise.all([tx.get(p), tx.get(w), tx.get(ref('app_setting_sm', 'referral_program'))]);
      if (profile.exists) return;
      const referralCode = String(input.referredByCode || '').trim().toUpperCase();
      const referred = referralCode ? await tx.get(col('user_sm').where('referral_code', '==', referralCode).limit(2)) : null;
      const owner = referred?.docs[0];
      if (referralCode && (!owner || owner.id === user.uid || owner.data().status !== 'active')) fail('Invalid referral code');
      const credits = settings.data()?.value?.enabled === false ? 0 : Number(settings.data()?.value?.signup_credits ?? 50);
      if (!Number.isSafeInteger(credits) || credits < 0) fail('Invalid signup-credit configuration', 503);
      const timestamp = now();
      tx.create(p, {id: user.uid, email: String(user.email || '').toLowerCase(), name: user.displayName || input.name || '',
        phone: input.phone || '', role: 'user', status: 'active', is_activated: false, license_expires_at: null,
        referral_code: randomUUID().replaceAll('-', '').slice(0, 10).toUpperCase(), created_at: timestamp, updated_at: timestamp});
      // Existing wallets are preserved; the profile lock prevents duplicate signup credits.
      if (!wallet.exists) {
        tx.create(w, {id: user.uid, user_id: user.uid, balance: credits, used: 0, created_at: timestamp, updated_at: timestamp});
        tx.create(ref('wallet_ledger_sm', hash(`signup:${user.uid}`)), {id: hash(`signup:${user.uid}`), user_id: user.uid,
          delta: credits, balance_after: credits, entry_type: 'signup_bonus', created_at: timestamp});
      }
      if (owner) tx.create(ref('referral_sm', user.uid), {id: user.uid, referrer_user_id: owner.id,
        referred_user_id: user.uid, status: 'signed_up', referral_code: referralCode, created_at: timestamp});
    });
  }
  async function rpc(name, p, actor) {
    if (name === 'fulfill_payment_sm') {
      if (actor) fail('Service authentication required', 403);
      const paymentQuery = col('payment_transaction_sm').where('tx_ref', '==', p.p_tx_ref).limit(2);
      return db.runTransaction(async tx => {
        const matches = await tx.get(paymentQuery);
        if (matches.docs.length !== 1) fail('Unknown payment reference');
        const document = matches.docs[0], payment = document.data();
        if (!['flutterwave', 'nowpayments'].includes(p.p_provider) || payment.provider !== p.p_provider) fail('Payment provider mismatch');
        if (!String(p.p_provider_transaction_id || '').trim()) fail('Missing provider transaction');
        if (payment.provider_transaction_id && payment.provider_transaction_id !== p.p_provider_transaction_id) fail('Payment transaction mismatch');
        if (Number(payment.expected_amount) !== Number(p.p_verified_amount) || payment.currency !== p.p_verified_currency) fail('Payment amount or currency mismatch');
        if (!['pending', 'verified'].includes(payment.status)) fail('Payment is not pending');
        const w = ref('wallet_sm', payment.user_id), unique = ref('payment_receipt_sm', hash(`${p.p_provider}:${p.p_provider_transaction_id}`));
        const [wallet, receipt, referral, settings] = await Promise.all([tx.get(w), tx.get(unique),
          tx.get(ref('referral_sm', payment.user_id)), tx.get(ref('app_setting_sm', 'referral_program'))]);
        if (!wallet.exists) fail('Payment wallet was not found');
        if (receipt.exists && receipt.data().payment_id !== document.id) fail('Provider transaction was already used');
        if (payment.status === 'verified') return {payment_id: document.id, payment_status: 'verified',
          wallet_balance: wallet.data().balance, was_already_fulfilled: true};
        if (!safeAmount(Number(payment.credits))) fail('Payment credits are invalid');
        const r = referral.data();
        const rewardEnabled = settings.data()?.value?.enabled !== false;
        const reward = Number(settings.data()?.value?.purchase_reward ?? 250);
        const rewardWallet = r?.status === 'signed_up' && r.referrer_user_id && r.referrer_user_id !== payment.user_id && rewardEnabled
          ? ref('wallet_sm', r.referrer_user_id) : null;
        const rewardSnapshot = rewardWallet ? await tx.get(rewardWallet) : null;
        const creator = r?.status === 'signed_up' && rewardEnabled && r.creator_id
          ? await tx.get(ref('creator_sm', r.creator_id)) : null;
        const balance = Number(wallet.data().balance) + Number(payment.credits), timestamp = now();
        tx.update(w, {balance, updated_at: timestamp});
        tx.update(document.ref, {status: 'verified', provider_transaction_id: p.p_provider_transaction_id,
          verified_at: timestamp, verification_payload: p.p_verification_payload || {}, updated_at: timestamp});
        tx.create(unique, {payment_id: document.id, created_at: timestamp});
        tx.create(ref('wallet_ledger_sm', hash(`payment:${document.id}`)), {user_id: payment.user_id,
          delta: payment.credits, balance_after: balance, payment_id: document.id, entry_type: 'payment', created_at: timestamp});
        if (rewardSnapshot?.exists && Number.isSafeInteger(reward) && reward > 0) {
          const after = Number(rewardSnapshot.data().balance) + reward;
          tx.update(rewardWallet, {balance: after, updated_at: timestamp});
          tx.create(ref('wallet_ledger_sm', hash(`referral:${payment.user_id}`)), {user_id: r.referrer_user_id,
            delta: reward, balance_after: after, entry_type: 'referral_bonus', created_at: timestamp});
        }
        if (r?.status === 'signed_up' && rewardEnabled) {
          tx.update(referral.ref, {status: 'converted', converted_at: timestamp});
          const rate = Number(creator?.data()?.commission_rate);
          if (creator?.data()?.status === 'active' && Number.isFinite(rate) && rate >= 0 && rate <= 100) {
            tx.create(ref('commission_sm', document.id), {id: document.id, creator_id: r.creator_id,
              referral_id: referral.id, payment_transaction_id: document.id, gross_amount: payment.expected_amount,
              currency: payment.currency, commission_rate: rate,
              commission_amount: Math.round(Number(payment.expected_amount) * rate) / 100,
              status: 'pending', created_at: timestamp});
          }
        }
        return {payment_id: document.id, payment_status: 'verified', wallet_balance: balance, was_already_fulfilled: false};
      });
    }
    if (!actor?.id) fail('Authentication required', 401);
    if (name.startsWith('admin_') || name === 'save_engine_key_sm') {
      if (!['admin', 'moderator'].includes(actor.role)) fail('Administrator access required', 403);
    }
    if (name === 'begin_payment_sm') {
      if (!['flutterwave', 'nowpayments'].includes(p.p_provider)) fail('Unsupported payment provider');
      const id = randomUUID();
      return db.runTransaction(async tx => {
        const [pack, profile] = await Promise.all([tx.get(ref('credit_package_sm', p.p_package_code)), tx.get(ref('user_sm', actor.id))]);
        if (profile.data()?.status !== 'active') fail('Account is not active', 403);
        const item = pack.data();
        if (!item?.is_active || !safeAmount(Number(item.credits)) || !Number.isFinite(Number(item.price)) || Number(item.price) < 0) fail('Unknown or inactive credit package');
        const row = {id, user_id: actor.id, user_email_snapshot: actor.email, package_id: pack.id,
          provider: p.p_provider, tx_ref: p.p_tx_ref, credits: item.credits, expected_amount: item.price,
          currency: item.currency, status: 'pending', created_at: now()};
        tx.create(ref('payment_transaction_sm', id), row);
        return row;
      });
    }
    if (name === 'consume_credits_sm' || name === 'admin_adjust_credits_sm') {
      let uid = actor.id, amount = -Number(p.p_amount);
      if (name === 'admin_adjust_credits_sm') {
        const matches = await col('user_sm').where('email', '==', String(p.p_email).toLowerCase()).limit(2).get();
        if (matches.docs.length !== 1) fail('User not found', 404);
        uid = matches.docs[0].id; amount = Number(p.p_delta);
      }
      if (!Number.isSafeInteger(amount) || amount === 0 || (name === 'consume_credits_sm' && (amount > 0 || amount < -100000))) fail('Invalid credit amount');
      if (!p.p_idempotency_key) fail('An idempotency key is required');
      const receipt = ref('wallet_ledger_sm', hash(`${name}:${uid}:${p.p_idempotency_key}`));
      return db.runTransaction(async tx => {
        const w = ref('wallet_sm', uid), profileRef = ref('user_sm', uid);
        const [wallet, existing, profile, session] = await Promise.all([tx.get(w), tx.get(receipt), tx.get(profileRef),
          p.p_stream_session_id ? tx.get(ref('stream_session_sm', p.p_stream_session_id)) : Promise.resolve(null)]);
        if (!wallet.exists || profile.data()?.status !== 'active') fail('Account is not active', 403);
        if (session && session.data()?.user_id !== actor.id) fail('Stream session not found', 403);
        if (existing.exists) return {balance: wallet.data().balance, used: wallet.data().used || 0, usage_id: receipt.id};
        const balance = Number(wallet.data().balance) + amount;
        if (balance < 0) fail('Insufficient credits', 402);
        const used = Number(wallet.data().used || 0) + (name === 'consume_credits_sm' ? -amount : 0), timestamp = now();
        tx.update(w, {balance, used, updated_at: timestamp});
        tx.create(receipt, {id: receipt.id, user_id: uid, delta: amount, balance_after: balance,
          actor_user_id: actor.id, entry_type: name === 'consume_credits_sm' ? 'usage' : 'admin_adjustment', created_at: timestamp});
        if (name === 'consume_credits_sm') tx.create(ref('usage_sm', receipt.id), {id: receipt.id, user_id: uid,
          credits_used: -amount, status: 'success', feature_name: p.p_feature_name, created_at: timestamp});
        if (session) tx.update(session.ref, {consumed_credits: Number(session.data().consumed_credits || 0) - amount, heartbeat_at: timestamp});
        return {balance, used, usage_id: receipt.id};
      });
    }
    if (name === 'create_stream_session_sm') {
      return db.runTransaction(async tx => {
        const [profile, wallet, availability] = await Promise.all([tx.get(ref('user_sm', actor.id)),
          tx.get(ref('wallet_sm', actor.id)), tx.get(ref('app_setting_sm', 'streaming_availability'))]);
        if (profile.data()?.status !== 'active') fail('Account is not active', 403);
        if (!(Number(wallet.data()?.balance) > 0)) fail('Insufficient credits', 402);
        if (availability.data()?.value?.enabled === false) fail('Live streaming is currently disabled', 503);
        const id = randomUUID(), timestamp = now();
        const row = {id, user_id: actor.id, user_email_snapshot: actor.email, device_id: p.p_device_id,
          mode: p.p_mode, prompt: p.p_prompt, preset: p.p_preset, enhance: p.p_enhance, quality: p.p_quality,
          fps: p.p_fps, model: p.p_model, status: 'starting', consumed_credits: 0, created_at: timestamp, heartbeat_at: timestamp};
        tx.create(ref('stream_session_sm', id), row);
        tx.update(ref('user_sm', actor.id), {device_id: p.p_device_id, updated_at: timestamp});
        return row;
      });
    }
    if (name === 'admin_list_users_sm') {
      const rows = await col('user_sm').get();
      const summaries = await Promise.all(rows.docs.map(d => summary(d.id)));
      return summaries.filter(r => !p.p_search || `${r.email} ${r.display_name}`.toLowerCase().includes(p.p_search.toLowerCase()))
        .slice(p.p_offset || 0, (p.p_offset || 0) + Math.min(p.p_limit || 50, 100)).map(r => ({...r, total_count: rows.size}));
    }
    if (name === 'admin_overview_sm') {
      const [users, wallets, payments, sessions] = await Promise.all(['user_sm','wallet_sm','payment_transaction_sm','stream_session_sm'].map(n => col(n).get()));
      return {total_users: users.size, active_users: users.docs.filter(d => d.data().status === 'active').length,
        total_credits_remaining: wallets.docs.reduce((n, d) => n + Number(d.data().balance || 0), 0),
        total_revenue: payments.docs.filter(d => d.data().status === 'verified').reduce((n, d) => n + Number(d.data().expected_amount || 0), 0),
        active_streams: sessions.docs.filter(d => ['starting','running'].includes(d.data().status)).length};
    }
    // Never fall back to another product's database or bypass authorization for an unmigrated operation.
    fail('This operation is not available on the Firebase backend yet.', 503);
  }
  return {rpc, provision, summary};
}
