export default function handler(req, res) {
  res.status(200).json({
    hasServiceAccount: !!process.env.FIREBASE_SERVICE_ACCOUNT_KEY,
    hasPrivateKey: !!process.env.FIREBASE_PRIVATE_KEY,
    hasProjectId: !!process.env.FIREBASE_PROJECT_ID,
    hasClientEmail: !!process.env.FIREBASE_CLIENT_EMAIL,
    hasApiKey: !!process.env.FIREBASE_API_KEY,
    privateKeyLength: process.env.FIREBASE_PRIVATE_KEY ? process.env.FIREBASE_PRIVATE_KEY.length : 0,
    serviceAccountLength: process.env.FIREBASE_SERVICE_ACCOUNT_KEY ? process.env.FIREBASE_SERVICE_ACCOUNT_KEY.length : 0
  });
}
