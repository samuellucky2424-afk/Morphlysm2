// Flutterwave webhook: checks the verif-hash header against FLW_SECRET_HASH, re-verifies the
// transaction with the Flutterwave API, then credits the wallet exactly once (idempotent by transaction id).
import {publicError, requireMethod} from '../server/shared/http.js';
import {constantTimeEqual, flwSecretHash, verifyAndFulfill} from '../server/shared/flutterwave.js';

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (!requireMethod(req, res, 'POST')) return;
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
