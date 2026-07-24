import crypto from 'crypto';
import { requireUser } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

function callbackUrl() {
  const configured = String(process.env.PUBLIC_APP_URL || '').replace(/\/+$/, '');
  try {
    const url = new URL(configured);
    if (url.protocol !== 'https:') throw new Error();
    return `${url.origin}/api/nowpayments-webhook`;
  } catch {
    throw new Error('PUBLIC_APP_URL must be a valid HTTPS origin.');
  }
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  let context;
  let transaction;
  try {
    context = await requireUser(req);
    const packageId = cleanString(req.body?.packageId, 64).toLowerCase();
    if (!packageId) return res.status(400).json({ error: 'packageId is required' });

    const txRef = `mph_cr_${context.appUser.id.slice(0, 12)}_${crypto.randomUUID().replaceAll('-', '')}`;
    const { data, error } = await context.client.rpc('begin_payment_sm', {
      p_package_code: packageId,
      p_provider: 'nowpayments',
      p_tx_ref: txRef,
    });
    if (error) throw error;
    transaction = Array.isArray(data) ? data[0] : data;

    const apiKey = String(process.env.NOWPAYMENTS_API_KEY || '').trim();
    if (!apiKey) throw new Error('NOWPayments payment configuration is invalid.');

    const payload = {
      price_amount: Number(transaction.expected_amount),
      price_currency: String(transaction.currency || '').toLowerCase(),
      pay_currency: (
        cleanString(process.env.NOWPAYMENTS_PAY_CURRENCY, 20) || 'usdttrc20'
      ).toLowerCase(),
      order_id: txRef,
      order_description: `Purchase ${transaction.credits} Morphly credits`,
      ipn_callback_url: callbackUrl(),
    };
    const providerResponse = await fetch('https://api.nowpayments.io/v1/payment', {
      method: 'POST',
      headers: { 'x-api-key': apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const providerData = await providerResponse.json().catch(() => ({}));
    if (!providerResponse.ok || !providerData.pay_address) {
      throw new Error(providerData.message || 'Unable to start crypto checkout');
    }

    const providerId = providerData.payment_id ? String(providerData.payment_id) : null;
    const providerPayload = {
      payment_id: providerId,
      pay_currency: providerData.pay_currency || payload.pay_currency,
      pay_amount: providerData.pay_amount || null,
      pay_address: providerData.pay_address,
    };
    const { error: updateError } = await context.service
      .from('payment_transaction_sm')
      .update({
        provider_transaction_id: providerId,
        provider_payload: providerPayload,
      })
      .eq('id', transaction.id);
    // The signed IPN can still fulfill this pending transaction by txRef if
    // this optional metadata write fails after NOWPayments created the charge.
    // Never mark a payable provider charge as failed solely for this write.
    if (updateError) {
      console.error('Could not save NOWPayments checkout metadata:', updateError);
    }

    return res.status(200).json({
      txRef,
      cryptoPayment: {
        paymentId: providerId,
        paymentStatus: providerData.payment_status || 'waiting',
        payAddress: providerData.pay_address,
        payAmount: providerData.pay_amount,
        payCurrency: providerData.pay_currency || payload.pay_currency,
        priceAmount: providerData.price_amount || Number(transaction.expected_amount),
        priceCurrency: providerData.price_currency || transaction.currency,
        orderId: txRef,
        credits: transaction.credits,
        validUntil: providerData.expiration_estimate_date || null,
      },
    });
  } catch (error) {
    if (context?.service && transaction?.id) {
      await context.service
        .from('payment_transaction_sm')
        .update({ status: 'failed', failure_reason: publicError(error, 'Provider error') })
        .eq('id', transaction.id)
        .eq('status', 'pending');
    }
    console.error('NOWPayments checkout failed:', error);
    return res.status(error.status || 400).json({
      error: publicError(error, 'Unable to start crypto checkout'),
    });
  }
}
