'use strict';
// CurseForge (https://www.curseforge.com) for the MOD page, next to Modrinth: search, install (with required
// dependencies) and update Forge 1.8.9 mods.
// The CurseForge API needs a key; it is kept on the FzClient server (Cloudflare Worker, route /cf/) and never
// ships inside the launcher. Mods installed from here are remembered in mods/.fzclient-curseforge.json, which is
// how they are recognised later and updated.
const fs = require('fs');
const path = require('path');
const { app, net, shell } = require('electron');
const { fits118, safeName, sha1 } = require('./modrinth');

const PROXY = 'https://fzclient-users.fuzuki-yozakura.workers.dev/cf/v1';
const GAME = 432;          // Minecraft
const CLASS_MODS = 6;      // "Mods"
const MC = '1.8.9';
const REQUIRED = 3;        // FileRelationType.RequiredDependency
const MANIFEST = '.fzclient-curseforge.json';

function ua() {
  return `FzClient-Launcher/${app.getVersion()}`;
}

async function api(pathAndQuery) {
  const res = await net.fetch(PROXY + pathAndQuery, { headers: { 'User-Agent': ua(), Accept: 'application/json' } });
  if (res.status === 503) throw new Error('CurseForge はまだ準備中です（サーバーに API キーが設定されていません）');
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`CurseForge に接続できませんでした（HTTP ${res.status}）`);
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    // the server is not answering with CurseForge data yet (Worker without the /cf/ route)
    throw new Error('CurseForge はまだ準備中です（サーバーに API キーが設定されていません）');
  }
}

// ---------------------------------------------------------------- what fits

// CurseForge lists the loader as a "game version" ("Forge", "Fabric"...) next to the Minecraft versions.
// Old 1.8.9 files often name no loader at all; on 1.8.9 that means Forge.
function fileFits(f) {
  if (!f || f.isAvailable === false) return false;
  const all = (f.gameVersions || []).map(String);
  const loaders = all.filter(v => !/^\d/.test(v) && !/^(client|server|java)/i.test(v));
  return fits118(all.filter(v => /^\d/.test(v)), loaders.length ? loaders : null);
}

// in search results: latestFilesIndexes says which files exist for which version + loader (1 = Forge)
function modFits(m) {
  return (m.latestFilesIndexes || []).some(i => i.gameVersion === MC && (i.modLoader == null || i.modLoader === 0 || i.modLoader === 1));
}

const newest = list => list.slice().sort((a, b) => Date.parse(b.fileDate) - Date.parse(a.fileDate))[0] || null;

/** Newest real Forge 1.8.9 file of a mod, or null. */
async function latestFile(modId) {
  const r = await api(`/mods/${modId}/files?gameVersion=${MC}&pageSize=50`);
  return newest(((r && r.data) || []).filter(fileFits));
}

function pageOf(m) {
  return (m && m.links && m.links.websiteUrl) || `https://www.curseforge.com/minecraft/mc-mods/${encodeURIComponent((m && m.slug) || '')}`;
}

// ---------------------------------------------------------------- search

async function search(query, offset = 0) {
  const p = new URLSearchParams({
    gameId: String(GAME), classId: String(CLASS_MODS), gameVersion: MC,
    searchFilter: String(query || '').trim(), sortField: '2', sortOrder: 'desc',   // 2 = popularity
    pageSize: '20', index: String(offset | 0),
  });
  const r = await api('/mods/search?' + p.toString());
  const raw = (r && r.data) || [];
  return {
    total: Math.min(r && r.pagination ? r.pagination.totalCount : 0, 10000),   // CurseForge stops at index 10000
    count: raw.length,
    hits: raw.filter(modFits).map(m => ({
      id: String(m.id), slug: m.slug, title: m.name,
      author: (m.authors && m.authors[0] && m.authors[0].name) || '',
      description: m.summary || '', icon: (m.logo && (m.logo.thumbnailUrl || m.logo.url)) || '',
      downloads: m.downloadCount || 0, url: pageOf(m),
      // the author can forbid downloads outside the CurseForge app / site
      external: m.allowModDistribution === false,
    })),
  };
}

// ---------------------------------------------------------------- manifest (which jar came from CurseForge)

function manifestPath(modsDir) {
  return path.join(modsDir, MANIFEST);
}

function readManifest(modsDir) {
  try {
    const m = JSON.parse(fs.readFileSync(manifestPath(modsDir), 'utf8'));
    return m && typeof m === 'object' ? m : {};
  } catch {
    return {};
  }
}

function writeManifest(modsDir, m) {
  fs.mkdirSync(modsDir, { recursive: true });
  fs.writeFileSync(manifestPath(modsDir), JSON.stringify(m, null, 2));
}

const baseName = f => String(f).replace(/\.disabled$/i, '');

/** The manifest without entries whose jar is gone (deleted by hand, replaced...). */
function liveManifest(modsDir) {
  const m = readManifest(modsDir);
  let changed = false;
  for (const name of Object.keys(m)) {
    if (!fs.existsSync(path.join(modsDir, name)) && !fs.existsSync(path.join(modsDir, name + '.disabled'))) {
      delete m[name];
      changed = true;
    }
  }
  if (changed) writeManifest(modsDir, m);
  return m;
}

// ---------------------------------------------------------------- download

async function downloadFile(file, modsDir, enabled = true) {
  if (!file.downloadUrl) {
    const e = new Error('この MOD は作者の設定で CurseForge のサイトからしかダウンロードできません');
    e.external = true;
    throw e;
  }
  const res = await net.fetch(file.downloadUrl, { headers: { 'User-Agent': ua() } });
  if (!res.ok) throw new Error(`ダウンロードに失敗しました（HTTP ${res.status}）`);
  const buf = Buffer.from(await res.arrayBuffer());
  const want = (file.hashes || []).find(h => h.algo === 1);   // 1 = SHA-1
  if (want && want.value && sha1(buf) !== String(want.value).toLowerCase()) throw new Error('ダウンロードしたファイルが壊れています');
  const name = safeName(file.fileName);
  const target = path.join(modsDir, name + (enabled ? '' : '.disabled'));
  fs.mkdirSync(modsDir, { recursive: true });
  fs.writeFileSync(target + '.part', buf);
  fs.renameSync(target + '.part', target);
  return name;
}

function remember(modsDir, name, mod, file) {
  const m = readManifest(modsDir);
  m[name] = {
    modId: mod.id, fileId: file.id, version: file.displayName || file.fileName,
    title: mod.name, slug: mod.slug, icon: (mod.logo && (mod.logo.thumbnailUrl || mod.logo.url)) || '', url: pageOf(mod),
  };
  writeManifest(modsDir, m);
}

/** Installs a mod and its required dependencies. Resolves with the number of mods added. */
async function install(modId, modsDir) {
  const have = new Set(Object.values(liveManifest(modsDir)).map(e => e.modId));
  const seen = new Set();
  let added = 0;
  async function add(id, depth) {
    if (seen.has(id) || depth > 4) return;
    seen.add(id);
    const [modRes, file] = await Promise.all([api(`/mods/${id}`), latestFile(id)]);
    const mod = modRes && modRes.data;
    if (!mod || !file) {
      if (depth === 0) throw new Error('この MOD には Forge 1.8.9 版がありません');
      return;   // a dependency without a 1.8.9 build: skip it, the main mod is still installed
    }
    if (!have.has(mod.id)) {
      try {
        const name = await downloadFile(file, modsDir);
        remember(modsDir, name, mod, file);
        have.add(mod.id);
        added++;
      } catch (e) {
        if (depth === 0) { e.url = pageOf(mod); throw e; }
        return;
      }
    }
    for (const d of file.dependencies || []) {
      if (d.relationType === REQUIRED && d.modId) await add(d.modId, depth + 1);
    }
  }
  await add(Number(modId), 0);
  return added;
}

/** Mod ids installed from CurseForge (to mark search results as installed). */
function installedProjects(modsDir) {
  return Object.values(liveManifest(modsDir)).map(e => String(e.modId));
}

/**
 * Adds CurseForge info to the installed-mods list (from modrinth.installed): mods that Modrinth did not know
 * but that were installed from here get their name, icon and version; with checkUpdates, also a newer file.
 */
async function annotate(list, modsDir, { checkUpdates = false } = {}) {
  const m = liveManifest(modsDir);
  const updates = checkUpdates ? await findUpdates(modsDir, m) : {};
  return list.map(item => {
    const e = m[baseName(item.file)];
    if (!e || item.project) return item;
    const u = updates[baseName(item.file)];
    return {
      ...item, source: 'curseforge', name: e.title,
      project: { id: String(e.modId), title: e.title, icon: e.icon, slug: e.slug, url: e.url },
      version: { id: String(e.fileId), number: e.version },
      update: u ? { id: String(u.id), number: u.displayName || u.fileName } : null,
    };
  });
}

/** jar name -> newer CurseForge file, only for the ones that have one. */
async function findUpdates(modsDir, m = liveManifest(modsDir)) {
  const out = {};
  await Promise.all(Object.entries(m).map(async ([name, e]) => {
    try {
      const f = await latestFile(e.modId);
      if (f && f.id !== e.fileId && Date.parse(f.fileDate) > 0) out[name] = f;
    } catch {
      // offline / no key: no update shown
    }
  }));
  return out;
}

/** Updates the given jars (or every CurseForge mod when files is null), except skip. Resolves with the number updated. */
async function update(modsDir, files = null, skip = []) {
  const m = liveManifest(modsDir);
  for (const name of skip) delete m[name];
  if (!Object.keys(m).length) return 0;
  const want = files ? new Set(files.map(baseName)) : null;
  const pick = Object.fromEntries(Object.entries(m).filter(([name]) => !want || want.has(name)));
  if (!Object.keys(pick).length) return 0;
  const updates = await findUpdates(modsDir, pick);
  let n = 0;
  for (const [name, file] of Object.entries(updates)) {
    if (!file.downloadUrl) continue;   // site-only mod: shown as "update available", installed by hand
    const e = m[name];
    const enabled = fs.existsSync(path.join(modsDir, name));
    const oldFull = path.join(modsDir, name + (enabled ? '' : '.disabled'));
    const newName = await downloadFile(file, modsDir, enabled);
    if (newName !== name) {
      try { await shell.trashItem(oldFull); } catch { fs.renameSync(oldFull, oldFull + '.old'); }
    }
    const cur = readManifest(modsDir);
    delete cur[name];
    cur[newName] = { ...e, fileId: file.id, version: file.displayName || file.fileName };
    writeManifest(modsDir, cur);
    n++;
  }
  return n;
}

module.exports = { search, install, installedProjects, annotate, update, fileFits };
