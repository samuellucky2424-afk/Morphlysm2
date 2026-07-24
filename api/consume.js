import { requireUser } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  positiveInteger,
  publicError,
  requireMethod,
} from './_shared/http.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  const amount = positiveInteger(req.body?.amount);
  const streamSessionId = cleanString(req.body?.streamSessionId, 64);
  const idempotencyKey = cleanString(
    req.headers['idempotency-key'] || req.body?.idempotencyKey,
    160,
  );
  if (!amount) return res.status(400).json({ error: 'amount must be an integer between 1 and 100000' });
  if (!idempotencyKey) return res.status(400).json({ error: 'An idempotency key is required' });
  if (streamSessionId && !UUID_PATTERN.test(streamSessionId)) {
    return res.status(400).json({ error: 'streamSessionId must be a valid UUID' });
  }

  try {
    const { client } = await requireUser(req);
    const { data, error } = await client.rpc('consume_credits_sm', {
      p_amount: amount,
      p_feature_name: cleanString(req.body?.featureName, 80) || 'live_stream',
      p_stream_session_id: streamSessionId || null,
      p_idempotency_key: idempotencyKey,
      p_metadata: {
        deviceId: cleanString(req.body?.deviceId, 120) || null,
        client: 'android',
      },
    });
    if (error) throw error;
    const result = Array.isArray(data) ? data[0] : data;
    return res.status(200).json({
      balance: Number(result?.balance || 0),
      used: Number(result?.used || 0),
      usageId: result?.usage_id || null,
    });
  } catch (error) {
    const message = String(error?.message || '');
    const status = message.toLowerCase().includes('insufficient') ? 402 : (error.status || 500);
    console.error('Consume API failed:', error);
    return res.status(status).json({ error: publicError(error) });
  }
}
