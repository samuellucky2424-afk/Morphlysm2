// Vidu S2-Editing realtime ("Pro"). The permanent VIDU_API_KEY stays on the server; the app receives only the
// short-lived client_secret + RTC join credentials Vidu scopes to this one live session.
const ALLOWED_HOSTS = ['api.vidu.com', 'api.vidu.cn'];

export function viduBaseUrl(env = process.env) {
  try {
    const url = new URL(String(env.VIDU_API_BASE_URL || 'https://api.vidu.com').trim());
    if (url.protocol !== 'https:' || !ALLOWED_HOSTS.includes(url.hostname) || url.username || url.password
      || url.port || url.pathname !== '/' || url.search || url.hash) return null;
    return url.origin;
  } catch { return null; }
}

const authHeader = key => (/^Token\s/i.test(key) ? key : `Token ${key}`);
const fail = (error, details, extra = {}) => ({error: {error, details, ...extra}});

export async function createViduSession({apiKey, maxSeconds, imageUrl, fetchImpl = fetch, env = process.env}) {
  if (!apiKey) return fail('VIDU_NOT_CONFIGURED', 'Pro is not configured yet.');
  const base = viduBaseUrl(env);
  if (!base) return fail('VIDU_NOT_CONFIGURED', 'Pro is not configured correctly.');
  const image = typeof imageUrl === 'string' ? imageUrl.trim() : '';
  if (!/^(https:\/\/|data:image\/(png|jpeg|webp);base64,)/i.test(image)) return fail('INVALID_REFERENCE_IMAGE', 'Pro needs a reference photo (JPG, PNG or WEBP).');
  if (image.length > 3_500_000) return fail('INVALID_REFERENCE_IMAGE', 'The reference photo is too large. Use one under 2 MB.');
  const sessionLimit = Math.max(1, Math.min(1800, Math.floor(Number(maxSeconds) || 1)));
  const timeout = Math.min(60000, Math.max(5000, Number(env.VIDU_SESSION_REQUEST_TIMEOUT_MS) || 15000));
  for (let attempt = 1; attempt <= 2; attempt++) {
    let response, data;
    try {
      response = await fetchImpl(`${base}/live/s_editing/realtime`, {method: 'POST',
        headers: {'Content-Type': 'application/json', Authorization: authHeader(apiKey)},
        body: JSON.stringify({image_url: image, editing_type: 'subject_replacement'}),
        signal: AbortSignal.timeout(timeout)});
      data = await response.json().catch(() => null);
    } catch { return fail('VIDU_SESSION_FAILED', 'Pro could not be reached. Try again shortly.'); }
    if (!response.ok) {
      if (response.status === 429 && attempt < 2) { await new Promise(r => setTimeout(r, 600)); continue; }
      const code = data?.code || data?.error_code || null, message = String(data?.message || data?.error || '');
      if (/submit is illegal|audit|policy violation/i.test(message) || /Audit|PolicyViolation/i.test(String(code || '')))
        return fail('REFERENCE_IMAGE_REJECTED', 'This photo was rejected by the Pro safety check. Use one clear, well-lit adult face.', {providerStatus: response.status});
      return fail('VIDU_SESSION_FAILED', response.status === 429 ? 'Pro is busy right now. Wait a moment and try again.'
        : 'Pro could not start this session. Try again or contact support.', {providerStatus: response.status, providerCode: code});
    }
    const d = data?.data && !data?.client_secret ? data.data : data;
    const liveId = String(d?.live?.id || d?.live_id || ''), token = String(d?.client_secret || '');
    const renderUid = String(d?.render_uid || ''), rtc = d?.rtc || null;
    if (!token || token === apiKey || !liveId || !renderUid || typeof rtc?.token !== 'string' || !rtc.token || rtc.token === apiKey || !rtc.user_id)
      return fail('VIDU_SESSION_FAILED', 'Pro could not prepare a secure video session. Try again.');
    const expiries = [Number(rtc.token_expire_at) * 1000, Date.parse(d?.expires_at || '')].filter(v => Number.isFinite(v) && v > 0);
    const expiresAt = expiries.length ? Math.min(...expiries) : Date.now() + sessionLimit * 1000;
    return {token, sessionLimit: Math.min(sessionLimit, Number(d?.live?.live_duration) || sessionLimit), expiresAt,
      provider: {liveId, renderUid, rtc: {appId: rtc.app_id ?? rtc.appid ?? null, channel: rtc.channel_id ?? rtc.channel ?? null,
        userId: rtc.user_id, token: rtc.token, gslb: rtc.gslb ?? null, nonce: rtc.nonce ?? null, timestamp: rtc.timestamp ?? null,
        expireAt: rtc.token_expire_at ?? null, raw: rtc}, signalingUrl: d?.ws_url || d?.signaling_url || null, apiBase: base}};
  }
  return fail('VIDU_SESSION_FAILED', 'Pro could not start this session.');
}

// Vidu has no server-side revoke for a client_secret; the live ends on client hangup or its own live_duration.
export async function revokeViduSession() { return {ok: false, consumed: null}; }
