// Flutterwave Standard (v3) helpers: hosted checkout, verification and idempotent wallet crediting.
import crypto from 'node:crypto';
import {createServiceClient} from './firebase.js';

const FLW_API = 'https://api.flutterwave.com/v3';
export const flwSecretKey = () => String(process.env.FLW_SECRET_KEY || process.env.FLUTTERWAVE_SECRET_KEY || '').trim();
export const flwSecretHash = () => String(process.env.FLW_SECRET_HASH || process.env.FLUTTERWAVE_WEBHOOK_HASH || '').trim();
export const flwPublicKey = () => String(process.env.FLW_PUBLIC_KEY || '').trim();

export function constantTimeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string' || !left || !right) return false;
  const a = Buffer.from(left), b = Buffer.from(right);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

async function flw(path, init = {}) {
  const secret = flwSecretKey();
  if (!secret.startsWith('FLWSECK')) throw Object.assign(new Error('Flutterwave is not configured.'), {status: 503});
  const response = await fetch(`${FLW_API}${path}`, {...init, signal: AbortSignal.timeout(20000),
    headers: {Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json', ...(init.headers || {})}});
  const body = await response.json().catch(() => ({}));
  return {ok: response.ok, body};
}

export async function createHostedCheckout({txRef, amount, currency, email, name, credits, redirectUrl, meta}) {
  const {ok, body} = await flw('/payments', {method: 'POST', body: JSON.stringify({
    tx_ref: txRef, amount, currency, redirect_url: redirectUrl,
    customer: {email, name: name || email},
    customizations: {title: 'Morphly credits', description: `${credits} Morphly credits`},
    meta,
  })});
  if (!ok || body.status !== 'success' || !body.data?.link) throw new Error(body.message || 'Unable to start Flutterwave checkout');
  return body.data.link;
}

// Verify with Flutterwave (by transaction id when known, else by tx_ref) and credit the wallet once.
export async function verifyAndFulfill({txRef, transactionId}) {
  const path = transactionId ? `/transactions/${encodeURIComponent(transactionId)}/verify`
    : `/transactions/verify_by_reference?tx_ref=${encodeURIComponent(txRef)}`;
  const {ok, body} = await flw(path);
  const v = body?.data;
  if (!ok || body?.status !== 'success' || !v) return {status: 'pending', verified: false};
  if (txRef && String(v.tx_ref) !== String(txRef)) throw Object.assign(new Error('Payment reference mismatch'), {status: 400});
  if (v.status !== 'successful') return {status: v.status || 'pending', verified: false};
  const {data, error} = await createServiceClient().rpc('fulfill_payment_sm', {
    p_tx_ref: String(v.tx_ref), p_provider: 'flutterwave', p_provider_transaction_id: String(v.id),
    p_verified_amount: Number(v.amount), p_verified_currency: String(v.currency || '').toUpperCase(),
    p_verification_payload: {provider: 'flutterwave', id: v.id, txRef: v.tx_ref, flwRef: v.flw_ref || null,
      amount: v.amount, currency: v.currency, status: v.status, paymentType: v.payment_type || null},
  });
  if (error) throw error;
  return {status: 'successful', verified: true, ...(Array.isArray(data) ? data[0] : data)};
}
