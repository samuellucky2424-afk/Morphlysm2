import { db, admin } from './_shared/firebase.js';

function generateRandomHex(length) {
  const chars = '0123456789ABCDEF';
  let res = '';
  for (let i = 0; i < length; i++) {
    res += chars[Math.floor(Math.random() * chars.length)];
  }
  return res;
}

export default async function handler(req, res) {
  // CORS Headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-admin-secret');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  // Verify Admin Secret
  const adminSecret = process.env.ADMIN_SECRET || 'admin123';
  const incomingSecret = req.headers['x-admin-secret'] || req.body.admin_secret;

  if (!incomingSecret || incomingSecret !== adminSecret) {
    return res.status(403).json({ error: 'Access denied: invalid admin secret' });
  }

  const { user_email, device_id, user_name, notes } = req.body;
  if (!user_email || !device_id) {
    return res.status(400).json({ error: 'user_email and device_id are required' });
  }

  const email = user_email.trim().toLowerCase();
  const deviceId = device_id.trim().toUpperCase();
  const name = user_name ? user_name.trim() : '';

  try {
    const userQuery = await db.collection('users').where('email', '==', email).get();
    let userId;
    const accessKey = `MP-${generateRandomHex(4)}-${generateRandomHex(4)}-${generateRandomHex(4)}-${generateRandomHex(4)}`;
    const expiresAt = new Date();
    expiresAt.setFullYear(expiresAt.getFullYear() + 1);

    if (userQuery.empty) {
      // Create user document placeholder
      const newDoc = db.collection('users').doc();
      userId = newDoc.id;
      await newDoc.set({
        email,
        displayName: name || email.split('@')[0],
        device_id: deviceId,
        is_activated: true,
        access_key: accessKey,
        notes: notes || '',
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      // Initialize wallet for new user placeholder
      await db.collection('wallets').doc(userId).set({
        user_id: userId,
        balance: 0,
        currency: 'USD',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } else {
      const doc = userQuery.docs[0];
      userId = doc.id;
      await doc.ref.update({
        device_id: deviceId,
        is_activated: true,
        access_key: accessKey,
        notes: notes || '',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    }

    // Write to key_logs
    await db.collection('key_logs').add({
      user_id: userId,
      email,
      device_id: deviceId,
      access_key: accessKey,
      activated_at: admin.firestore.FieldValue.serverTimestamp(),
      expires_at: admin.firestore.Timestamp.fromDate(expiresAt),
      status: 'ACTIVE'
    });

    return res.status(200).json({
      key: accessKey,
      expires_at: expiresAt.toISOString()
    });

  } catch (error) {
    console.error('Error activating access key:', error);
    return res.status(500).json({ error: error.message || 'Failed to activate access key' });
  }
}
