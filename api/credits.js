import { db, admin } from './_shared/firebase.js';

export default async function handler(req, res) {
  // CORS Headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-admin-secret');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const url = new URL(req.url, `http://${req.headers.host}`);
  const path = url.pathname.replace(/^\/api\/credits/, '');

  try {
    if (req.method === 'POST' && path === '/add') {
      return await handleAddCredits(req, res);
    } else if (req.method === 'GET' && path.startsWith('/email/')) {
      return await handleGetBalanceByEmail(req, res, path);
    } else {
      return res.status(404).json({ error: 'Endpoint not found' });
    }
  } catch (error) {
    console.error('Credits API error:', error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}

async function handleAddCredits(req, res) {
  // Verify Admin Secret
  const adminSecret = process.env.ADMIN_SECRET || 'admin123';
  const incomingSecret = req.headers['x-admin-secret'] || req.body.admin_secret;

  if (!incomingSecret || incomingSecret !== adminSecret) {
    return res.status(403).json({ error: 'Access denied: invalid admin secret' });
  }

  const { user_email, credits, package_id, reference } = req.body;
  if (!user_email || credits === undefined) {
    return res.status(400).json({ error: 'user_email and credits are required' });
  }

  const email = user_email.trim().toLowerCase();
  const creditsAmount = parseInt(credits, 10);

  if (isNaN(creditsAmount)) {
    return res.status(400).json({ error: 'credits must be a valid integer' });
  }

  // Find user doc by email
  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) {
    return res.status(404).json({ error: 'User account not found' });
  }

  const userDoc = userSnap.docs[0];
  const userId = userDoc.id;

  // Perform firestore transaction to update wallet atomic balance
  const walletRef = db.collection('wallets').doc(userId);
  let newBalance = 0;

  await db.runTransaction(async (transaction) => {
    const walletDoc = await transaction.get(walletRef);
    let currentBalance = 0;
    if (walletDoc.exists) {
      currentBalance = walletDoc.data().balance || 0;
    }
    newBalance = currentBalance + creditsAmount;
    if (newBalance < 0) newBalance = 0; // prevent negative balance

    transaction.set(walletRef, {
      user_id: userId,
      balance: newBalance,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    // Optional: Log credits transaction in applogs or usage collection
    const logRef = db.collection('usage').doc();
    transaction.set(logRef, {
      userId,
      email,
      action: creditsAmount >= 0 ? 'admin_add_credits' : 'admin_debit_credits',
      amount: Math.abs(creditsAmount),
      package_id: package_id || null,
      reference: reference || null,
      newBalance,
      timestamp: admin.firestore.FieldValue.serverTimestamp()
    });
  });

  return res.status(200).json({
    email,
    new_total: newBalance,
    remaining: newBalance
  });
}

async function handleGetBalanceByEmail(req, res, path) {
  const emailEscaped = path.replace(/^\/email\//, '');
  const email = decodeURIComponent(emailEscaped).trim().toLowerCase();

  if (!email) {
    return res.status(400).json({ error: 'Email parameter is missing' });
  }

  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) {
    return res.status(404).json({ error: 'User not found' });
  }

  const userDoc = userSnap.docs[0];
  const userId = userDoc.id;
  const userData = userDoc.data();

  const walletDoc = await db.collection('wallets').doc(userId).get();
  const balance = walletDoc.exists ? walletDoc.data().balance || 0 : 0;

  return res.status(200).json({
    email,
    plan: userData.role === 'admin' ? 'Admin Plan' : (userData.is_activated ? 'Pro Activation' : 'Free Tier'),
    total: balance,
    used: 0,
    remaining: balance
  });
}
