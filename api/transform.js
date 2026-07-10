import { db, admin } from './_shared/firebase.js';

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

function cleanText(value, fallback = '') {
  return String(value || fallback).trim();
}

export default async function handler(req, res) {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  try {
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
  } catch (error) {
    console.error('Error creating transform session:', error);
    return res.status(500).json({ error: error.message || 'Internal server error' });
  }
}
