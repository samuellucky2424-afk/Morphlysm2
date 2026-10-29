// Android XMax streaming, billed on the server from wall-clock time.
//  start: closes any previous open session (revokes its key), reserves maxSeconds*rate credits up front,
//         issues a single-session temporary key that expires shortly after the paid-for time.
//  heartbeat: keeps the session alive and reports the projected balance; client-reported seconds are ignored.
//  end: requires sessionId; charges elapsed wall-clock seconds, refunds the rest of the hold, revokes the key.
// Sessions whose app vanished are settled lazily (next start / balance fetch) using XMax's own usage counter.
import {randomUUID} from 'node:crypto';
import {createXmaxAndroidKey, revokeXmaxKey, KEY_GRACE_SECONDS} from './xmax-token.js';

const error = (message,status=400) => {throw Object.assign(new Error(message),{status,expose:true});};
export const RATE = 2, MAX_SECONDS = 1800, MIN_SECONDS = 30, CONNECT_GRACE_SECONDS = 5, STALE_MS = 60000;
const OPEN = ['starting','running'];
const iso = () => new Date().toISOString();

// Seconds of wall-clock time to bill for a session at time t (ms).
export function wallSeconds(row, t) {
  const end = Math.min(t, row.createdAt + row.maxSeconds * 1000);
  return Math.max(0, Math.min(row.maxSeconds, Math.floor((end - row.createdAt) / 1000) - CONNECT_GRACE_SECONDS));
}

export function androidRealtime(db, issueKey = createXmaxAndroidKey, revokeKey = revokeXmaxKey, clock = Date.now) {
  const doc = (table,id) => db.collection(table).doc(id);

  // Settles one session exactly once. mode: 'end' (client ended), 'expired', 'replaced', 'stale'.
  async function settle(sessionId, mode, apiKey) {
    const keyDoc = doc('stream_key_sm', sessionId);
    const keySnap = await keyDoc.get();
    const revoked = keySnap.exists ? await revokeKey({apiKey, token: keySnap.data().token}) : {ok: true, consumed: null};
    const result = await db.runTransaction(async tx => {
      const session = await tx.get(doc('stream_session_sm', sessionId));
      const row = session.data();
      if (!row || !OPEN.includes(row.status)) return {already: true, row};
      const [wallet, lock] = await Promise.all([tx.get(doc('wallet_sm', row.user_id)), tx.get(doc('stream_lock_sm', row.user_id))]);
      const now = clock(), lastBeat = row.heartbeatAt || row.createdAt;
      const silent = now - lastBeat > STALE_MS;
      let seconds = wallSeconds(row, now);
      if (silent && mode !== 'end') {
        // App disappeared: bill at least up to the last heartbeat, at most up to now; XMax's counter decides in between.
        const floor = wallSeconds(row, lastBeat + 15000);
        seconds = revoked.consumed == null ? seconds : Math.max(floor, Math.min(seconds, Math.ceil(revoked.consumed)));
      }
      if (!revoked.ok) seconds = Math.max(seconds, wallSeconds(row, now)); // key may still be live until expiry
      const legacy = !Number.isFinite(Number(row.reserved));
      const balance = Number(wallet.data()?.balance || 0), reserved = legacy ? 0 : Number(row.reserved);
      const alreadyCharged = legacy ? Number(row.consumed_credits || 0) : 0;
      const owed = Math.max(0, seconds * row.rate - alreadyCharged);
      const charge = legacy ? Math.min(owed, balance) : Math.min(owed, reserved);
      const refund = legacy ? 0 : reserved - charge;
      const after = legacy ? balance - charge : balance + refund, ts = iso();
      if (wallet.exists) tx.update(wallet.ref, {balance: after, used: Number(wallet.data().used || 0) + charge,
        reserved: Math.max(0, Number(wallet.data().reserved || 0) - reserved), updated_at: ts});
      tx.update(session.ref, {status: 'completed', ended_at: ts, end_reason: mode, billed_seconds: seconds,
        consumed_credits: alreadyCharged + charge, refunded_credits: refund, key_revoked: revoked.ok,
        provider_consumed: revoked.consumed, heartbeat_at: ts});
      if (lock.data()?.sessionId === sessionId) tx.update(lock.ref, {expiresAt: 0});
      const ledger = (type, delta) => tx.create(db.collection('wallet_ledger_sm').doc(`${sessionId}_${type}`),
        {user_id: row.user_id, stream_session_id: sessionId, delta, entry_type: type, balance_after: after, created_at: ts});
      if (!legacy) ledger('stream_release', reserved);
      if (charge) ledger('usage', -charge);
      return {already: false, row, remaining: after, seconds};
    });
    if (revoked.ok && keySnap.exists) await keyDoc.delete().catch(() => {});
    return result;
  }

  // Settles the user's open session if it is expired or silent (or always, when replacing it).
  async function settleOpen(uid, apiKey, {replace = false} = {}) {
    const lock = (await doc('stream_lock_sm', uid).get()).data();
    if (!lock?.sessionId) return null;
    const row = (await doc('stream_session_sm', lock.sessionId).get()).data();
    if (!row || !OPEN.includes(row.status)) return null;
    const now = clock(), expired = now >= row.createdAt + row.maxSeconds * 1000;
    const silent = now - (row.heartbeatAt || row.createdAt) > STALE_MS;
    if (!replace && !expired && !silent) return null;
    return settle(lock.sessionId, expired ? 'expired' : silent ? 'stale' : 'replaced', apiKey);
  }

  async function start(actor, body, apiKey) {
    if (body.provider !== 'xmax' || body.platform !== 'android') error('Choose the Android XMax engine.');
    if (!apiKey) error('XMax is not configured on the Android backend.',503);
    await settleOpen(actor.id, apiKey, {replace: true}); // one active session per user: the old key is revoked first
    const id=randomUUID(), timestamp=clock();
    const maxSeconds=await db.runTransaction(async tx=>{
      const [profile,wallet,lock,availability]=await Promise.all([tx.get(doc('user_sm',actor.id)),
        tx.get(doc('wallet_sm',actor.id)),tx.get(doc('stream_lock_sm',actor.id)),
        tx.get(doc('app_setting_sm','streaming_availability'))]);
      if (profile.data()?.status !== 'active') error('Account is not active',403);
      if (availability.data()?.value?.enabled === false) error('Streaming is disabled',503);
      const balance=Number(wallet.data()?.balance||0);
      const seconds=Math.min(MAX_SECONDS,Math.floor(balance/RATE));
      if (!wallet.exists || seconds<MIN_SECONDS) error('Not enough credits for a stream',402);
      if (lock.data()?.expiresAt>timestamp) error('End your existing stream first.',409);
      const reserved=seconds*RATE, ts=new Date(timestamp).toISOString();
      tx.update(wallet.ref,{balance:balance-reserved,reserved:Number(wallet.data().reserved||0)+reserved,updated_at:ts});
      tx.create(doc('stream_session_sm',id),{id,user_id:actor.id,provider:'xmax',model:'x2.1',status:'starting',
        created_at:ts,createdAt:timestamp,heartbeatAt:timestamp,maxSeconds:seconds,rate:RATE,reserved,consumed_credits:0,
        billingVersion:3,device_id:String(body.installationId||'').slice(0,120)});
      tx.create(db.collection('wallet_ledger_sm').doc(`${id}_stream_hold`),{user_id:actor.id,stream_session_id:id,
        delta:-reserved,entry_type:'stream_hold',balance_after:balance-reserved,created_at:ts});
      tx.set(doc('stream_lock_sm',actor.id),{sessionId:id,expiresAt:timestamp+(seconds+KEY_GRACE_SECONDS)*1000});
      return seconds;
    });
    const key=await issueKey({apiKey,maxSeconds});
    if (key.error) {
      await db.runTransaction(async tx=>{
        const [lock,wallet,session]=await Promise.all([tx.get(doc('stream_lock_sm',actor.id)),tx.get(doc('wallet_sm',actor.id)),
          tx.get(doc('stream_session_sm',id))]);
        if(session.data()?.status!=='starting')return;
        const reserved=Number(session.data().reserved||0);
        tx.update(wallet.ref,{balance:Number(wallet.data().balance||0)+reserved,
          reserved:Math.max(0,Number(wallet.data().reserved||0)-reserved),updated_at:iso()});
        tx.update(session.ref,{status:'failed',ended_at:iso(),refunded_credits:reserved});
        tx.create(db.collection('wallet_ledger_sm').doc(`${id}_stream_release`),{user_id:actor.id,stream_session_id:id,
          delta:reserved,entry_type:'stream_release',balance_after:Number(wallet.data().balance||0)+reserved,created_at:iso()});
        if(lock.data()?.sessionId===id)tx.update(lock.ref,{expiresAt:0});
      });
      error(key.error.details||'XMax could not start.',502);
    }
    await doc('stream_key_sm',id).set({token:key.token,user_id:actor.id,created_at:iso()});
    return {allowed:true,provider:'xmax',model:'x2.1',sessionId:id,token:key.token,maxSeconds,
      billingVersion:3,serverNow:clock()};
  }

  async function meter(actor,body,close=false,apiKey=process.env.XMAX_API_KEY) {
    const sessionId=typeof body?.sessionId==='string'&&/^[A-Za-z0-9_-]{8,80}$/.test(body.sessionId)?body.sessionId:null;
    if(!sessionId) error('A valid sessionId is required');
    const snap=await doc('stream_session_sm',sessionId).get(), row=snap.data();
    if(!row||row.user_id!==actor.id) error('Session ownership mismatch',403);
    const walletBalance=async()=>Number((await doc('wallet_sm',actor.id).get()).data()?.balance||0);
    if(!OPEN.includes(row.status)) return {remainingCredits:await walletBalance(),shouldStop:true,recordedSeconds:0};
    const now=clock(), expired=now>=row.createdAt+row.maxSeconds*1000;
    if(close||expired){
      const r=await settle(sessionId,close?'end':'expired',apiKey);
      return {remainingCredits:r.already?await walletBalance():r.remaining,shouldStop:true,recordedSeconds:r.seconds||0};
    }
    // Heartbeat: record liveness and report the balance the user would have if the stream stopped now.
    const live=await db.runTransaction(async tx=>{
      const [session,wallet,profile]=await Promise.all([tx.get(snap.ref),tx.get(doc('wallet_sm',actor.id)),tx.get(doc('user_sm',actor.id))]);
      const r=session.data();
      if(!OPEN.includes(r.status)) return null;
      if(profile.data()?.status!=='active') return 'inactive';
      tx.update(session.ref,{status:'running',heartbeatAt:now,heartbeat_at:new Date(now).toISOString()});
      const seconds=wallSeconds(r,now), legacy=!Number.isFinite(Number(r.reserved));
      const balance=Number(wallet.data()?.balance||0);
      return {remainingCredits:legacy?Math.max(0,balance-(seconds*r.rate-Number(r.consumed_credits||0))):balance+Number(r.reserved)-Math.min(Number(r.reserved),seconds*r.rate),
        shouldStop:false,recordedSeconds:seconds};
    });
    if(live==='inactive'){const r=await settle(sessionId,'end',apiKey);return {remainingCredits:r.remaining??0,shouldStop:true,recordedSeconds:0};}
    if(!live) return {remainingCredits:await walletBalance(),shouldStop:true,recordedSeconds:0};
    return live;
  }
  return {start,meter,settle,settleOpen};
}
