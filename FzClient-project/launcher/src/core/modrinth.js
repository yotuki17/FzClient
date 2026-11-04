'use strict';
// Modrinth (https://modrinth.com) for the MOD page: search, install (with required dependencies), recognise the
// jars already in mods/ by their SHA-1, and update them to the newest Forge 1.8.9 build.
// Only public API endpoints are used; no account is needed.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app, net, shell } = require('electron');

const API = 'https://api.modrinth.com/v2';
const MC = '1.8.9';
const LOADER = 'forge';
/** Jars the launcher manages itself; never touched here. */
const OWN = /^fzclient(opt)?\.jar(\.disabled)?$/i;

function ua() {
  // Modrinth asks every app to identify itself
  return `yotuki17/FzClient-Launcher/${app.getVersion()} (github.com/yotuki17/FzClient)`;
}

async function api(pathAndQuery, body) {
  const res = await net.fetch(API + pathAndQuery, body === undefined
    ? { headers: { 'User-Agent': ua() } }
    : { method: 'POST', headers: { 'User-Agent': ua(), 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`Modrinth に接続できませんでした（HTTP ${res.status}）`);
  return res.json();
}

const q = v => encodeURIComponent(JSON.stringify(v));

/**
 * True when a version (or file) really is for Forge 1.8.9. Some projects tag every Minecraft version and every
 * loader at once (e.g. "works everywhere" patchers); a jar that claims 1.13+ or Fabric / NeoForge as well
 * cannot be a real 1.8.9 Forge mod, so those are left out.
 */
function fits118(gameVersions, loaders) {
  const g = (gameVersions || []).map(String);
  if (!g.includes(MC)) return false;
  if (loaders && !loaders.map(l => String(l).toLowerCase()).includes(LOADER)) return false;
  if (loaders && loaders.some(l => /^(fabric|quilt|neoforge|liteloader|rift)$/i.test(String(l)))) return false;
  return !g.some(v => /^1\.(1[3-9]|[2-9]\d)(\.|$)/.test(v) || /^\d\dw\d\d/.test(v));
}
const fits = v => !!v && fits118(v.game_versions, v.loaders);

function sha1(buf) {
  return crypto.createHash('sha1').update(buf).digest('hex');
}

// ---------------------------------------------------------------- what is in mods/

/** Jar files in mods/ (enabled and disabled), except FzClient's own. */
function jars(modsDir) {
  fs.mkdirSync(modsDir, { recursive: true });
  return fs.readdirSync(modsDir)
    .filter(f => /\.jar(\.disabled)?$/i.test(f) && !OWN.test(f))
    .map(f => {
      const full = path.join(modsDir, f);
      const st = fs.statSync(full);
      return { file: f, full, size: st.size, mtime: st.mtimeMs, enabled: !/\.disabled$/i.test(f) };
    });
}

// SHA-1 per file, remembered by size + mtime so the page does not re-read every jar each time
const hashCache = new Map();
function hashOf(j) {
  const key = `${j.full}|${j.size}|${j.mtime}`;
  let h = hashCache.get(key);
  if (!h) {
    h = sha1(fs.readFileSync(j.full));
    hashCache.set(key, h);
  }
  return h;
}

// Modrinth info per hash (version + project), kept for the whole launcher session
const known = new Map();   // sha1 -> { version, project } | null (= not on Modrinth)

/**
 * The installed mods with whatever Modrinth knows about them:
 * { file, name, enabled, size, project?: { id, title, icon, slug }, version?: { id, number }, update?: { id, number } }
 */
async function installed(modsDir, { checkUpdates = false } = {}) {
  const list = jars(modsDir).map(j => ({ ...j, sha1: hashOf(j) }));
  const unknown = list.map(j => j.sha1).filter(h => !known.has(h));
  if (unknown.length) {
    try {
      const versions = await api('/version_files', { hashes: unknown, algorithm: 'sha1' }) || {};
      const projectIds = [...new Set(Object.values(versions).map(v => v.project_id))];
      const projects = projectIds.length ? await api('/projects?ids=' + q(projectIds)) || [] : [];
      const byId = new Map(projects.map(p => [p.id, p]));
      for (const h of unknown) {
        const v = versions[h];
        const p = v && byId.get(v.project_id);
        known.set(h, v && p ? {
          version: { id: v.id, number: v.version_number },
          project: { id: p.id, title: p.title, icon: p.icon_url || '', slug: p.slug },
        } : null);
      }
    } catch {
      // offline: show the files without Modrinth info, try again next time
    }
  }
  let updates = {};
  if (checkUpdates) updates = await findUpdates(list.filter(j => known.get(j.sha1)).map(j => j.sha1));
  return list.map(j => {
    const k = known.get(j.sha1);
    const u = updates[j.sha1];
    return {
      file: j.file, enabled: j.enabled, size: j.size,
      name: k ? k.project.title : j.file.replace(/\.disabled$/i, '').replace(/\.jar$/i, ''),
      project: k ? k.project : null, version: k ? k.version : null,
      update: u ? { id: u.id, number: u.version_number } : null,
    };
  });
}

/** sha1 -> newest Forge 1.8.9 version, only for files that have a newer one. */
async function findUpdates(hashes) {
  if (!hashes.length) return {};
  const res = await api('/version_files/update', { hashes, algorithm: 'sha1', loaders: [LOADER], game_versions: [MC] }) || {};
  const out = {};
  for (const [h, v] of Object.entries(res)) {
    if (!fits(v)) continue;
    const file = primary(v);
    if (file && file.hashes && file.hashes.sha1 !== h) out[h] = v;
  }
  return out;
}

function primary(version) {
  const files = (version && version.files) || [];
  return files.find(f => f.primary) || files[0];
}

// ---------------------------------------------------------------- download

function safeName(name) {
  const base = path.basename(String(name || ''));
  if (!/\.jar$/i.test(base) || base.startsWith('.')) throw new Error('jar ではないファイルです');
  return base;
}

async function downloadVersion(version, modsDir, enabled = true) {
  const f = primary(version);
  if (!f) throw new Error('ダウンロードできるファイルがありません');
  const res = await net.fetch(f.url, { headers: { 'User-Agent': ua() } });
  if (!res.ok) throw new Error(`ダウンロードに失敗しました（HTTP ${res.status}）`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (f.hashes && f.hashes.sha1 && sha1(buf) !== f.hashes.sha1) throw new Error('ダウンロードしたファイルが壊れています');
  const name = safeName(f.filename) + (enabled ? '' : '.disabled');
  const target = path.join(modsDir, name);
  fs.writeFileSync(target + '.part', buf);
  fs.renameSync(target + '.part', target);
  return name;
}

/** Newest real Forge 1.8.9 version of a project, or null. */
async function latestVersion(projectId) {
  const list = await api(`/project/${encodeURIComponent(projectId)}/version?loaders=${q([LOADER])}&game_versions=${q([MC])}`);
  return (list || []).find(fits) || null;
}

/** Every real Forge 1.8.9 version of a project, newest first: [{ id, number, date, type }] */
async function versions(projectId) {
  const list = await api(`/project/${encodeURIComponent(projectId)}/version?loaders=${q([LOADER])}&game_versions=${q([MC])}`);
  return (list || []).filter(fits).map(v => ({ id: v.id, number: v.version_number, date: v.date_published, type: v.version_type }));
}

/** Replaces the installed jar `file` by another version of the same project (newer or older). */
async function setVersion(modsDir, file, versionId) {
  const j = jars(modsDir).find(x => x.file === file);
  const k = j && known.get(hashOf(j));
  if (!k) throw new Error('この MOD は Modrinth のものではありません');
  const v = await api(`/version/${encodeURIComponent(versionId)}`);
  if (!v || v.project_id !== k.project.id || !fits(v)) throw new Error('この MOD の Forge 1.8.9 版ではありません');
  const name = await downloadVersion(v, modsDir, j.enabled);
  if (name !== j.file) {
    try { await shell.trashItem(j.full); } catch { fs.renameSync(j.full, j.full + '.old'); }
  }
  return name;
}

// project id -> true / false (has a real Forge 1.8.9 build), for the whole launcher session
const compat = new Map();
async function compatible(projectId) {
  if (compat.has(projectId)) return compat.get(projectId);
  let ok;
  try {
    ok = !!(await latestVersion(projectId));
  } catch {
    return true;   // could not ask: show it, installing will say if it does not fit
  }
  compat.set(projectId, ok);
  return ok;
}

/** Installs a project and its required dependencies. Resolves with the titles that were added. */
async function install(projectId, modsDir) {
  const have = new Set();
  for (const j of jars(modsDir)) {
    const k = known.get(hashOf(j));
    if (k) have.add(k.project.id);
  }
  const added = [];
  const seen = new Set();
  async function add(id, depth) {
    if (seen.has(id) || depth > 4) return;
    seen.add(id);
    const v = await latestVersion(id);
    if (!v) {
      if (depth === 0) throw new Error('この MOD には Forge 1.8.9 版がありません');
      return;   // a dependency without a 1.8.9 build: skip it, the main mod is still installed
    }
    if (!have.has(v.project_id)) {
      await downloadVersion(v, modsDir);
      have.add(v.project_id);
      added.push(v.project_id);
    }
    for (const d of v.dependencies || []) {
      if (d.dependency_type === 'required' && d.project_id) await add(d.project_id, depth + 1);
    }
  }
  await add(projectId, 0);
  return added.length;
}

/**
 * Replaces each given file (or every Modrinth mod when files is null) by its newest Forge 1.8.9 build.
 * The old jar goes to the Recycle Bin, so an update can be undone. Resolves with the number of updated mods.
 */
async function update(modsDir, files = null) {
  const list = jars(modsDir).filter(j => !files || files.includes(j.file)).map(j => ({ ...j, sha1: hashOf(j) }));
  const updates = await findUpdates(list.map(j => j.sha1));
  let n = 0;
  for (const j of list) {
    const v = updates[j.sha1];
    if (!v) continue;
    const name = await downloadVersion(v, modsDir, j.enabled);
    if (name !== j.file) {
      try { await shell.trashItem(j.full); } catch { fs.renameSync(j.full, j.full + '.old'); }
    }
    n++;
  }
  return n;
}

// ---------------------------------------------------------------- search

async function search(query, offset = 0) {
  const facets = [[`versions:${MC}`], [`categories:${LOADER}`], ['project_type:mod']];
  const index = query && query.trim() ? 'relevance' : 'downloads';
  const r = await api(`/search?query=${encodeURIComponent(query || '')}&facets=${q(facets)}&index=${index}&limit=20&offset=${offset | 0}`);
  const raw = r ? r.hits : [];
  // the search filters say "has some 1.8.9 build" and "has some Forge build", not "has a Forge 1.8.9 build":
  // check each result and drop the ones without one
  const ok = await Promise.all(raw.map(h => compatible(h.project_id)));
  return {
    total: r ? r.total_hits : 0,
    count: raw.length,   // results read from Modrinth (some may have been dropped), for the next offset
    hits: raw.filter((h, i) => ok[i]).map(h => ({
      id: h.project_id, slug: h.slug, title: h.title, author: h.author, description: h.description,
      icon: h.icon_url || '', downloads: h.downloads,
    })),
  };
}

/** Project ids of the installed Modrinth mods (to mark search results as installed). */
function installedProjects(modsDir) {
  const ids = [];
  for (const j of jars(modsDir)) {
    const k = known.get(hashOf(j));
    if (k) ids.push(k.project.id);
  }
  return ids;
}

/** Jar names (without .disabled) that Modrinth recognised: their updates come from Modrinth. */
function knownFiles(modsDir) {
  return jars(modsDir).filter(j => known.get(hashOf(j))).map(j => j.file.replace(/\.disabled$/i, ''));
}

module.exports = { knownFiles,  installed, install, update, search, installedProjects, versions, setVersion, fits118, safeName, sha1, jars, OWN };
