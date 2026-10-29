import {
  formatAccount,
  getAccountSummary,
  requireUser,
} from '../server/shared/firebase.js';
import {
  applyHttpHeaders,
  handleOptions,
  publicError,
  requireMethod,
} from '../server/shared/http.js';

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, OPTIONS');
  if (handleOptions(req, res) || !requireMethod(req, res, 'GET')) return;

  try {
    const { service, appUser } = await requireUser(req);
    const summary = await getAccountSummary(service, appUser.id);
    const user = formatAccount(summary);
    // Current plan = package name of the latest verified purchase (no composite index needed).
    const paid = await service.db.collection('payment_transaction_sm').where('user_id', '==', appUser.id).get();
    const latest = paid.docs.map(d => d.data()).filter(p => p.status === 'verified')
      .sort((a, b) => String(b.verified_at || b.created_at).localeCompare(String(a.verified_at || a.created_at)))[0];
    let planName = null;
    if (latest?.package_id) {
      const pack = await service.db.collection('credit_package_sm').doc(String(latest.package_id)).get();
      planName = pack.data()?.name || latest.package_id;
    }
    user.currentPlan = planName;
    user.currentPlanId = latest?.package_id || null;
    user.currentPlanPurchasedAt = latest?.verified_at || null;
    return res.status(200).json({
      balance: user.balance,
      used: user.creditsUsed,
      user,
      license: {
        status: user.is_activated ? 'active' : 'inactive',
        expiresAt: user.licenseExpiresAt || '-',
      },
    });
  } catch (error) {
    console.error('Balance API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
