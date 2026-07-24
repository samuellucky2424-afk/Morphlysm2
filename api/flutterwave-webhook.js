import crypto from 'crypto';
import { createServiceClient } from './_shared/supabase.js';
import { publicError, requireMethod } from './_shared/http.js';

export const config = { api: { bodyParser: false } };

function constantTimeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false;
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

async function rawBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  return Buffer.concat(chunks);
}

function validSignature(req, body, secret) {
  if (!secret) return false;
  const signature = req.headers['flutterwave-signature'];
  if (typeof signature === 'string') {
    const expected = crypto.createHmac('sha256', secret).update(body).digest('base64');
    return constantTimeEqual(signature, expected);
  }
  return constantTimeEqual(
    req.headers['verif-hash'] || req.headers['x-verif-hash'],
    secret,
  );
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (!requireMethod(req, res, 'POST')) return;

  const body = await rawBody(req);
  if (!validSignature(req, body, String(process.env.FLUTTERWAVE_WEBHOOK_HASH || ''))) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  try {
    const event = JSON.parse(body.toString('utf8'));
    const type = event?.type || event?.event;
    if (
      type !== 'charge.completed' ||
      !['successful', 'succeeded'].includes(event?.data?.status)
    ) {
      return res.status(200).json({ received: true });
    }

    const providerId = String(event.data.id || '');
    const txRef = String(event.data.tx_ref || event.data.reference || '');
    if (!providerId || !txRef) {
      return res.status(400).json({ error: 'Webhook is missing payment identifiers' });
    }

    const secret = String(process.env.FLUTTERWAVE_SECRET_KEY || '');
    const verifyResponse = await fetch(
      `https://api.flutterwave.com/v3/transactions/${encodeURIComponent(providerId)}/verify`,
      { headers: { Authorization: `Bearer ${secret}` } },
    );
    const verification = await verifyResponse.json().catch(() => ({}));
    const verified = verification?.data;
    if (
      !verifyResponse.ok ||
      verification?.status !== 'success' ||
      verified?.status !== 'successful' ||
      String(verified?.tx_ref || '') !== txRef
    ) {
      return res.status(400).json({ error: 'Payment verification failed' });
    }

    const { data, error } = await createServiceClient().rpc('fulfill_payment_sm', {
      p_tx_ref: txRef,
      p_provider: 'flutterwave',
      p_provider_transaction_id: String(verified.id),
      p_verified_amount: Number(verified.amount),
      p_verified_currency: String(verified.currency || '').toUpperCase(),
      p_verification_payload: {
        provider: 'flutterwave',
        id: verified.id,
        txRef: verified.tx_ref,
        amount: verified.amount,
        currency: verified.currency,
        status: verified.status,
      },
    });
    if (error) throw error;
    return res.status(200).json({ received: true, result: Array.isArray(data) ? data[0] : data });
  } catch (error) {
    console.error('Flutterwave webhook failed:', error);
    return res.status(500).json({ error: publicError(error, 'Webhook processing failed') });
  }
}
