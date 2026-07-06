import { db, admin } from './_shared/firebase.js';

export default async function handler(req, res) {
  // Handle CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-admin-secret, Authorization');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  // 1. Verify admin secret key
  const adminSecret = process.env.ADMIN_SECRET || 'admin123';
  const incomingSecret = req.headers['x-admin-secret'] || req.query.admin_secret || req.body.admin_secret;

  if (!incomingSecret || incomingSecret !== adminSecret) {
    return res.status(403).json({ error: 'Access denied: invalid admin secret' });
  }

  // Parse path to route requests manually since we rewrote everything to this file
  const url = new URL(req.url, `http://${req.headers.host}`);
  const path = url.pathname.replace(/^\/api\/admin/, '');

  try {
    // ROUTES HANDLER
    if (path === '/overview') {
      return await handleOverview(req, res);
    } else if (path === '/packages') {
      if (req.method === 'POST') return await handleSavePackages(req, res);
      return await handleGetPackages(req, res);
    } else if (path === '/config') {
      if (req.method === 'POST') return await handleSaveConfig(req, res);
      return await handleGetConfig(req, res);
    } else if (path === '/wallet/add') {
      return await handleWalletAdd(req, res);
    } else if (path === '/users') {
      if (req.method === 'DELETE') {
        return await handleDeleteUser(req, res);
      }
      return await handleListUsers(req, res);
    } else if (path === '/users/lookup') {
      return await handleUserLookup(req, res);
    } else if (path === '/users/status') {
      return await handleUpdateUserStatus(req, res);
    } else if (path === '/users/restore') {
      return await handleUserRestore(req, res);
    } else if (path === '/key-logs') {
      if (req.method === 'DELETE') {
        return await handleDeleteKeyLog(req, res);
      }
      return await handleListKeyLogs(req, res);
    } else if (path === '/engine-key') {
      if (req.method === 'POST') {
        return await handleSaveEngineKey(req, res);
      }
      return await handleGetEngineKey(req, res);
    } else if (path === '/free-credits-settings') {
      if (req.method === 'POST') {
        return await handleSaveFreeCredits(req, res);
      }
      return await handleGetFreeCredits(req, res);
    } else if (path === '/streaming-availability') {
      if (req.method === 'POST') {
        return await handleSaveStreamingAvailability(req, res);
      }
      return await handleGetStreamingAvailability(req, res);
    } else if (path === '/streaming-monitor') {
      return await handleStreamingMonitor(req, res);
    } else {
      // Fallback/Stub other dashboard requests to prevent UI crashes
      return res.status(200).json({ success: true, message: 'Endpoint matched fallback stub' });
    }
  } catch (error) {
    console.error(`Admin API error on ${path}:`, error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}

// ── ROUTE HANDLERS ─────────────────────────────────

async function handleOverview(req, res) {
  const usersSnap = await db.collection('users').get();
  const totalUsers = usersSnap.size;

  const activeLicensesSnap = await db.collection('users').where('is_activated', '==', true).get();
  const activeLicenses = activeLicensesSnap.size;

  const walletsSnap = await db.collection('wallets').get();
  let creditsIssued = 0;
  walletsSnap.forEach(doc => {
    creditsIssued += doc.data().balance || 0;
  });

  return res.status(200).json({
    users: totalUsers,
    active_licenses: activeLicenses,
    credits_issued: creditsIssued,
    credits_used: Math.round(creditsIssued * 0.15) // mock/heuristic statistic
  });
}

async function handleListUsers(req, res) {
  const q = (req.query.q || '').toLowerCase();
  const usersRef = db.collection('users');
  const usersSnap = await usersRef.get();

  let users = [];
  usersSnap.forEach(doc => {
    const data = doc.data();
    const email = data.email || '';
    const name = data.displayName || '';
    const phone = data.phone || '';
    const deviceId = data.device_id || '';

    if (!q || email.toLowerCase().includes(q) || name.toLowerCase().includes(q) || phone.includes(q) || deviceId.toLowerCase().includes(q)) {
      users.push({
        id: doc.id,
        name: data.displayName || '—',
        email: data.email,
        phone: data.phone || '—',
        device_id: data.device_id || '—',
        license_status: data.is_activated ? 'active' : 'none',
        credits_remaining: 0, // will load from wallets
        created_at: data.createdAt ? data.createdAt.toDate().toISOString() : '—',
        last_login: data.updatedAt ? data.updatedAt.toDate().toISOString() : '—',
      });
    }
  });

  // Populate wallets in parallel
  await Promise.all(users.map(async (u) => {
    const walletDoc = await db.collection('wallets').doc(u.id).get();
    if (walletDoc.exists) {
      u.credits_remaining = walletDoc.data().balance || 0;
    }
  }));

  return res.status(200).json({ users });
}

async function handleDeleteUser(req, res) {
  const email = req.query.email;
  if (!email) {
    return res.status(400).json({ error: 'email is required' });
  }

  // Find user by email
  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) {
    return res.status(404).json({ error: 'User not found' });
  }

  const userDoc = userSnap.docs[0];
  const userId = userDoc.id;

  // Delete from Authentication if possible (Firebase Admin auth.deleteUser)
  try {
    await admin.auth().deleteUser(userId);
  } catch (err) {
    console.warn(`Auth user delete skipped or failed for UID: ${userId}`, err);
  }

  // Delete Firestore documents
  await db.collection('users').doc(userId).delete();
  await db.collection('wallets').doc(userId).delete();
  
  return res.status(200).json({ success: true, message: `User ${email} deleted successfully` });
}

async function handleUserLookup(req, res) {
  const email = req.query.email;
  if (!email) return res.status(400).json({ error: 'email is required' });

  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) {
    return res.status(200).json({ user_exists: false });
  }

  const doc = userSnap.docs[0];
  const data = doc.data();
  const userId = doc.id;

  const walletDoc = await db.collection('wallets').doc(userId).get();
  const walletData = walletDoc.exists ? walletDoc.data() : { balance: 0 };

  return res.status(200).json({
    user_exists: true,
    email: data.email,
    user: {
      device_id: data.device_id || '—',
      created_at: data.createdAt ? data.createdAt.toDate().toISOString() : new Date().toISOString(),
      phone: data.phone || '—',
      last_login: data.updatedAt ? data.updatedAt.toDate().toISOString() : '—'
    },
    credits: {
      total: walletData.balance || 0,
      used: 0,
      remaining: walletData.balance || 0
    },
    license: {
      status: data.is_activated ? 'active' : 'none',
      access_key: data.access_key || '—'
    }
  });
}

async function handleUserRestore(req, res) {
  const { email } = req.body;
  if (!email) return res.status(400).json({ error: 'email is required' });

  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) return res.status(404).json({ error: 'User not found' });

  const doc = userSnap.docs[0];
  await doc.ref.update({
    status: 'active',
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  });

  return res.status(200).json({ success: true, message: 'User account restored' });
}

async function handleListKeyLogs(req, res) {
  const keysSnap = await db.collection('key_logs').orderBy('activated_at', 'desc').get();
  let logs = [];
  keysSnap.forEach(doc => {
    const data = doc.data();
    logs.push({
      id: doc.id,
      access_key: data.access_key,
      email: data.email,
      device_id: data.device_id,
      activated_at: data.activated_at ? data.activated_at.toDate().toISOString() : '—',
      expires_at: data.expires_at ? data.expires_at.toDate().toISOString() : '—',
      status: data.status || 'ACTIVE'
    });
  });
  return res.status(200).json({ logs });
}

async function handleDeleteKeyLog(req, res) {
  const id = req.query.id;
  if (!id) return res.status(400).json({ error: 'id is required' });
  await db.collection('key_logs').doc(id).delete();
  return res.status(200).json({ success: true });
}

async function handleGetEngineKey(req, res) {
  const doc = await db.collection('settings').doc('engine_key').get();
  if (doc.exists) {
    return res.status(200).json(doc.data());
  }
  return res.status(200).json({ mode: 'single', single_key: '' });
}

async function handleSaveEngineKey(req, res) {
  const body = req.body;
  await db.collection('settings').doc('engine_key').set({
    ...body,
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  }, { merge: true });
  return res.status(200).json({ success: true });
}

async function handleGetFreeCredits(req, res) {
  const doc = await db.collection('settings').doc('free_credits').get();
  if (doc.exists) {
    return res.status(200).json(doc.data());
  }
  return res.status(200).json({ enabled: false, amount: 1500 });
}

async function handleSaveFreeCredits(req, res) {
  const { enabled, amount } = req.body;
  await db.collection('settings').doc('free_credits').set({
    enabled: !!enabled,
    amount: parseInt(amount, 10) || 1500,
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  });
  return res.status(200).json({ success: true });
}

async function handleGetStreamingAvailability(req, res) {
  const doc = await db.collection('settings').doc('streaming_availability').get();
  if (doc.exists) {
    return res.status(200).json(doc.data());
  }
  return res.status(200).json({ enabled: true });
}

async function handleSaveStreamingAvailability(req, res) {
  const { enabled } = req.body;
  await db.collection('settings').doc('streaming_availability').set({
    enabled: !!enabled,
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  });
  return res.status(200).json({ success: true });
}

async function handleStreamingMonitor(req, res) {
  // Return stub active sessions
  return res.status(200).json({
    active_sessions: [],
    total_streaming: 0,
    users_with_credits: 0,
    users_without_credits: 0,
    timestamp: new Date().toISOString()
  });
}

// --- NEW ROUTE HANDLERS ---

async function handleGetPackages(req, res) {
  const doc = await db.collection('settings').doc('packages').get();
  if (doc.exists && doc.data().packages) {
    return res.status(200).json({ packages: doc.data().packages });
  }
  // Defaults
  return res.status(200).json({ packages: [
    { id: 'basic', name: 'Basic', price: 29000, credits: 1000, timeLabel: '~8m 20s' },
    { id: 'pro', name: 'Pro', price: 58000, credits: 2000, timeLabel: '~16m 40s' },
    { id: 'enterprise', name: 'Enterprise', price: 145000, credits: 5000, timeLabel: '~41m 40s' },
    { id: 'vip', name: 'VIP plan', price: 290000, credits: 10000, timeLabel: '~83m 20s' }
  ]});
}

async function handleSavePackages(req, res) {
  const { packages } = req.body;
  if (!packages || !Array.isArray(packages)) return res.status(400).json({ error: 'packages array is required' });
  await db.collection('settings').doc('packages').set({
    packages,
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  });
  return res.status(200).json({ success: true });
}

async function handleGetConfig(req, res) {
  const doc = await db.collection('settings').doc('global_config').get();
  if (doc.exists) {
    return res.status(200).json(doc.data());
  }
  return res.status(200).json({ notification: '' });
}

async function handleSaveConfig(req, res) {
  const { notification } = req.body;
  await db.collection('settings').doc('global_config').set({
    notification: notification || '',
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  }, { merge: true });
  return res.status(200).json({ success: true });
}

async function handleWalletAdd(req, res) {
  const { email, amount } = req.body;
  if (!email || !amount) return res.status(400).json({ error: 'email and amount are required' });
  
  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) return res.status(404).json({ error: 'User not found' });
  
  const userId = userSnap.docs[0].id;
  const walletRef = db.collection('wallets').doc(userId);
  
  await db.runTransaction(async (t) => {
    const wDoc = await t.get(walletRef);
    if (!wDoc.exists) {
      t.set(walletRef, {
        user_id: userId,
        balance: parseInt(amount, 10),
        currency: 'USD',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } else {
      const currentBalance = wDoc.data().balance || 0;
      t.update(walletRef, {
        balance: currentBalance + parseInt(amount, 10),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    }
  });
  
  return res.status(200).json({ success: true, message: `Added ${amount} credits to ${email}` });
}

async function handleUpdateUserStatus(req, res) {
  const { email, status } = req.body;
  if (!email || !status) return res.status(400).json({ error: 'email and status are required' });
  
  const userSnap = await db.collection('users').where('email', '==', email).get();
  if (userSnap.empty) return res.status(404).json({ error: 'User not found' });
  
  const doc = userSnap.docs[0];
  await doc.ref.update({
    account_status: status,
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  });
  
  return res.status(200).json({ success: true, message: `User status updated to ${status}` });
}

