import { db } from './_shared/firebase.js';

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

function pickKey(config, email) {
  const envKey = process.env.DECART_API_KEY || process.env.LIVE_ENGINE_API_KEY || '';
  if (!config) return envKey;

  if (config.mode === 'multi' && Array.isArray(config.keys) && config.keys.length > 0) {
    const keys = config.keys.map((key) => String(key || '').trim()).filter(Boolean);
    if (keys.length === 0) return envKey;
    const seed = String(email || '').split('').reduce((sum, char) => sum + char.charCodeAt(0), 0);
    return keys[seed % keys.length];
  }

  return String(config.single_key || envKey).trim();
}

export default async function handler(req, res) {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  try {
    const { email, deviceId, model } = req.body || {};
    if (!email || !deviceId) {
      return res.status(400).json({ error: 'email and deviceId are required' });
    }

    const availabilityDoc = await db.collection('settings').doc('streaming_availability').get();
    if (availabilityDoc.exists && availabilityDoc.data()?.enabled === false) {
      return res.status(503).json({ error: 'Live streaming is currently disabled.' });
    }

    const keyDoc = await db.collection('settings').doc('engine_key').get();
    const apiKey = pickKey(keyDoc.exists ? keyDoc.data() : null, email);
    if (!apiKey) {
      return res.status(503).json({ error: 'Live engine key is not configured.' });
    }

    return res.status(200).json({
      apiKey,
      model: model || 'lucy-2.1',
      expiresIn: 900,
      maxSessionDuration: 900
    });
  } catch (error) {
    console.error('Error creating Decart token:', error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}
