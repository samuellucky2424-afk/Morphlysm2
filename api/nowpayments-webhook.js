import { db, admin } from './_shared/firebase.js';
import crypto from 'crypto';

// Utility to sort object keys recursively as per NOWPayments documentation
function sortObject(obj) {
  return Object.keys(obj).sort().reduce(
    (result, key) => {
      result[key] = (obj[key] && typeof obj[key] === 'object') ? sortObject(obj[key]) : obj[key];
      return result;
    },
    {}
  );
}

export default async function handler(req, res) {
  // Handle CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-nowpayments-sig');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const nowpaymentsIpnSecret = process.env.NOWPAYMENTS_IPN_SECRET_KEY;
  if (!nowpaymentsIpnSecret) {
    console.error("Missing NOWPAYMENTS_IPN_SECRET_KEY");
    return res.status(500).json({ error: "Server configuration error" });
  }

  // 1. Verify NOWPayments IPN Signature
  const receivedSig = req.headers['x-nowpayments-sig'];
  if (!receivedSig) {
    return res.status(401).json({ error: 'Missing x-nowpayments-sig header' });
  }

  try {
    const params = req.body;
    const sortedParams = JSON.stringify(sortObject(params));
    const hmac = crypto.createHmac('sha512', nowpaymentsIpnSecret);
    hmac.update(sortedParams);
    const calculatedSig = hmac.digest('hex');

    if (calculatedSig !== receivedSig) {
      return res.status(401).json({ error: 'HMAC signature does not match' });
    }

    // 2. Process the payment
    const paymentStatus = params.payment_status;
    const orderId = String(params.order_id || '');
    
    // We only want to fulfill if it's finished (or partially_paid if you want to allow it, but let's stick to finished)
    if (paymentStatus !== 'finished') {
      // Just acknowledge receipt for other statuses
      return res.status(200).json({ received: true, status: paymentStatus });
    }

    if (!orderId) {
      return res.status(400).json({ error: 'Webhook is missing order_id' });
    }

    // 3. Find transaction in Firestore. New invoices use the Firestore doc id
    // as order_id; older invoices used txRef, so keep that lookup too.
    let txDocRef = db.collection('payment_transactions').doc(orderId);
    const directTxDoc = await txDocRef.get();
    if (!directTxDoc.exists) {
      const txQuerySnap = await db.collection('payment_transactions')
        .where('txRef', '==', orderId)
        .limit(1)
        .get();

      if (txQuerySnap.empty) {
        return res.status(400).json({ error: 'unknown payment reference (order_id not found)' });
      }
      txDocRef = txQuerySnap.docs[0].ref;
    }
    
    // 4. Update transaction status and user wallet atomically in a transaction
    await db.runTransaction(async (transaction) => {
      const txDocSnap = await transaction.get(txDocRef);
      if (!txDocSnap.exists) {
        throw new Error('unknown payment reference (order_id not found)');
      }

      const txData = txDocSnap.data();
      if (txData.status === 'verified') {
        // Idempotent success - already verified
        return; 
      }

      if (txData.status !== 'pending') {
        throw new Error('payment is not pending');
      }

      // Note: we can optionally verify the received amount/currency against txData
      // but NOWPayments will only send 'finished' if the expected amount was fully paid.

      // Update payment transaction document
      transaction.update(txDocRef, {
        status: 'verified',
        providerTransactionId: String(params.payment_id),
        verificationPayload: {
          provider: 'nowpayments',
          id: params.payment_id,
          amount: params.price_amount,
          currency: params.price_currency,
          status: params.payment_status,
        },
        verifiedAt: admin.firestore.FieldValue.serverTimestamp()
      });

      // Update user wallet document
      const walletRef = db.collection('wallets').doc(txData.userId);
      const walletDocSnap = await transaction.get(walletRef);
      
      let currentBalance = 0;
      if (walletDocSnap.exists) {
        currentBalance = walletDocSnap.data().balance || 0;
      }

      const newBalance = currentBalance + txData.credits;
      transaction.set(walletRef, {
        user_id: txData.userId,
        balance: newBalance,
        currency: txData.currency, // e.g., USD
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      // Add to credit ledger
      const ledgerRef = db.collection('credit_ledger').doc();
      transaction.set(ledgerRef, {
        id: ledgerRef.id,
        userId: txData.userId,
        delta: txData.credits,
        balanceAfter: newBalance,
        reason: 'crypto_payment',
        paymentTransactionId: txDocRef.id,
        streamSessionId: null,
        metadata: {
          provider: 'nowpayments',
          payment_id: params.payment_id,
          order_id: orderId,
          txRef: txData.txRef || null
        },
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });
    });

    return res.status(200).json({ received: true });
  } catch (error) {
    console.error('NOWPayments Webhook processing failed:', error);
    return res.status(500).json({ error: error.message || 'Webhook processing failed' });
  }
}
