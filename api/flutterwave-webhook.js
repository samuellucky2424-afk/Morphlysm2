// Flutterwave webhook: checks the verif-hash header against FLW_SECRET_HASH, re-verifies the
// transaction with the Flutterwave API, then credits the wallet exactly once (idempotent by transaction id).
import {publicError, requireMethod} from '../server/shared/http.js';
import {constantTimeEqual, flwSecretHash, verifyAndFulfill} from '../server/shared/flutterwave.js';

// Optional relay: when FLW_WEBHOOK_FORWARD_URL is set, every webhook is also posted (body + verif-hash) to that
// URL so another system can share the single Flutterwave webhook slot. Never blocks or fails our own handling.
function forwardWebhook(req) {
  const url = String(process.env.FLW_WEBHOOK_FORWARD_URL || '').trim();
  if (!/^https:\/\//i.test(url)) return Promise.resolve();
  const body = typeof req.body === 'string' ? req.body : JSON.stringify(req.body ?? {});
  const headers = {'Content-Type': 'application/json'};
  const hash = req.headers['verif-hash'] || req.headers['x-verif-hash'];
  if (hash) headers['verif-hash'] = String(hash);
  return fetch(url, {method: 'POST', headers, body, signal: AbortSignal.timeout(4000)})
    .catch(error => console.error('Webhook forward failed:', error?.message));
}
// Waits at most `ms` for the relay so the serverless instance is not frozen mid-request.
const settleForward = (p, ms = 4500) => Promise.race([p, new Promise(r => setTimeout(r, ms))]);

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (!requireMethod(req, res, 'POST')) return;
  const forwarded = forwardWebhook(req);
  try { return await handle(req, res); } finally { await settleForward(forwarded); }
}

async function handle(req, res) {
  const header = req.headers['verif-hash'] || req.headers['x-verif-hash'];
  if (!constantTimeEqual(String(header || ''), flwSecretHash())) return res.status(401).json({error: 'Unauthorized'});
  try {
    const event = typeof req.body === 'string' ? JSON.parse(req.body) : (req.body || {});
    const type = event.event || event.type || event['event.type'];
    const data = event.data || event;
    if (!['charge.completed', 'CARD_TRANSACTION', 'BANK_TRANSFER_TRANSACTION'].includes(type) || !data.tx_ref && !data.txRef) {
      return res.status(200).json({received: true, ignored: true});
    }
    const txRef = String(data.tx_ref || data.txRef), transactionId = data.id ? String(data.id) : undefined;
    const result = await verifyAndFulfill({txRef, transactionId});
    return res.status(200).json({received: true, ...result});
  } catch (error) {
    console.error('Flutterwave webhook failed:', error);
    return res.status(error.status === 400 ? 200 : 500).json({error: publicError(error, 'Webhook processing failed')});
  }
}
