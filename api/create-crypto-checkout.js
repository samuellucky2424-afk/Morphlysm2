import { auth, db, admin } from './_shared/firebase.js';
import fetch from 'node-fetch';
import crypto from 'crypto';

async function getPackages() {
  const doc = await db.collection('settings').doc('packages').get();
  if (doc.exists && doc.data().packages) {
    return doc.data().packages;
  }
  // Default packages fallback
  return [
    { id: 'basic', name: 'Basic', price: 29000, credits: 1000, currency: 'NGN', timeLabel: '~8m 20s' },
    { id: 'pro', name: 'Pro', price: 58000, credits: 2000, currency: 'NGN', timeLabel: '~16m 40s' },
    { id: 'enterprise', name: 'Enterprise', price: 145000, credits: 5000, currency: 'NGN', timeLabel: '~41m 40s' },
    { id: 'vip', name: 'VIP plan', price: 290000, credits: 10000, currency: 'NGN', timeLabel: '~83m 20s' }
  ];
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

function sanitizeProviderError(message, fallback) {
  const text = String(message || "");
  if (
    text.includes("PRIVATE KEY") ||
    text.includes("BEGIN ") ||
    text.includes("Bearer ") ||
    text.toLowerCase().includes("authorization") ||
    text.toLowerCase().includes("x-api-key")
  ) {
    return fallback;
  }
  return text || fallback;
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
    const packages = await getPackages();
    const creditPackage = packages.find((item) => 
      item.id === packageId || 
      (item.name && item.name.toLowerCase() === packageId.toLowerCase()) ||
      (item.title && item.title.toLowerCase() === packageId.toLowerCase())
    );
    if (!creditPackage) {
      return res.status(400).json({ error: "Unknown credit package" });
    }

    // Map the generic package fields for the transaction
    const expectedAmount = creditPackage.price || creditPackage.amount;
    const currency = creditPackage.currency || "NGN";

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
      expectedAmount: expectedAmount,
      currency: currency,
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
    if (nowpaymentsApiKey.includes("PRIVATE KEY") || nowpaymentsApiKey.includes("BEGIN ")) {
      throw new Error("NOWPayments configuration is invalid. Check NOWPAYMENTS_API_KEY.");
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

    const payload = {
      price_amount: expectedAmount,
      price_currency: currency,
      pay_currency: 'USDTTRC20',
      order_id: transactionId,
      order_description: `Purchase ${creditPackage.credits} Morphly Credits`,
      ipn_callback_url: ipnCallbackUrl,
    };

    const checkoutResponse = await fetch("https://api.nowpayments.io/v1/payment", {
      method: "POST",
      headers: {
        "x-api-key": nowpaymentsApiKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const checkoutData = await checkoutResponse.json();
    if (!checkoutResponse.ok || !checkoutData.pay_address) {
      await transactionRef.update({
        status: 'failed',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      throw new Error(
        sanitizeProviderError(
          checkoutData.message,
          "NOWPayments configuration is invalid. Please contact support."
        )
      );
    }

    await transactionRef.update({
      providerTransactionId: checkoutData.payment_id ? String(checkoutData.payment_id) : null,
      providerPayload: {
        provider: 'nowpayments',
        paymentId: checkoutData.payment_id || null,
        payCurrency: checkoutData.pay_currency || payload.pay_currency,
        payAmount: checkoutData.pay_amount || null,
        payAddress: checkoutData.pay_address || null,
      },
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return res.status(200).json({
      txRef,
      cryptoPayment: {
        paymentId: checkoutData.payment_id || null,
        paymentStatus: checkoutData.payment_status || 'waiting',
        payAddress: checkoutData.pay_address,
        payAmount: checkoutData.pay_amount,
        payCurrency: checkoutData.pay_currency || payload.pay_currency,
        priceAmount: checkoutData.price_amount || expectedAmount,
        priceCurrency: checkoutData.price_currency || currency,
        orderId: transactionId,
        credits: creditPackage.credits,
        validUntil: checkoutData.expiration_estimate_date || null
      }
    });
  } catch (error) {
    const publicMessage = sanitizeProviderError(
      error.message,
      "NOWPayments configuration is invalid. Please contact support."
    );
    console.error('Error in create-crypto-checkout API:', publicMessage);
    return res.status(400).json({ error: publicMessage || "Unable to start crypto checkout" });
  }
}
