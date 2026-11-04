'use strict';
const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { settings, accounts, dirs } = require('./src/core/store');
const auth = require('./src/core/auth');
const { Game, MC_VERSION } = require('./src/core/game');
const updater = require('./src/core/updater');
const modrinth = require('./src/core/modrinth');
const curseforge = require('./src/core/curseforge');
const skins = require('./src/core/skins');
const optimize = require('./src/core/optimize');
const shots = require('./src/core/shots');

shots.registerScheme();   // fzshot: pictures for the screenshot page (must happen before "ready")
const modsDir = () => path.join(dirs().gameDir, 'mods');

let win;
let running = null;      // the Minecraft process while it runs
let loginCancelled = false;

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

function send(channel, data) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, data);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1040, height: 640, minWidth: 900, minHeight: 560,
    frame: false, backgroundColor: '#0B1416', show: false, title: 'FzClient Launcher',
    icon: path.join(__dirname, 'renderer', 'assets', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());
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
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => {
    shots.handle(() => path.join(dirs().gameDir, 'screenshots'));
    createWindow();
    updater.startLauncherUpdates(send, log);
    // look for a new FzClient right away, so pressing Play does not have to wait for it
    win.webContents.once('did-finish-load', () => updater.checkMod(bundledJar(), log, send));
  });
  app.on('window-all-closed', () => app.quit());
}

// ---------------------------------------------------------------- window controls
ipcMain.on('win:min', () => win.minimize());
ipcMain.on('win:max', () => (win.isMaximized() ? win.unmaximize() : win.maximize()));
ipcMain.on('win:close', () => win.close());

// ---------------------------------------------------------------- settings / info
ipcMain.handle('settings:get', () => settings.get());
ipcMain.handle('settings:set', (e, patch) => settings.set(patch));
ipcMain.handle('info', () => ({
  version: app.getVersion(), locale: app.getLocale(), mc: MC_VERSION, dirs: dirs(), msa: auth.msaAvailable(),
  totalMemMb: Math.floor(require('os').totalmem() / 1048576),
}));
ipcMain.handle('open:folder', (e, which) => {
  const d = dirs();
  const target = which === 'mods' ? path.join(d.gameDir, 'mods')
    : which === 'logs' ? path.join(d.gameDir, 'logs')
    : which === 'screenshots' ? path.join(d.gameDir, 'screenshots') : d.gameDir;
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
ipcMain.handle('mods:list', () => {
  const dir = path.join(dirs().gameDir, 'mods');
  fs.mkdirSync(dir, { recursive: true });
  return fs.readdirSync(dir)
    .filter(f => /\.jar(\.disabled)?$/i.test(f) && f !== 'FzClient.jar')
    .map(f => ({ file: f, name: f.replace(/\.disabled$/i, '').replace(/\.jar$/i, ''), enabled: !/\.disabled$/i.test(f),
      size: fs.statSync(path.join(dir, f)).size }))
    .sort((a, b) => a.name.localeCompare(b.name));
});
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
ipcMain.handle('mods:installed', async (e, checkUpdates) => {
  const opts = { checkUpdates: !!checkUpdates };
  return curseforge.annotate(await modrinth.installed(modsDir(), opts), modsDir(), opts);
});
const SOURCES = { modrinth, curseforge };
const source = s => SOURCES[s] || modrinth;
ipcMain.handle('mods:search', (e, query, offset, src) => source(src).search(String(query || '').slice(0, 100), offset));
ipcMain.handle('mods:installedProjects', (e, src) => source(src).installedProjects(modsDir()));
ipcMain.handle('mods:install', (e, projectId, src) => {
  const ok = src === 'curseforge' ? /^\d{1,10}$/ : /^[A-Za-z0-9]{6,12}$/;
  if (!ok.test(String(projectId))) throw new Error('bad project');
  return source(src).install(String(projectId), modsDir());
});
/** Modrinth first, then CurseForge for the jars Modrinth does not know. */
async function updateMods(files) {
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

// ---------------------------------------------------------------- screenshots
ipcMain.handle('shots:list', () => shots.list());
ipcMain.handle('shots:copy', (e, file) => shots.copy(file));
ipcMain.handle('shots:reveal', (e, file) => shots.reveal(file));
ipcMain.handle('shots:remove', (e, file) => shots.remove(file));

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
  const child = await game.launch({
    account: acc, memoryMb: s.memoryMb, width: s.width, height: s.height, fullscreen: s.fullscreen,
    jvmArgs: s.jvmArgs, bundledJar: mod.jar, launcherVersion: app.getVersion(),
    optimizeJvm: !!s.optimizeJvm, jvmExtras: s.optimizeJvm ? optimize.jvmExtras() : [], highPriority: s.gamePriority === 'high',
    onProgress: (stage, done, total, bytesDone, bytesTotal) => {
      const now = Date.now();
      if (now - lastSent < 80 && done < total) return; // don't flood the page
      lastSent = now;
      send('game:progress', { stage, done, total, bytesDone, bytesTotal });
    },
    onLog: line => { log(line.trimEnd()); send('game:log', line); },
  });
  running = child;
  send('game:state', { running: true });
  child.on('exit', code => {
    running = null;
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
