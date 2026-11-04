'use strict';
// Screenshot gallery: lists .minecraft/screenshots and serves the pictures to the page through the "fzshot:"
// scheme (fzshot://img/<file> = full size, fzshot://thumb/<file> = small JPEG, cached on disk).
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app, protocol, nativeImage, clipboard, shell } = require('electron');

/** Must run before the app is ready. */
function registerScheme() {
  protocol.registerSchemesAsPrivileged([{ scheme: 'fzshot', privileges: { standard: true, secure: true, supportFetchAPI: true } }]);
}

let dirOf = () => '';

/** Only a plain file name inside the screenshots folder, never a path. */
function fileIn(name) {
  const base = path.basename(decodeURIComponent(String(name || '')));
  if (!/\.(png|jpe?g)$/i.test(base)) return null;
  const full = path.join(dirOf(), base);
  return fs.existsSync(full) ? full : null;
}

function thumbDir() { return path.join(app.getPath('userData'), 'thumbs'); }

function thumbnail(full) {
  const st = fs.statSync(full);
  const key = crypto.createHash('sha1').update(`${full}|${st.size}|${st.mtimeMs}`).digest('hex');
  const cached = path.join(thumbDir(), key + '.jpg');
  if (fs.existsSync(cached)) return fs.readFileSync(cached);
  const img = nativeImage.createFromPath(full);
  const buf = img.resize({ width: 360, quality: 'good' }).toJPEG(82);
  fs.mkdirSync(thumbDir(), { recursive: true });
  fs.writeFileSync(cached, buf);
  return buf;
}

/** Call once the app is ready. screenshotsDir() gives the current folder (it follows the game folder setting). */
function handle(screenshotsDir) {
  dirOf = screenshotsDir;
  protocol.handle('fzshot', req => {
    try {
      const u = new URL(req.url);
      const full = fileIn(u.pathname.slice(1));
      if (!full) return new Response('not found', { status: 404 });
      if (u.host === 'thumb') return new Response(thumbnail(full), { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'max-age=31536000' } });
      const type = /\.png$/i.test(full) ? 'image/png' : 'image/jpeg';
      return new Response(fs.readFileSync(full), { headers: { 'Content-Type': type } });
    } catch {
      return new Response('error', { status: 500 });
    }
  });
}

/** Newest first: [{ file, time, size }] */
function list() {
  const dir = dirOf();
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter(f => /\.(png|jpe?g)$/i.test(f))
    .map(f => {
      const st = fs.statSync(path.join(dir, f));
      return { file: f, time: st.mtimeMs, size: st.size };
    })
    .sort((a, b) => b.time - a.time);
}

function copy(name) {
  const full = fileIn(name);
  if (!full) throw new Error('ファイルが見つかりません');
  clipboard.writeImage(nativeImage.createFromPath(full));
}

function reveal(name) {
  const full = fileIn(name);
  if (full) shell.showItemInFolder(full);
}

/** To the Recycle Bin (can be restored). */
async function remove(name) {
  const full = fileIn(name);
  if (!full) throw new Error('ファイルが見つかりません');
  await shell.trashItem(full);
}

module.exports = { registerScheme, handle, list, copy, reveal, remove };
