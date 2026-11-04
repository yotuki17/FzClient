'use strict';
// Launcher settings (plain JSON) and accounts (tokens encrypted with Windows' own user encryption via safeStorage).
const fs = require('fs');
const path = require('path');
const { app, safeStorage } = require('electron');

const DEFAULTS = {
  gameDir: '',            // empty = %APPDATA%\.fzclient
  memoryMb: 3072,
  width: 1280,
  height: 720,
  fullscreen: false,
  jvmArgs: '',
  afterLaunch: 'minimize', // minimize | close | keep
  lang: 'auto',           // auto | ja | en
  selected: '',           // uuid of the selected account
  autoUpdateMods: true,   // update Modrinth mods before each launch
  optimizeJvm: false,     // 最適化 page: extra Java options
  gamePriority: 'normal', // 最適化 page: 'high' = the game gets the CPU first
};

function file(name) {
  return path.join(app.getPath('userData'), name);
}

function readJson(f, def) {
  try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return def; }
}

function writeJson(f, data) {
  fs.mkdirSync(path.dirname(f), { recursive: true });
  const tmp = f + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, f);
}

const settings = {
  get() { return { ...DEFAULTS, ...readJson(file('settings.json'), {}) }; },
  set(patch) {
    const s = { ...settings.get(), ...patch };
    writeJson(file('settings.json'), s);
    return s;
  },
};

const accounts = {
  list() {
    const f = file('accounts.dat');
    try {
      const raw = fs.readFileSync(f);
      const text = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(raw) : raw.toString('utf8');
      return JSON.parse(text);
    } catch {
      // unreadable (e.g. Windows can no longer decrypt it): keep a copy before the next save overwrites it
      try { if (fs.existsSync(f) && !fs.existsSync(f + '.bak')) fs.copyFileSync(f, f + '.bak'); } catch { /* ignore */ }
      return [];
    }
  },
  save(list) {
    const text = JSON.stringify(list);
    fs.mkdirSync(path.dirname(file('accounts.dat')), { recursive: true });
    fs.writeFileSync(file('accounts.dat'),
      safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(text) : Buffer.from(text, 'utf8'));
  },
  upsert(acc) {
    const list = accounts.list().filter(a => a.uuid !== acc.uuid);
    list.push(acc);
    accounts.save(list);
  },
  remove(uuid) {
    accounts.save(accounts.list().filter(a => a.uuid !== uuid));
  },
};

/** Where Minecraft keeps its files: the shared folder (libraries, assets, Java) and the game folder (mods, saves). */
function dirs() {
  const s = settings.get();
  const root = path.join(app.getPath('appData'), '.fzclient');
  return { root, gameDir: s.gameDir || root };
}

module.exports = { settings, accounts, dirs };
