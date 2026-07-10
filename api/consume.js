import { db, admin } from './_shared/firebase.js';

export default async function handler(req, res) {
  // Handle CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const { email, amount } = req.body;
  if (!email || amount === undefined) {
    return res.status(400).json({ error: 'email and amount are required' });
  }

  const changeAmount = parseInt(amount, 10);
  if (isNaN(changeAmount)) {
    return res.status(400).json({ error: 'amount must be a valid integer' });
  }

  try {
    // 1. Look up user by email in Firestore
    const usersSnap = await db.collection('users').where('email', '==', email).limit(1).get();
    if (usersSnap.empty) {
      return res.status(404).json({ error: 'User not found' });
    }

    const userId = usersSnap.docs[0].id;
    const walletRef = db.collection('wallets').doc(userId);

    let transactionResult;
    try {
      transactionResult = await db.runTransaction(async (transaction) => {
        const walletDoc = await transaction.get(walletRef);
        let currentBalance = 0;
        let currency = 'NGN';

        if (walletDoc.exists) {
          const data = walletDoc.data();
          currentBalance = data.balance || 0;
          currency = data.currency || 'NGN';
        } else {
          // If no wallet exists, initialize it in transaction
          transaction.set(walletRef, {
            user_id: userId,
            balance: 0,
            currency: 'NGN',
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          });
        }

        const newBalance = currentBalance - changeAmount; // positive changeAmount deducts, negative changeAmount adds
        if (newBalance < 0) {
          throw new Error('Insufficient credits');
        }

        transaction.update(walletRef, {
          balance: newBalance,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        return { currentBalance, newBalance };
      });
    } catch (txError) {
      if (txError.message === 'Insufficient credits') {
        // Log a warning in applogs collection
        await db.collection('applogs').add({
          userId: userId,
          logLevel: 'warn',
          message: `Deduction of ${changeAmount} credits rejected: Insufficient balance`,
          createdAt: admin.firestore.FieldValue.serverTimestamp()
        });
        return res.status(400).json({ error: 'Insufficient credits' });
      }
      throw txError;
    }

    const { newBalance } = transactionResult;

    // 4. Log the usage in usage collection
    await db.collection('usage').add({
      userId: userId,
      featureName: changeAmount >= 0 ? 'live_stream' : 'admin_sync',
      creditsUsed: changeAmount,
      status: 'success',
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 5. Query total usages to return 'used' summary
    const usageSnap = await db.collection('usage')
      .where('userId', '==', userId)
      .where('status', '==', 'success')
      .get();

    let totalUsed = 0;
    usageSnap.forEach((doc) => {
      totalUsed += (doc.data().creditsUsed || 0);
    });

    return res.status(200).json({ balance: newBalance, used: totalUsed });
  } catch (error) {
    console.error('Error in consume API:', error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}
