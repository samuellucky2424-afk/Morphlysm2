// Builds the static output: only public pages plus the admin UI under its private path.
// Everything else in the repo (server/, api sources, docs) is never served as a static file.
import {cpSync, mkdirSync, rmSync} from 'node:fs';
rmSync('public', {recursive: true, force: true});
mkdirSync('public/lucky/privatelife/logs', {recursive: true});
for (const f of ['index.html', 'payment-complete.html', 'reset-password.html']) cpSync(f, `public/${f}`);
cpSync('server/admin-ui', 'public/lucky/privatelife/logs', {recursive: true});
console.log('public/ built');
