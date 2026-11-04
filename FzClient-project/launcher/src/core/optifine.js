'use strict';
// OptiFine for the MOD and 最適化 pages.
//  - Its licence only allows downloading it from optifine.net, so the launcher never ships or fetches the jar by
//    itself: "入れる" opens the official download page in the browser (the user clicks Download there) and then
//    picks the new jar up from the Downloads folder and puts it into mods/.
//  - Presets write OptiFine's own option file (optionsof.txt) and a few vanilla options (options.txt). Before the
//    first change both files are copied to the launcher's data folder, so "元に戻す" puts back exactly what was there.
const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const { app, shell } = require('electron');

/** Newest stable OptiFine for 1.8.9: the official page that leads to its download. */
const VERSION = 'HD_U_M5';
const PAGE = `https://optifine.net/adloadx?f=OptiFine_1.8.9_${VERSION}.jar`;
const isJar = f => /optifine/i.test(f) && /\.jar(\.disabled)?$/i.test(f);

/** OptiFine's version from its file name, e.g. "HD U M5" or "HD U M6 pre2". */
function versionOf(file) {
  const m = /1\.8\.9_(HD_U_[A-Z0-9]+(?:_pre\d+)?)/i.exec(file);
  return m ? m[1].replace(/_/g, ' ') : '';
}

/** The OptiFine jar in mods/, or null: { file, enabled, version, preview }. */
function installed(modsDir) {
  let files = [];
  try { files = fs.readdirSync(modsDir).filter(isJar); } catch { /* no mods folder yet */ }
  if (!files.length) return null;
  // an enabled one first
  files.sort((a, b) => /\.disabled$/i.test(a) - /\.disabled$/i.test(b));
  const file = files[0];
  return { file, enabled: !/\.disabled$/i.test(file), version: versionOf(file), preview: /pre\d|preview/i.test(file), count: files.length };
}

/** True when the jar really is OptiFine (it carries OptiFine's own classes). */
function looksLikeOptiFine(file) {
  try {
    return new AdmZip(file).getEntries().some(e => e.entryName.startsWith('optifine/'));
  } catch {
    return false;
  }
}

/** Puts an OptiFine jar into mods/; older OptiFine jars go to the Recycle Bin (two at once crash the game). */
async function addJar(src, modsDir) {
  const name = path.basename(src);
  if (!isJar(name) || !/1\.8\.9/.test(name)) throw new Error('Minecraft 1.8.9 用の OptiFine の jar を選んでください');
  if (!looksLikeOptiFine(src)) throw new Error('OptiFine の jar ではないようです');
  fs.mkdirSync(modsDir, { recursive: true });
  for (const f of fs.readdirSync(modsDir).filter(isJar)) {
    if (f === name) continue;
    try { await shell.trashItem(path.join(modsDir, f)); } catch { fs.renameSync(path.join(modsDir, f), path.join(modsDir, f + '.old')); }
  }
  fs.copyFileSync(src, path.join(modsDir, name));
  return { file: name, version: versionOf(name) };
}

// ---------------------------------------------------------------- install through the official page

let watch = null;

/**
 * Opens the official download page and waits (up to 15 minutes) for the jar to appear in Downloads.
 * done({ ok, file, version, error }) is called once.
 */
function startInstall(modsDir, done) {
  cancelInstall();
  shell.openExternal(PAGE);
  const dir = app.getPath('downloads');
  const since = Date.now() - 10000;
  const sizes = new Map();   // file -> size at the last look: only take a file whose download has finished
  const started = Date.now();
  const timer = setInterval(async () => {
    if (Date.now() - started > 15 * 60 * 1000) {
      cancelInstall();
      done({ ok: false, error: 'ダウンロードが見つかりませんでした。もう一度「OptiFine を入れる」を押すか、「ファイルから」を使ってください' });
      return;
    }
    let names = [];
    try { names = fs.readdirSync(dir); } catch { return; }
    for (const n of names) {
      if (!/^(preview_)?OptiFine_1\.8\.9_.*\.jar$/i.test(n)) continue;
      const full = path.join(dir, n);
      let st;
      try { st = fs.statSync(full); } catch { continue; }
      if (st.mtimeMs < since || st.size < 100000) continue;
      if (sizes.get(n) !== st.size) { sizes.set(n, st.size); continue; }   // still growing (or first look)
      cancelInstall();
      try {
        const r = await addJar(full, modsDir);
        done({ ok: true, ...r });
      } catch (e) {
        done({ ok: false, error: e.message });
      }
      return;
    }
  }, 2000);
  watch = timer;
}

function cancelInstall() {
  if (watch) clearInterval(watch);
  watch = null;
}

// ---------------------------------------------------------------- option presets

// OptiFine's option values: 0 = default, 1 = fast, 2 = fancy, 3 = off (clouds, trees, rain, fog, better grass,
// connected textures, dropped items, vignette); dynamic lights 1 = fast, 2 = fancy, 3 = off; animated water / lava
// 0 = on, 2 = off. Keys an OptiFine version does not know are simply ignored by it.
const PVP = {
  of: {
    ofFastRender: 'true', ofFastMath: 'true', ofSmartAnimations: 'true', ofRenderRegions: 'true', ofSmoothFps: 'false',
    ofChunkUpdates: '1', ofChunkUpdatesDynamic: 'false', ofLazyChunkLoading: 'true', ofDynamicLights: '3',
    // fog stays on (fast): 1.8.9 fills the sky below the horizon with the fog colour, so with fog off the lower
    // half of custom skies disappears
    ofClouds: '3', ofTrees: '1', ofRain: '3', ofFogType: '1', ofBetterGrass: '3', ofDroppedItems: '1', ofVignette: '1',
    ofVoidParticles: 'false', ofWaterParticles: 'false', ofPortalParticles: 'false', ofDrippingWaterLava: 'false',
    ofRainSplash: 'false', ofAnimatedPortal: 'false', ofAnimatedRedstone: 'false', ofAnimatedSmoke: 'false',
    ofLagometer: 'false', ofProfiler: 'false', ofAaLevel: '0', ofAfLevel: '1',
  },
  vanilla: { useVbo: 'true', entityShadows: 'false' },
};
const PRESETS = {
  // more FPS, the picture stays clear: what most PvP players use
  pvp: PVP,
  // everything that costs FPS off
  fps: {
    of: {
      ...PVP.of, ofConnectedTextures: '3', ofAnimatedWater: '2', ofAnimatedLava: '2', ofAnimatedFlame: 'false',
      ofSky: 'false', ofStars: 'false', ofSunMoon: 'false', ofCustomSky: 'false', ofBetterSnow: 'false',
      ofSwampColors: 'false', ofSmoothBiomes: 'false', ofNaturalTextures: 'false', ofMipmapType: '0', ofTranslucentBlocks: '1',
    },
    vanilla: { ...PVP.vanilla, fancyGraphics: 'false', ao: '0', mipmapLevels: '0', particles: '1' },
  },
};

/** { of: { key: value before the first preset | null }, vanilla: {...} }: only the keys a preset changed. */
function backupFile() { return path.join(app.getPath('userData'), 'optifine-backup.json'); }
const FILES = { of: 'optionsof.txt', vanilla: 'options.txt' };

/** "key:value" lines -> [[key, value], ...] in file order (lines without ":" are kept as they are). */
function readOptions(file) {
  try {
    return fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(l => l.length).map(l => {
      const i = l.indexOf(':');
      return i < 0 ? [l, null] : [l.slice(0, i), l.slice(i + 1)];
    });
  } catch {
    return [];
  }
}

/** Sets (value) or removes (null) keys, keeping every other line and the order. */
function writeOptions(file, patch) {
  const lines = readOptions(file).filter(l => !(l[1] !== null && patch[l[0]] === null));
  const seen = new Set();
  for (const l of lines) if (l[1] !== null && patch[l[0]] != null) { l[1] = patch[l[0]]; seen.add(l[0]); }
  for (const [k, v] of Object.entries(patch)) if (v !== null && !seen.has(k)) lines.push([k, v]);
  fs.writeFileSync(file, lines.map(([k, v]) => (v === null ? k : `${k}:${v}`)).join('\n') + '\n');
}

function readBackup() {
  try { return JSON.parse(fs.readFileSync(backupFile(), 'utf8')); } catch { return null; }
}

function applyPreset(gameDir, id) {
  const p = PRESETS[id];
  if (!p) throw new Error('bad preset');
  fs.mkdirSync(gameDir, { recursive: true });
  // remember each key's value from before the very first preset (a second preset must not overwrite that)
  const backup = readBackup() || { of: {}, vanilla: {} };
  for (const part of Object.keys(FILES)) {
    const now = new Map(readOptions(path.join(gameDir, FILES[part])));
    for (const k of Object.keys(p[part])) if (!(k in backup[part])) backup[part][k] = now.has(k) ? now.get(k) : null;
  }
  fs.writeFileSync(backupFile(), JSON.stringify(backup, null, 2));
  for (const part of Object.keys(FILES)) writeOptions(path.join(gameDir, FILES[part]), p[part]);
}

/** Puts back only the keys a preset changed; everything else changed since then stays. */
function revertPresets(gameDir) {
  const backup = readBackup();
  if (!backup) return false;
  for (const part of Object.keys(FILES)) {
    const file = path.join(gameDir, FILES[part]);
    if (fs.existsSync(file)) writeOptions(file, backup[part] || {});
  }
  fs.unlinkSync(backupFile());
  return true;
}

function hasBackup() {
  return fs.existsSync(backupFile());
}

module.exports = { VERSION, PAGE, installed, addJar, startInstall, cancelInstall, applyPreset, revertPresets, hasBackup, isJar };
