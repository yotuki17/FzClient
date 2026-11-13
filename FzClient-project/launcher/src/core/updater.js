'use strict';
// Automatic updates, both served from the GitHub Releases of one public repository (see RELEASE.md):
//  - the launcher itself: electron-updater reads latest.yml from the newest release, downloads the new installer
//    in the background and installs it when you press "再起動して更新" (or when the launcher is closed).
//  - the FzClient mod: fzclient.json ({ version, sha1 }) + fzclient.jar from the newest release. The jar is kept in
//    the launcher's data folder and used instead of the bundled one, so a mod update needs no new installer.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app, net, shell } = require('electron');
/** A Mac: the launcher is not signed by Apple, so it cannot replace itself; a new version is only announced and
 *  the button opens the download (the mod still updates itself, below). */
const MAC = process.platform === 'darwin';
const MAC_DMG = 'https://github.com/yotuki17/FzClient/releases/latest/download/FzClient-Launcher-mac-arm64.dmg';

/** owner/repo whose Releases hold the updates. Keep in sync with "publish" in package.json. */
const REPO = 'yotuki17/FzClient';
const LATEST = `https://github.com/${REPO}/releases/latest/download/`;
const UA = 'FzClientLauncher/' + app.getVersion();

// ---------------------------------------------------------------- launcher

let autoUpdater = null;
let launcherReady = null;   // version string once a new launcher is downloaded

function startLauncherUpdates(send, log) {
  if (!app.isPackaged) return;
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch (e) {
    log('[update] electron-updater unavailable: ' + e.message);
    return;
  }
  autoUpdater.autoDownload = !MAC;
  autoUpdater.autoInstallOnAppQuit = !MAC;
  autoUpdater.logger = { info: m => log('[update] ' + m), warn: m => log('[update] ' + m), error: m => log('[update] ' + m), debug: () => {} };
  autoUpdater.on('update-available', i => {
    if (MAC) {
      launcherReady = i.version;
      send('update:launcher', { state: 'ready', version: i.version, manual: true });
      return;
    }
    send('update:launcher', { state: 'downloading', version: i.version, percent: 0 });
  });
  autoUpdater.on('download-progress', p => send('update:launcher', { state: 'downloading', percent: Math.round(p.percent || 0) }));
  autoUpdater.on('update-downloaded', i => {
    launcherReady = i.version;
    send('update:launcher', { state: 'ready', version: i.version });
  });
  autoUpdater.on('error', e => log('[update] launcher: ' + (e && e.message || e)));
  const check = () => autoUpdater.checkForUpdates().catch(e => log('[update] check failed: ' + e.message));
  setTimeout(check, 3000);
  setInterval(check, 3 * 60 * 60 * 1000);   // long sessions still find new versions
}

function launcherStatus() {
  return { ready: launcherReady, manual: MAC };
}

/** Closes the launcher and runs the downloaded installer silently; the new launcher starts by itself. */
function installLauncherNow() {
  if (MAC) { shell.openExternal(MAC_DMG); return; }
  if (autoUpdater && launcherReady) autoUpdater.quitAndInstall(true, true);
}

// ---------------------------------------------------------------- FzClient mod

function updDir() { return path.join(app.getPath('userData'), 'updates'); }
function cachedJar() { return path.join(updDir(), 'fzclient.jar'); }
function metaFile() { return path.join(updDir(), 'fzclient.json'); }

function sha1Of(file) {
  try { return crypto.createHash('sha1').update(fs.readFileSync(file)).digest('hex'); } catch { return null; }
}

function readMeta() {
  try { return JSON.parse(fs.readFileSync(metaFile(), 'utf8')); } catch { return null; }
}

/** A fetch that gives up quickly (so an offline PC can still play right away). */
async function quickFetch(url, ms) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  try {
    const res = await net.fetch(url, { headers: { 'User-Agent': UA }, signal: ctl.signal, cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res;
  } finally {
    clearTimeout(timer);
  }
}

/** The downloaded jar, when it is intact and was fetched by this launcher version or for a newer mod. */
function usableCache(bundled) {
  const meta = readMeta();
  if (!meta || !meta.sha1 || sha1Of(cachedJar()) !== meta.sha1) return null;
  // after a launcher update the bundled jar may be newer than an old download: only trust the cache if it was
  // made by this launcher version (or the bundled jar is the very same file)
  if (meta.launcher !== app.getVersion() && sha1Of(bundled) !== meta.sha1) return null;
  return cachedJar();
}

let modCheck = null;   // the running / last check (one per launcher start, refreshed every few hours)
let modCheckedAt = 0;

/**
 * Looks for a newer FzClient jar on GitHub. Resolves with { jar, updated, version } and never throws:
 * offline or without a release it simply keeps the jar it already has.
 */
function checkMod(bundled, log, send) {
  if (modCheck && Date.now() - modCheckedAt < 30 * 60 * 1000) return modCheck;
  modCheckedAt = Date.now();
  modCheck = (async () => {
    const fallback = usableCache(bundled) || bundled;
    let manifest;
    try {
      manifest = await (await quickFetch(LATEST + 'fzclient.json', 6000)).json();
    } catch (e) {
      log('[update] mod check skipped: ' + e.message);
      modCheckedAt = 0;   // try again next time (e.g. when Play is pressed after the PC came online)
      return { jar: fallback, updated: false };
    }
    const want = String(manifest.sha1 || '').toLowerCase();
    if (!/^[0-9a-f]{40}$/.test(want)) return { jar: fallback, updated: false };
    if (sha1Of(bundled) === want) return { jar: bundled, updated: false, version: manifest.version };
    const meta = readMeta();
    if (meta && meta.sha1 === want && sha1Of(cachedJar()) === want) {
      if (meta.launcher !== app.getVersion()) fs.writeFileSync(metaFile(), JSON.stringify({ ...meta, launcher: app.getVersion() }));
      return { jar: cachedJar(), updated: false, version: manifest.version };
    }
    try {
      if (send) send('update:mod', { state: 'downloading', version: manifest.version });
      const buf = Buffer.from(await (await quickFetch(LATEST + 'fzclient.jar', 60000)).arrayBuffer());
      if (crypto.createHash('sha1').update(buf).digest('hex') !== want) throw new Error('checksum mismatch');
      fs.mkdirSync(updDir(), { recursive: true });
      fs.writeFileSync(cachedJar() + '.part', buf);
      fs.renameSync(cachedJar() + '.part', cachedJar());
      fs.writeFileSync(metaFile(), JSON.stringify({ version: manifest.version, sha1: want, launcher: app.getVersion() }));
      log('[update] FzClient ' + manifest.version + ' downloaded');
      if (send) send('update:mod', { state: 'done', version: manifest.version });
      return { jar: cachedJar(), updated: true, version: manifest.version };
    } catch (e) {
      log('[update] mod download failed: ' + e.message);
      if (send) send('update:mod', { state: 'failed' });
      modCheckedAt = 0;
      return { jar: fallback, updated: false };
    }
  })();
  return modCheck;
}

module.exports = { REPO, startLauncherUpdates, launcherStatus, installLauncherNow, checkMod };
