/* Morphly admin dashboard (vanilla JS, no build step). Auth: /api/admin/login sets an HttpOnly, Secure,
   SameSite=Strict session cookie. No tokens are stored in the browser; only the signed-in email (for display)
   is kept in sessionStorage. Every state-changing call sends X-Morphly-Admin: 1 (CSRF check on the server). */
(() => {
const $ = (s, el = document) => el.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
try {localStorage.removeItem('morphly_admin');} catch {}
const store = {get: () => JSON.parse(sessionStorage.getItem('morphly_admin_ui') || 'null'), set: v => sessionStorage.setItem('morphly_admin_ui', JSON.stringify({email: v.email})), clear: () => sessionStorage.removeItem('morphly_admin_ui')};
let state = {page: (location.hash.slice(1) || 'overview'), cur: 'NGN', search: ''};

const fmt = n => Number(n || 0).toLocaleString('en-US');
const money = (n, c = state.cur) => (c === 'NGN' ? '₦' : c + ' ') + fmt(Math.round(n || 0));
const short = n => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : fmt(n);
const date = v => v ? new Date(v).toLocaleDateString('en-GB', {day: '2-digit', month: 'short', year: 'numeric'}) : '—';
const ago = v => {if (!v) return '—'; const s = (Date.now() - new Date(v)) / 1000; return s < 60 ? 'just now' : s < 3600 ? Math.floor(s / 60) + 'm ago' : s < 86400 ? Math.floor(s / 3600) + 'h ago' : Math.floor(s / 86400) + 'd ago';};
const dur = s => s >= 3600 ? `${Math.floor(s / 3600)}h ${Math.floor(s % 3600 / 60)}m` : `${Math.floor(s / 60)}m ${s % 60}s`;
const initials = e => esc(String(e || '?').slice(0, 2).toUpperCase());
const pill = s => `<span class="pill ${['verified', 'active', 'completed', 'converted', 'successful'].includes(s) ? 'ok' : ['failed', 'suspended', 'error', 'banned'].includes(s) ? 'bad' : ['pending', 'running', 'starting', 'signed_up', 'warn'].includes(s) ? 'warn' : ''}">${esc(s)}</span>`;
const delta = (a, b) => {if (!b) return a ? '<span class="up">New</span>' : '—'; const p = (a - b) / b * 100; return `<span class="${p >= 0 ? 'up' : 'down'}">${p >= 0 ? '+' : ''}${p.toFixed(1)}%</span> vs previous period`;};
function toast(m) {const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.append(t); setTimeout(() => t.remove(), 2600);}

async function api(path, opts = {}) {
  const r = await fetch('/api/admin' + path, {method: opts.method || 'GET', credentials: 'same-origin', cache: 'no-store',
    headers: {'Content-Type': 'application/json', 'X-Morphly-Admin': '1'}, body: opts.body ? JSON.stringify(opts.body) : undefined});
  const j = await r.json().catch(() => ({}));
  if (r.status === 401 || r.status === 403) {if (path !== '/login') {store.clear(); render();} throw new Error(j.error || 'Access denied');}
  if (!r.ok) throw new Error(j.error || 'Request failed');
  if (j.audit_logged === false) setTimeout(() => toast('Warning: this action could not be written to the audit log'), 2700);
  return j;
}

/* ---------- charts (inline SVG) ---------- */
function line(seriesList, {h = 240, colors = ['#e11d2e', '#1c1c1c'], fmtY = short} = {}) {
  const W = 760, P = 36, pts = seriesList[0]?.length || 0; if (!pts) return '<div class="empty">No data yet</div>';
  const max = Math.max(1, ...seriesList.flat().map(p => p.value)), x = i => P + i * (W - P - 10) / Math.max(1, pts - 1), y = v => h - 26 - v / max * (h - 46);
  const grid = [0, .25, .5, .75, 1].map(f => `<line x1="${P}" x2="${W - 10}" y1="${y(max * f)}" y2="${y(max * f)}" stroke="#eee" stroke-dasharray="${f ? 4 : 0}"/><text x="${P - 8}" y="${y(max * f) + 4}" text-anchor="end" font-size="11" fill="#9a9a9e">${fmtY(max * f)}</text>`).join('');
  const labels = seriesList[0].map((p, i) => i % Math.ceil(pts / 7) === 0 ? `<text x="${x(i)}" y="${h - 6}" text-anchor="middle" font-size="11" fill="#9a9a9e">${p.date.slice(5)}</text>` : '').join('');
  const paths = seriesList.map((s, k) => {
    const d = s.map((p, i) => { if (!i) return `M${x(i)},${y(p.value)}`; const px = x(i - 1), py = y(s[i - 1].value), cx = (px + x(i)) / 2; return `C${cx},${py} ${cx},${y(p.value)} ${x(i)},${y(p.value)}`; }).join('');
    const area = k === 0 ? `<path d="${d}L${x(pts - 1)},${h - 26}L${x(0)},${h - 26}Z" fill="url(#g${k})"/>` : '';
    return `${area}<path d="${d}" fill="none" stroke="${colors[k]}" stroke-width="${k ? 2 : 2.6}" ${k ? 'stroke-dasharray="5 5"' : ''} stroke-linecap="round"/>`;
  }).join('');
  return `<svg class="chart" viewBox="0 0 ${W} ${h}"><defs><linearGradient id="g0" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#e11d2e" stop-opacity=".18"/><stop offset="1" stop-color="#e11d2e" stop-opacity="0"/></linearGradient></defs>${grid}${labels}${paths}</svg>`;
}
function bars(s, {h = 220, fmtY = short} = {}) {
  if (!s.length) return '<div class="empty">No data yet</div>';
  const W = 520, P = 34, max = Math.max(1, ...s.map(p => p.value)), bw = (W - P - 10) / s.length;
  return `<svg class="chart" viewBox="0 0 ${W} ${h}">${[0, .5, 1].map(f => `<line x1="${P}" x2="${W}" y1="${h - 24 - f * (h - 44)}" y2="${h - 24 - f * (h - 44)}" stroke="#eee" stroke-dasharray="4"/><text x="${P - 6}" y="${h - 20 - f * (h - 44)}" font-size="11" text-anchor="end" fill="#9a9a9e">${fmtY(max * f)}</text>`).join('')}
  ${s.map((p, i) => {const bh = Math.max(2, p.value / max * (h - 44)); return `<rect x="${P + i * bw + bw * .22}" y="${h - 24 - bh}" width="${bw * .56}" height="${bh}" rx="6" fill="${i === s.length - 1 ? '#e11d2e' : '#f6b3b9'}"/>${s.length <= 16 && i % Math.ceil(s.length / 8) === 0 ? `<text x="${P + i * bw + bw / 2}" y="${h - 6}" font-size="11" text-anchor="middle" fill="#9a9a9e">${esc((p.label || p.date || '').slice(-5))}</text>` : ''}`;}).join('')}</svg>`;
}
function donut(parts) {
  const total = parts.reduce((n, p) => n + p.value, 0) || 1; let a = -Math.PI / 2; const C = ['#e11d2e', '#1c1c1c', '#f6b3b9', '#9a9a9e', '#ffd6da'];
  const arcs = parts.map((p, i) => {const b = a + p.value / total * Math.PI * 2, L = b - a > Math.PI ? 1 : 0, r = 70, R = 70; const d = `M${100 + R * Math.cos(a)},${100 + R * Math.sin(a)}A${r},${r} 0 ${L} 1 ${100 + R * Math.cos(b - .0001)},${100 + R * Math.sin(b - .0001)}`; a = b; return `<path d="${d}" stroke="${C[i % 5]}" stroke-width="26" fill="none"/>`;}).join('');
  return `<div style="display:flex;align-items:center;gap:20px"><svg viewBox="0 0 200 200" width="150">${arcs}</svg><div class="list" style="flex:1">${parts.map((p, i) => `<div class="li"><span class="dot" style="background:${C[i % 5]}"></span>${esc(p.label)}<span class="r">${Math.round(p.value / total * 100)}%</span></div>`).join('')}</div></div>`;
}

/* ---------- layout ---------- */
const I = {
  overview: '<path d="M3 12l9-8 9 8M5 10v10h14V10" />', users: '<circle cx="9" cy="8" r="4"/><path d="M2 21c0-4 3-7 7-7s7 3 7 7M17 11a3 3 0 100-6M22 21c0-3-2-5-4-6"/>',
  purchases: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18M7 15h4"/>', performance: '<path d="M3 20h18M6 16l4-5 4 3 5-7"/>',
  errors: '<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18v.5"/>', referrals: '<circle cx="6" cy="12" r="3"/><circle cx="18" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><path d="M8.6 10.6l6.8-3.2M8.6 13.4l6.8 3.2"/>',
  audit: '<path d="M9 3h6l1 2h3v16H5V5h3zM9 11h6M9 15h6"/>', packages: '<path d="M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8"/>', settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.6 1.6 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.6 1.6 0 00-2.7 1.1V21a2 2 0 11-4 0v-.1A1.6 1.6 0 009 19.4a1.6 1.6 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1A1.6 1.6 0 003 14.2H3a2 2 0 110-4h.1A1.6 1.6 0 004.6 9 1.6 1.6 0 004.3 7.2l-.1-.1a2 2 0 112.8-2.8l.1.1A1.6 1.6 0 009 4.6 1.6 1.6 0 0010 3.1V3a2 2 0 114 0v.1a1.6 1.6 0 001 1.5 1.6 1.6 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.6 1.6 0 00-.3 1.8V9a1.6 1.6 0 001.5 1H21a2 2 0 110 4h-.1a1.6 1.6 0 00-1.5 1z"/>',
};
const ico = k => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${I[k]}</svg>`;
const NAV = [['Dashboards', [['overview', 'Overview'], ['performance', 'Performance'], ['errors', 'Errors']]], ['Business', [['users', 'Users'], ['purchases', 'Purchases'], ['referrals', 'Referrals']]], ['Configuration', [['packages', 'Packages'], ['settings', 'Settings'], ['audit', 'Audit log']]]];
const TITLE = Object.fromEntries(NAV.flatMap(([, l]) => l));

function shell(inner) {
  const s = store.get() || {email: 'admin@morphly.app'};
  return `<div class="app"><aside class="side"><div class="brand"><div class="logo">M</div><span>Morphly Admin</span></div>
  ${NAV.map(([sec, items]) => `<div class="sec">${sec}</div>${items.map(([k, l]) => `<a class="nav ${state.page === k ? 'on' : ''}" href="#${k}">${ico(k)}<span>${l}</span></a>`).join('')}`).join('')}
  <div class="foot">Live · Firestore nova-8fb74<br>2 credits / second</div></aside>
  <main class="main"><header class="top"><div class="crumb">Dashboards / <b>${TITLE[state.page] || ''}</b></div>
  <label class="search">⌕<input id="q" placeholder="Search users, email, code" value="${esc(state.search)}"></label>
  <button class="btn" id="reload">Refresh</button><div class="avatar" title="${esc(s.email)}">${initials(s.email)}</div><button class="btn" id="logout">Sign out</button></header>
  <section class="content" id="content">${inner}</section></main></div>`;
}

/* ---------- pages ---------- */
const P = {};
P.overview = async () => {
  const d = await api('/overview?days=30'), k = d.kpis; state.cur = k.currency || 'NGN';
  const card = (l, v, sub) => `<div class="kpi"><div class="l">${l}</div><div class="v">${v}</div><div class="d">${sub}</div></div>`;
  return `<div class="head"><div><h1>Overview</h1><div class="sub">Last 30 days across the Morphly app</div></div><div class="tabs"><button class="on">30 days</button></div></div>
  <div class="kpis">${card('Total users', fmt(k.total_users), `${fmt(k.new_signups)} new this period`)}${card('New signups', fmt(k.new_signups), delta(k.new_signups, k.new_signups_prev))}
  ${card('Active users (7d)', fmt(k.active_users), `${fmt(k.live_streams)} streaming right now`)}${card('Revenue', money(k.revenue), delta(k.revenue, k.revenue_prev))}
  ${card('Credits sold', short(k.credits_sold), `${money(k.revenue_all_time)} all-time revenue`)}${card('Credits used', short(k.credits_used), `${short(k.credits_outstanding)} still in wallets`)}
  ${card('Streams today', fmt(k.streams_today), `${fmt(k.errors_24h)} errors in last 24h`)}${card('Buyers', fmt(k.first_time_buyers), `${fmt(k.repeat_buyers)} came back to buy again`)}</div>
  <div class="grid"><div class="card"><h3>Revenue &amp; signups <span class="legend"><span><i class="dot" style="background:#e11d2e"></i>Revenue</span><span><i class="dot" style="background:#1c1c1c"></i>Signups (scaled)</span></span></h3>
  ${(() => {const r = d.series.revenue, s = d.series.signups, m = Math.max(1, ...r.map(p => p.value)) / Math.max(1, ...s.map(p => p.value)); return line([r, s.map(p => ({...p, value: p.value * m}))], {fmtY: v => short(v)});})()}</div>
  <div class="card"><h3>New signups</h3><div class="list">${d.recent_signups.map(u => `<div class="li who"><div class="a">${initials(u.email)}</div><div>${esc(u.name || u.email.split('@')[0])}<small>${esc(u.email)}</small></div><span class="r">${ago(u.created_at)}</span></div>`).join('') || '<div class="empty">No signups yet</div>'}</div></div></div>
  <div class="grid eq"><div class="card"><h3>Streams per day</h3>${bars(d.series.streams.slice(-14))}</div><div class="card"><h3>Credits used per day</h3>${bars(d.series.credits_used.slice(-14))}</div></div>
  <div class="card w"><h3>Recent purchases <a class="meta" href="#purchases">View all →</a></h3>${table(['Customer', 'Package', 'Credits', 'Amount', 'Date', 'Status'], d.recent_purchases.map(p => [who(p.user_email_snapshot), esc(p.package_id), fmt(p.credits), money(p.expected_amount, p.currency), date(p.verified_at), pill(p.status)]))}</div>`;
};
const who = (e, sub = '') => `<div class="who"><div class="a">${initials(e)}</div><div>${esc(e)}${sub ? `<small>${esc(sub)}</small>` : ''}</div></div>`;
const table = (h, rows) => `<div class="tablewrap"><table><thead><tr>${h.map(x => `<th>${x}</th>`).join('')}</tr></thead><tbody>${rows.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('') || `<tr><td colspan="${h.length}" class="empty">Nothing here yet</td></tr>`}</tbody></table></div>`;

P.users = async () => {
  const d = await api('/users?q=' + encodeURIComponent(state.search)); state.users = d.users;
  return `<div class="head"><div><h1>Users</h1><div class="sub">${fmt(d.users.length)} accounts · add or remove credits, enable or disable</div></div></div>
  ${table(['User', 'Credits', 'Used', 'Plan', 'Referral', 'Signed up', 'Last active', 'Status', ''], d.users.map(u => [who(u.email, u.name || u.id.slice(0, 10)), `<b>${fmt(u.balance)}</b>`, fmt(u.used),
    u.plan ? esc(u.plan) : '<span class="mono">free</span>', `<span class="mono">${esc(u.referral_code)}</span>${u.referred_by ? `<br><small style="color:#8a8a8f">by ${esc(u.referred_by)}</small>` : ''}`, date(u.created_at), ago(u.last_active), pill(u.status),
    `<button class="btn s" data-credit="${esc(u.id)}">Credits</button> <button class="btn s" data-status="${esc(u.id)}" data-to="${u.status === 'active' ? 'suspended' : 'active'}">${u.status === 'active' ? 'Disable' : 'Enable'}</button>`]))}`;
};
P.purchases = async () => {
  const d = await api('/purchases'), t = d.totals; state.cur = d.purchases[0]?.currency || state.cur;
  const tab = state.ptab || 'all';
  const lists = {all: table(['Customer', 'Package', 'Credits', 'Amount', 'Reference', 'Created', 'Status'], d.purchases.map(p => [who(p.email), esc(p.package_id), fmt(p.credits), money(p.amount, p.currency), `<span class="mono">${esc(p.tx_ref)}</span>`, date(p.created_at), pill(p.status)])),
    first: table(['Customer', 'First package', 'First purchase', 'Total purchases'], d.first_time_buyers.map(b => [who(b.email), esc(b.first_package), date(b.first_purchase_at), fmt(d.repeat_buyers.find(r => r.user_id === b.user_id)?.purchases || 1)])),
    repeat: table(['Customer', 'Purchases', 'Total spent', 'Packages', 'First', 'Latest'], d.repeat_buyers.map(b => [who(b.email), `<b>${b.purchases}</b>`, money(b.total_spent, b.currency), esc(b.packages.join(', ')), date(b.first_purchase_at), date(b.last_purchase_at)]))};
  return `<div class="head"><div><h1>Purchases</h1><div class="sub">Flutterwave payments, first-time and repeat buyers</div></div></div>
  <div class="kpis"><div class="kpi"><div class="l">Revenue</div><div class="v">${money(t.revenue)}</div><div class="d">${fmt(t.verified)} paid orders</div></div>
  <div class="kpi"><div class="l">First-time buyers</div><div class="v">${fmt(d.first_time_buyers.length)}</div><div class="d">${fmt(d.only_once_buyers.length)} bought once only</div></div>
  <div class="kpi"><div class="l">Repeat buyers</div><div class="v">${fmt(d.repeat_buyers.length)}</div><div class="d">${d.first_time_buyers.length ? Math.round(d.repeat_buyers.length / d.first_time_buyers.length * 100) : 0}% came back</div></div>
  <div class="kpi"><div class="l">Pending / failed</div><div class="v">${fmt(t.pending)} / ${fmt(t.failed)}</div><div class="d">checkouts not completed</div></div></div>
  <div class="grid"><div class="card"><h3>Revenue, last 30 days</h3>${line([d.revenue_series])}</div><div class="card"><h3>Revenue by package</h3>${donut(d.revenue_by_package.map(p => ({label: p.id, value: p.value})))}</div></div>
  <div class="tabs" id="ptabs">${[['all', 'All purchases'], ['first', 'First-time buyers'], ['repeat', 'Repeat buyers']].map(([k, l]) => `<button data-ptab="${k}" class="${tab === k ? 'on' : ''}">${l}</button>`).join('')}</div>${lists[tab]}`;
};
P.performance = async () => {
  const d = await api('/performance'), t = d.totals;
  return `<div class="head"><div><h1>Performance</h1><div class="sub">Live face-swap stream sessions</div></div></div>
  <div class="kpis"><div class="kpi"><div class="l">Sessions</div><div class="v">${fmt(t.sessions)}</div><div class="d">${fmt(t.completed)} completed</div></div>
  <div class="kpi"><div class="l">Average duration</div><div class="v">${dur(t.avg_duration_seconds)}</div><div class="d">${dur(t.total_stream_seconds)} streamed in total</div></div>
  <div class="kpi"><div class="l">Failures</div><div class="v">${fmt(t.failed)}</div><div class="d"><span class="${t.failure_rate > .1 ? 'down' : 'up'}">${(t.failure_rate * 100).toFixed(1)}%</span> failure rate</div></div>
  <div class="kpi"><div class="l">Credits consumed</div><div class="v">${short(t.credits_consumed)}</div><div class="d">at 2 credits per second</div></div></div>
  <div class="grid eq"><div class="card"><h3>Sessions, last 14 days</h3>${bars(d.sessions_series)}</div><div class="card"><h3>Failures, last 14 days</h3>${line([d.failures_series], {h: 220})}</div></div>
  ${table(['User', 'Status', 'Started', 'Duration', 'Credits', 'Engine', 'Device'], d.sessions.map(s => [who(s.email), pill(s.status), `${date(s.started_at)} <small style="color:#8a8a8f">${ago(s.started_at)}</small>`, dur(s.duration_seconds), fmt(s.credits), `<b>${s.tier === 'pro' ? 'Pro' : 'Plus'}</b> <small style="color:#8a8a8f">${esc(`${s.provider || ''} ${s.model || ''}`)}${s.rate ? ` · ${s.rate} cr/s` : ''}</small>`, `<span class="mono">${esc((s.device_id || '').slice(0, 14))}</span>`]))}`;
};
P.errors = async () => {
  const d = await api('/errors');
  return `<div class="head"><div><h1>Errors</h1><div class="sub">Reported by the Android app (POST /api/log-error) and the server</div></div><button class="btn" id="clearErrors">Clear all</button></div>
  <div class="grid"><div class="card"><h3>Errors, last 14 days</h3>${bars(d.series)}</div><div class="card"><h3>By source</h3>${donut(Object.entries(d.by_source).map(([label, value]) => ({label, value})))}</div></div>
  ${table(['When', 'Source', 'Message', 'Where', 'User / device', ''], d.errors.map(e => [ago(e.created_at), pill(e.source), `<div style="white-space:normal;max-width:460px">${esc(e.message)}</div>`, `<span class="mono">${esc(e.context || e.code || '')}</span>`, `<span class="mono">${esc((e.user_id || '').slice(0, 10))} ${esc(e.device || '')} ${esc(e.app_version || '')}</span>`, `<button class="btn s" data-delerr="${esc(e.id)}">Dismiss</button>`]))}`;
};
P.audit = async () => {
  const d = await api('/audit');
  return `<div class="head"><div><h1>Audit log</h1><div class="sub">Every admin sign-in, sign-out and change (latest 300)</div></div></div>
  ${table(['When', 'Admin', 'Action', 'Target', 'Details', 'IP'], d.entries.map(e => [`${date(e.created_at)} <small style="color:#8a8a8f">${ago(e.created_at)}</small>`, esc(e.actor_email || '—'), pill(e.action), `<span class="mono">${esc(String(e.entity || '').slice(0, 24))}</span>`, `<span class="mono" style="white-space:normal">${esc(JSON.stringify(e.data || '').slice(0, 200))}</span>`, `<span class="mono">${esc(e.ip || '')}</span>`]))}`;
};
P.referrals = async () => {
  const d = await api('/referrals');
  return `<div class="head"><div><h1>Referrals</h1><div class="sub">Every user has a referral code; referrers earn ${fmt(d.program.purchase_reward ?? 250)} credits on a friend's first purchase</div></div></div>
  <div class="kpis"><div class="kpi"><div class="l">Referred users</div><div class="v">${fmt(d.totals.referrals)}</div><div class="d">signed up with a code</div></div>
  <div class="kpi"><div class="l">Converted</div><div class="v">${fmt(d.totals.converted)}</div><div class="d">went on to purchase</div></div>
  <div class="kpi"><div class="l">Conversion</div><div class="v">${d.totals.referrals ? Math.round(d.totals.converted / d.totals.referrals * 100) : 0}%</div><div class="d">of referred users</div></div>
  <div class="kpi"><div class="l">Signup bonus</div><div class="v">${fmt(d.program.signup_credits ?? 50)}</div><div class="d">credits for new users</div></div></div>
  <div class="grid"><div>${table(['Referred user', 'Referred by', 'Code', 'Joined', 'Status'], d.referrals.map(r => [who(r.referred), esc(r.referrer), `<span class="mono">${esc(r.code)}</span>`, date(r.created_at), pill(r.status)]))}</div>
  <div class="card"><h3>Top referrers</h3><div class="bars">${d.top_referrers.map(r => `<div class="bar"><span title="${esc(r.email)}">${esc(r.email.split('@')[0])}</span><div class="t"><i style="width:${r.referrals / Math.max(1, d.top_referrers[0].referrals) * 100}%"></i></div><span>${r.referrals} · ${r.converted} paid</span></div>`).join('') || '<div class="empty">No referrals yet</div>'}</div></div></div>`;
};
P.packages = async () => {const d = await api('/packages'); state.pk = d.packages; return pkHtml();};
const pkHtml = () => {const d = {packages: state.pk};
  return `<div class="head"><div><h1>Credit packages</h1><div class="sub">Shown in the app and charged through Flutterwave · 2 credits = 1 second of streaming</div></div><div><button class="btn" id="addPk">Add package</button> <button class="btn p" id="savePk">Save changes</button></div></div>
  ${table(['Code', 'Name', 'Credits', 'Price', 'Currency', 'Stream time', 'Active'], d.packages.map((p, i) => [`<input class="f" data-i="${i}" data-k="code" value="${esc(p.code)}" ${p.id ? 'readonly' : ''}>`, `<input class="f" data-i="${i}" data-k="name" value="${esc(p.name)}">`, `<input class="f" data-i="${i}" data-k="credits" type="number" value="${esc(p.credits)}">`, `<input class="f" data-i="${i}" data-k="price" type="number" value="${esc(p.price)}">`, `<input class="f" data-i="${i}" data-k="currency" value="${esc(p.currency)}" style="width:80px">`, `<span class="mono">${dur(Math.floor(p.credits / 2))}</span>`, `<button class="sw ${p.is_active !== false ? 'on' : ''}" data-i="${i}" data-k="is_active"></button>`]))}`;
};
P.settings = async () => {
  const s = await api('/settings'); state.st = s;
  const tg = (k, l, sub) => `<div class="toggle"><div><b>${l}</b><div class="sub">${sub}</div></div><button class="sw ${s[k] ? 'on' : ''}" data-st="${k}"></button></div>`;
  return `<div class="head"><div><h1>Settings</h1><div class="sub">App-wide configuration read by the Android app</div></div><button class="btn p" id="saveSt">Save settings</button></div>
  <div class="grid eq"><div class="card w"><h3>Streaming</h3>${tg('streamingEnabled', 'Live streaming', 'Turn off to block new streams for everyone')}
  <label class="sub" style="display:block;margin-top:14px">In-app announcement<textarea class="f" id="notification" rows="3">${esc(s.notification)}</textarea></label></div>
  <div class="card w"><h3>Referrals &amp; bonuses</h3>${tg('referralEnabled', 'Referral program', 'Reward users who invite friends')}
  <div class="form" style="margin-top:14px"><label>Signup credits<input class="f" id="signupCredits" type="number" value="${s.signupCredits}"></label><label>Referrer reward (credits)<input class="f" id="purchaseReward" type="number" value="${s.purchaseReward}"></label></div></div></div>
  <div class="card w"><h3>Payments</h3><div class="toggle"><div><b>Flutterwave</b><div class="sub">FLW_PUBLIC_KEY, FLW_SECRET_KEY and FLW_SECRET_HASH on Vercel · webhook: <span class="mono">${esc(location.origin)}/api/flutterwave-webhook</span></div></div>${pill(s.flutterwaveConfigured ? 'active' : 'pending')}</div></div>`;
};

/* ---------- actions ---------- */
function bind() {
  $('#logout')?.addEventListener('click', async () => {try {await api('/logout', {method: 'POST'});} catch {} store.clear(); render();});
  $('#reload')?.addEventListener('click', () => go(state.page));
  $('#q')?.addEventListener('keydown', e => {if (e.key === 'Enter') {state.search = e.target.value; location.hash = 'users'; go('users');}});
  document.querySelectorAll('[data-ptab]').forEach(b => b.onclick = () => {state.ptab = b.dataset.ptab; go('purchases');});
  document.querySelectorAll('[data-credit]').forEach(b => b.onclick = () => creditModal(state.users.find(u => u.id === b.dataset.credit)));
  document.querySelectorAll('[data-status]').forEach(b => b.onclick = async () => {if (!confirm(`${b.dataset.to === 'active' ? 'Enable' : 'Disable'} this user?`)) return; await api('/users/status', {method: 'POST', body: {uid: b.dataset.status, status: b.dataset.to}}); toast('User updated'); go('users');});
  document.querySelectorAll('[data-delerr]').forEach(b => b.onclick = async () => {await api('/errors?id=' + encodeURIComponent(b.dataset.delerr), {method: 'DELETE'}); go('errors');});
  $('#clearErrors')?.addEventListener('click', async () => {const c = prompt('This permanently deletes every logged error. Type DELETE-ALL-ERRORS to confirm.'); if (c !== 'DELETE-ALL-ERRORS') return; try {const r = await api('/errors?id=all&confirm=' + encodeURIComponent(c), {method: 'DELETE'}); toast(`Deleted ${fmt(r.deleted)} errors`); go('errors');} catch (e) {toast(e.message);}});
  document.querySelectorAll('input[data-i]').forEach(i => i.oninput = () => {const p = state.pk[i.dataset.i]; p[i.dataset.k] = ['credits', 'price'].includes(i.dataset.k) ? Number(i.value) : i.value;});
  document.querySelectorAll('button.sw[data-i]').forEach(b => b.onclick = () => {const p = state.pk[b.dataset.i]; p.is_active = p.is_active === false; b.classList.toggle('on');});
  const _a = document.getElementById('addPk'), _s = document.getElementById('savePk');
  if (_a) _a.onclick = () => {state.pk.push({code: '', name: '', credits: 1000, price: 0, currency: 'NGN', is_active: true}); paint(pkHtml()); bind();};
  if (_s) _s.onclick = async () => {try {await api('/packages', {method: 'POST', body: {packages: state.pk}}); toast('Packages saved'); go('packages');} catch (e) {toast(e.message);}};
  document.querySelectorAll('[data-st]').forEach(b => b.onclick = () => {state.st[b.dataset.st] = !state.st[b.dataset.st]; b.classList.toggle('on');});
  $('#saveSt')?.addEventListener('click', async () => {const s = state.st; try {await api('/settings', {method: 'POST', body: {...s, notification: $('#notification').value, signupCredits: Number($('#signupCredits').value), purchaseReward: Number($('#purchaseReward').value)}}); toast('Settings saved');} catch (e) {toast(e.message);}});
}
function creditModal(u) {
  const m = document.createElement('div'); m.className = 'modal';
  m.innerHTML = `<div class="card"><h3>Adjust credits</h3>${who(u.email, `Balance ${fmt(u.balance)} credits`)}<label class="sub">Amount (negative to remove)<input class="f" id="cd" type="number" value="1000"></label><label class="sub">Reason<input class="f" id="cr" placeholder="Support top-up"></label><div style="display:flex;gap:8px;justify-content:flex-end"><button class="btn" id="cx">Cancel</button><button class="btn p" id="cs">Apply</button></div></div>`;
  document.body.append(m); $('#cx', m).onclick = () => m.remove();
  $('#cs', m).onclick = async () => {try {const r = await api('/users/credits', {method: 'POST', body: {uid: u.id, delta: Number($('#cd', m).value), reason: $('#cr', m).value, idempotencyKey: crypto.randomUUID()}}); toast(`New balance ${fmt(r.balance)}`); m.remove(); go('users');} catch (e) {toast(e.message);}};
}
function paint(html) {if (html != null) $('#content').innerHTML = html;}
async function go(page) {
  state.page = P[page] ? page : 'overview'; document.getElementById('root').innerHTML = shell('<div class="empty">Loading…</div>');
  try {$('#content').innerHTML = await P[state.page]();} catch (e) {$('#content').innerHTML = `<div class="err">${esc(e.message)}</div>`;}
  bind();
}
function loginView() {
  document.getElementById('root').innerHTML = `<div class="login"><div class="art"><div class="brand" style="padding:0"><div class="logo" style="background:#fff;color:#e11d2e">M</div><span>Morphly</span></div><div><h2>Run every part of Morphly from one place.</h2><p>Users, credits, payments, streams and errors, live from Firestore.</p></div><small style="opacity:.7">Admins only · access is limited to approved emails</small></div>
  <div class="box"><form id="lf"><div class="logo">M</div><h1>Sign in to Admin</h1><div class="sub">Use your Morphly administrator account</div><div id="le"></div>
  <input class="f" id="le-mail" type="email" placeholder="Email" autocomplete="username" required><input class="f" id="le-pw" type="password" placeholder="Password" autocomplete="current-password" required><button class="btn p" style="padding:12px">Sign in</button></form></div></div>`;
  $('#lf').onsubmit = async e => {e.preventDefault(); try {
    const r = await fetch('/api/admin/login', {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-Morphly-Admin': '1'}, body: JSON.stringify({email: $('#le-mail').value, password: $('#le-pw').value})});
    const j = await r.json().catch(() => ({})); $('#le-pw').value = ''; if (!r.ok) throw new Error(j.error || 'Sign in failed'); store.set(j); render();
  } catch (err) {$('#le').innerHTML = `<div class="err">${esc(err.message)}</div>`;}};
}
async function render() {
  if (!store.get()) {
    // The cookie may still be valid (e.g. new tab): ask the server instead of trusting the browser.
    const r = await fetch('/api/admin/me', {credentials: 'same-origin', headers: {'X-Morphly-Admin': '1'}}).catch(() => null);
    if (!r?.ok) return loginView();
    store.set(await r.json());
  }
  go(location.hash.slice(1) || 'overview');
}
window.addEventListener('hashchange', () => store.get() && go(location.hash.slice(1)));

render();
})();
