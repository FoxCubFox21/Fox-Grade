// Tiny helper so shell scripts don't need fragile inline `node -e` (which broke on quoting).
import { spawnSync } from 'node:child_process';
const [, , what, mcv] = process.argv;
const get = (u) => { try { return JSON.parse(spawnSync('curl', ['-sL', u], { encoding: 'utf8' }).stdout || 'null'); } catch { return null; } };
if (what === 'loader') { const j = get(`https://meta.fabricmc.net/v2/versions/loader/${mcv}`); console.log(j?.[0]?.loader?.version || ''); }
else if (what === 'installer') { const j = get('https://meta.fabricmc.net/v2/versions/installer'); console.log(j?.[0]?.version || ''); }
else if (what === 'versions') { const j = get('https://meta.fabricmc.net/v2/versions/game'); console.log((j || []).slice(0, 8).map(v => v.version).join(' ')); }
