// Permanent credentials stay on the server; clients receive only bounded, single-session temporary keys.
const XMAX_BASE = () => String(process.env.XMAX_API_BASE || 'https://api.xmax.cloud').replace(/\/+$/, '');
export const KEY_GRACE_SECONDS = 30;

export async function createXmaxAndroidKey({apiKey, maxSeconds, fetchImpl = fetch}) {
  if (!apiKey) return {error: {error: 'XMAX_NOT_CONFIGURED', details: 'XMax is not configured.'}};
  const sessionLimit = Math.min(1800, Math.max(1, Math.floor(Number(maxSeconds) || 0)));
  const request = extra => fetchImpl(`${XMAX_BASE()}/open/api/v1/temporary-api-key`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'X-Api-Key': apiKey},
    // Short expiry (paid-for seconds + small grace), points capped, one concurrent realtime session.
    body: JSON.stringify({expireSeconds: sessionLimit + KEY_GRACE_SECONDS, pointsLimit: sessionLimit, ...extra}),
    signal: AbortSignal.timeout(15000),
  });
  try {
    let response = await request({temporaryApiKeySessionLimit: 1});
    let body = await response.json().catch(() => null);
    // Older API hosts may reject the session-limit field; the key is still bounded by time and points.
    if (response.status === 400 || (body?.success === false && Number(body?.code) === 400)) {
      response = await request({}); body = await response.json().catch(() => null);
    }
    const token = body?.data?.temporaryApiKey;
    if (!response.ok || body?.success === false || typeof token !== 'string' || !token || token === apiKey) {
      return {error: {error: 'XMAX_SESSION_FAILED', providerStatus: response.status,
        details: 'XMax could not issue a temporary session. Check its server configuration and provider balance.'}};
    }
    return {token, sessionLimit, expiresAt: body.data.expireTimestamp ?? null};
  } catch {
    return {error: {error: 'XMAX_SESSION_FAILED', details: 'XMax could not be reached. Try again shortly.'}};
  }
}

// Deactivates a temporary key. Returns {ok, consumed} where consumed is XMax's own usage counter (if reported).
export async function revokeXmaxKey({apiKey, token, fetchImpl = fetch}) {
  if (!apiKey || !token) return {ok: false, consumed: null};
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const response = await fetchImpl(`${XMAX_BASE()}/open/api/v1/temporary-api-key/${encodeURIComponent(token)}/inactive`, {
        method: 'PUT', headers: {'X-Api-Key': apiKey}, signal: AbortSignal.timeout(8000)});
      const body = await response.json().catch(() => null);
      const status = String(body?.data?.status || '').toLowerCase();
      if (response.ok && body?.success !== false && ['inactive', 'timeout', ''].includes(status)) {
        const consumed = Number(body?.data?.consumedCredit ?? body?.data?.consumedPoints);
        return {ok: true, consumed: Number.isFinite(consumed) ? consumed : null};
      }
      // Already expired/unknown keys cannot be used either.
      if ([46018, 46022].includes(Number(body?.code))) return {ok: true, consumed: null};
    } catch { /* retry once */ }
  }
  return {ok: false, consumed: null};
}
