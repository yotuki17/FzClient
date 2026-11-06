'use strict';
// Recording (the Alt+X overlay, see capture.js). This prepares the two programs it needs:
//  - ffmpeg: from BtbN/FFmpeg-Builds on GitHub (the LGPL build of the newest stable version), downloaded once.
//  - FzRecorder.exe: our helper that feeds ffmpeg the game's sound only (src/tools/FzRecorder.cs). It is built on
//    this PC with the C# compiler that comes with Windows, so no program of ours is downloaded; it is built again
//    whenever its source changes with a launcher update.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { app, net } = require('electron');
const AdmZip = require('adm-zip');

const REPO = 'BtbN/FFmpeg-Builds';
const ASSET = /^ffmpeg-n(\d+)\.(\d+)-latest-win64-lgpl-shared-\d+\.\d+\.zip$/;
const SOURCE = path.join(__dirname, '..', 'tools', 'FzRecorder.cs');

function dir() { return path.join(app.getPath('userData'), 'tools', 'recorder'); }
const ffmpegExe = () => path.join(dir(), 'ffmpeg.exe');
const helperExe = () => path.join(dir(), 'FzRecorder.exe');
const helperStamp = () => path.join(dir(), 'FzRecorder.sha1');

function sourceHash() {
  return crypto.createHash('sha1').update(fs.readFileSync(SOURCE)).digest('hex');
}

function helperFresh() {
  try { return fs.existsSync(helperExe()) && fs.readFileSync(helperStamp(), 'utf8').trim() === sourceHash(); } catch { return false; }
}

function status() {
  let version = '';
  try { version = fs.readFileSync(path.join(dir(), 'ffmpeg-version.txt'), 'utf8').trim(); } catch { /* not there */ }
  const ffmpeg = fs.existsSync(ffmpegExe());
  return { ready: ffmpeg && fs.existsSync(helperExe()), ffmpeg, version, busy: !!busy };
}

// ---------------------------------------------------------------- ffmpeg

/** The newest stable LGPL build: { name, url, size }. */
async function findFfmpeg() {
  const res = await net.fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { 'User-Agent': 'FzClient-Launcher', Accept: 'application/vnd.github+json' },
  });
  if (!res.ok) throw new Error(`GitHub に接続できませんでした（HTTP ${res.status}）`);
  const rel = await res.json();
  const builds = (rel.assets || []).map(a => ({ a, m: ASSET.exec(a.name) })).filter(x => x.m)
    .sort((x, y) => (y.m[1] - x.m[1]) || (y.m[2] - x.m[2]));
  if (!builds.length) throw new Error('ffmpeg のダウンロードが見つかりませんでした');
  const a = builds[0].a;
  return { name: a.name, url: a.browser_download_url, size: a.size };
}

/** Downloads with progress (percent) and returns the whole file. */
async function fetchWithProgress(url, size, onPercent) {
  const res = await net.fetch(url, { headers: { 'User-Agent': 'FzClient-Launcher' } });
  if (!res.ok) throw new Error(`ダウンロードに失敗しました（HTTP ${res.status}）`);
  const total = Number(res.headers.get('content-length')) || size || 0;
  const reader = res.body.getReader();
  const parts = [];
  let got = 0, shown = -1;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(Buffer.from(value));
    got += value.length;
    const p = total ? Math.floor(got * 100 / total) : 0;
    if (p !== shown) { shown = p; onPercent(p); }
  }
  return Buffer.concat(parts);
}

async function installFfmpeg(progress) {
  progress({ stage: 'find' });
  const build = await findFfmpeg();
  const zip = new AdmZip(await fetchWithProgress(build.url, build.size, p => progress({ stage: 'download', percent: p })));
  progress({ stage: 'extract' });
  fs.mkdirSync(dir(), { recursive: true });
  let found = false;
  for (const e of zip.getEntries()) {
    // ffmpeg.exe and the libraries it needs, from the bin folder (not ffplay / ffprobe)
    const m = /\/bin\/([^/]+)$/.exec(e.entryName);
    if (!m || e.isDirectory || /^(ffplay|ffprobe)\.exe$/i.test(m[1])) continue;
    fs.writeFileSync(path.join(dir(), m[1]), e.getData());
    if (/^ffmpeg\.exe$/i.test(m[1])) found = true;
  }
  if (!found) throw new Error('ffmpeg.exe が見つかりませんでした');
  fs.writeFileSync(path.join(dir(), 'ffmpeg-version.txt'), build.name.replace(/\.zip$/, ''));
}

// ---------------------------------------------------------------- the sound helper

function cscExe() {
  return path.join(process.env.WINDIR || 'C:\\Windows', 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe');
}

/** Builds FzRecorder.exe from the source shipped with the launcher. */
function buildHelper(log) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(cscExe())) { reject(new Error('Windows の C# コンパイラ（.NET Framework 4）が見つかりません')); return; }
    fs.mkdirSync(dir(), { recursive: true });
    // the compiler can't read inside the launcher's app.asar: give it a real file
    const src = path.join(dir(), 'FzRecorder.cs');
    fs.writeFileSync(src, fs.readFileSync(SOURCE));
    const out = helperExe() + '.new';
    const p = spawn(cscExe(), ['/nologo', '/optimize+', '/platform:x64', '/target:exe', '/out:' + out, src], { windowsHide: true });
    let text = '';
    p.stdout.on('data', d => { text += d; });
    p.stderr.on('data', d => { text += d; });
    p.on('error', reject);
    p.on('close', code => {
      if (code !== 0 || !fs.existsSync(out)) {
        if (log) log('[recorder] csc: ' + text.trim().slice(0, 2000));
        reject(new Error('録画の補助プログラムを作れませんでした'));
        return;
      }
      try {
        fs.renameSync(out, helperExe());   // fails while a game is recording with the old one: keep that one then
        fs.writeFileSync(helperStamp(), sourceHash());
      } catch (e) {
        try { fs.unlinkSync(out); } catch { /* fine */ }
      }
      resolve();
    });
  });
}

// ---------------------------------------------------------------- what the launcher calls

let busy = null;

/** Downloads ffmpeg (once) and builds the helper. progress({ stage, percent }). */
function prepare(progress, log) {
  if (busy) return busy;
  busy = (async () => {
    try {
      if (!fs.existsSync(ffmpegExe())) await installFfmpeg(progress);
      if (!helperFresh()) {
        progress({ stage: 'build' });
        await buildHelper(log);
      }
      return status();
    } finally {
      busy = null;
    }
  })();
  return busy;
}

/**
 * At launch: { ffmpeg, helper } when recording is prepared, else null. A helper that is older than its source
 * (launcher update) is built again first; if that fails the old one is used.
 */
async function forLaunch(log) {
  if (!fs.existsSync(ffmpegExe()) || !fs.existsSync(helperExe())) return null;
  if (!helperFresh()) {
    try { await buildHelper(log); } catch (e) { if (log) log('[recorder] ' + e.message); }
  }
  return { ffmpeg: ffmpegExe(), helper: helperExe() };
}

/** Removes ffmpeg and the helper again (the recordings stay). */
function remove() {
  fs.rmSync(dir(), { recursive: true, force: true });
}

/** ffmpeg, when it is there (the gallery makes pictures of clips with it). */
function ffmpegPath() { return fs.existsSync(ffmpegExe()) ? ffmpegExe() : null; }

module.exports = { status, prepare, forLaunch, remove, ffmpegPath };
