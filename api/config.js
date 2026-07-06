import { db } from './_shared/firebase.js';

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  if (req.method === 'OPTIONS') return res.status(200).end();

  try {
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
