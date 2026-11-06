'use strict';
// The gallery: screenshots (<game>/screenshots) and recorded clips (<game>/recordings, .mp4), for the launcher's
// page and the Alt+X overlay. Files are served through the "fzshot:" scheme:
//   fzshot://img/<file>    a screenshot, full size        fzshot://thumb/<file>   its small JPEG (cached on disk)
//   fzshot://clip/<file>   a clip (with ranges: seeking)  fzshot://cthumb/<file>  a picture from it (ffmpeg, cached)
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');
const { spawn } = require('child_process');
const { app, protocol, nativeImage, clipboard, shell } = require('electron');

/** Must run before the app is ready. */
function registerScheme() {
  protocol.registerSchemesAsPrivileged([{ scheme: 'fzshot', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }]);
}

let shotsDir = () => '', clipsDir = () => '', ffmpeg = () => null;

const SHOT = /\.(png|jpe?g)$/i, CLIP = /\.mp4$/i;

/** Only a plain file name inside its folder, never a path. */
function fileIn(name, kind) {
  const base = path.basename(decodeURIComponent(String(name || '')));
  const clip = kind === 'clip';
  if (!(clip ? CLIP : SHOT).test(base)) return null;
  const full = path.join(clip ? clipsDir() : shotsDir(), base);
  return fs.existsSync(full) ? full : null;
}

function thumbDir() { return path.join(app.getPath('userData'), 'thumbs'); }

function thumbKey(full) {
  const st = fs.statSync(full);
  return crypto.createHash('sha1').update(`${full}|${st.size}|${st.mtimeMs}`).digest('hex');
}

function thumbnail(full) {
  const cached = path.join(thumbDir(), thumbKey(full) + '.jpg');
  if (fs.existsSync(cached)) return fs.readFileSync(cached);
  const img = nativeImage.createFromPath(full);
  const buf = img.resize({ width: 360, quality: 'good' }).toJPEG(82);
  fs.mkdirSync(thumbDir(), { recursive: true });
  fs.writeFileSync(cached, buf);
  return buf;
}

/** A picture from 1 s into the clip (or its first one), made once with ffmpeg; null without ffmpeg. */
function clipThumbnail(full) {
  const cached = path.join(thumbDir(), thumbKey(full) + '.v.jpg');
  if (fs.existsSync(cached)) return Promise.resolve(fs.readFileSync(cached));
  const exe = ffmpeg();
  if (!exe || !fs.existsSync(exe)) return Promise.resolve(null);
  fs.mkdirSync(thumbDir(), { recursive: true });
  const run = seek => new Promise(resolve => {
    const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-y', ...(seek ? ['-ss', '1'] : []), '-i', full,
      '-frames:v', '1', '-vf', 'scale=360:-2', '-q:v', '4', cached], { windowsHide: true, stdio: 'ignore' });
    const kill = setTimeout(() => p.kill(), 15000);
    p.on('error', () => { clearTimeout(kill); resolve(false); });
    p.on('close', code => { clearTimeout(kill); resolve(code === 0 && fs.existsSync(cached)); });
  });
  return run(true).then(ok => ok || run(false)).then(ok => (ok ? fs.readFileSync(cached) : null));
}

/** A file as a response, with byte ranges (a video player asks for parts of it to seek). */
function serveFile(full, type, req) {
  const size = fs.statSync(full).size;
  const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.get('range') || '');
  if (m && (m[1] || m[2])) {
    let start = m[1] ? Number(m[1]) : Math.max(0, size - Number(m[2]));
    let end = m[1] && m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
    if (start >= size || start > end) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
    return new Response(Readable.toWeb(fs.createReadStream(full, { start, end })), {
      status: 206,
      headers: { 'Content-Type': type, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': String(end - start + 1), 'Accept-Ranges': 'bytes' },
    });
  }
  return new Response(Readable.toWeb(fs.createReadStream(full)), {
    headers: { 'Content-Type': type, 'Content-Length': String(size), 'Accept-Ranges': 'bytes' },
  });
}

/**
 * Call once the app is ready. The folders follow the game folder setting; ffmpegPath() is the recorder's ffmpeg
 * (for clip pictures), or null.
 */
function handle(screenshots, recordings, ffmpegPath) {
  shotsDir = screenshots;
  clipsDir = recordings;
  ffmpeg = ffmpegPath || (() => null);
  protocol.handle('fzshot', async req => {
    try {
      const u = new URL(req.url);
      const clip = u.host === 'clip' || u.host === 'cthumb';
      const full = fileIn(u.pathname.slice(1), clip ? 'clip' : 'shot');
      if (!full) return new Response('not found', { status: 404 });
      if (u.host === 'thumb') return new Response(thumbnail(full), { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'max-age=31536000' } });
      if (u.host === 'cthumb') {
        const buf = await clipThumbnail(full);
        return buf ? new Response(buf, { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'max-age=31536000' } }) : new Response('none', { status: 404 });
      }
      if (u.host === 'clip') return serveFile(full, 'video/mp4', req);
      return serveFile(full, /\.png$/i.test(full) ? 'image/png' : 'image/jpeg', req);
    } catch {
      return new Response('error', { status: 500 });
    }
  });
}

function filesOf(dir, re, kind) {
  if (!dir || !fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => re.test(f)).map(f => {
    try {
      const st = fs.statSync(path.join(dir, f));
      return st.isFile() ? { file: f, kind, time: st.mtimeMs, size: st.size } : null;
    } catch { return null; }
  }).filter(Boolean);
}

/** Newest first: [{ file, kind: 'shot' | 'clip', time, size }] */
function list() {
  return [...filesOf(shotsDir(), SHOT, 'shot'), ...filesOf(clipsDir(), CLIP, 'clip')].sort((a, b) => b.time - a.time);
}

function copy(name) {
  const full = fileIn(name, 'shot');
  if (!full) throw new Error('ファイルが見つかりません');
  clipboard.writeImage(nativeImage.createFromPath(full));
}

function reveal(name, kind) {
  const full = fileIn(name, kind);
  if (full) shell.showItemInFolder(full);
}

/** To the Recycle Bin (can be restored). */
async function remove(name, kind) {
  const full = fileIn(name, kind);
  if (!full) throw new Error('ファイルが見つかりません');
  await shell.trashItem(full);
}

module.exports = { registerScheme, handle, list, copy, reveal, remove };
