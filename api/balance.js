import { db } from './_shared/firebase.js';

export default async function handler(req, res) {
  // Handle CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ error: 'Email parameter is required' });
  }

  try {
    // 1. Look up user by email in Firestore
    const usersSnap = await db.collection('users').where('email', '==', email).limit(1).get();
    if (usersSnap.empty) {
      // User doesn't exist yet, return zero balance and usage
      return res.status(200).json({ balance: 0, used: 0 });
    }

    const userDoc = usersSnap.docs[0];
    const userId = userDoc.id;
    const userData = userDoc.data();

    // 2. Fetch wallet balance from Firestore
    const walletDoc = await db.collection('wallets').doc(userId).get();
    const balance = walletDoc.exists ? (walletDoc.data().balance || 0) : 0;

    // 3. Sum total credits consumed from usage collection where status is success
    const usageSnap = await db.collection('usage')
      .where('userId', '==', userId)
      .where('status', '==', 'success')
      .get();

    let used = 0;
    usageSnap.forEach((doc) => {
      used += (doc.data().creditsUsed || 0);
    });

    const toIso = (value) => {
      if (!value) return null;
      if (typeof value.toDate === 'function') return value.toDate().toISOString();
      return value;
    };

    const licenseActive = userData.is_activated === true || userData.role === 'admin';

    return res.status(200).json({
      balance,
      used,
      user: {
        id: userId,
        email: userData.email || email,
        name: userData.displayName || userData.name || (userData.email || email).split('@')[0],
        phone: userData.phone || '',
        deviceId: userData.device_id || '',
        referralCode: userData.referralCode || '',
        createdAt: toIso(userData.createdAt)
      },
      license: {
        status: licenseActive ? 'active' : 'inactive',
        expiresAt: toIso(userData.expires_at || userData.license_expires_at || userData.expiresAt) || '-'
      }
    });
  } catch (error) {
    console.error('Error in balance API:', error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}
