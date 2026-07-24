import crypto from 'crypto';
import { requireUser } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  publicError,
  requireMethod,
  safeRedirectUrl,
} from './_shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'POST, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'POST')) return;

  let context;
  let transaction;
  try {
    context = await requireUser(req);
    const packageId = cleanString(req.body?.packageId, 64).toLowerCase();
    if (!packageId) return res.status(400).json({ error: 'packageId is required' });

    const txRef = `mph_${context.appUser.id.slice(0, 12)}_${crypto.randomUUID().replaceAll('-', '')}`;
    const { data, error } = await context.client.rpc('begin_payment_sm', {
      p_package_code: packageId,
      p_provider: 'flutterwave',
      p_tx_ref: txRef,
    });
    if (error) throw error;
    transaction = Array.isArray(data) ? data[0] : data;

    const secret = String(process.env.FLUTTERWAVE_SECRET_KEY || '').trim();
    if (!secret.startsWith('FLWSECK')) {
      throw new Error('Flutterwave payment configuration is invalid.');
    }

    const providerResponse = await fetch('https://api.flutterwave.com/v3/payments', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${secret}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        tx_ref: txRef,
        amount: Number(transaction.expected_amount),
        currency: transaction.currency,
        redirect_url: safeRedirectUrl(req.body?.redirectUrl),
        customer: { email: context.appUser.email },
        customizations: {
          title: 'Morphly credits',
          description: `${transaction.credits} Morphly credits`,
        },
        meta: {
          payment_id: transaction.id,
          user_id: context.appUser.id,
          credit_package: packageId,
        },
      }),
    });
    const providerData = await providerResponse.json().catch(() => ({}));
    if (!providerResponse.ok || providerData.status !== 'success' || !providerData.data?.link) {
      throw new Error(providerData.message || 'Unable to start Flutterwave checkout');
    }

    await context.service
      .from('payment_transaction_sm')
      .update({ provider_payload: { checkout_link_created: true } })
      .eq('id', transaction.id);

    return res.status(200).json({ txRef, checkoutUrl: providerData.data.link });
  } catch (error) {
    if (context?.service && transaction?.id) {
      await context.service
        .from('payment_transaction_sm')
        .update({ status: 'failed', failure_reason: publicError(error, 'Provider error') })
        .eq('id', transaction.id)
        .eq('status', 'pending');
    }
    console.error('Flutterwave checkout failed:', error);
    return res.status(error.status || 400).json({
      error: publicError(error, 'Unable to start checkout'),
    });
  }
}
