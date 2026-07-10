import { auth, db, admin } from './_shared/firebase.js';
import fetch from 'node-fetch';

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

  const { email, password } = req.body;
  if (!email || !password) {
    return res.status(400).json({ error: 'email and password are required' });
  }

  const firebaseApiKey = process.env.FIREBASE_API_KEY;
  if (!firebaseApiKey) {
    return res.status(500).json({
      error: 'Backend configuration missing. Please configure FIREBASE_API_KEY in your Vercel settings.'
    });
  }

  try {
    // 1. Authenticate with Firebase Auth REST API
    const authUrl = `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${firebaseApiKey}`;
    const authRes = await fetch(authUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        email,
        password,
        returnSecureToken: true
      })
    });

    const authData = await authRes.json();
    if (!authRes.ok) {
      const errMsg = authData.error?.message || 'Invalid email or password';
      let formattedMsg = errMsg;
      if (errMsg === 'EMAIL_NOT_FOUND' || errMsg === 'INVALID_PASSWORD') {
        formattedMsg = 'Invalid email or password';
      } else if (errMsg === 'USER_DISABLED') {
        formattedMsg = 'Your account has been disabled. Please contact support.';
      }
      return res.status(authRes.status).json({ error: formattedMsg });
    }

    const userId = authData.localId;
    const idToken = authData.idToken;

    // 2. Fetch custom user state from Firestore
    const userRef = db.collection('users').doc(userId);
    const userDocSnap = await userRef.get();

    let userData = {
      email: email,
      phone: authData.phoneNumber || null,
      role: 'user',
      status: 'active',
      displayName: authData.displayName || email.split('@')[0],
    };

    if (userDocSnap.exists) {
      userData = userDocSnap.data();
      // Retroactively generate a referralCode if one doesn't exist
      if (!userData.referralCode) {
        userData.referralCode = 'REF-' + Math.random().toString(36).substring(2, 8).toUpperCase();
        await userRef.update({ referralCode: userData.referralCode });
      }
    } else {
      // Create user document if it doesn't exist (e.g. signed up via console)
      userData.referralCode = 'REF-' + Math.random().toString(36).substring(2, 8).toUpperCase();
      await userRef.set({
        ...userData,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }

    // Check if user is suspended
    if (userData.status === 'suspended') {
      return res.status(403).json({ error: 'Your account has been suspended. Please contact support.' });
    }

    // 3. Fetch wallet balance from Firestore
    const walletRef = db.collection('wallets').doc(userId);
    const walletDocSnap = await walletRef.get();

    let walletData = {
      balance: 0,
      currency: 'NGN',
    };

    if (walletDocSnap.exists) {
      walletData = walletDocSnap.data();
    } else {
      // Create wallet if it doesn't exist
      await walletRef.set({
        user_id: userId,
        balance: 0,
        currency: 'NGN',
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }

    return res.status(200).json({
      token: idToken,
      user: {
        id: userId,
        email: userData.email || email,
        name: userData.displayName || authData.displayName || email.split('@')[0],
        role: userData.role || 'user',
        status: userData.status || 'active',
        balance: walletData.balance || 0,
        referralCode: userData.referralCode || '',
        is_activated: userData.is_activated === true || userData.role === 'admin'
      }
    });
  } catch (error) {
    console.error('Error in login API:', error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}
