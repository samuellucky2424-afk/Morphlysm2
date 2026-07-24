// Supabase-authenticated Morphly administration dashboard.
const API_URL = window.location.origin + '/api';
let ADMIN_SECRET = sessionStorage.getItem('morphly_admin_access_token') || '';
let cachedCreditPackages = [];
let usersPage = 1;
let _currentKeyMode = 'single';
sessionStorage.removeItem('ss_admin_secret');
localStorage.removeItem('ss_admin_secret');

let adminRefreshToken = sessionStorage.getItem('morphly_admin_refresh_token') || '';
let freeCreditsEnabled = false;
let streamingAvailabilityEnabled = true;
let creatorRows = [];
let toastTimer;

const fmtDate = (value) => value
  ? new Date(value).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
  : '—';
const fmtCred = (value) => Number.isFinite(Number(value)) ? Number(value).toLocaleString() : '—';
const esc = (value) => String(value ?? '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;');

function toast(message, type = '') {
  const element = document.getElementById('toast');
  element.textContent = message;
  element.className = `toast ${type} show`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { element.className = `toast ${type}`; }, 4000);
}

function formatNgn(amount) {
  return new Intl.NumberFormat('en-NG', {
    style: 'currency',
    currency: 'NGN',
    maximumFractionDigits: 0,
  }).format(Number(amount || 0));
}

function renderCreditPlanOptions(packages) {
  const select = document.getElementById('creditPlan');
  const previous = select.value;
  select.innerHTML = '<option value="">Select package</option>';
  for (const pkg of packages || []) {
    const option = document.createElement('option');
    option.value = pkg.id;
    option.dataset.credits = String(pkg.credits || 0);
    option.textContent = `${pkg.name} — ${fmtCred(pkg.credits)} credits (${formatNgn(pkg.price)})`;
    select.appendChild(option);
  }
  select.insertAdjacentHTML('beforeend', '<option value="custom">Custom amount...</option>');
  if ([...select.options].some((option) => option.value === previous)) select.value = previous;
}

window.quickCredit = function quickCreditSecure(email) {
  showTab('credits');
  document.getElementById('creditEmail').value = email;
};

async function adminRequest(path, options = {}, canRefresh = true) {
  const headers = new Headers(options.headers || {});
  if (ADMIN_SECRET) headers.set('Authorization', `Bearer ${ADMIN_SECRET}`);
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${API_URL}${path}`, { ...options, headers });
  if (response.status === 401 && canRefresh && adminRefreshToken) {
    const refreshResponse = await fetch(`${API_URL}/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: adminRefreshToken }),
    });
    if (refreshResponse.ok) {
      const refreshed = await refreshResponse.json();
      ADMIN_SECRET = refreshed.token;
      adminRefreshToken = refreshed.refreshToken;
      sessionStorage.setItem('morphly_admin_access_token', ADMIN_SECRET);
      sessionStorage.setItem('morphly_admin_refresh_token', adminRefreshToken);
      return adminRequest(path, options, false);
    }
    doLogout();
  }
  return response;
}

async function responseJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}

window.doAdminLogin = async function doAdminLoginSecure() {
  const email = document.getElementById('adminEmail').value.trim().toLowerCase();
  const password = document.getElementById('adminPass').value;
  const errorElement = document.getElementById('loginErr');
  if (!email || !password) {
    errorElement.textContent = 'Admin email and password are required';
    return;
  }
  errorElement.textContent = 'Signing in...';
  try {
    const response = await fetch(`${API_URL}/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    const data = await responseJson(response);
    ADMIN_SECRET = data.token;
    adminRefreshToken = data.refreshToken || '';
    const check = await adminRequest('/admin/overview');
    await responseJson(check);
    sessionStorage.setItem('morphly_admin_access_token', ADMIN_SECRET);
    sessionStorage.setItem('morphly_admin_refresh_token', adminRefreshToken);
    document.getElementById('loginSection').style.display = 'none';
    document.getElementById('adminApp').style.display = 'flex';
    errorElement.textContent = '';
    showTab('plans');
    initAdmin();
  } catch (error) {
    ADMIN_SECRET = '';
    adminRefreshToken = '';
    errorElement.textContent = error.message;
  }
};

window.doLogout = function doAdminLogout() {
  ADMIN_SECRET = '';
  adminRefreshToken = '';
  sessionStorage.removeItem('morphly_admin_access_token');
  sessionStorage.removeItem('morphly_admin_refresh_token');
  document.getElementById('adminApp').style.display = 'none';
  document.getElementById('loginSection').style.display = 'flex';
  document.getElementById('adminPass').value = '';
  document.getElementById('loginErr').textContent = '';
};

window.restoreAdminSessionIfLoggedIn = async function restoreAdminSession() {
  if (!ADMIN_SECRET) {
    document.getElementById('loginSection').style.display = 'flex';
    document.getElementById('adminApp').style.display = 'none';
    return;
  }
  try {
    await responseJson(await adminRequest('/admin/overview'));
    document.getElementById('loginSection').style.display = 'none';
    document.getElementById('adminApp').style.display = 'flex';
    showTab('plans');
    initAdmin();
  } catch {
    doLogout();
  }
};

window.initAdmin = function initAdminSecure() {
  loadStats();
  loadPackages();
  refreshEngineKeyStatus();
};

window.loadStats = async function loadStatsSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/overview'));
    document.getElementById('statUsers').textContent = data.users || 0;
    document.getElementById('statLic').textContent = data.active_licenses || 0;
    document.getElementById('statIssued').textContent = fmtCred(Number(data.credits_issued || 0));
    document.getElementById('statUsed').textContent = fmtCred(Number(data.credits_used || 0));
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.addCredits = async function addCreditsSecure() {
  const email = document.getElementById('creditEmail').value.trim().toLowerCase();
  const select = document.getElementById('creditPlan');
  const plan = select.value;
  const reference = document.getElementById('creditRef').value.trim();
  const errorElement = document.getElementById('creditErr');
  let credits = Number.parseInt(select.selectedOptions[0]?.dataset.credits || plan, 10);
  if (plan === 'custom') {
    credits = Number.parseInt(document.getElementById('customCredits').value, 10);
    if (document.querySelector('input[name="creditAction"]:checked')?.value === 'debit') credits *= -1;
  }
  if (!email || !Number.isSafeInteger(credits) || credits === 0) {
    errorElement.textContent = 'Email and a non-zero credit amount are required.';
    return;
  }
  try {
    const data = await responseJson(await adminRequest('/credits/add', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        user_email: email,
        credits,
        package_id: plan === 'custom' ? null : plan,
        reference,
      }),
    }));
    errorElement.textContent = '';
    toast(`Credits adjusted. New balance: ${fmtCred(Number(data.new_total))}`, 'ok');
  } catch (error) {
    errorElement.textContent = error.message;
  }
};

window.checkBalance = async function checkBalanceSecure() {
  const email = document.getElementById('balEmail').value.trim().toLowerCase();
  if (!email) return;
  try {
    const data = await responseJson(
      await adminRequest(`/credits/email/${encodeURIComponent(email)}`),
    );
    document.getElementById('balPlan').textContent = data.plan || '—';
    document.getElementById('balTotal').textContent = fmtCred(Number(data.total || 0));
    document.getElementById('balUsed').textContent = fmtCred(Number(data.used || 0));
    document.getElementById('balRemain').textContent = fmtCred(Number(data.remaining || 0));
    document.getElementById('balResult').style.display = 'block';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadUsers = async function loadUsersSecure(page = 1) {
  if (page < 1) return;
  usersPage = page;
  const body = document.getElementById('usersBody');
  body.innerHTML = '<tr><td colspan="9" class="empty">Loading...</td></tr>';
  try {
    const search = document.getElementById('userSearch').value.trim();
    const data = await responseJson(
      await adminRequest(`/admin/users?page=${page}&q=${encodeURIComponent(search)}`),
    );
    document.getElementById('usersCountLabel').textContent = `(${data.total || 0})`;
    body.innerHTML = data.users?.length ? data.users.map((user) => `
      <tr>
        <td class="td-name">${esc(user.name || '—')}</td>
        <td>${esc(user.email)}</td><td>${esc(user.phone || '—')}</td>
        <td>${esc(user.device_id || '—')}</td>
        <td><span class="td-badge badge-active">${esc(user.license_status || 'none')} / ${esc(user.account_status)}</span></td>
        <td>${fmtCred(Number(user.credits_remaining || 0))}</td>
        <td>${fmtDate(user.created_at)}</td><td>${fmtDate(user.last_login)}</td>
        <td><div class="td-action">
          <button class="td-btn" onclick="quickCredit('${esc(user.email)}')">CREDITS</button>
          <button class="td-btn" onclick="updateUserStatus('${esc(user.email)}','suspended')">SUSPEND</button>
          <button class="td-btn" onclick="updateUserStatus('${esc(user.email)}','banned')">BAN</button>
          <button class="td-btn red" onclick="deleteUser('${esc(user.email)}')">DELETE</button>
        </div></td>
      </tr>`).join('') : '<tr><td colspan="9" class="empty">No users found.</td></tr>';
    document.getElementById('usersPagination').style.display = 'flex';
    document.getElementById('usersPageInfo').textContent =
      `Page ${data.page} • ${data.total || 0} users`;
    document.getElementById('usersPrevBtn').disabled = page <= 1;
    document.getElementById('usersNextBtn').disabled = !data.hasNext;
  } catch (error) {
    body.innerHTML = `<tr><td colspan="9" class="empty">${esc(error.message)}</td></tr>`;
  }
};

window.onUserSearchInput = function onUserSearch() {
  clearTimeout(window.morphlyUserSearchTimer);
  window.morphlyUserSearchTimer = setTimeout(() => loadUsers(1), 300);
};

window.clearUserSearch = function clearUserSearchSecure() {
  document.getElementById('userSearch').value = '';
  document.getElementById('userLookupPanel').style.display = 'none';
  loadUsers(1);
};

window.lookupUserByEmail = async function lookupUserSecure() {
  const email = document.getElementById('userSearch').value.trim().toLowerCase();
  if (!email.includes('@')) return toast('Enter an exact email address', 'err');
  try {
    const data = await responseJson(
      await adminRequest(`/admin/users/lookup?email=${encodeURIComponent(email)}`),
    );
    const panel = document.getElementById('userLookupPanel');
    panel.style.display = 'block';
    panel.innerHTML = data.user_exists
      ? `${esc(data.email)} • ${esc(data.user.status)} • ${fmtCred(Number(data.credits.remaining))} credits
         ${data.user.status === 'deleted' ? `<button class="td-btn" onclick="restoreUser('${esc(data.email)}')">RESTORE</button>` : ''}`
      : 'No account found.';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.restoreUser = async function restoreUserSecure(email) {
  try {
    await responseJson(await adminRequest('/admin/users/restore', {
      method: 'POST',
      body: JSON.stringify({ email }),
    }));
    toast('Account restored', 'ok');
    lookupUserByEmail();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.updateUserStatus = async function updateUserStatusSecure(email, status) {
  if (!confirm(`Mark ${email} as ${status}?`)) return;
  try {
    await responseJson(await adminRequest('/admin/users/status', {
      method: 'POST',
      body: JSON.stringify({ email, status }),
    }));
    toast(`User marked ${status}`, 'ok');
    loadUsers(usersPage);
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.deleteUser = async function deleteUserSecure(email) {
  if (!confirm(`Soft-delete ${email}? The account can be restored.`)) return;
  try {
    await responseJson(
      await adminRequest(`/admin/users?email=${encodeURIComponent(email)}`, { method: 'DELETE' }),
    );
    toast('User disabled and soft-deleted', 'ok');
    loadUsers(1);
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadPackages = async function loadPackagesSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/packages'));
    cachedCreditPackages = data.packages || [];
    renderCreditPlanOptions(cachedCreditPackages);
    for (const pkg of cachedCreditPackages) {
      const price = document.getElementById(`pkg_${pkg.id}_price`);
      const credits = document.getElementById(`pkg_${pkg.id}_credits`);
      if (price) price.value = pkg.price;
      if (credits) credits.value = pkg.credits;
    }
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.saveAllPackages = async function savePackagesSecure() {
  const definitions = [
    ['basic', 'Basic', '~8m 20s'],
    ['pro', 'Pro', '~16m 40s'],
    ['enterprise', 'Enterprise', '~41m 40s'],
    ['vip', 'VIP plan', '~83m 20s'],
  ];
  const packages = definitions.map(([id, name, timeLabel]) => ({
    id, name, timeLabel, currency: 'NGN',
    price: Number(document.getElementById(`pkg_${id}_price`).value),
    credits: Number(document.getElementById(`pkg_${id}_credits`).value),
  }));
  try {
    await responseJson(await adminRequest('/admin/packages', {
      method: 'POST',
      body: JSON.stringify({ packages }),
    }));
    toast('Packages saved', 'ok');
    loadPackages();
  } catch (error) {
    document.getElementById('planSaveErr').textContent = error.message;
  }
};

window.loadGlobalNotification = async function loadNotificationSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/config'));
    document.getElementById('globalNotificationInput').value = data.notification || '';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.saveGlobalNotification = async function saveNotificationSecure() {
  try {
    await responseJson(await adminRequest('/admin/config', {
      method: 'POST',
      body: JSON.stringify({
        notification: document.getElementById('globalNotificationInput').value,
      }),
    }));
    toast('Notification saved', 'ok');
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.setKeyMode = function setKeyModeSecure(mode) {
  _currentKeyMode = mode === 'multi' ? 'multi' : 'single';
  document.getElementById('singleKeySection').style.display = _currentKeyMode === 'single' ? '' : 'none';
  document.getElementById('multiKeySection').style.display = _currentKeyMode === 'multi' ? '' : 'none';
};

window.toggleKeyVisibility = function toggleKeyVisibility() {
  const input = document.getElementById('engineKeyInput');
  input.type = input.type === 'password' ? 'text' : 'password';
  document.getElementById('toggleKeyBtn').textContent = input.type === 'password' ? '👁 SHOW' : 'HIDE';
};

window.saveEngineKey = async function saveEngineKeySecure() {
  const payload = { mode: _currentKeyMode };
  if (_currentKeyMode === 'single') payload.single_key = document.getElementById('engineKeyInput').value.trim();
  else payload.keys = document.getElementById('multiKeyInput').value.split('\n').map((key) => key.trim()).filter(Boolean);
  try {
    await responseJson(await adminRequest('/admin/engine-key', {
      method: 'POST',
      body: JSON.stringify(payload),
    }));
    document.getElementById('engineKeyInput').value = '';
    document.getElementById('multiKeyInput').value = '';
    toast('Engine keys encrypted in Supabase Vault', 'ok');
    refreshEngineKeyStatus();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.refreshEngineKeyStatus = async function refreshKeysSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/engine-key'));
    document.getElementById('engineKeyStatus').textContent =
      data.configured ? `✓ ${data.mode.toUpperCase()} MODE` : 'NOT CONFIGURED';
    document.getElementById('engineKeyPreview').textContent =
      (data.keys || []).map((key) => `${key.label}: ${key.is_active ? 'active' : 'inactive'}${key.is_exhausted ? ' / exhausted' : ''}`).join(' • ');
    document.getElementById('resetExhaustedBtn').style.display =
      (data.keys || []).some((key) => key.is_exhausted) ? '' : 'none';
  } catch (error) {
    document.getElementById('engineKeyStatus').textContent = error.message;
  }
};

window.clearEngineKey = async function clearKeysSecure() {
  if (!confirm('Disable all live-engine keys?')) return;
  try {
    await responseJson(await adminRequest('/admin/engine-key', { method: 'DELETE' }));
    toast('All engine keys disabled', 'ok');
    refreshEngineKeyStatus();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.resetExhaustedKeys = async function resetKeysSecure() {
  try {
    await responseJson(await adminRequest('/admin/engine-key/reset', { method: 'POST' }));
    toast('Engine keys reset', 'ok');
    refreshEngineKeyStatus();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadReferralSettings = async function loadReferralSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/referral-settings'));
    document.getElementById('refEnabledToggle').checked = data.enabled;
    document.getElementById('refRewardAmount').value = data.rewardAmount || 250;
    document.getElementById('refToggleKnob').style.transform = data.enabled ? 'translateX(20px)' : '';
    document.getElementById('refToggleStatus').textContent =
      `New users: ${data.signupCredits || 50} • First-purchase referrer reward: ${data.rewardAmount || 250}`;
  } catch (error) {
    document.getElementById('refToggleStatus').textContent = error.message;
  }
};

window.saveReferralSettings = async function saveReferralSecure() {
  try {
    await responseJson(await adminRequest('/admin/referral-settings', {
      method: 'POST',
      body: JSON.stringify({
        enabled: document.getElementById('refEnabledToggle').checked,
        rewardAmount: Number(document.getElementById('refRewardAmount').value),
      }),
    }));
    toast('Referral settings saved', 'ok');
    loadReferralSettings();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadPayments = async function loadPaymentsSecure(kind = 'payments') {
  const bodyId = kind === 'crypto' ? 'cryptoPayTableBody' : 'payTableBody';
  const body = document.getElementById(bodyId);
  try {
    const data = await responseJson(await adminRequest(`/admin/${kind}`));
    body.innerHTML = data.payments?.length ? data.payments.map((payment, index) => kind === 'crypto' ? `
      <tr><td>${index + 1}</td><td>${fmtDate(payment.created_at)}</td>
      <td>${esc(payment.user_email_snapshot)}</td><td>${Number(payment.expected_amount).toLocaleString()} ${esc(payment.currency)}</td>
      <td>USDTTRC20</td><td>${esc(payment.provider_transaction_id || '—')}</td>
      <td>${esc(payment.status)}</td><td>${esc(payment.tx_ref)}</td></tr>` : `
      <tr><td>${index + 1}</td><td>${fmtDate(payment.created_at)}</td>
      <td>${esc(payment.user_email_snapshot)}</td><td>Credits</td>
      <td>${fmtCred(Number(payment.credits))}</td><td>${Number(payment.expected_amount).toLocaleString()}</td>
      <td>Flutterwave</td><td>${esc(payment.tx_ref)} / ${esc(payment.status)}</td></tr>`).join('')
      : `<tr><td colspan="8" class="empty">No ${kind} found.</td></tr>`;
  } catch (error) {
    body.innerHTML = `<tr><td colspan="8" class="empty">${esc(error.message)}</td></tr>`;
  }
};

window.loadBackgroundPresetsAdmin = async function loadBackgroundsSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/backgrounds'));
    document.getElementById('bgPresetsJson').value = JSON.stringify(data.presets || [], null, 2);
  } catch (error) {
    document.getElementById('bgPresetsErr').textContent = error.message;
  }
};

window.saveBackgroundPresetsAdmin = async function saveBackgroundsSecure() {
  try {
    const presets = JSON.parse(document.getElementById('bgPresetsJson').value || '[]');
    await responseJson(await adminRequest('/admin/backgrounds', {
      method: 'POST',
      body: JSON.stringify({ presets }),
    }));
    toast('Background presets saved', 'ok');
  } catch (error) {
    document.getElementById('bgPresetsErr').textContent = error.message;
  }
};

window.loadStreamingMonitor = async function loadMonitorSecure() {
  const body = document.getElementById('monitorTableBody');
  try {
    const data = await responseJson(await adminRequest('/admin/streaming-monitor'));
    body.innerHTML = data.active_sessions?.length ? data.active_sessions.map((session) => `
      <tr><td>${esc(session.email)}</td><td>${esc(session.device_id)}</td>
      <td>${Math.floor(Number(session.duration_seconds || 0) / 60)}m</td>
      <td>${fmtCred(Number(session.consumed_credits || 0))}</td><td>${esc(session.status)}</td></tr>`).join('')
      : '<tr><td colspan="5" class="empty">No active streams.</td></tr>';
  } catch (error) {
    body.innerHTML = `<tr><td colspan="5" class="empty">${esc(error.message)}</td></tr>`;
  }
};

window.loadBrandingSettings = async function loadBrandingSecure() {
  try {
    const [free, streaming] = await Promise.all([
      responseJson(await adminRequest('/admin/free-credits-settings')),
      responseJson(await adminRequest('/admin/streaming-availability')),
    ]);
    freeCreditsEnabled = free.enabled === true;
    streamingAvailabilityEnabled = streaming.enabled !== false;
    document.getElementById('freeCreditsAmount').value = free.amount || 1500;
    document.getElementById('freeCreditsLabel').textContent = freeCreditsEnabled ? 'Enabled' : 'Disabled';
    document.getElementById('streamingAvailabilityLabel').textContent =
      streamingAvailabilityEnabled ? 'Enabled' : 'Disabled';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.toggleFreeCredits = function toggleFreeCreditsSecure() {
  freeCreditsEnabled = !freeCreditsEnabled;
  document.getElementById('freeCreditsLabel').textContent = freeCreditsEnabled ? 'Enabled' : 'Disabled';
};

window.saveFreeCreditsSettings = async function saveFreeCreditsSecure() {
  try {
    await responseJson(await adminRequest('/admin/free-credits-settings', {
      method: 'POST',
      body: JSON.stringify({
        enabled: freeCreditsEnabled,
        amount: Number(document.getElementById('freeCreditsAmount').value),
      }),
    }));
    toast('Activation-credit setting saved', 'ok');
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.toggleStreamingAvailability = function toggleStreamingSecure() {
  streamingAvailabilityEnabled = !streamingAvailabilityEnabled;
  document.getElementById('streamingAvailabilityLabel').textContent =
    streamingAvailabilityEnabled ? 'Enabled' : 'Disabled';
};

window.saveStreamingAvailabilitySettings = async function saveStreamingSecure() {
  try {
    await responseJson(await adminRequest('/admin/streaming-availability', {
      method: 'POST',
      body: JSON.stringify({ enabled: streamingAvailabilityEnabled }),
    }));
    toast('Streaming availability saved', 'ok');
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.sendMassEmail = async function sendMassEmailSecure() {
  const result = document.getElementById('me_result');
  const errorElement = document.getElementById('me_err');
  result.textContent = 'Sending...';
  errorElement.textContent = '';
  try {
    const data = await responseJson(await adminRequest('/admin/mass-email', {
      method: 'POST',
      body: JSON.stringify({
        subject: document.getElementById('me_subject').value,
        body: document.getElementById('me_body_text').value,
      }),
    }));
    result.textContent = `Sent: ${data.sent}; failed: ${data.failed}`;
  } catch (error) {
    result.textContent = '';
    errorElement.textContent = error.message;
  }
};

window.showCreatorSubTab = function showCreatorSubTabSecure(name) {
  for (const id of ['overview', 'manage', 'referrals', 'commissions', 'payouts', 'fraud']) {
    document.getElementById(`csub-${id}`).style.display = id === name ? '' : 'none';
  }
  if (name === 'overview') loadCreatorOverview();
  if (name === 'manage') loadCreators();
  if (name === 'referrals') loadCreatorReferrals();
  if (name === 'commissions') loadCommissions();
  if (name === 'payouts') loadPayouts();
  if (name === 'fraud') loadSuspiciousActivity();
};

window.loadCreatorOverview = async function loadCreatorOverviewSecure() {
  try {
    const [overview, settings] = await Promise.all([
      responseJson(await adminRequest('/admin/creator/overview')),
      responseJson(await adminRequest('/admin/creator/settings')),
    ]);
    document.getElementById('globalCommPct').value = settings.default_rate || 30;
    document.getElementById('globalMinPayout').value = settings.minimum_payout || 5000;
    document.getElementById('globalCommCurrent').textContent = `${settings.default_rate || 30}%`;
    document.getElementById('globalMinPayoutCurrent').textContent =
      Number(settings.minimum_payout || 5000).toLocaleString();
    document.getElementById('leaderboardBody').innerHTML =
      `<tr><td>—</td><td>${overview.active_creators || 0} active creators</td>
       <td>${overview.conversions || 0}</td><td>${Number(overview.pending_commission || 0).toLocaleString()}</td></tr>`;
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.saveGlobalCommission = async function saveGlobalCommissionSecure() {
  try {
    await responseJson(await adminRequest('/admin/creator/settings', {
      method: 'POST',
      body: JSON.stringify({
        default_rate: Number(document.getElementById('globalCommPct').value),
        minimum_payout: Number(document.getElementById('globalMinPayout').value),
      }),
    }));
    toast('Commission settings saved', 'ok');
    loadCreatorOverview();
  } catch (error) {
    document.getElementById('globalCommErr').textContent = error.message;
  }
};

window.createCreator = async function createCreatorSecure() {
  const errorElement = document.getElementById('newCreatorErr');
  try {
    await responseJson(await adminRequest('/admin/creator/creators', {
      method: 'POST',
      body: JSON.stringify({
        email: document.getElementById('newCreatorEmail').value,
        displayName: document.getElementById('newCreatorName').value,
        commissionRate: Number(document.getElementById('newCreatorComm').value),
        referralCode: document.getElementById('newCreatorCode').value,
      }),
    }));
    errorElement.textContent = '';
    toast('Creator created', 'ok');
    loadCreators();
  } catch (error) {
    errorElement.textContent = error.message;
  }
};

window.loadCreators = async function loadCreatorsSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/creator/creators'));
    creatorRows = data.creators || [];
    renderCreators(creatorRows);
  } catch (error) {
    toast(error.message, 'err');
  }
};

function renderCreators(rows) {
  document.getElementById('creatorsListBody').innerHTML = rows.length ? rows.map((creator) => `
    <tr><td>${esc(creator.display_name)}</td><td>${esc(creator.email)}</td>
    <td>${esc(creator.referral_code)}</td><td>${Number(creator.commission_rate)}%</td>
    <td>${fmtCred(Number(creator.referral_bonus_credits || 250))}</td><td>—</td><td>—</td>
    <td>${esc(creator.status)}</td><td>—</td></tr>`).join('')
    : '<tr><td colspan="9" class="empty">No creators.</td></tr>';
}

window.filterCreatorsList = function filterCreatorsSecure() {
  const query = document.getElementById('creatorSearch').value.trim().toLowerCase();
  renderCreators(creatorRows.filter((row) =>
    `${row.display_name} ${row.email} ${row.referral_code}`.toLowerCase().includes(query)));
};

window.loadCreatorReferrals = async function loadCreatorReferralsSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/creator/referrals'));
    document.getElementById('referralsListBody').innerHTML = data.referrals?.length
      ? data.referrals.map((row) => `<tr><td>${esc(row.referral_code)}</td>
        <td>${esc(row.referred_user_id)}</td><td>${esc(row.status)}</td>
        <td>${fmtDate(row.signed_up_at)}</td><td>${fmtDate(row.converted_at)}</td></tr>`).join('')
      : '<tr><td colspan="5" class="empty">No referrals.</td></tr>';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadCommissions = async function loadCommissionsSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/creator/commissions'));
    document.getElementById('commissionsListBody').innerHTML = data.commissions?.length
      ? data.commissions.map((row) => `<tr><td>${esc(row.creator_id)}</td>
        <td>${Number(row.gross_amount).toLocaleString()}</td><td>${Number(row.commission_amount).toLocaleString()}</td>
        <td>${Number(row.commission_rate)}%</td><td>${esc(row.status)}</td>
        <td>${fmtDate(row.created_at)}</td><td>—</td></tr>`).join('')
      : '<tr><td colspan="7" class="empty">No commissions.</td></tr>';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.approveAllPendingCommissions = async function approveCommissionsSecure() {
  if (!confirm('Approve all pending commissions?')) return;
  try {
    const data = await responseJson(await adminRequest('/admin/creator/commissions/approve-all', {
      method: 'POST',
    }));
    toast(`${data.count} commissions approved`, 'ok');
    loadCommissions();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.createPayoutBatch = async function createPayoutBatchSecure() {
  try {
    await responseJson(await adminRequest('/admin/creator/payout-batches', {
      method: 'POST',
      body: JSON.stringify({
        periodStart: document.getElementById('payoutPeriodStart').value,
        periodEnd: document.getElementById('payoutPeriodEnd').value,
      }),
    }));
    toast('Payout batch created', 'ok');
    loadPayouts();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadPayouts = async function loadPayoutsSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/creator/payouts'));
    document.getElementById('payoutsListBody').innerHTML = data.payouts?.length
      ? data.payouts.map((row) => `<tr><td>${esc(row.creator_id)}</td>
        <td>${Number(row.amount).toLocaleString()}</td><td>${esc(row.status)}</td>
        <td>${esc(row.period_start)} – ${esc(row.period_end)}</td>
        <td>${fmtDate(row.created_at)}</td><td>—</td></tr>`).join('')
      : '<tr><td colspan="6" class="empty">No payouts.</td></tr>';
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.loadSuspiciousActivity = async function loadFraudSecure() {
  try {
    const data = await responseJson(await adminRequest('/admin/creator/fraud-scan', { method: 'POST' }));
    document.getElementById('fraudScanStatus').textContent = `${data.detected || 0} suspicious groups detected`;
    const ipEvents = (data.events || []).filter((event) => event.event_type === 'duplicate_signup_ip');
    document.getElementById('suspiciousIpBody').innerHTML = ipEvents.length
      ? ipEvents.map((event) => `<tr><td>${esc(event.evidence?.hash || '—')}</td>
        <td>${event.evidence?.referralIds?.length || 0}</td><td>${esc(event.severity)}</td></tr>`).join('')
      : '<tr><td colspan="3" class="empty">No suspicious IPs.</td></tr>';
    document.getElementById('suspiciousCreatorsBody').innerHTML =
      '<tr><td colspan="3" class="empty">No conversion anomaly detected.</td></tr>';
  } catch (error) {
    document.getElementById('fraudScanStatus').textContent = error.message;
  }
};

window.loadSupportTickets = async function loadSupportSecure() {
  const body = document.getElementById('supportTableBody');
  try {
    const data = await responseJson(await adminRequest('/admin/support'));
    body.innerHTML = data.tickets?.length ? data.tickets.map((ticket, index) => `
      <tr><td>${index + 1}</td><td>${fmtDate(ticket.created_at)}</td>
      <td>${esc(ticket.email_snapshot)}</td><td>${esc(ticket.category)}</td>
      <td>${esc(ticket.subject)}</td><td>${esc(ticket.message)}</td><td>${esc(ticket.status)}</td>
      <td><button class="td-btn" onclick="resolveSupportTicket('${ticket.id}')">RESOLVE</button></td></tr>`).join('')
      : '<tr><td colspan="8" class="empty">No support tickets.</td></tr>';
  } catch (error) {
    body.innerHTML = `<tr><td colspan="8" class="empty">${esc(error.message)}</td></tr>`;
  }
};

window.resolveSupportTicket = async function resolveSupportSecure(id) {
  const resolution = prompt('Resolution note:');
  if (resolution === null) return;
  try {
    await responseJson(await adminRequest('/admin/support', {
      method: 'PATCH',
      body: JSON.stringify({ id, status: 'resolved', resolution }),
    }));
    toast('Ticket resolved', 'ok');
    loadSupportTickets();
  } catch (error) {
    toast(error.message, 'err');
  }
};

window.showTab = function showTabSecure(id) {
  document.querySelectorAll('.tab-btn').forEach((button) => {
    button.classList.toggle('on', button.getAttribute('onclick')?.includes(`'${id}'`));
  });
  document.querySelectorAll('.tab-content').forEach((content) => content.classList.remove('on'));
  const target = document.getElementById(`tab-${id}`);
  if (!target) return;
  target.classList.add('on');
  if (id === 'plans' || id === 'credits') loadPackages();
  if (id === 'users') loadUsers(1);
  if (id === 'referral') loadReferralSettings();
  if (id === 'payments') loadPayments('payments');
  if (id === 'crypto') loadPayments('crypto');
  if (id === 'monitor') loadStreamingMonitor();
  if (id === 'backgrounds') loadBackgroundPresetsAdmin();
  if (id === 'support') loadSupportTickets();
  if (id === 'branding') loadBrandingSettings();
  if (id === 'notifications') loadGlobalNotification();
  if (id === 'creators') showCreatorSubTab('overview');
};

document.getElementById('multiKeyInput')?.addEventListener('input', (event) => {
  document.getElementById('multiKeyCount').textContent =
    event.target.value.split('\n').map((key) => key.trim()).filter(Boolean).length;
});
document.getElementById('creditPlan')?.addEventListener('change', (event) => {
  document.getElementById('customCreditRow').style.display =
    event.target.value === 'custom' ? 'block' : 'none';
});
document.getElementById('adminPass')?.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') doAdminLogin();
});
document.addEventListener('DOMContentLoaded', restoreAdminSessionIfLoggedIn);
