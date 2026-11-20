'use strict';
// Skins for the SKIN page: a library of skins kept by the launcher, and changing the skin / cape of a Microsoft
// account through Minecraft's own profile API (the same thing minecraft.net does).
//
// Library: <userData>/skins/<id>.png + index.json [{ id, name, variant, added }]
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app, net } = require('electron');

const PROFILE = 'https://api.minecraftservices.com/minecraft/profile';

function dir() {
  return path.join(app.getPath('userData'), 'skins');
}
function indexFile() {
  return path.join(dir(), 'index.json');
}
function readIndex() {
  try {
    const l = JSON.parse(fs.readFileSync(indexFile(), 'utf8'));
    return Array.isArray(l) ? l : [];
  } catch {
    return [];
  }
}
function writeIndex(list) {
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(indexFile() + '.tmp', JSON.stringify(list, null, 2));
  fs.renameSync(indexFile() + '.tmp', indexFile());
}
const cleanId = id => (/^[a-f0-9]{16}$/.test(String(id)) ? String(id) : null);
const variantOf = v => (String(v).toLowerCase() === 'slim' ? 'slim' : 'classic');

/** Width / height of a PNG, or null when it is not a PNG. */
function pngSize(buf) {
  if (!buf || buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47 || buf.readUInt32BE(4) !== 0x0d0a1a0a) return null;
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

/** Throws when the picture cannot be a Minecraft skin. */
function checkSkin(buf) {
  const s = pngSize(buf);
  if (!s) throw new Error('PNG の画像ではありません');
  if (!(s.w === 64 && (s.h === 64 || s.h === 32))) throw new Error('スキンは 64×64（または 64×32）の PNG にしてください');
  if (buf.length > 24 * 1024) throw new Error('ファイルが大きすぎます（24KB まで）');
}

const dataUrl = buf => 'data:image/png;base64,' + buf.toString('base64');

// ---------------------------------------------------------------- library

function list() {
  return readIndex().map(e => {
    let png = null;
    try { png = fs.readFileSync(path.join(dir(), e.id + '.png')); } catch { /* file gone */ }
    return png ? { ...e, image: dataUrl(png) } : null;
  }).filter(Boolean);
}

function add(buf, name, variant) {
  checkSkin(buf);
  const hash = crypto.createHash('sha1').update(buf).digest('hex').slice(0, 16);
  const all = readIndex();
  const had = all.find(e => e.id === hash);
  if (had) return had;   // the same picture is already in the library
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(path.join(dir(), hash + '.png'), buf);
  const entry = { id: hash, name: String(name || 'Skin').slice(0, 32), variant: variantOf(variant), added: Date.now() };
  all.unshift(entry);
  writeIndex(all);
  return entry;
}

function update(id, patch) {
  const all = readIndex();
  const e = all.find(x => x.id === cleanId(id));
  if (!e) throw new Error('スキンが見つかりません');
  if (patch.name != null) e.name = String(patch.name).trim().slice(0, 32) || e.name;
  if (patch.variant != null) e.variant = variantOf(patch.variant);
  writeIndex(all);
  return e;
}

function remove(id) {
  const k = cleanId(id);
  if (!k) return;
  writeIndex(readIndex().filter(e => e.id !== k));
  try { fs.unlinkSync(path.join(dir(), k + '.png')); } catch { /* already gone */ }
}

function file(id) {
  const k = cleanId(id);
  if (!k) throw new Error('スキンが見つかりません');
  return fs.readFileSync(path.join(dir(), k + '.png'));
}

async function download(url) {
  const u = String(url).replace(/^http:\/\//, 'https://');
  if (!/^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]+$/.test(u)) throw new Error('スキンの場所が正しくありません');
  const res = await net.fetch(u);
  if (!res.ok) throw new Error(`スキンを取得できませんでした（HTTP ${res.status}）`);
  return Buffer.from(await res.arrayBuffer());
}

/** Adds the skin that a player is wearing right now (by player name). */
async function addFromPlayer(name) {
  if (!/^[A-Za-z0-9_]{3,16}$/.test(String(name || ''))) throw new Error('プレイヤー名は英数字と _ の3〜16文字です');
  const idRes = await net.fetch('https://api.mojang.com/users/profiles/minecraft/' + encodeURIComponent(name));
  if (idRes.status === 404 || idRes.status === 204) throw new Error('そのプレイヤーは見つかりませんでした');
  if (!idRes.ok) throw new Error(`Mojang に接続できませんでした（HTTP ${idRes.status}）`);
  const who = await idRes.json();
  const pRes = await net.fetch('https://sessionserver.mojang.com/session/minecraft/profile/' + who.id);
  if (!pRes.ok) throw new Error(`Mojang に接続できませんでした（HTTP ${pRes.status}）`);
  const p = await pRes.json();
  const prop = (p.properties || []).find(x => x.name === 'textures');
  const tex = prop ? JSON.parse(Buffer.from(prop.value, 'base64').toString('utf8')).textures : {};
  if (!tex.SKIN) throw new Error('このプレイヤーは標準のスキンです');
  const buf = await download(tex.SKIN.url);
  const slim = tex.SKIN.metadata && tex.SKIN.metadata.model === 'slim';
  return add(buf, who.name, slim ? 'slim' : 'classic');
}

// ---------------------------------------------------------------- the account's own skin and cape

async function api(token, method, url, body) {
  const headers = { Authorization: 'Bearer ' + token };
  let payload;
  if (body && body.multipart) { headers['Content-Type'] = 'multipart/form-data; boundary=' + body.boundary; payload = body.data; }
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await net.fetch(url, { method, headers, body: payload });
  if (res.status === 401) throw new Error('もう一度ログインしてください');
  if (res.status === 429) throw new Error('変更が多すぎます。少し待ってからもう一度試してください');
  if (!res.ok) {
    let msg = '';
    try { const j = await res.json(); msg = j.errorMessage || j.error || ''; } catch { /* not json */ }
    throw new Error(`Minecraft のサーバーに断られました（HTTP ${res.status}${msg ? ': ' + msg : ''}）`);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : {};
}

/** What the account wears now: { skin: { image, variant } | null, capes: [{ id, name, image, active }] }. */
async function current(token) {
  const p = await api(token, 'GET', PROFILE);
  return shape(p);
}

async function shape(p) {
  const active = (p.skins || []).find(s => s.state === 'ACTIVE');
  let skin = null;
  if (active) {
    try {
      skin = { image: dataUrl(await download(active.url)), variant: variantOf(active.variant) };
    } catch { /* show without the picture */ }
  }
  const capes = [];
  for (const c of p.capes || []) {
    let image = '';
    try { image = dataUrl(await download(c.url)); } catch { /* no picture */ }
    capes.push({ id: c.id, name: c.alias || 'Cape', image, active: c.state === 'ACTIVE' });
  }
  return { skin, capes };
}

/** Puts a library skin on the account. */
async function apply(token, id, variant) {
  const buf = file(id);
  checkSkin(buf);
  // multipart/form-data by hand: "variant" and the PNG as "file"
  const boundary = '----FzClientSkin' + crypto.randomBytes(8).toString('hex');
  const data = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="variant"\r\n\r\n${variantOf(variant)}\r\n`),
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="skin.png"\r\nContent-Type: image/png\r\n\r\n`),
    buf,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);
  return shape(await api(token, 'POST', PROFILE + '/skins', { multipart: true, boundary, data }));
}

/** Back to the default skin (Steve / Alex). */
async function reset(token) {
  return shape(await api(token, 'DELETE', PROFILE + '/skins/active'));
}

/** Shows one of the account's capes, or none (id = null). */
async function cape(token, id) {
  if (id) {
    if (!/^[0-9a-f-]{36}$/i.test(String(id))) throw new Error('bad cape');
    return shape(await api(token, 'PUT', PROFILE + '/capes/active', { capeId: id }));
  }
  return shape(await api(token, 'DELETE', PROFILE + '/capes/active'));
}

// ---------------------------------------------------------------- faces for the account list / home screen
// The picture comes from the player's real skin (Mojang's session server), not from a third-party head service: those
// keep answering with Steve for a while after a hiccup. The last skin that came is kept on disk, so a Mojang outage
// still shows the right face.
const FACE_FOR = 5 * 60 * 1000;
const faceMemo = new Map();   // uuid -> { at, p: Promise<{ skin } | { none: true } | null> }
const facesDir = () => path.join(app.getPath('userData'), 'faces');

/** The skin PNG (data URL) a player wears: { skin }, { none: true } for the default skin, or null (unknown). */
function skinOf(uuid) {
  const id = String(uuid || '').replace(/-/g, '').toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(id)) return Promise.resolve(null);
  const m = faceMemo.get(id);
  if (m && Date.now() - m.at < FACE_FOR) return m.p;
  const keep = path.join(facesDir(), id + '.png');
  const slimMark = path.join(facesDir(), id + '.slim');   // there when the kept skin has thin arms
  const kept = () => {
    try { return { skin: dataUrl(fs.readFileSync(keep)), slim: fs.existsSync(slimMark) }; } catch { return null; }
  };
  const p = (async () => {
    try {
      const res = await net.fetch('https://sessionserver.mojang.com/session/minecraft/profile/' + id);
      if (res.status === 204 || res.status === 404) return { none: true };   // not a Mojang account (offline)
      if (!res.ok) return kept();
      const prof = await res.json();
      const prop = (prof.properties || []).find(x => x.name === 'textures');
      const tex = prop ? JSON.parse(Buffer.from(prop.value, 'base64').toString('utf8')).textures || {} : {};
      if (!tex.SKIN) {
        for (const f of [keep, slimMark]) try { fs.unlinkSync(f); } catch { /* none kept */ }
        return { none: true };
      }
      const buf = await download(tex.SKIN.url);
      checkSkin(buf);
      const slim = !!(tex.SKIN.metadata && tex.SKIN.metadata.model === 'slim');
      try {
        fs.mkdirSync(facesDir(), { recursive: true });
        fs.writeFileSync(keep, buf);
        if (slim) fs.writeFileSync(slimMark, ''); else try { fs.unlinkSync(slimMark); } catch { /* was not slim */ }
      } catch { /* shown anyway */ }
      return { skin: dataUrl(buf), slim, fresh: true };
    } catch {
      return kept();   // offline or Mojang down: the last one that came
    }
  })();
  faceMemo.set(id, { at: Date.now(), p });
  // a failed look-up (nothing, or only the kept copy) is asked again after half a minute, not five
  p.then(r => {
    if (r && (r.none || r.fresh)) return;
    const now = faceMemo.get(id);
    if (now && now.p === p) now.at = Date.now() - FACE_FOR + 30000;
  });
  return p;
}

/** Forgets what skinOf knows (the account's skin was just changed). */
function forgetFace(uuid) {
  if (uuid) faceMemo.delete(String(uuid).replace(/-/g, '').toLowerCase());
  else faceMemo.clear();
}

module.exports = { list, add, update, remove, addFromPlayer, current, apply, reset, cape, checkSkin, skinOf, forgetFace };
