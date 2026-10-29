// Latest Android build info for the in-app update prompt.
// Source of truth: the repo's latest GitHub release (published by .github/workflows/android-release.yml),
// cached ~5 min; fallback: Firestore app_setting_sm/app_version {value:{latestVersion, versionCode, minVersionCode, notes}}.
import {firebaseRuntime} from './firebase-runtime.js';

const REPO = process.env.GITHUB_RELEASE_REPO || 'samuellucky2424-afk/Morphlysm2';
const DOWNLOAD_URL = process.env.APP_DOWNLOAD_URL || 'https://luckyweb.cloud';
const TTL_MS = 5 * 60 * 1000;
let cache = {at: 0, value: null};

async function fromGithub() {
  const headers = {Accept: 'application/vnd.github+json', 'User-Agent': 'morphly-server'};
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {headers, signal: AbortSignal.timeout(8000)});
  if (!r.ok) throw new Error(`GitHub ${r.status}`);
  const rel = await r.json();
  const body = String(rel.body || '');
  const code = Number((body.match(/versionCode:\s*(\d+)/i) || [])[1]);
  const min = Number((body.match(/minVersionCode:\s*(\d+)/i) || [])[1]);
  if (!code) throw new Error('Release has no versionCode');
  const notes = body.split('\n').filter(l => !/^\s*(min)?versionCode:/i.test(l)).join('\n').trim();
  return {latestVersion: String(rel.tag_name || '').replace(/^v/, ''), versionCode: code,
    ...(min ? {minVersionCode: min} : {}), notes, publishedAt: rel.published_at, source: 'github'};
}

async function fromFirestore() {
  const doc = await firebaseRuntime().db.collection('app_setting_sm').doc('app_version').get();
  const v = doc.exists ? (doc.data().value || {}) : {};
  if (!v.versionCode) return null;
  return {latestVersion: String(v.latestVersion || ''), versionCode: Number(v.versionCode),
    ...(v.minVersionCode ? {minVersionCode: Number(v.minVersionCode)} : {}), notes: String(v.notes || ''), source: 'firestore'};
}

export async function appVersionRoute(req, res) {
  res.setHeader('Cache-Control', 'public, max-age=60');
  if (!cache.value || Date.now() - cache.at > TTL_MS) {
    let value = null;
    try { value = await fromGithub(); } catch (e) { console.warn('app-version github failed:', e.message); }
    if (!value) { try { value = await fromFirestore(); } catch (e) { console.warn('app-version firestore failed:', e.message); } }
    if (value) cache = {at: Date.now(), value};
  }
  if (!cache.value) return res.status(503).json({error: 'Version info unavailable', downloadUrl: DOWNLOAD_URL});
  return res.status(200).json({...cache.value, downloadUrl: DOWNLOAD_URL});
}
