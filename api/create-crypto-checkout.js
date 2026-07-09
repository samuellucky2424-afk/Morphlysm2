import { auth, db, admin } from './_shared/firebase.js';
import fetch from 'node-fetch';
import crypto from 'crypto';

function getPackages() {
  const raw = process.env.CREDIT_PACKAGES_JSON;
  if (!raw) throw new Error("Missing required configuration: CREDIT_PACKAGES_JSON");
  const value = JSON.parse(raw);
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error("CREDIT_PACKAGES_JSON must contain at least one package");
  }
  return value;
}

function safeRedirectUrl(value) {
  const fallback = "morphly://payment-return";
  if (typeof value !== "string" || value.length === 0) return fallback;
  try {
    const url = new URL(value);
    if (url.protocol === "morphly:" && url.hostname === "payment-return") {
      return url.toString();
    }
    const allowed = (process.env.ALLOWED_PAYMENT_REDIRECT_ORIGINS || "")
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean);
    if (url.protocol === "https:" && allowed.includes(url.origin)) {
      return url.toString();
    }
  } catch (e) {
    // Ignore invalid url parse
  }
  return fallback;
}

export default async function handler(req, res) {
  // Handle CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  try {
    // 1. Verify User Session Token
    const bearerHeader = req.headers.authorization;
    if (!bearerHeader || !bearerHeader.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Unauthorized: missing authorization token' });
    }
    const idToken = bearerHeader.split('Bearer ')[1];
    let decodedToken;
    try {
      decodedToken = await auth.verifyIdToken(idToken);
    } catch (authErr) {
      return res.status(401).json({ error: 'Unauthorized: invalid token' });
    }

    const userId = decodedToken.uid;
    const userEmail = decodedToken.email;
    if (!userEmail) {
      return res.status(400).json({ error: "An email address is required for checkout" });
    }

    // 2. Parse Package Details
    const { packageId, redirectUrl } = req.body;
    const creditPackage = getPackages().find((item) => item.id === packageId);
    if (!creditPackage) {
      return res.status(400).json({ error: "Unknown credit package" });
    }

    // 3. Generate Reference & Create Pending Transaction record
    const uuidStr = crypto.randomUUID().replace(/-/g, '');
    const txRef = `mph_cr_${userId.substring(0, 12)}_${uuidStr}`; // "cr" for crypto

    const transactionRef = db.collection('payment_transactions').doc();
    const transactionId = transactionRef.id;

    await transactionRef.set({
      id: transactionId,
      userId: userId,
      txRef: txRef,
      credits: creditPackage.credits,
      expectedAmount: creditPackage.amount,
      currency: creditPackage.currency,
      status: 'pending',
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      verifiedAt: null,
      providerTransactionId: null,
      verificationPayload: null
    });

    // 4. Connect to NOWPayments checkout API
    const nowpaymentsApiKey = process.env.NOWPAYMENTS_API_KEY;
    if (!nowpaymentsApiKey) {
      throw new Error("Missing required secret: NOWPAYMENTS_API_KEY");
    }

    // Determine the base url to send to NOWPayments for the IPN webhook
    // Vercel populates 'x-forwarded-host', fallback to 'host'
    const host = req.headers['x-forwarded-host'] || req.headers.host;
    const protocol = req.headers['x-forwarded-proto'] || 'https';
    let ipnCallbackUrl = "";
    if (process.env.PUBLIC_APP_URL) {
       ipnCallbackUrl = `${process.env.PUBLIC_APP_URL}/api/nowpayments-webhook`;
    } else if (host) {
       ipnCallbackUrl = `${protocol}://${host}/api/nowpayments-webhook`;
    }

    const checkoutResponse = await fetch("https://api.nowpayments.io/v1/invoice", {
      method: "POST",
      headers: {
        "x-api-key": nowpaymentsApiKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        price_amount: creditPackage.amount,
        price_currency: creditPackage.currency.toLowerCase(),
        order_id: transactionId,
        order_description: creditPackage.title,
        ipn_callback_url: ipnCallbackUrl,
        success_url: safeRedirectUrl(redirectUrl),
        cancel_url: safeRedirectUrl(redirectUrl),
      }),
    });

    const checkoutData = await checkoutResponse.json();
    if (!checkoutResponse.ok || !checkoutData.invoice_url) {
      await transactionRef.update({
        status: 'failed',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      throw new Error(checkoutData.message || "NOWPayments could not create a checkout link");
    }

    return res.status(200).json({ txRef, checkoutUrl: checkoutData.invoice_url });
  } catch (error) {
    console.error('Error in create-crypto-checkout API:', error);
    return res.status(400).json({ error: error.message || "Unable to start crypto checkout" });
  }
}
