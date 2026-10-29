// Flutterwave payments for the Android app.
//   POST /api/create-checkout            {packageId, redirectUrl?} -> {txRef, checkoutUrl}
//   GET|POST /api/payments/verify        {txRef | transaction_id}   -> {status, wallet_balance}
import crypto from 'crypto';
import {requireUser} from '../server/shared/firebase.js';
import {applyHttpHeaders, cleanString, handleOptions, publicError, requireMethod, safeRedirectUrl} from '../server/shared/http.js';
import {createHostedCheckout, verifyAndFulfill} from '../server/shared/flutterwave.js';

function route(req) {
  const url = new URL(req.url, `https://${req.headers.host || 'localhost'}`);
  return url.searchParams.get('route') || 'create';
}

async function create(req, res) {
  if (!requireMethod(req, res, 'POST')) return;
  const context = await requireUser(req);
  let transaction;
  try {
    const packageId = cleanString(req.body?.packageId, 64).toLowerCase();
    if (!packageId) return res.status(400).json({error: 'packageId is required'});
    // tx_ref ties the payment to the user and package; the random suffix keeps every attempt unique.
    const txRef = `mph_${context.appUser.id.slice(0, 12)}_${packageId.slice(0, 16)}_${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`;
    const {data, error} = await context.client.rpc('begin_payment_sm', {p_package_code: packageId, p_provider: 'flutterwave', p_tx_ref: txRef});
    if (error) throw error;
    transaction = Array.isArray(data) ? data[0] : data;
    const host = req.headers['x-forwarded-host'] || req.headers.host;
    const redirectUrl = req.body?.redirectUrl ? safeRedirectUrl(req.body.redirectUrl) : `https://${host}/payment-complete.html`;
    const checkoutUrl = await createHostedCheckout({txRef, amount: Number(transaction.expected_amount), currency: transaction.currency,
      email: context.appUser.email, name: context.appUser.display_name, credits: transaction.credits, redirectUrl,
      meta: {payment_id: transaction.id, user_id: context.appUser.id, credit_package: packageId}});
    await context.service.db.collection('payment_transaction_sm').doc(transaction.id)
      .update({checkout_link_created: true, updated_at: new Date().toISOString()});
    return res.status(200).json({txRef, checkoutUrl, credits: transaction.credits, amount: transaction.expected_amount, currency: transaction.currency});
  } catch (error) {
    if (transaction?.id) await context.service.db.collection('payment_transaction_sm').doc(transaction.id)
      .update({status: 'failed', failure_reason: publicError(error, 'Provider error')}).catch(() => {});
    throw error;
  }
}

async function verify(req, res) {
  if (!requireMethod(req, res, 'GET', 'POST')) return;
  const context = await requireUser(req);
  const input = {...(req.query || {}), ...(req.body || {})};
  const txRef = cleanString(input.txRef || input.tx_ref, 120);
  const transactionId = cleanString(input.transactionId || input.transaction_id, 40);
  if (!txRef) return res.status(400).json({error: 'txRef is required'});
  const rows = await context.service.db.collection('payment_transaction_sm').where('tx_ref', '==', txRef).limit(1).get();
  const payment = rows.docs[0]?.data();
  if (!payment || payment.user_id !== context.appUser.id) return res.status(404).json({error: 'Payment not found'});
  if (payment.status === 'verified') return res.status(200).json({status: 'successful', verified: true, was_already_fulfilled: true});
  const result = await verifyAndFulfill({txRef, transactionId: transactionId || undefined});
  return res.status(200).json(result);
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, POST, OPTIONS');
  if (handleOptions(req, res)) return;
  try {
    return route(req) === 'verify' ? await verify(req, res) : await create(req, res);
  } catch (error) {
    console.error('Flutterwave payment route failed:', error);
    return res.status(error.status || 400).json({error: publicError(error, 'Payment request failed')});
  }
}
