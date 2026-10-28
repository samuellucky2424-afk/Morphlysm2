import {randomUUID} from 'node:crypto';
import {createXmaxAndroidKey} from './xmax-token.js';

const error = (message,status=400) => {throw Object.assign(new Error(message),{status});};
export function androidRealtime(db, issueKey = createXmaxAndroidKey) {
  const doc = (table,id) => db.collection(table).doc(id);
  async function start(actor, body, apiKey) {
    if (body.provider !== 'xmax' || body.platform !== 'android') error('Choose the Android XMax engine.');
    if (!apiKey) error('XMax is not configured on the Android backend.',503);
    const id=randomUUID(), timestamp=Date.now();
    const maxSeconds=await db.runTransaction(async tx=>{
      const [profile,wallet,lock,availability]=await Promise.all([tx.get(doc('user_sm',actor.id)),
        tx.get(doc('wallet_sm',actor.id)),tx.get(doc('stream_lock_sm',actor.id)),
        tx.get(doc('app_setting_sm','streaming_availability'))]);
      if (profile.data()?.status !== 'active') error('Account is not active',403);
      if (availability.data()?.value?.enabled === false) error('Streaming is disabled',503);
      const seconds=Math.min(1800,Math.floor(Number(wallet.data()?.balance||0)/2));
      if (seconds<1) error('Insufficient credits',402);
      if (lock.data()?.expiresAt>timestamp) error('End your existing stream first.',409);
      tx.create(doc('stream_session_sm',id),{id,user_id:actor.id,provider:'xmax',model:'x2.1',status:'starting',
        created_at:new Date(timestamp).toISOString(),createdAt:timestamp,maxSeconds:seconds,rate:2,consumed_credits:0,
        billedSeconds:[],device_id:String(body.installationId||'').slice(0,120)});
      tx.set(doc('stream_lock_sm',actor.id),{sessionId:id,expiresAt:timestamp+(seconds+120)*1000});
      return seconds;
    });
    const key=await issueKey({apiKey,maxSeconds});
    if (key.error) {
      await db.runTransaction(async tx=>{
        const lock=await tx.get(doc('stream_lock_sm',actor.id));
        tx.update(doc('stream_session_sm',id),{status:'failed',ended_at:new Date().toISOString()});
        if(lock.data()?.sessionId===id)tx.update(lock.ref,{expiresAt:0});
      });
      error(key.error.details||'XMax could not start.',502);
    }
    return {allowed:true,provider:'xmax',model:'x2.1',sessionId:id,token:key.token,maxSeconds,
      billingVersion:2,serverNow:Date.now()};
  }
  async function meter(actor,body,close=false) {
    const seconds=body.epochSeconds||[];
    if(body.billingVersion!==2||!Array.isArray(seconds)||seconds.length>60||seconds.some(s=>!Number.isSafeInteger(s)))error('Invalid usage timestamps');
    if(typeof body.sessionId!=='string'||!body.sessionId||body.sessionId.includes('/'))error('Invalid session');
    return db.runTransaction(async tx=>{
      const [session,wallet,profile,lock]=await Promise.all([tx.get(doc('stream_session_sm',body.sessionId)),
        tx.get(doc('wallet_sm',actor.id)),tx.get(doc('user_sm',actor.id)),tx.get(doc('stream_lock_sm',actor.id))]);
      const row=session.data();
      if(!row||row.user_id!==actor.id)error('Session ownership mismatch',403);
      if(profile.data()?.status!=='active'||!wallet.exists)error('Account is not active',403);
      const last=Math.floor(Date.now()/1000)-1,first=Math.floor(row.createdAt/1000);
      if(seconds.some(s=>s<first||s>last||s>=first+row.maxSeconds))error('Invalid usage timestamps');
      const previous=new Set(row.billedSeconds||[]), balance=Number(wallet.data().balance||0);
      if(!['starting','running'].includes(row.status))return {remainingCredits:balance,shouldStop:true};
      const fresh=[...new Set(seconds)].filter(s=>!previous.has(s));
      const payable=fresh.slice(0,Math.floor(balance/row.rate)),cost=payable.length*row.rate;
      payable.forEach(s=>previous.add(s));
      const remaining=balance-cost,used=Number(wallet.data().used||0)+cost;
      const ended=close||remaining<row.rate||Date.now()>=row.createdAt+row.maxSeconds*1000;
      tx.update(wallet.ref,{balance:remaining,used,updated_at:new Date().toISOString()});
      tx.update(session.ref,{status:ended?'completed':'running',consumed_credits:Number(row.consumed_credits||0)+cost,
        billedSeconds:[...previous],heartbeat_at:new Date().toISOString(),...(ended?{ended_at:new Date().toISOString()}: {})});
      if(ended&&lock.data()?.sessionId===row.id)tx.update(lock.ref,{expiresAt:0});
      if(cost)tx.create(db.collection('wallet_ledger_sm').doc(),{user_id:actor.id,stream_session_id:row.id,
        delta:-cost,entry_type:'usage',balance_after:remaining,created_at:new Date().toISOString()});
      return {remainingCredits:remaining,shouldStop:ended,recordedSeconds:payable.length};
    });
  }
  return {start,meter};
}
