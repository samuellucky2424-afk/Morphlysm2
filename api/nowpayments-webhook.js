import crypto from 'crypto';
import { createServiceClient } from './_shared/supabase.js';
import { publicError, requireMethod } from './_shared/http.js';

function sortObject(value) {
  if (Array.isArray(value)) return value.map(sortObject);
  if (!value || typeof value !== 'object') return value;
  return Object.keys(value).sort().reduce((result, key) => {
    result[key] = sortObject(value[key]);
    return result;
  }, {});
}

function constantTimeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false;
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (!requireMethod(req, res, 'POST')) return;

  const secret = String(process.env.NOWPAYMENTS_IPN_SECRET_KEY || '');
  const receivedSignature = req.headers['x-nowpayments-sig'];
  if (!secret || typeof receivedSignature !== 'string') {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  const calculatedSignature = crypto
    .createHmac('sha512', secret)
    .update(JSON.stringify(sortObject(req.body || {})))
    .digest('hex');
  if (!constantTimeEqual(receivedSignature, calculatedSignature)) {
    return res.status(401).json({ error: 'HMAC signature does not match' });
  }

  const payload = req.body || {};
  if (payload.payment_status !== 'finished') {
    return res.status(200).json({ received: true, status: payload.payment_status });
  }

  try {
    const txRef = String(payload.order_id || '');
    const providerId = String(payload.payment_id || '');
    if (!txRef || !providerId) {
      return res.status(400).json({ error: 'Webhook is missing payment identifiers' });
    }
    const { data, error } = await createServiceClient().rpc('fulfill_payment_sm', {
      p_tx_ref: txRef,
      p_provider: 'nowpayments',
      p_provider_transaction_id: providerId,
      p_verified_amount: Number(payload.price_amount),
      p_verified_currency: String(payload.price_currency || '').toUpperCase(),
      p_verification_payload: {
        provider: 'nowpayments',
        id: providerId,
        amount: payload.price_amount,
        currency: payload.price_currency,
        payAmount: payload.actually_paid || payload.pay_amount,
        payCurrency: payload.pay_currency,
        status: payload.payment_status,
      },
    });
    if (error) throw error;
    return res.status(200).json({ received: true, result: Array.isArray(data) ? data[0] : data });
  } catch (error) {
    console.error('NOWPayments webhook failed:', error);
    return res.status(500).json({ error: publicError(error, 'Webhook processing failed') });
  }
}
