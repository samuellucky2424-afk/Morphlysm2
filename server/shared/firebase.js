import {firebaseRuntime, firebaseAuthRequest} from './firebase-runtime.js';
import {FirebaseQuery} from './firebase-query.js';
import {createOperations} from './firebase-operations.js';

const wrap = fn => async (...args) => {
  try {return {data: await fn(...args), error: null};}
  catch (error) {return {data: null, error};}
};
let defaultsPromise;
export async function ensureFirebaseDefaults() {
  if (!defaultsPromise) defaultsPromise = (async () => {
    const {db} = firebaseRuntime();
    const settings = [
      ['global_config', {notification: ''}, true],
      ['streaming_availability', {enabled: true}, true],
      ['referral_program', {enabled: true, signup_credits: 50, purchase_reward: 250}, true],
    ];
    // Create missing Android settings only. Existing settings and desktop collections are untouched.
    for (const [key, value, is_public] of settings) await db.runTransaction(async tx => {
      const reference = db.collection('app_setting_sm').doc(key), old = await tx.get(reference);
      if (!old.exists) tx.create(reference, {id: key, key, value, is_public});
    });
    const fallback = [['basic','Basic',1000,29000,'~8m 20s'], ['pro','Pro',2000,58000,'~16m 40s'],
      ['enterprise','Enterprise',5000,145000,'~41m 40s'], ['vip','VIP plan',10000,290000,'~83m 20s']];
    const existing = await db.collection('credit_package_sm').limit(1).get();
    if (existing.empty) for (const [i, [code,name,credits,price,time_label]] of fallback.entries()) {
      await db.runTransaction(async tx => {
        const reference = db.collection('credit_package_sm').doc(code), old = await tx.get(reference);
        if (!old.exists) tx.create(reference, {id: code, code, name, credits, price, currency: 'NGN', time_label,
          sort_order: (i + 1) * 10, is_active: true});
      });
    }
  })().catch(error => {defaultsPromise = null; throw error;});
  return defaultsPromise;
}
export function bearerToken(req) {
  return /^Bearer\s+(.+)$/i.exec(String(req.headers?.authorization || ''))?.[1]?.trim() || '';
}
async function firebaseUser(token) {
  const {auth} = firebaseRuntime();
  const claims = await auth.verifyIdToken(token, true);
  const record = await auth.getUser(claims.uid);
  return {id: record.uid, uid: record.uid, email: record.email, displayName: record.displayName,
    user_metadata: {name: record.displayName || ''}};
}
export function createServiceClient(actor = null) {
  const {db, auth} = firebaseRuntime(), operations = createOperations(db);
  class AndroidQuery extends FirebaseQuery {
    async matching() {
      if (this.table === 'account_summary_sm') {
        const rows = await db.collection('user_sm').get();
        return Promise.all(rows.docs.map(async row => {
          const summary = await operations.summary(row.id);
          return {id: row.id, ref: row.ref, data: () => summary};
        }));
      }
      return super.matching();
    }
  }
  return {
    provider: 'firebase', db,
    from: table => new AndroidQuery(db, table),
    rpc: wrap((name, p) => operations.rpc(name, p, actor)),
    auth: {
      getUser: wrap(async token => ({user: await firebaseUser(token)})),
      admin: {createUser: wrap(async input => {
        await ensureFirebaseDefaults();
        const record = await auth.createUser({email: input.email, password: input.password,
          displayName: input.user_metadata?.name || ''});
        await operations.provision(record, {...input.user_metadata, referredByCode: input.user_metadata?.referredByCode});
        return {user: {id: record.uid, email: record.email}};
      })},
    },
  };
}
export function createPublicClient() {
  async function session(operation, body) {
    const response = await firebaseAuthRequest(operation, body);
    const user = await firebaseUser(response.idToken);
    await ensureFirebaseDefaults();
    await createOperations(firebaseRuntime().db).provision(user);
    return {user, session: {access_token: response.idToken, refresh_token: response.refreshToken,
      expires_in: Number(response.expiresIn || 3600), expires_at: Math.floor(Date.now() / 1000) + Number(response.expiresIn || 3600)}};
  }
  return {auth: {
    signInWithPassword: wrap(input => session('signInWithPassword', {...input, returnSecureToken: true})),
    refreshSession: wrap(input => session('refresh', {refreshToken: input.refresh_token})),
    signOut: async () => ({error: null}),
    resetPasswordForEmail: wrap(async email => {
      await firebaseAuthRequest('sendOobCode', {requestType: 'PASSWORD_RESET', email});
      return {};
    }),
  }};
}
export async function requireUser(req) {
  const token = bearerToken(req);
  if (!token) throw Object.assign(new Error('Authentication required'), {status: 401});
  let authUser;
  try {authUser = await firebaseUser(token);}
  catch {throw Object.assign(new Error('Session is invalid or expired'), {status: 401});}
  const summary = await createOperations(firebaseRuntime().db).summary(authUser.id);
  if (summary.status !== 'active') throw Object.assign(new Error('Account is not active'), {status: 403});
  const appUser = {id: authUser.id, ...summary};
  const client = createServiceClient(appUser);
  return {token, authUser, appUser, client, service: client};
}
export async function requireAdmin(req) {
  const context = await requireUser(req);
  if (!['admin', 'moderator'].includes(context.appUser.role)) throw Object.assign(new Error('Administrator access required'), {status: 403});
  return context;
}
export async function getAccountSummary(client, uid) {
  return createOperations(client.db).summary(uid);
}
export function formatAccount(summary) {
  return {id: summary.user_id, email: summary.email, name: summary.display_name || summary.email?.split('@')[0] || 'User',
    phone: summary.phone || '', role: summary.role || 'user', status: summary.status || 'active',
    balance: Number(summary.balance || 0), creditsUsed: Number(summary.credits_used || 0), referralCode: summary.referral_code || '',
    deviceId: summary.device_id || '', is_activated: summary.is_activated === true || summary.role === 'admin',
    licenseExpiresAt: summary.license_expires_at || null, createdAt: summary.created_at || null};
}
export function throwIfError(error) {if (error) throw error;}
