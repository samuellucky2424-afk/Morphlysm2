#!/usr/bin/env node
// Grants or revokes the Firebase custom claim admin=true for one email on nova-8fb74.
// Uses FIREBASE_SERVICE_ACCOUNT_JSON (or the split Firebase admin env vars). Never prints credentials.
// This does not edit ADMIN_EMAILS. That allowlist is a Vercel environment variable.
import {firebaseRuntime} from '../server/shared/firebase-runtime.js';

const args = process.argv.slice(2);
const email = String(args.find(arg => !arg.startsWith('--')) || '').trim().toLowerCase();
const revoke = args.includes('--revoke');
const verifyEmail = args.includes('--verify-email');
const help = args.includes('--help') || args.includes('-h');

if (help || !email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
  console.error('Usage: node scripts/grant-admin.mjs <email> [--verify-email | --revoke]');
  console.error('Sets admin=true on the Firebase Auth user in project nova-8fb74.');
  console.error('Then set ADMIN_EMAILS on Vercel to the same email and redeploy.');
  process.exit(help ? 0 : 1);
}
if (revoke && verifyEmail) {
  console.error('Choose either --revoke or --verify-email.');
  process.exit(1);
}

const {auth} = firebaseRuntime();
let user;
try {
  user = await auth.getUserByEmail(email);
} catch (error) {
  if (error?.code === 'auth/user-not-found') {
    console.error(`No Firebase Auth user for ${email} in project nova-8fb74.`);
    process.exit(2);
  }
  console.error(error?.message || 'Could not read that user.');
  process.exit(1);
}

if (verifyEmail && !user.emailVerified) await auth.updateUser(user.uid, {emailVerified: true});
const claims = {...(user.customClaims || {})};
if (revoke) delete claims.admin;
else claims.admin = true;
await auth.setCustomUserClaims(user.uid, claims);
if (revoke) await auth.revokeRefreshTokens(user.uid);

console.log(`${revoke ? 'Removed' : 'Set'} admin claim for ${email} (uid ${user.uid}).`);
if (!revoke && !(verifyEmail || user.emailVerified)) {
  console.log('Email is not verified. Admin sign-in will fail until it is.');
  console.log('Re-run with --verify-email only after you have confirmed this person owns the address.');
}
if (!revoke) {
  console.log('Next: set ADMIN_EMAILS on the Vercel project morphlysm2 to include this email, redeploy, then sign in at /admin/login.');
  console.log('Sign in again so Firebase issues a token that includes the new claim.');
}
