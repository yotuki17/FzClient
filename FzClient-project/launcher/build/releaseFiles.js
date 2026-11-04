// After `npm run dist`: puts the mod files for the GitHub release next to the installer.
//   dist/fzclient.jar   the mod (same file as resources/fzclient.jar)
//   dist/fzclient.json  { version, sha1 } - the launcher compares sha1 to decide whether to download
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const AdmZip = require('adm-zip');

const root = path.join(__dirname, '..');
const jar = path.join(root, 'resources', 'fzclient.jar');
const dist = path.join(root, 'dist');
const buf = fs.readFileSync(jar);
let version = 'unknown';
try {
  const info = JSON.parse(new AdmZip(jar).readAsText('mcmod.info'));
  version = info[0].version;
} catch { /* keep "unknown" */ }
const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '');
const manifest = { version: `${version}+${stamp}`, sha1: crypto.createHash('sha1').update(buf).digest('hex') };
fs.mkdirSync(dist, { recursive: true });
fs.copyFileSync(jar, path.join(dist, 'fzclient.jar'));
fs.writeFileSync(path.join(dist, 'fzclient.json'), JSON.stringify(manifest, null, 2));
console.log('fzclient.json:', manifest);
