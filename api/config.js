import { requireUser } from '../server/shared/firebase.js';
import {hit, tooMany} from '../server/shared/rate-limit.js';
import {androidRealtime} from '../server/shared/firebase-realtime.js';
import {logErrorRoute, recordError} from '../server/shared/error-log.js';
import {appVersionRoute} from '../server/shared/app-version.js';
import {MOBILE_ROUTES, publicConfig as mobilePublicConfig} from '../server/shared/mobile-routes.js';
import {applyHttpHeaders, handleOptions, publicError, requireMethod} from '../server/shared/http.js';

function routeFor(req) {
  const url = new URL(req.url, `https://${req.headers.host || 'localhost'}`);
  return url.searchParams.get('route') || url.pathname.replace(/^\/api\//, '');
}

export default async function handler(req, res) {
  applyHttpHeaders(req, res, 'GET, POST, OPTIONS');
  if (handleOptions(req, res)) return;
  try {
    const route = routeFor(req);
    if (route === 'log-error') return await logErrorRoute(req, res);
    if (route === 'app-version') return await appVersionRoute(req, res);
    if (MOBILE_ROUTES[route]) return await MOBILE_ROUTES[route](req, res);
    if (['start-session','heartbeat','end-session'].includes(route)) {
      if (!requireMethod(req,res,'POST')) return;
      const context = await requireUser(req);
      const realtime = androidRealtime(context.service.db);
      if (route === 'start-session') {
        const limit = await hit(context.service.db, `start:${context.appUser.id}`, {limit: 20, windowMs: 600000});
        if (!limit.allowed) throw tooMany(limit.retryAfterSeconds, 'Too many stream starts. Try again shortly.');
      }
      return res.status(200).json(route === 'start-session'
        ? await realtime.start(context.appUser,req.body||{},process.env.XMAX_API_KEY)
        : await realtime.meter(context.appUser,req.body||{},route==='end-session'));
    }
    if (route === 'decart-token' || route === 'transform') return res.status(410).json({error:'Update Morphly to use XMax.'});
    if (!requireMethod(req, res, 'GET')) return;
    return await mobilePublicConfig(req, res);
  } catch (error) {
    const message = publicError(error);
    if (!error.status || error.status >= 500) await recordError({source: 'server', message: error.message, stack: error.stack, context: req.url, ref: error.ref});
    return res.status(error.status || 500).json({ error: message });
  }
}
