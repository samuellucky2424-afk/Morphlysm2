import { auth, db, admin } from './_shared/firebase.js';

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

  const { name, email, phone, password, referredByCode } = req.body;
  if (!email || !password || !name) {
    return res.status(400).json({ error: 'name, email, and password are required' });
  }

  try {
    // 1. Create user in Firebase Authentication
    const userRecord = await auth.createUser({
      email,
      password,
      displayName: name,
      phoneNumber: phone || undefined,
    });

    const userId = userRecord.uid;

    // Generate unique referral code
    const referralCode = 'REF-' + Math.random().toString(36).substring(2, 8).toUpperCase();

    // 2. Initialize user document in Firestore 'users' collection
    await db.collection('users').doc(userId).set({
      email: email,
      phone: phone || null,
      role: 'user',
      status: 'active',
      displayName: name,
      referralCode: referralCode,
      referredByCode: referredByCode || null,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    // 3. Process referral if provided
    let initialBalance = 0;
    if (referredByCode) {
      const settingsDoc = await db.collection('settings').doc('referral_program').get();
      const settings = settingsDoc.exists ? settingsDoc.data() : { enabled: false, rewardAmount: 100 };
      
      if (settings.enabled) {
        const referrerSnap = await db.collection('users').where('referralCode', '==', referredByCode).limit(1).get();
        if (!referrerSnap.empty) {
          const referrerId = referrerSnap.docs[0].id;
          const rewardAmount = parseInt(settings.rewardAmount, 10) || 100;
          
          initialBalance = rewardAmount;
          
          // Add reward to referrer
          const referrerWalletRef = db.collection('wallets').doc(referrerId);
          await db.runTransaction(async (t) => {
            const wDoc = await t.get(referrerWalletRef);
            if (wDoc.exists) {
              const currentBalance = wDoc.data().balance || 0;
              t.update(referrerWalletRef, {
                balance: currentBalance + rewardAmount,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
              });
            }
          });
        }
      }
    }

    // 4. Initialize wallet document in Firestore 'wallets' collection
    await db.collection('wallets').doc(userId).set({
      user_id: userId,
      balance: initialBalance,
      currency: 'USD',
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return res.status(200).json({
      message: 'Registration successful!',
      user: {
        id: userId,
        email: userRecord.email,
        displayName: name,
        referralCode: referralCode,
        balance: initialBalance
      }
    });
  } catch (error) {
    console.error('Error in signup API:', error);
    // Map Firebase errors to user-friendly messages
    let errMsg = error.message || 'Failed to sign up user';
    if (error.code === 'auth/email-already-exists') {
      errMsg = 'The email address is already in use by another account.';
    }
    return res.status(400).json({ error: errMsg });
  }
}
