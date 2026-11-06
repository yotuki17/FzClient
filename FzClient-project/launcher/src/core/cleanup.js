'use strict';
// Caches cleaned on every launcher start. Only things the launcher made itself and can make again:
// never screenshots, clips, settings, accounts, mods the user put in, the game folder's own data or %APPDATA%\.fzclient.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app } = require('electron');

function sizeOf(p) {
  try {
    const st = fs.statSync(p);
    if (!st.isDirectory()) return st.size;
    let n = 0;
    for (const f of fs.readdirSync(p)) n += sizeOf(path.join(p, f));
    return n;
  } catch { return 0; }
}

/** Removes a file or folder; a file still in use is simply left for next time. Returns the bytes freed. */
function rm(p) {
  if (!fs.existsSync(p)) return 0;
  const n = sizeOf(p);
  try { fs.rmSync(p, { recursive: true, force: true, maxRetries: 2 }); } catch { return 0; }
  return fs.existsSync(p) ? 0 : n;
}

function sha1Of(file) {
  try { return crypto.createHash('sha1').update(fs.readFileSync(file)).digest('hex'); } catch { return null; }
}

/**
 * Before the app is ready (Chromium has not opened them yet): the browser caches of the launcher window.
 * Settings and logins live elsewhere (Local Storage, Preferences, accounts.dat) and are not touched.
 */
function beforeReady() {
  const ud = app.getPath('userData');
  let freed = 0;
  for (const d of ['Cache', 'Code Cache', 'GPUCache', 'DawnGraphiteCache', 'DawnWebGPUCache', 'Shared Dictionary', 'blob_storage']) {
    freed += rm(path.join(ud, d));
  }
  return freed;
}

/**
 * After start: leftovers of updates and thumbnails of deleted pictures.
 *  bundledJar  the FzClient jar inside the installed launcher
 *  gameDir     the game folder (only FzClient's own renamed old jars are removed from mods/)
 *  media       { shots, clips } folders, to keep the thumbnails that still belong to a file
 */
function afterReady({ bundledJar, gameDir, media, log }) {
  let freed = 0;
  const ud = app.getPath('userData');

  // the launcher installer an update already ran (electron-updater keeps a copy of ~85 MB); nothing is
  // downloaded yet in this session, so a newer version is simply fetched again when there is one
  const upd = path.join(app.getPath('cache'), 'fzclient-launcher-updater');
  freed += rm(path.join(upd, 'installer.exe'));
  freed += rm(path.join(upd, 'pending'));

  // a downloaded FzClient jar the launcher no longer uses (same as the bundled one, or from an older launcher)
  const updates = path.join(ud, 'updates');
  try {
    const meta = JSON.parse(fs.readFileSync(path.join(updates, 'fzclient.json'), 'utf8'));
    const jar = path.join(updates, 'fzclient.jar');
    const bundledSha = sha1Of(bundledJar);
    if (!meta.sha1 || sha1Of(jar) !== meta.sha1 || meta.sha1 === bundledSha || meta.launcher !== app.getVersion()) {
      freed += rm(updates);
    }
  } catch {
    // no meta: whatever is there cannot be used
    if (fs.existsSync(updates)) freed += rm(updates);
  }
  try {
    for (const f of fs.readdirSync(updates)) if (f.endsWith('.part')) freed += rm(path.join(updates, f));
  } catch { }

  // thumbnails whose screenshot / clip was deleted, moved or edited
  const thumbs = path.join(ud, 'thumbs');
  try {
    if (fs.existsSync(thumbs)) {
      const keep = new Set();
      const add = dir => {
        if (!dir || !fs.existsSync(dir)) return;
        for (const f of fs.readdirSync(dir)) {
          const full = path.join(dir, f);
          try {
            const st = fs.statSync(full);
            if (st.isFile()) keep.add(crypto.createHash('sha1').update(`${full}|${st.size}|${st.mtimeMs}`).digest('hex'));
          } catch { }
        }
      };
      add(media.shots);
      add(media.clips);
      for (const f of fs.readdirSync(thumbs)) {
        const key = f.replace(/(\.v)?\.jpg$/i, '');
        if (!keep.has(key)) freed += rm(path.join(thumbs, f));
      }
    }
  } catch { }

  // FzClient / FzHUD jars the launcher itself renamed to .old when it put the new FzClient.jar in
  try {
    const mods = path.join(gameDir, 'mods');
    for (const f of fs.readdirSync(mods)) {
      if (/^(fzclient|fzhud)-.*\.jar\.old$/i.test(f)) freed += rm(path.join(mods, f));
    }
  } catch { }

  return freed;
}

function mb(n) { return (n / 1048576).toFixed(1) + ' MB'; }

module.exports = { beforeReady, afterReady, mb };
