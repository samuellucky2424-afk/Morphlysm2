import { db, admin } from './_shared/firebase.js';
import fetch from 'node-fetch';
import crypto from 'crypto';

function constantTimeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const aBuf = Buffer.from(a, 'utf8');
  const bBuf = Buffer.from(b, 'utf8');
  if (aBuf.length !== bBuf.length) {
    return false;
  }
  return crypto.timingSafeEqual(aBuf, bBuf);
}

export default async function handler(req, res) {
  // Handle CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, verif-hash, x-verif-hash');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  // 1. Verify Webhook Signature Hash
  const expectedHash = process.env.FLUTTERWAVE_WEBHOOK_HASH;
  const suppliedHash = req.headers['verif-hash'] || req.headers['x-verif-hash'];
  if (!expectedHash || !suppliedHash || !constantTimeEqual(suppliedHash, expectedHash)) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  try {
    const event = req.body;
    if (event?.event !== 'charge.completed' || event?.data?.status !== 'successful') {
      return res.status(200).json({ received: true });
    }

    const transactionId = String(event.data.id || '');
    const txRef = String(event.data.tx_ref || '');
    if (!transactionId || !txRef) {
      return res.status(400).json({ error: 'Webhook is missing payment identifiers' });
    }

    // 2. Query Flutterwave API directly to verify transaction details
    const flutterwaveSecret = process.env.FLUTTERWAVE_SECRET_KEY;
    if (!flutterwaveSecret) {
      throw new Error("Missing required secret: FLUTTERWAVE_SECRET_KEY");
    }

    const verificationResponse = await fetch(
      `https://api.flutterwave.com/v3/transactions/${encodeURIComponent(transactionId)}/verify`,
      { headers: { Authorization: `Bearer ${flutterwaveSecret}` } }
    );
    
    const verification = await verificationResponse.json();
    const verified = verification?.data;
    if (
      !verificationResponse.ok ||
      verification?.status !== 'success' ||
      verified?.status !== 'successful' ||
      String(verified?.tx_ref || '') !== txRef
    ) {
      return res.status(400).json({ error: 'Payment verification failed' });
    }

    // 3. Find transaction in Firestore
    const txQuerySnap = await db.collection('payment_transactions')
      .where('txRef', '==', txRef)
      .limit(1)
      .get();

    if (txQuerySnap.empty) {
      return res.status(400).json({ error: 'unknown payment reference' });
    }

    const txDocId = txQuerySnap.docs[0].id;
    const txDocRef = db.collection('payment_transactions').doc(txDocId);

    // 4. Update transaction status and user wallet atomically in a transaction
    await db.runTransaction(async (transaction) => {
      const txDocSnap = await transaction.get(txDocRef);
      if (!txDocSnap.exists) {
        throw new Error('unknown payment reference');
      }

      const txData = txDocSnap.data();
      if (txData.status === 'verified') {
        if (txData.providerTransactionId !== String(verified.id)) {
          throw new Error('payment reference was already verified by a different transaction');
        }
        return; // Idempotent success
      }

      if (txData.status !== 'pending') {
        throw new Error('payment is not pending');
      }

      if (String(verified.currency).toUpperCase() !== txData.currency.toUpperCase()) {
        throw new Error('currency mismatch');
      }

      if (Number(verified.amount) !== txData.expectedAmount) {
        throw new Error('amount mismatch');
      }

      // Update payment transaction document
      transaction.update(txDocRef, {
        status: 'verified',
        providerTransactionId: String(verified.id),
        verificationPayload: {
          provider: 'flutterwave',
          id: verified.id,
          txRef: verified.tx_ref,
          amount: verified.amount,
          currency: verified.currency,
          status: verified.status,
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
        currency: txData.currency,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      // Add to credit ledger
      const ledgerRef = db.collection('credit_ledger').doc();
      transaction.set(ledgerRef, {
        id: ledgerRef.id,
        userId: txData.userId,
        delta: txData.credits,
        balanceAfter: newBalance,
        reason: 'payment',
        paymentTransactionId: txDocId,
        streamSessionId: null,
        metadata: {
          provider: 'flutterwave',
          txRef: txRef
        },
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });
    });

    return res.status(200).json({ received: true });
  } catch (error) {
    console.error('Webhook processing failed:', error);
    return res.status(500).json({ error: error.message || 'Webhook processing failed' });
  }
}
