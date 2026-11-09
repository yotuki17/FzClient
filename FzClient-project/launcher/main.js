'use strict';
const { app, BrowserWindow, ipcMain, shell, globalShortcut, Notification } = require('electron');
const path = require('path');
const fs = require('fs');

// Every process the launcher starts (recorder helper, ffmpeg, PowerShell...) is kept here, so closing the launcher
// can end them all instead of leaving them running in the background. Done before the modules below load, as they
// take spawn from child_process when they load. The game itself is not ended (see shutdown).
const children = new Set();
{
  const cp = require('child_process');
  const spawn0 = cp.spawn;
  cp.spawn = function (...args) {
    const p = spawn0.apply(this, args);
    // detached = meant to outlive the launcher (the update installer): never ended here
    const opts = args.find(a => a && typeof a === 'object' && !Array.isArray(a));
    if (opts && opts.detached) return p;
    children.add(p);
    p.once('exit', () => children.delete(p));
    p.once('error', () => children.delete(p));
    return p;
  };
}

const { settings, accounts, dirs } = require('./src/core/store');
const auth = require('./src/core/auth');
const { Game, MC_VERSION } = require('./src/core/game');
const updater = require('./src/core/updater');
const modrinth = require('./src/core/modrinth');
const curseforge = require('./src/core/curseforge');
const skins = require('./src/core/skins');
const optimize = require('./src/core/optimize');
const shots = require('./src/core/shots');
const optifine = require('./src/core/optifine');
const recorder = require('./src/core/recorder');
const { Link } = require('./src/core/link');
const { Capture } = require('./src/core/capture');
const cleanup = require('./src/core/cleanup');

shots.registerScheme();   // fzshot: pictures for the screenshot page (must happen before "ready")
const modsDir = () => path.join(dirs().gameDir, 'mods');

let win;
let running = null;      // the Minecraft process while it runs
let loginCancelled = false;

function ticketFile() { return path.join(app.getPath('userData'), 'launch.ticket'); }

/** "token time": good for a few minutes, read and removed by FzClient when the game starts. */
function writeTicket() {
  try {
    const token = require('crypto').randomBytes(24).toString('hex');
    fs.writeFileSync(ticketFile(), token + ' ' + Date.now());
    return token;
  } catch (e) {
    log('[ticket] ' + e.message);
    return null;
  }
}

function removeTicket() {
  try { fs.rmSync(ticketFile(), { force: true }); } catch { }
}

function bundledJar() {
  // packaged: resources/fzclient.jar next to app.asar; dev: ./resources/fzclient.jar
  const packed = path.join(process.resourcesPath || '', 'fzclient.jar');
  return fs.existsSync(packed) ? packed : path.join(__dirname, 'resources', 'fzclient.jar');
}

function logFile() {
  return path.join(app.getPath('userData'), 'launcher.log');
}

function log(line) {
  try { fs.appendFileSync(logFile(), `[${new Date().toISOString()}] ${line}\n`); } catch { /* ignore */ }
}

/**
 * launcher.log used to get every line the game printed, forever (a practice map's command blocks made it 48 GB).
 * At start a log over 5 MB is cut down to its last 512 KB.
 */
function trimLog() {
  const file = logFile(), keep = 512 * 1024;
  try {
    const size = fs.statSync(file).size;
    if (size <= 5 * 1024 * 1024) return;
    const fd = fs.openSync(file, 'r');
    const buf = Buffer.alloc(keep);
    const n = fs.readSync(fd, buf, 0, keep, size - keep);
    fs.closeSync(fd);
    const tail = buf.subarray(0, n);
    const from = tail.indexOf(10) + 1;   // start at a whole line
    fs.writeFileSync(file + '.tmp', Buffer.concat([Buffer.from(`[${new Date().toISOString()}] [log] trimmed from ${size} bytes\n`), tail.subarray(from)]));
    fs.renameSync(file + '.tmp', file);
  } catch { /* no log yet */ }
}

/**
 * The game's own output goes to game-output.log, started fresh for every launch and capped at 20 MB (the game
 * keeps its full log in logs/latest.log anyway). Written through a stream, not a file call per line.
 */
const GAME_LOG_MAX = 20 * 1024 * 1024;
function gameLog() {
  let out = null, written = 0, full = false;
  try { out = fs.createWriteStream(path.join(app.getPath('userData'), 'game-output.log'), { flags: 'w' }); } catch { /* no log */ }
  return {
    write(text) {
      if (!out || full) return;
      written += Buffer.byteLength(text);
      if (written > GAME_LOG_MAX) { full = true; out.write('\n[launcher] game output over 20 MB, the rest is only in logs/latest.log\n'); return; }
      out.write(text);
    },
    end() { if (out) out.end(); },
  };
}

function send(channel, data) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, data);
}

/**
 * First start: wide enough for the side panels (screenshot preview / mod details need 1200 px), but never bigger
 * than the screen. Later starts: the size the window had when it was last closed.
 */
function windowSize() {
  const { screen } = require('electron');
  const area = screen.getPrimaryDisplay().workAreaSize;
  const saved = settings.get().winSize || {};
  const fit = (want, max, min) => Math.max(min, Math.min(want, max));
  return {
    width: fit(saved.width || 1300, area.width - 40, 900),
    height: fit(saved.height || 800, area.height - 40, 560),
    maximized: !!saved.maximized,
  };
}

function rememberSize() {
  if (!win || win.isDestroyed()) return;
  const b = win.getNormalBounds();
  settings.set({ winSize: { width: b.width, height: b.height, maximized: win.isMaximized() } });
}

function createWindow() {
  const size = windowSize();
  win = new BrowserWindow({
    width: size.width, height: size.height, minWidth: 900, minHeight: 560,
    frame: false, backgroundColor: '#0B1416', show: false, title: 'FzClient Launcher',
    icon: path.join(__dirname, 'renderer', 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => {
    if (size.maximized) win.maximize();
    win.show();
  });
  win.on('close', rememberSize);
  // the hidden overlay / recording-mark windows would otherwise keep the launcher running in the background
  win.on('closed', () => app.quit());
  // links open in the normal browser, never inside the launcher
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', e => e.preventDefault());
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  // unneeded caches, every start (only this launcher's own; see src/core/cleanup.js)
  let freedEarly = 0;
  try { freedEarly = cleanup.beforeReady(); } catch { }
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => {
    trimLog();
    // before anything can download into the update folder (the mod check starts once the window has loaded)
    try {
      const d = dirs();
      const freed = freedEarly + cleanup.afterReady({
        bundledJar: bundledJar(), gameDir: d.gameDir,
        media: { shots: path.join(d.gameDir, 'screenshots'), clips: path.join(d.gameDir, 'recordings') },
      });
      log('[cleanup] ' + cleanup.mb(freed) + ' of caches removed');
    } catch (e) { log('[cleanup] ' + e.message); }
    shots.handle(() => path.join(dirs().gameDir, 'screenshots'), () => path.join(dirs().gameDir, 'recordings'), recorder.ffmpegPath);
    createWindow();
    updater.startLauncherUpdates(send, log);
    // look for a new FzClient right away, so pressing Play does not have to wait for it
    win.webContents.once('did-finish-load', () => updater.checkMod(bundledJar(), log, send));
  });
  // the overlay and the recording mark are hidden windows too: closing the launcher's window quits everything
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', shutdown);
  app.on('will-quit', () => globalShortcut.unregisterAll());
}

/**
 * Closing the launcher: a recording that was running is saved, then every window, timer and process the launcher
 * started ends, so nothing stays behind in the background. A running Minecraft keeps running.
 */
let shuttingDown = false;
function shutdown(event) {
  if (shuttingDown) return;
  shuttingDown = true;
  event.preventDefault();
  try { endPlay(); } catch { /* already gone */ }
  if (badgeTimer) { clearInterval(badgeTimer); badgeTimer = null; }
  for (const w of BrowserWindow.getAllWindows()) { try { w.destroy(); } catch { /* gone */ } }
  // the recorder needs about 2 seconds to finish the file; anything still running after that is ended
  const game = running;
  const waitMs = [...children].some(p => p !== game && p.exitCode === null) ? 3000 : 0;
  setTimeout(() => {
    for (const p of children) {
      if (p === game || p.exitCode !== null) continue;
      try { require('child_process').spawnSync('taskkill', ['/PID', String(p.pid), '/T', '/F'], { windowsHide: true }); } catch { /* gone */ }
    }
    // the normal quit now (a downloaded launcher update installs on it); if anything still holds it, exit anyway
    setTimeout(() => app.exit(0), 3000);
    app.quit();
  }, waitMs);
}

// ---------------------------------------------------------------- window controls
ipcMain.on('win:min', () => win.minimize());
ipcMain.on('win:max', () => (win.isMaximized() ? win.unmaximize() : win.maximize()));
ipcMain.on('win:close', () => win.close());

// ---------------------------------------------------------------- settings / info
ipcMain.handle('settings:get', () => settings.get());
ipcMain.handle('settings:set', (e, patch) => {
  const r = settings.set(patch);
  // recording options: the running recorder starts again with them
  if (patch && play && play.capture && Object.keys(patch).some(k => /^rec/.test(k))) {
    if (Object.keys(patch).some(k => /^rec(?!Badge|ReplaySeconds)/.test(k))) play.capture.optionsChanged();
    pushOverlay();
    updateBadge();
  }
  return r;
});
ipcMain.handle('info', () => ({
  version: app.getVersion(), locale: app.getLocale(), mc: MC_VERSION, dirs: dirs(), msa: auth.msaAvailable(),
  totalMemMb: Math.floor(require('os').totalmem() / 1048576),
}));
ipcMain.handle('open:folder', (e, which) => {
  const d = dirs();
  const target = which === 'mods' ? path.join(d.gameDir, 'mods')
    : which === 'logs' ? path.join(d.gameDir, 'logs')
    : which === 'screenshots' ? path.join(d.gameDir, 'screenshots')
    : which === 'config' ? path.join(d.gameDir, 'config')
    : which === 'recordings' ? path.join(d.gameDir, 'recordings') : d.gameDir;
  fs.mkdirSync(target, { recursive: true });
  return shell.openPath(target);
});
ipcMain.handle('open:url', (e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
ipcMain.handle('pick:folder', async () => {
  const { dialog } = require('electron');
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] });
  return r.canceled ? null : r.filePaths[0];
});

// ---------------------------------------------------------------- updates
ipcMain.handle('update:status', () => updater.launcherStatus());
ipcMain.handle('update:install', () => {
  if (running) throw new Error('Minecraft を閉じてから更新してください');
  updater.installLauncherNow();
});

// ---------------------------------------------------------------- mods (enable / disable by renaming)
ipcMain.handle('mods:toggle', (e, file) => {
  const dir = path.join(dirs().gameDir, 'mods');
  if (!plainFile(file)) throw new Error('bad file');
  const from = path.join(dir, file);
  const to = /\.disabled$/i.test(file) ? from.replace(/\.disabled$/i, '') : from + '.disabled';
  fs.renameSync(from, to);
});
ipcMain.handle('mods:add', async () => {
  const { dialog } = require('electron');
  const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'], filters: [{ name: 'MOD (.jar)', extensions: ['jar'] }] });
  if (r.canceled) return 0;
  const dir = path.join(dirs().gameDir, 'mods');
  fs.mkdirSync(dir, { recursive: true });
  for (const f of r.filePaths) fs.copyFileSync(f, path.join(dir, path.basename(f)));
  return r.filePaths.length;
});

// ---------------------------------------------------------------- Modrinth
const plainFile = f => typeof f === 'string' && f === path.basename(f) && /\.jar(\.disabled)?$/i.test(f);
const SOURCES = { modrinth, curseforge };
const source = s => SOURCES[s] || modrinth;
/** "modrinth:<id>" / "curseforge:<id>": a mod's key in settings.modPins (stays the same across versions). */
const pinKey = m => (m.project ? `${m.source === 'curseforge' ? 'curseforge' : 'modrinth'}:${m.project.id}` : null);
async function installedMods(checkUpdates) {
  const opts = { checkUpdates: !!checkUpdates };
  const list = await curseforge.annotate(await modrinth.installed(modsDir(), opts), modsDir(), opts);
  const pins = settings.get().modPins || {};
  return list.map(m => ({ ...m, source: m.source || (m.project ? 'modrinth' : null), pinned: !!pins[pinKey(m)] }));
}
async function installedMod(file) {
  const m = plainFile(file) && (await installedMods(false)).find(x => x.file === file);
  if (!m) throw new Error('MOD が見つかりません');
  return m;
}
ipcMain.handle('mods:installed', (e, checkUpdates) => installedMods(checkUpdates));
/** The versions a site has for one installed mod, and which one is installed now. */
ipcMain.handle('mods:versions', async (e, file) => {
  const m = await installedMod(file);
  if (!m.project) throw new Error('手動で追加した MOD はバージョンを選べません');
  return { current: m.version && m.version.id, list: await source(m.source).versions(m.project.id) };
});
/** Installs the chosen version of one mod. An older one is pinned, or the next update would undo it. */
ipcMain.handle('mods:setVersion', async (e, file, versionId, pin) => {
  if (running) throw new Error('Minecraft を閉じてから変更してください');
  const m = await installedMod(file);
  if (!m.project) throw new Error('手動で追加した MOD はバージョンを選べません');
  const name = await source(m.source).setVersion(modsDir(), file, String(versionId));
  if (typeof pin === 'boolean') setPin(m, pin);
  return name;
});
function setPin(m, on) {
  const pins = { ...(settings.get().modPins || {}) };
  if (on) pins[pinKey(m)] = true;
  else delete pins[pinKey(m)];
  settings.set({ modPins: pins });
}
ipcMain.handle('mods:pin', async (e, file, on) => {
  const m = await installedMod(file);
  if (!m.project) throw new Error('手動で追加した MOD は自動で更新されません');
  setPin(m, !!on);
});
ipcMain.handle('mods:search', (e, query, offset, src) => source(src).search(String(query || '').slice(0, 100), offset));
ipcMain.handle('mods:installedProjects', (e, src) => source(src).installedProjects(modsDir()));
ipcMain.handle('mods:install', (e, projectId, src) => {
  const ok = src === 'curseforge' ? /^\d{1,10}$/ : /^[A-Za-z0-9]{6,12}$/;
  if (!ok.test(String(projectId))) throw new Error('bad project');
  return source(src).install(String(projectId), modsDir());
});
/** Modrinth first, then CurseForge for the jars Modrinth does not know. No list = every mod that is not pinned. */
async function updateMods(files) {
  if (!files) files = (await installedMods(false)).filter(m => !m.pinned).map(m => m.file);
  if (!files.length) return 0;
  const n = await modrinth.update(modsDir(), files);
  let m = 0;
  try {
    m = await curseforge.update(modsDir(), files, modrinth.knownFiles(modsDir()));
  } catch (e) {
    log('[mods] CurseForge update skipped: ' + e.message);
  }
  return n + m;
}
ipcMain.handle('mods:update', (e, files) => {
  if (running) throw new Error('Minecraft を閉じてから更新してください');
  const list = Array.isArray(files) ? files.filter(plainFile) : null;
  return updateMods(list);
});
ipcMain.handle('mods:remove', async (e, file) => {
  if (!plainFile(file) || /^fzclient/i.test(file)) throw new Error('bad file');
  await shell.trashItem(path.join(modsDir(), file));   // Recycle Bin: can be restored
});

// ---------------------------------------------------------------- OptiFine (installed through optifine.net only)
ipcMain.handle('optifine:status', () => ({
  installed: optifine.installed(modsDir()), latest: optifine.VERSION.replace(/_/g, ' '),
  preset: settings.get().ofPreset || '', canRevert: optifine.hasBackup(),
}));
ipcMain.handle('optifine:install', () => {
  optifine.startInstall(modsDir(), r => {
    log('[optifine] ' + JSON.stringify(r));
    send('optifine:installed', r);
  });
});
ipcMain.handle('optifine:cancel', () => optifine.cancelInstall());
ipcMain.handle('optifine:addFile', async () => {
  const { dialog } = require('electron');
  const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters: [{ name: 'OptiFine (.jar)', extensions: ['jar'] }] });
  if (r.canceled) return null;
  return optifine.addJar(r.filePaths[0], modsDir());
});
ipcMain.handle('optifine:preset', (e, id) => {
  // Minecraft writes its option files when it closes, which would undo the change
  if (running) throw new Error('Minecraft を閉じてから変更してください');
  optifine.applyPreset(dirs().gameDir, String(id));
  settings.set({ ofPreset: String(id) });
});
ipcMain.handle('optifine:revert', () => {
  if (running) throw new Error('Minecraft を閉じてから変更してください');
  optifine.revertPresets(dirs().gameDir);
  settings.set({ ofPreset: '' });
});

// ---------------------------------------------------------------- overlay (Alt+X) and recording, while the game runs
// The overlay is a window of the launcher laid over the game (always on top, see-through). The recording is done
// here too (capture.js), so neither depends on what the game shows or on the server. FzClient in the game sends
// the keys and its numbers through the link (link.js); without it the overlay still records.
let overlay = null;
let play = null;   // while the game runs: { link, capture, active }

function recOptions() {
  const s = settings.get();
  return {
    replay: !!s.recReplay, replaySeconds: Math.max(10, Math.min(120, Number(s.recReplaySeconds) || 30)),
    area: s.recArea === 'monitor' ? 'monitor' : 'game', fps: Number(s.recFps) === 30 ? 30 : 60,
  };
}

function uiLang() {
  const pref = settings.get().lang || 'auto';
  return pref === 'auto' ? (/^ja/i.test(app.getLocale() || '') ? 'ja' : 'en') : pref;
}

function overlayState() {
  return {
    lang: uiLang(), link: !!(play && play.link.connected), prepared: !!(play && play.capture),
    rec: play && play.capture ? play.capture.state() : null,
  };
}

function overlayVisible() { return !!(overlay && !overlay.isDestroyed() && overlay.isVisible()); }

function pushOverlay() {
  if (overlayVisible()) overlay.webContents.send('ov:state', overlayState());
}

function overlayWindow() {
  if (overlay && !overlay.isDestroyed()) return overlay;
  overlay = new BrowserWindow({
    show: false, frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false,
    resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false,
    skipTaskbar: true, alwaysOnTop: true, title: 'FzClient Overlay',
    webPreferences: { preload: path.join(__dirname, 'src', 'overlay', 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  overlay.setAlwaysOnTop(true, 'screen-saver');   // over the game, which is on top itself while it has the focus
  overlay.setContentProtection(true);             // never in a recording of the whole monitor
  overlay.loadFile(path.join(__dirname, 'renderer', 'overlay', 'overlay.html'));
  overlay.on('blur', hideOverlay);                // Alt+Tab / a click elsewhere: out of the way
  overlay.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  overlay.webContents.on('will-navigate', e => e.preventDefault());
  return overlay;
}

/** open: { gallery: true, file } = show the gallery panel, with that file big ('' = none). */
function showOverlay(open) {
  const w = overlayWindow();
  const { screen } = require('electron');
  // the game's monitor: the mouse is there (the game holds it in the middle of its window)
  w.setBounds(screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).bounds);
  const go = () => {
    w.show();
    w.focus();
    w.webContents.send('ov:state', overlayState());
    w.webContents.send('ov:show', open && open.gallery ? { file: open.file || '' } : null);
    if (play) play.link.send({ t: 'watch', on: true });
  };
  if (w.webContents.isLoading()) w.webContents.once('did-finish-load', go);
  else go();
}

function hideOverlay() {
  if (!overlayVisible()) return;
  overlay.hide();   // Windows gives the focus back to the window under it: the game
  if (play) play.link.send({ t: 'watch', on: false });
}

/** The small recording mark in the bottom right of the game's monitor while the recorder runs (left out of captures, clicks go through). */
let badge = null, badgeTimer = null, badgeReady = false;
function updateBadge() {
  const r = play && play.capture ? play.capture.state() : null;
  if (!(r && r.running && settings.get().recBadge !== false)) {
    if (badge && !badge.isDestroyed() && badge.isVisible()) badge.hide();
    if (badgeTimer) { clearInterval(badgeTimer); badgeTimer = null; }
    return;
  }
  if (!badge || badge.isDestroyed()) {
    badge = new BrowserWindow({
      show: false, width: 22, height: 22, frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false,
      resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false, focusable: false,
      skipTaskbar: true, alwaysOnTop: true, title: 'FzClient REC',
      webPreferences: { preload: path.join(__dirname, 'src', 'overlay', 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    });
    badge.setAlwaysOnTop(true, 'screen-saver');
    badge.setContentProtection(true);
    badge.setIgnoreMouseEvents(true);
    badge.loadFile(path.join(__dirname, 'renderer', 'overlay', 'badge.html'));
    badgeReady = false;
    badge.webContents.once('did-finish-load', () => { badgeReady = true; updateBadge(); });
    return;
  }
  if (!badgeReady) return;
  badge.webContents.send('ov:state', overlayState());
  if (!badge.isVisible()) {
    const { screen } = require('electron');
    const b = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).bounds;
    badge.setPosition(b.x + b.width - 22 - 10, b.y + b.height - 22 - 10);   // bottom right, out of the way
    badge.showInactive();
  }
  // the game puts itself on top again whenever it gets the focus: stay over it
  if (!badgeTimer) badgeTimer = setInterval(() => { if (badge && !badge.isDestroyed() && badge.isVisible()) badge.moveTop(); }, 1000);
}

function toggleOverlay() {
  if (overlayVisible()) hideOverlay();
  else showOverlay();
}

/** A save finished (or failed): the overlay, a line in the game's chat, else a Windows notification. */
function recNotify(text, error) {
  pushOverlay();
  if (!play) return;
  const m = /^(.*?: )(.+)$/.exec(text);
  if (play.link.connected) play.link.send({ t: 'chat', text: m ? m[1] : text, arg: m ? m[2] : '', error: !!error });
  if (!error && play.link.connected) sendRecent(3);   // the title menu shows the newest
  else if (Notification.isSupported()) new Notification({ title: 'FzClient', body: text }).show();
}

function recNotPrepared() {
  recNotify('ランチャーの「設定」→「録画」で準備してください', true);
}

/** Without the link the launcher listens for Alt+X itself (with it the game sends its keys, only while it has the focus). */
function updateShortcuts() {
  globalShortcut.unregisterAll();
  if (!play || play.link.connected) return;
  try { globalShortcut.register('Alt+X', toggleOverlay); } catch (e) { log('[overlay] Alt+X: ' + e.message); }
}

function gameMessage(m) {
  if (!play) return;
  if (m.t === 'key') {
    if (m.k === 'overlay') toggleOverlay();
    else if (m.k === 'record') { if (play.capture) play.capture.toggleRecording(); else recNotPrepared(); }
    else if (m.k === 'save') { if (play.capture) play.capture.saveReplay(); else recNotPrepared(); }
  } else if (m.t === 'state') {
    play.active = !!m.active;
    if (play.capture) play.capture.setFullscreen(!!m.fs);
  } else if (m.t === 'stats') {
    if (overlayVisible()) overlay.webContents.send('ov:stats', m);
  } else if (m.t === 'recent') {
    sendRecent(Math.max(1, Math.min(6, m.n | 0 || 3)));
  } else if (m.t === 'gallery') {
    // from the game's title menu: the overlay with its gallery, that picture / clip big (or just the gallery)
    showOverlay({ gallery: true, file: typeof m.file === 'string' ? m.file : '' });
  }
}

/** The newest screenshots / clips for the game's title menu, with their small pictures as files. */
let recentBusy = false;
async function sendRecent(n) {
  if (!play || recentBusy) return;
  recentBusy = true;
  try {
    const items = [];
    for (const s of shots.list().slice(0, n)) {
      let thumb = null;
      try { thumb = await shots.thumbFile(s.file, s.kind); } catch { /* none */ }
      items.push({ file: s.file, kind: s.kind, time: s.time, thumb: thumb || '' });
    }
    if (play) play.link.send({ t: 'recent', items });
  } finally { recentBusy = false; }
}

function startPlay(child, link, tools) {
  const capture = tools ? new Capture({
    game: { pid: child.pid, gameDir: dirs().gameDir }, tools, options: recOptions,
    notify: recNotify, changed: () => { pushOverlay(); updateBadge(); }, log,
    sound: kind => { if (settings.get().recSound !== false) send('sound', kind); },   // the launcher's window plays it: never in the recording
  }) : null;
  play = { child, link, capture, active: false };
  link.on('message', gameMessage);
  link.on('connected', () => { updateShortcuts(); pushOverlay(); });
  link.on('disconnected', () => { updateShortcuts(); pushOverlay(); });
  updateShortcuts();
}

function endPlay() {
  if (!play) return;
  hideOverlay();
  if (play.capture) play.capture.close();
  play.link.close();
  play = null;
  updateBadge();
  globalShortcut.unregisterAll();
}

ipcMain.on('ov:hide', hideOverlay);
ipcMain.on('ov:record', () => { if (play && play.capture) play.capture.toggleRecording(); });
ipcMain.on('ov:save', () => { if (play && play.capture) play.capture.saveReplay(); });
ipcMain.on('ov:replay', (e, on) => {
  settings.set({ recReplay: !!on });
  if (play && play.capture) play.capture.optionsChanged();
  pushOverlay();
  send('rec:settings', settings.get());
});
ipcMain.on('ov:switch', (e, i, on) => { if (play) play.link.send({ t: 'set', i: i | 0, on: !!on }); });
ipcMain.on('ov:bind', (e, k, code, mods) => {
  if (play && ['overlay', 'record', 'save'].includes(k)) play.link.send({ t: 'bind', k, code: code | 0, mods: (mods | 0) & 7 });
});
ipcMain.on('ov:seconds', (e, sec) => {
  // read by the recorder whenever it needs it: no restart, the seconds kept so far stay
  settings.set({ recReplaySeconds: Math.max(10, Math.min(120, Math.round((sec | 0) / 5) * 5)) });
  pushOverlay();
  updateBadge();
  send('rec:settings', settings.get());
});
ipcMain.on('ov:menu', () => {
  if (play) play.link.send({ t: 'menu' });
  hideOverlay();
});
ipcMain.on('ov:folder', () => {
  const d = path.join(dirs().gameDir, 'recordings');
  fs.mkdirSync(d, { recursive: true });
  hideOverlay();
  shell.openPath(d);
});

// preparing ffmpeg + the helper (settings page)
ipcMain.handle('rec:status', () => recorder.status());
ipcMain.handle('rec:prepare', async () => {
  const r = await recorder.prepare(p => send('rec:progress', p), log);
  log('[recorder] ready ' + JSON.stringify(r));
  return r;
});
ipcMain.handle('rec:remove', () => {
  if (running) throw new Error('Minecraft を閉じてから削除してください');
  recorder.remove();
});

// ---------------------------------------------------------------- screenshots
ipcMain.handle('shots:list', () => shots.list());
ipcMain.handle('shots:copy', (e, file) => shots.copy(file));
ipcMain.handle('shots:reveal', (e, file, kind) => shots.reveal(file, kind === 'clip' ? 'clip' : 'shot'));
ipcMain.handle('shots:remove', (e, file, kind) => shots.remove(file, kind === 'clip' ? 'clip' : 'shot'));
// the overlay's gallery
ipcMain.handle('ov:gallery', () => shots.list().slice(0, 60));
ipcMain.on('ov:reveal', (e, file, kind) => { hideOverlay(); shots.reveal(file, kind === 'clip' ? 'clip' : 'shot'); });

// ---------------------------------------------------------------- accounts
function publicAccount(a) {
  return { type: a.type, name: a.name, uuid: a.uuid };
}
ipcMain.handle('accounts:list', () => ({ list: accounts.list().map(publicAccount), selected: settings.get().selected }));
ipcMain.handle('accounts:select', (e, uuid) => settings.set({ selected: uuid }));
ipcMain.handle('accounts:remove', (e, uuid) => {
  accounts.remove(uuid);
  if (settings.get().selected === uuid) settings.set({ selected: (accounts.list()[0] || {}).uuid || '' });
});
ipcMain.handle('accounts:offline', (e, name) => {
  if (!/^[A-Za-z0-9_]{3,16}$/.test(name || '')) throw new Error('名前は英数字と _ の3〜16文字にしてください');
  const acc = auth.offline(name);
  accounts.upsert(acc);
  settings.set({ selected: acc.uuid });
  return publicAccount(acc);
});
ipcMain.handle('accounts:msaStart', async () => {
  loginCancelled = false;
  const dc = await auth.startDeviceCode();
  shell.openExternal(dc.verification_uri);
  // finish in the background and tell the page when done
  auth.pollDeviceCode(dc, () => loginCancelled).then(acc => {
    accounts.upsert(acc);
    settings.set({ selected: acc.uuid });
    send('accounts:msaDone', { ok: true, account: publicAccount(acc) });
  }).catch(err => send('accounts:msaDone', { ok: false, error: err.message }));
  return { code: dc.user_code, url: dc.verification_uri };
});
ipcMain.handle('accounts:msaCancel', () => { loginCancelled = true; });

// ---------------------------------------------------------------- skins
/** The selected Microsoft account with a fresh Minecraft token (skins can only be changed for those). */
async function msaAccount() {
  const s = settings.get();
  let acc = accounts.list().find(a => a.uuid === s.selected) || accounts.list()[0];
  if (!acc) throw new Error('先にアカウントを追加してください');
  if (acc.type !== 'msa') throw new Error('オフラインアカウントのスキンは変えられません（Microsoft アカウントだけです）');
  if (!acc.expiresAt || acc.expiresAt < Date.now() + 60000) {
    acc = await auth.refresh(acc);
    accounts.upsert(acc);
  }
  return acc;
}
const skinName = n => String(n || '').replace(/\.png$/i, '').slice(0, 32);
ipcMain.handle('skins:list', () => skins.list());
ipcMain.handle('skins:current', async () => skins.current((await msaAccount()).accessToken));
ipcMain.handle('skins:addFile', async (e, variant) => {
  const { dialog } = require('electron');
  const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'], filters: [{ name: 'Skin (.png)', extensions: ['png'] }] });
  if (r.canceled) return [];
  const added = [];
  for (const f of r.filePaths) added.push(skins.add(fs.readFileSync(f), skinName(path.basename(f)), variant));
  return added;
});
ipcMain.handle('skins:addData', (e, base64, name, variant) => skins.add(Buffer.from(String(base64), 'base64'), skinName(name), variant));
ipcMain.handle('skins:addPlayer', (e, name) => skins.addFromPlayer(String(name || '').trim()));
ipcMain.handle('skins:update', (e, id, patch) => skins.update(id, { name: patch && patch.name, variant: patch && patch.variant }));
ipcMain.handle('skins:remove', (e, id) => skins.remove(id));
ipcMain.handle('skins:apply', async (e, id, variant) => skins.apply((await msaAccount()).accessToken, id, variant));
ipcMain.handle('skins:reset', async () => skins.reset((await msaAccount()).accessToken));
ipcMain.handle('skins:cape', async (e, id) => skins.cape((await msaAccount()).accessToken, id || null));

// ---------------------------------------------------------------- PC optimisation
function optJava() {
  optimize.setJavaPath(new Game(dirs(), log).javaExe());
}
ipcMain.handle('opt:status', async () => { optJava(); return optimize.status(settings.get()); });
ipcMain.handle('opt:recommended', (e, nvidia) => optimize.recommended(!!nvidia));
ipcMain.handle('opt:change', async (e, want) => {
  if (running) throw new Error('Minecraft を閉じてから変更してください');
  optJava();
  const clean = {};
  for (const id of optimize.TWEAK_IDS) if (want && id in want) clean[id] = !!want[id];
  const r = await optimize.change(clean, settings.get());
  if (Object.keys(r.settings).length) settings.set(r.settings);
  log('[optimize] ' + JSON.stringify({ want: clean, done: r.done, errors: r.errors }));
  if (r.log) log('[optimize] script output:\n' + r.log.slice(0, 4000));
  log('[optimize] state after:\n' + optimize.lastState().slice(0, 3000));
  return { done: r.done, errors: r.errors };
});
ipcMain.handle('opt:flushDns', () => optimize.flushDns());
ipcMain.handle('opt:debug', () => optimize.debugInfo());
ipcMain.handle('opt:measure', (e, seconds) => {
  if (!running) throw new Error('先にゲームを起動してください');
  return optimize.measure(Math.max(5, Math.min(60, seconds | 0 || 20)));
});

// ---------------------------------------------------------------- play
ipcMain.handle('game:launch', async () => {
  try {
    return await launchGame();
  } catch (e) {
    log('[error] ' + (e && e.stack || e));
    throw e;
  }
});

async function launchGame() {
  if (running) throw new Error('もう起動しています');
  const s = settings.get();
  let acc = accounts.list().find(a => a.uuid === s.selected) || accounts.list()[0];
  if (!acc) throw new Error('先にアカウントを追加してください');
  if (acc.type === 'msa' && (!acc.expiresAt || acc.expiresAt < Date.now() + 60000)) {
    send('game:progress', { stage: 'ログイン中', done: 0, total: 1 });
    acc = await auth.refresh(acc);
    accounts.upsert(acc);
  }
  send('game:progress', { stage: 'FzClient の更新を確認中', done: 0, total: 1 });
  const mod = await updater.checkMod(bundledJar(), log, send);
  if (s.autoUpdateMods) {
    send('game:progress', { stage: 'MOD を更新中', done: 0, total: 1 });
    try {
      const n = await updateMods(null);
      if (n) send('mods:autoUpdated', n);
    } catch (e) {
      log('[mods] auto update skipped: ' + e.message);   // offline etc.: play with what is there
    }
  }
  const game = new Game(dirs(), log);
  let lastSent = 0;
  const tools = await recorder.forLaunch(log);   // ffmpeg + helper for recording, when they are prepared
  // the link to FzClient in the game (overlay keys, numbers, switches)
  const link = new Link(log);
  let linkProp = null;
  try { linkProp = await link.start(); } catch (e) { log('[link] ' + e.message); }
  // the launch ticket: FzClient only runs when this launcher started the game (mod: boot/LaunchGuard.java)
  const ticket = writeTicket();
  const output = gameLog();
  let child;
  try {
    child = await game.launch({
      account: acc, memoryMb: s.memoryMb, width: s.width, height: s.height, fullscreen: s.fullscreen,
      jvmArgs: s.jvmArgs, bundledJar: mod.jar, launcherVersion: app.getVersion(),
      optimizeJvm: !!s.optimizeJvm, jvmExtras: [...(s.optimizeJvm ? optimize.jvmExtras() : []), ...(linkProp ? [linkProp] : []), ...(ticket ? ['-Dfzclient.ticket=' + ticket] : [])],
      highPriority: s.gamePriority === 'high',
      affinityMask: s.gameX3d && optimize.cacheCores() ? optimize.cacheCores().mask : 0,
      onProgress: (stage, done, total, bytesDone, bytesTotal) => {
        const now = Date.now();
        if (now - lastSent < 80 && done < total) return; // don't flood the page
        lastSent = now;
        send('game:progress', { stage, done, total, bytesDone, bytesTotal });
      },
      onLog: text => output.write(text),
    });
  } catch (e) {
    output.end();
    removeTicket();
    link.close();
    throw e;
  }
  running = child;
  startPlay(child, link, tools);
  send('game:state', { running: true });
  child.on('exit', code => {
    running = null;
    removeTicket();
    endPlay();
    output.end();
    log('[exit] ' + code);
    send('game:state', { running: false, code });
    if (win && !win.isDestroyed()) { win.show(); win.restore(); }
  });
  // hide the launcher once the game window is up (not when the game already quit, or the launcher would stay hidden)
  setTimeout(() => {
    if (running !== child || !win || win.isDestroyed()) return;
    if (s.afterLaunch === 'close') win.hide();
    else if (s.afterLaunch === 'minimize') win.minimize();
  }, 4000);
  return true;
}
ipcMain.handle('game:kill', () => { if (running) running.kill(); });
