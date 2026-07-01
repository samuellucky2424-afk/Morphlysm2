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
    const txRef = `mph_${userId.substring(0, 12)}_${uuidStr}`;

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

    // 4. Connect to Flutterwave checkout API
    const flutterwaveSecret = process.env.FLUTTERWAVE_SECRET_KEY;
    if (!flutterwaveSecret) {
      throw new Error("Missing required secret: FLUTTERWAVE_SECRET_KEY");
    }

    const checkoutResponse = await fetch("https://api.flutterwave.com/v3/payments", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${flutterwaveSecret}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        tx_ref: txRef,
        amount: creditPackage.amount,
        currency: creditPackage.currency,
        redirect_url: safeRedirectUrl(redirectUrl),
        customer: { email: userEmail },
        customizations: {
          title: "Morphly credits",
          description: creditPackage.title,
        },
        meta: {
          payment_id: transactionId,
          user_id: userId,
          credit_package: creditPackage.id,
        },
      }),
    });

    const checkoutData = await checkoutResponse.json();
    if (!checkoutResponse.ok || checkoutData.status !== "success" || !checkoutData.data?.link) {
      await transactionRef.update({
        status: 'failed',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      throw new Error(checkoutData.message || "Flutterwave could not create a checkout link");
    }

    return res.status(200).json({ txRef, checkoutUrl: checkoutData.data.link });
  } catch (error) {
    console.error('Error in create-checkout API:', error);
    return res.status(400).json({ error: error.message || "Unable to start checkout" });
  }
}
