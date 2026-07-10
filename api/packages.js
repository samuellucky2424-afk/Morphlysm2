import { db } from './_shared/firebase.js';

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  if (req.method === 'OPTIONS') return res.status(200).end();

  try {
    const doc = await db.collection('settings').doc('packages').get();
    let packages = [];
    if (doc.exists && doc.data().packages) {
      packages = doc.data().packages;
    } else {
      // Default packages fallback
      packages = [
        { id: 'basic', name: 'Basic', price: 29000, credits: 1000, currency: 'NGN', timeLabel: '~8m 20s' },
        { id: 'pro', name: 'Pro', price: 58000, credits: 2000, currency: 'NGN', timeLabel: '~16m 40s' },
        { id: 'enterprise', name: 'Enterprise', price: 145000, credits: 5000, currency: 'NGN', timeLabel: '~41m 40s' },
        { id: 'vip', name: 'VIP plan', price: 290000, credits: 10000, currency: 'NGN', timeLabel: '~83m 20s' }
      ];
    }
    return res.status(200).json({ packages });
  } catch (error) {
    console.error('Error fetching packages:', error);
    return res.status(500).json({ error: 'Internal server error' });
  }
}
