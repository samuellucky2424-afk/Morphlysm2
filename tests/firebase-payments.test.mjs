import {test} from 'node:test';
import assert from 'node:assert/strict';
import {initializeApp} from 'firebase-admin/app';
import {getFirestore} from 'firebase-admin/firestore';
import {createOperations} from '../server/shared/firebase-operations.js';
import {requireUser} from '../server/shared/firebase.js';
import {androidRealtime} from '../server/shared/firebase-realtime.js';
const enabled = Boolean(process.env.FIRESTORE_EMULATOR_HOST);
const db = enabled ? getFirestore(initializeApp({projectId: 'demo-morphly-android-migration'})) : null;
const ops = enabled ? createOperations(db) : null;
let sequence = 0;
const unique = () => `test-${Date.now()}-${sequence++}`;
const get = async (table,id) => (await db.collection(table).doc(id).get()).data();
async function user(credits = 50) {
  const id = unique(), actor = {id, uid: id, email: `${id}@example.invalid`, role: 'user', status: 'active'};
  await db.collection('user_sm').doc(id).set(actor);
  await db.collection('wallet_sm').doc(id).set({user_id: id, balance: credits, used: 0});
  return actor;
}
async function payment(actor, credits = 100) {
  const code = unique();
  await db.collection('credit_package_sm').doc(code).set({code, credits, price: 29000, currency: 'NGN', is_active: true});
  return ops.rpc('begin_payment_sm', {p_package_code: code, p_provider: 'flutterwave', p_tx_ref: unique()}, actor);
}
function verification(row, transaction = unique()) {
  return {p_tx_ref: row.tx_ref, p_provider: row.provider, p_provider_transaction_id: transaction,
    p_verified_amount: row.expected_amount, p_verified_currency: row.currency};
}
test('missing and forged bearer credentials fail closed', async () => {
  await assert.rejects(requireUser({headers:{}}), error => error.status === 401);
  await assert.rejects(requireUser({headers:{authorization:'Bearer forged'}}), error => error.status === 401);
});
test('signup provision is idempotent and preserves an existing wallet', {skip: !enabled}, async () => {
  const id = unique(), record = {uid: id, email:`${id}@example.invalid`};
  await db.collection('wallet_sm').doc(id).set({balance: 777, used: 8});
  await Promise.all([ops.provision(record), ops.provision(record)]);
  assert.equal((await get('wallet_sm',id)).balance,777);
  assert.equal((await get('user_sm',id)).role,'user');
});
test('concurrent verified callbacks credit exactly once and preserve the original balance', {skip: !enabled}, async () => {
  const actor = await user(23), row = await payment(actor), p = verification(row);
  await Promise.all(Array.from({length:5},()=>ops.rpc('fulfill_payment_sm',p,null)));
  assert.equal((await get('wallet_sm',actor.id)).balance,123);
  assert.equal((await ops.rpc('fulfill_payment_sm',p,null)).was_already_fulfilled,true);
});
test('amount, currency, provider, client fulfillment and provider-ID reuse cannot credit a wallet', {skip: !enabled}, async () => {
  const actor = await user(), row = await payment(actor), p = verification(row);
  for (const patch of [{p_verified_amount:1},{p_verified_currency:'USD'},{p_provider:'nowpayments'}]) {
    await assert.rejects(ops.rpc('fulfill_payment_sm',{...p,...patch},null));
  }
  await assert.rejects(ops.rpc('fulfill_payment_sm',p,actor),/Service/);
  assert.equal((await get('wallet_sm',actor.id)).balance,50);
  await ops.rpc('fulfill_payment_sm',p,null);
  const second = await payment(actor);
  await assert.rejects(ops.rpc('fulfill_payment_sm',verification(second,p.p_provider_transaction_id),null),/already used/);
  assert.equal((await get('wallet_sm',actor.id)).balance,150);
});
test('usage retries debit once, concurrent spending never overdraws, and sessions enforce ownership', {skip: !enabled}, async () => {
  const actor = await user(10), session = unique();
  await db.collection('stream_session_sm').doc(session).set({user_id:actor.id,consumed_credits:0});
  const p = {p_amount:6,p_feature_name:'live_stream',p_stream_session_id:session,p_idempotency_key:unique()};
  await Promise.all([ops.rpc('consume_credits_sm',p,actor),ops.rpc('consume_credits_sm',p,actor)]);
  assert.equal((await get('wallet_sm',actor.id)).balance,4);
  const result = await Promise.allSettled([1,2].map(()=>ops.rpc('consume_credits_sm',{...p,p_amount:3,p_idempotency_key:unique()},actor)));
  assert.equal(result.filter(x=>x.status==='fulfilled').length,1);
  const other = await user();
  await assert.rejects(ops.rpc('consume_credits_sm',{...p,p_idempotency_key:unique()},other),/session/);
  assert.equal((await get('wallet_sm',other.id)).balance,50);
});
test('concurrent first purchases reward a referrer and creator only once', {skip: !enabled}, async () => {
  const actor=await user(),referrer=await user(0),creator=unique();
  await db.collection('referral_sm').doc(actor.id).set({status:'signed_up',referrer_user_id:referrer.id,creator_id:creator});
  await db.collection('creator_sm').doc(creator).set({status:'active',commission_rate:30});
  const rows=await Promise.all([payment(actor),payment(actor)]);
  await Promise.all(rows.map(row=>ops.rpc('fulfill_payment_sm',verification(row),null)));
  assert.equal((await get('wallet_sm',referrer.id)).balance,250);
  const commissions=await db.collection('commission_sm').where('creator_id','==',creator).get();
  assert.equal(commissions.size,1);
  assert.equal(commissions.docs[0].data().commission_amount,8700);
});
test('XMax reserves up front, bills wall-clock, refunds the rest and revokes the key', {skip: !enabled}, async () => {
  const actor=await user(1000); let t=Date.now(); const revoked=[];
  const realtime=androidRealtime(db,async ({maxSeconds})=>({token:`tk-${maxSeconds}`}),
    async ({token})=>{revoked.push(token);return {ok:true,consumed:null};},()=>t);
  const s=await realtime.start(actor,{provider:'xmax',platform:'android'},'server-only');
  assert.equal(s.maxSeconds,500); assert.equal(s.provider,'xmax'); assert.equal(typeof s.serverNow,'number');
  assert.equal((await get('wallet_sm',actor.id)).balance,0);
  assert.equal((await get('stream_key_sm',s.sessionId)).token,'tk-500');
  await assert.rejects(realtime.meter(actor,{}),/sessionId/);
  await assert.rejects(realtime.meter(await user(),{sessionId:s.sessionId}),/ownership/);
  t+=65000; // 60 billable seconds after 5s grace
  const hb=await realtime.meter(actor,{sessionId:s.sessionId,billingVersion:2,epochSeconds:[1,2,3]});
  assert.equal(hb.remainingCredits,880); assert.equal(hb.shouldStop,false);
  await Promise.all([realtime.meter(actor,{sessionId:s.sessionId},true),realtime.meter(actor,{sessionId:s.sessionId},true)]);
  const w=await get('wallet_sm',actor.id);
  assert.equal(w.balance,880); assert.equal(w.used,120); assert.equal(w.reserved,0);
  assert.equal((await get('stream_lock_sm',actor.id)).expiresAt,0);
  assert.equal(await get('stream_key_sm',s.sessionId),undefined);
  assert.ok(revoked.includes('tk-500'));
});
test('a new start replaces the old session and an abandoned session is settled from provider usage', {skip: !enabled}, async () => {
  const actor=await user(200); let t=Date.now(); const realtime=androidRealtime(db,async ({maxSeconds})=>({token:`k${t}`}),
    async ()=>({ok:true,consumed:20}),()=>t);
  const a=await realtime.start(actor,{provider:'xmax',platform:'android'},'k');
  t+=10*60000; // app vanished; heartbeat stale; XMax says 20 points used
  const b=await realtime.start(actor,{provider:'xmax',platform:'android'},'k');
  assert.equal((await get('stream_session_sm',a.sessionId)).status,'completed');
  assert.equal((await get('stream_session_sm',a.sessionId)).billed_seconds,20);
  assert.equal(b.maxSeconds,80); // (200-40)/2
  await realtime.settleOpen(actor.id,'k',{replace:true});
  assert.equal((await get('wallet_sm',actor.id)).balance,160);
});
test('failed XMax startup refunds the hold and releases the lock', {skip: !enabled}, async () => {
  const actor=await user(100);
  const realtime=androidRealtime(db,async()=>({error:{details:'Provider unavailable'}}));
  await assert.rejects(realtime.start(actor,{provider:'xmax',platform:'android'},'server-only'),/unavailable/);
  assert.equal((await get('wallet_sm',actor.id)).balance,100);
  assert.equal((await get('stream_lock_sm',actor.id)).expiresAt,0);
});
test('signup credits are held until email verification, once per device', {skip: !enabled}, async () => {
  await db.collection('app_setting_sm').doc('referral_program').set({value:{enabled:true,signup_credits:50}});
  const a=unique(), b=unique();
  await ops.provision({uid:a,email:`${a}@x.invalid`},{deviceHash:'dev1',ipHash:'ip1'});
  assert.equal((await get('wallet_sm',a)).balance,0);
  assert.equal(await ops.claimSignupBonus(a),true); assert.equal(await ops.claimSignupBonus(a),false);
  assert.equal((await get('wallet_sm',a)).balance,50);
  await ops.provision({uid:b,email:`${b}@x.invalid`},{deviceHash:'dev1',ipHash:'ip2'});
  assert.equal(await ops.claimSignupBonus(b),false);
  assert.equal((await get('user_sm',b)).signup_bonus_status,'denied_device');
  const code=(await get('user_sm',a)).referral_code, c=unique();
  await ops.provision({uid:c,email:`${c}@x.invalid`,emailVerified:true},{deviceHash:'dev1',ipHash:'ip3',referredByCode:code});
  assert.equal((await get('referral_sm',c)).status,'self_referral');
});
