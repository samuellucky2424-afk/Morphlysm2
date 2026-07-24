import { createServiceClient, requireUser } from './_shared/supabase.js';
import {
  applyHttpHeaders,
  cleanString,
  handleOptions,
  publicError,
  requireMethod,
} from './_shared/http.js';

function routeFor(req) {
  const url = new URL(req.url, `https://${req.headers.host || 'localhost'}`);
  return url.searchParams.get('route') || url.pathname.replace(/^\/api\//, '');
}

async function publicConfig(res) {
  const service = createServiceClient();
  const { data, error } = await service
    .from('app_setting_sm')
    .select('key,value')
    .eq('is_public', true)
    .in('key', ['global_config', 'streaming_availability', 'referral_program']);
  if (error) throw error;
  const values = Object.fromEntries((data || []).map((row) => [row.key, row.value]));
  return res.status(200).json({
    notification: values.global_config?.notification || '',
    streamingEnabled: values.streaming_availability?.enabled !== false,
    referralEnabled: values.referral_program?.enabled !== false,
    signupCredits: Number(values.referral_program?.signup_credits ?? 50),
    referralPurchaseReward: Number(values.referral_program?.purchase_reward ?? 250),
  });
}

async function decartToken(req, res) {
  if (!requireMethod(req, res, 'POST')) return;
  const { service, appUser } = await requireUser(req);

  const [{ data: availability, error: availabilityError }, { data: wallet, error: walletError }] =
    await Promise.all([
      service.from('app_setting_sm').select('value').eq('key', 'streaming_availability').single(),
      service.from('wallet_sm').select('balance').eq('user_id', appUser.id).single(),
    ]);
  if (availabilityError) throw availabilityError;
  if (walletError) throw walletError;
  if (availability?.value?.enabled === false) {
    return res.status(503).json({ error: 'Live streaming is currently disabled.' });
  }
  if (Number(wallet?.balance || 0) <= 0) {
    return res.status(402).json({ error: 'Insufficient credits' });
  }

  let apiKey = String(process.env.DECART_API_KEY || process.env.LIVE_ENGINE_API_KEY || '').trim();
  if (!apiKey) {
    const { data, error } = await service.rpc('next_engine_key_for_service_sm');
    if (!error) {
      const result = Array.isArray(data) ? data[0] : data;
      apiKey = String(result?.api_key || '').trim();
    } else if (!String(error.message || '').includes('Could not find the function')) {
      throw error;
    }
  }
  if (!apiKey) return res.status(503).json({ error: 'Live engine key is not configured.' });

  return res.status(200).json({
    apiKey,
    model: cleanString(req.body?.model, 80) || 'lucy-2.1',
    maxSessionDuration: 900,
  });
}

async function transform(req, res) {
  if (!requireMethod(req, res, 'POST')) return;
  const { client } = await requireUser(req);
  const body = req.body || {};
  const deviceId = cleanString(body.deviceId, 120);
  if (!deviceId) return res.status(400).json({ error: 'deviceId is required' });

  const { data, error } = await client.rpc('create_stream_session_sm', {
    p_device_id: deviceId,
    p_mode: cleanString(body.mode, 20) || 'style',
    p_prompt: cleanString(
      body.prompt,
      2000,
    ) || 'Transform the live camera into a clean high-definition cinematic style.',
    p_preset: cleanString(body.preset || body.presetLabel, 120) || null,
    p_enhance: body.enhance === true,
    p_quality: cleanString(body.quality, 20) || 'medium',
    p_fps: Math.min(Math.max(Number.parseInt(body.fps, 10) || 20, 1), 60),
    p_model: cleanString(body.model, 80) || 'lucy-2.1',
    p_has_face_image: body.hasFaceImage === true || Boolean(body.faceImage),
    p_face_image_mime_type: cleanString(body.faceImageMimeType, 100) || null,
  });
  if (error) throw error;
  const row = Array.isArray(data) ? data[0] : data;
  return res.status(200).json({
    success: true,
    sessionId: row.id,
    externalSessionId: row.external_session_id,
    status: row.status,
    mode: row.mode,
    prompt: row.prompt,
    enhance: row.enhance,
    quality: row.quality,
    fps: row.fps,
    model: row.model,
  });
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, POST, OPTIONS');
  if (handleOptions(req, res)) return;
  try {
    const route = routeFor(req);
    if (route === 'decart-token') return await decartToken(req, res);
    if (route === 'transform') return await transform(req, res);
    if (!requireMethod(req, res, 'GET')) return;
    return await publicConfig(res);
  } catch (error) {
    console.error('Config API failed:', error);
    return res.status(error.status || 500).json({ error: publicError(error) });
  }
}
