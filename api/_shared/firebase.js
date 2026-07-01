import admin from 'firebase-admin';

let db = null;
let auth = null;

const hasCredentials = process.env.FIREBASE_SERVICE_ACCOUNT_KEY || 
  (process.env.FIREBASE_PROJECT_ID && process.env.FIREBASE_CLIENT_EMAIL && process.env.FIREBASE_PRIVATE_KEY);

if (hasCredentials) {
  if (!admin.apps.length) {
    try {
      let config = {};
      if (process.env.FIREBASE_SERVICE_ACCOUNT_KEY) {
        config = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_KEY);
      } else {
        config = {
          projectId: process.env.FIREBASE_PROJECT_ID,
          clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
          // Replace literal escaped newlines with actual newline characters
          privateKey: process.env.FIREBASE_PRIVATE_KEY 
            ? process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, '\n') 
            : undefined,
        };
      }

      admin.initializeApp({
        credential: admin.credential.cert(config)
      });
    } catch (err) {
      console.error('Firebase Admin initialization error:', err);
    }
  }
} else {
  console.warn('Firebase credentials not set in environment. Running in mock/verification mode.');
}

if (admin.apps.length) {
  db = admin.firestore();
  auth = admin.auth();
} else {
  // Return dummy proxies to prevent crashes on import when referencing db or auth methods
  const createMock = (name) => new Proxy({}, {
    get: (target, prop) => {
      return () => {
        throw new Error(`Firebase not initialized. Cannot call ${name}.${String(prop)} without credentials.`);
      };
    }
  });
  db = createMock('firestore');
  auth = createMock('auth');
}

export { admin, db, auth };
export default admin;
