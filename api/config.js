import { db, admin } from './_shared/firebase.js';

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
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

function cleanText(value, fallback = '') {
  return String(value || fallback).trim();
}

async function handleDecartToken(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

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
}

async function handleTransform(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  const body = req.body || {};
  const email = cleanText(body.email);
  const deviceId = cleanText(body.deviceId);
  if (!email || !deviceId) {
    return res.status(400).json({ error: 'email and deviceId are required' });
  }

  const mode = cleanText(body.mode, 'style');
  const prompt = cleanText(body.prompt, 'Transform the live camera into a clean high-definition cinematic style.');
  const model = cleanText(body.model, 'lucy-2.1');
  const quality = cleanText(body.quality, 'medium');
  const fps = Number.parseInt(body.fps, 10) || 20;
  const sessionId = `morphly_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

  await db.collection('stream_sessions').doc(sessionId).set({
    sessionId,
    email,
    deviceId,
    mode,
    prompt,
    preset: cleanText(body.preset || body.presetLabel),
    enhance: !!body.enhance,
    quality,
    fps,
    model,
    status: 'starting',
    hasFaceImage: !!body.faceImage,
    faceImageMimeType: cleanText(body.faceImageMimeType),
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  });

  return res.status(200).json({
    success: true,
    sessionId,
    status: 'starting',
    mode,
    prompt,
    enhance: !!body.enhance,
    quality,
    fps,
    model
  });
}

export default async function handler(req, res) {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(200).end();

  try {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const route = url.searchParams.get('route') || url.pathname.replace(/^\/api\//, '');
    if (route === 'decart-token') {
      return await handleDecartToken(req, res);
    }
    if (route === 'transform') {
      return await handleTransform(req, res);
    }
    if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

    const doc = await db.collection('settings').doc('global_config').get();
    let config = { notification: '' };
    if (doc.exists) {
      config = { ...config, ...doc.data() };
    }
    return res.status(200).json(config);
  } catch (error) {
    console.error('Error fetching config:', error);
    return res.status(500).json({ error: 'Internal server error' });
  }
}
