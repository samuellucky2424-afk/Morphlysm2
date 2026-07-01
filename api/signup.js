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

  const { name, email, phone, password } = req.body;
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

    // 2. Initialize user document in Firestore 'users' collection
    await db.collection('users').doc(userId).set({
      email: email,
      phone: phone || null,
      role: 'user',
      status: 'active',
      displayName: name,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    // 3. Initialize wallet document in Firestore 'wallets' collection
    await db.collection('wallets').doc(userId).set({
      user_id: userId,
      balance: 0,
      currency: 'USD',
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return res.status(200).json({
      message: 'Registration successful!',
      user: {
        id: userId,
        email: userRecord.email,
        displayName: name,
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
