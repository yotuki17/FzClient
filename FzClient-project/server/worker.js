// FzClient users - who is playing with FzClient right now.
// Cloudflare Worker + D1 (binding name: DB). Stores only Minecraft UUIDs and the last time they were seen.
//
//   POST /hello  {"uuid":"<32 hex>"}            -> marks that player as using FzClient now
//   POST /check  {"uuids":["<32 hex>", ...]}    -> {"users":[...]} the ones seen in the last 15 minutes
//   POST /bye    {"uuid":"<32 hex>"}            -> forgets that player (setting turned off)
//   POST /cos/set  {name, serverId, data, cape?} -> stores what that player wears (FzClient cosmetics).
//        Proven like joining a server: the game did a Mojang session "join" with serverId, checked here with
//        hasJoined, so only the account's owner can change it. cape = optional PNG (base64) for a custom cape.
//   POST /cos/get  {"uuids":[...]}              -> {"cosmetics":{uuid: data}}
//   GET  /cos/cape/<sha1>                       -> a custom cape PNG
//   POST /cos/admin/remove {uuid} + header x-admin-key: ADMIN_KEY secret -> takes a player's custom cape down
//   POST /preset/put {code}                    -> {id} a 6 character id for a long FzClient preset code
//   POST /preset/get {id}                      -> {code}
//   Identity check: Mojang refuses requests from Cloudflare (403), so with the secrets VERIFY_URL (the Val Town
//        address of verify.ts) and VERIFY_SECRET set, the check is done there and its signed answer used.
//   GET  /hl/all                                -> {"limits":{"BEDWARS:Lighthouse":105, ...}} build height limits
//        that FzClient players found ("Build height limit reached!"), shared so everyone knows them.
//   POST /hl/report {name, serverId, limits:{key:y}} -> stores what that player found (proven like /cos/set).
//        Each player has one value per map; the shown value is the lowest one at least two players agree on,
//        else the lowest one (a single wrong report is outvoted as soon as two players agree on another).
//   POST /dn/get {nicks:[...]}                  -> {now, found:[{nick, name, gone, how, skin, at}]} who FzClient players
//        found behind these Hypixel nicks (by nick only: there is no lookup from a real name to a nick). name null =
//        no answer any more (gone = the name that left). Answers nobody confirmed for 7 days are not given out.
//   POST /dn/report {name, serverId, items:[{nick, real, how: skin|numbers, skin, age} | {nick, how: 'reset', age}]}
//        -> {ok, now, results} (proven like /cos/set). One name per nick and one nick per name, the newest find
//        winning either way (age = ms since it was found). 'reset' takes back an answer you sent. Limits: 20 items
//        per report, one report per 10 s and 200 items a day per player, 20,000 items a day for everyone.
//   POST /dn/admin/purge {uuid} + header x-admin-key -> takes back everything that player sent and refuses more
//   GET  /cf/v1/...                             -> CurseForge API for the launcher (Minecraft mods only).
//        The CurseForge API key stays here as the secret CF_API_KEY, so it is never inside the launcher.
//   POST /dvc/id {}                             -> {id} the Discord application of the voice chat HUD
//   POST /dvc/token {grant_type, code | refresh_token} -> Discord's OAuth2 token answer. The application's
//        secrets DISCORD_CLIENT_ID and DISCORD_SECRET stay here, so they are never inside the game.

const ONLINE_MS = 15 * 60 * 1000;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
  });
}

/** Minecraft UUID as 32 lower-case hex characters, or null. */
function clean(v) {
  const s = String(v || '').toLowerCase().replace(/-/g, '');
  return /^[0-9a-f]{32}$/.test(s) ? s : null;
}

// Only the read-only CurseForge calls the launcher needs.
const CF_PATHS = [
  /^\/v1\/mods\/search$/,
  /^\/v1\/mods\/\d{1,10}$/,
  /^\/v1\/mods\/\d{1,10}\/files$/,
  /^\/v1\/mods\/\d{1,10}\/files\/\d{1,12}$/,
];

async function curseforge(req, env, ctx, url) {
  if (req.method !== 'GET') return json({ error: 'method' }, 405);
  // the key exactly as CurseForge gave it: spaces, line breaks or quotes from copy & paste are dropped
  const apiKey = String(env.CF_API_KEY || '').trim().replace(/^["'`]+|["'`]+$/g, '').trim();
  if (!apiKey) return json({ error: 'no key' }, 503);
  const sub = url.pathname.slice(3);   // "/cf/v1/..." -> "/v1/..."
  if (!CF_PATHS.some(r => r.test(sub))) return json({ error: 'not allowed' }, 403);
  if (sub === '/v1/mods/search' && url.searchParams.get('gameId') !== '432') return json({ error: 'minecraft only' }, 403);

  // the same answer for 10 minutes: fewer calls against the key's limit
  const cache = caches.default;
  const key = new Request('https://cf-cache.fzclient' + sub + url.search);
  const hit = await cache.match(key);
  if (hit) return hit;
  const res = await fetch('https://api.curseforge.com' + sub + url.search, {
    // CurseForge turns away requests without a User-Agent (the answer is then "API Key missing or invalid")
    headers: { 'x-api-key': apiKey, Accept: 'application/json', 'User-Agent': 'FzClient-Launcher (github.com/yotuki17/FzClient)' },
  });
  const out = new Response(res.body, {
    status: res.status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': res.ok ? 'public, max-age=600' : 'no-store' },
  });
  if (res.ok) ctx.waitUntil(cache.put(key, out.clone()));
  return out;
}

// ---------------------------------------------------------------- cosmetics

// Any short lowercase id is kept: the game ignores ids it does not know, so new cosmetics in the mod
// do not need a Worker update. 'custom' (an uploaded cape) is handled below.
const ID = /^[a-z0-9_]{1,24}$/;

let tablesReady = false;
async function tables(env) {
  if (tablesReady) return;
  await env.DB.batch([
    env.DB.prepare('CREATE TABLE IF NOT EXISTS cosmetics (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)'),
    env.DB.prepare('CREATE TABLE IF NOT EXISTS capes (hash TEXT PRIMARY KEY, png BLOB NOT NULL, owner TEXT NOT NULL, created INTEGER NOT NULL)'),
  ]);
  tablesReady = true;
}

const pick = v => (typeof v === 'string' && ID.test(v) ? v : '');

/** Only the known fields, with values the game can draw. */
function cleanData(d) {
  d = d && typeof d === 'object' ? d : {};
  const out = { cape: pick(d.cape), aura: pick(d.aura) };
  const halo = pick(d.halo);
  if (halo) out.halo = halo;
  const pet = pick(d.pet);
  if (pet) out.pet = pet;
  return out;
}

/** Width / height of a PNG, or null. */
function pngSize(b) {
  if (b.length < 24 || b[0] !== 0x89 || b[1] !== 0x50 || b[2] !== 0x4e || b[3] !== 0x47) return null;
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return { w: v.getUint32(16), h: v.getUint32(20) };
}

async function sha1(bytes) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-1', bytes));
  return [...d].map(x => x.toString(16).padStart(2, '0')).join('');
}

/**
 * Mojang says this account did a session "join" with this serverId just now: the request really is from them.
 * {id} when proven, else {status}: Mojang's HTTP status (204 = no such join, others = Mojang could not answer).
 */
async function verify(env, name, serverId) {
  if (!/^[A-Za-z0-9_]{1,16}$/.test(String(name || '')) || !/^-?[0-9a-f]{1,64}$/.test(String(serverId || ''))) return { status: 0 };
  if (env.VERIFY_URL && env.VERIFY_SECRET) return verifyRemote(env, name, serverId);
  const url = 'https://sessionserver.mojang.com/session/minecraft/hasJoined?username=' + encodeURIComponent(name)
    + '&serverId=' + encodeURIComponent(serverId);
  // Mojang sometimes turns Cloudflare away (403): ask a few different ways, report every answer.
  const ways = [
    { headers: { 'User-Agent': 'FzClient-Users' } },
    { headers: { Accept: 'application/json' } },
    { headers: { 'User-Agent': 'FzClient-Users (github.com/yotuki17/FzClient)', Accept: 'application/json' }, cf: { cacheTtl: 0, cacheEverything: false } },
  ];
  const statuses = [];
  for (const w of ways) {
    let res;
    try {
      res = await fetch(url, w);
    } catch {
      statuses.push(-1);
      continue;
    }
    statuses.push(res.status);
    if (res.status === 200) {
      try {
        const id = clean((await res.json()).id);
        if (id) return { id, status: statuses };
      } catch { /* try the next way */ }
    }
    if (res.status === 204) break;   // Mojang answered: no such join
  }
  return { status: statuses };
}

/**
 * The same check done by verify.ts on Val Town (Mojang turns Cloudflare away). Its answer is signed with
 * HMAC-SHA256 and the shared secret VERIFY_SECRET, so nobody in between can fake it; answers older than a minute
 * are refused.
 */
async function verifyRemote(env, name, serverId) {
  let p;
  try {
    const base = String(env.VERIFY_URL).trim().replace(/\/+$/, '');
    const res = await fetch(base + '/?username=' + encodeURIComponent(name) + '&serverId=' + encodeURIComponent(serverId));
    if (res.status !== 200) return { status: 'verifier ' + res.status };
    p = await res.json();
  } catch {
    return { status: 'verifier unreachable' };
  }
  const id = typeof p.id === 'string' ? p.id : '';
  const status = Number(p.status);
  const ts = Number(p.ts);
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > 60000) return { status: 'verifier stale' };
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(String(env.VERIFY_SECRET).trim()),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const sig = String(p.sig || '');
  if (!/^[0-9a-f]{64}$/.test(sig)) return { status: 'verifier bad signature' };
  const sigBytes = new Uint8Array(sig.match(/../g).map(h => parseInt(h, 16)));
  const ok = await crypto.subtle.verify('HMAC', key, sigBytes, new TextEncoder().encode(`${id}|${name}|${serverId}|${status}|${ts}`));
  if (!ok) return { status: 'verifier bad signature' };
  const clean_id = clean(id);
  return clean_id ? { id: clean_id, status } : { status };
}

async function cosmetics(req, env, url) {
  await tables(env);
  const now = Date.now();

  if (req.method === 'GET' && url.pathname.startsWith('/cos/cape/')) {
    const hash = url.pathname.slice('/cos/cape/'.length);
    if (!/^[0-9a-f]{40}$/.test(hash)) return json({ error: 'bad hash' }, 400);
    const row = await env.DB.prepare('SELECT png FROM capes WHERE hash = ?1').bind(hash).first();
    if (!row) return json({ error: 'not found' }, 404);
    return new Response(new Uint8Array(row.png), { headers: { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400' } });
  }
  if (req.method !== 'POST') return json({ error: 'method' }, 405);
  let body;
  try { body = await req.json(); } catch { return json({ error: 'bad json' }, 400); }

  if (url.pathname === '/cos/get') {
    const ids = [...new Set((Array.isArray(body.uuids) ? body.uuids : []).map(clean).filter(Boolean))].slice(0, 300);
    const out = {};
    for (let i = 0; i < ids.length; i += 90) {
      const part = ids.slice(i, i + 90);
      const r = await env.DB.prepare('SELECT uuid, data FROM cosmetics WHERE uuid IN (' + part.map(() => '?').join(',') + ')').bind(...part).all();
      for (const row of r.results) { try { out[row.uuid] = JSON.parse(row.data); } catch { /* skip */ } }
    }
    return json({ cosmetics: out });
  }

  if (url.pathname === '/cos/set') {
    const v = await verify(env, body.name, body.serverId);
    const id = v.id;
    if (!id) return json({ error: 'not verified', mojang: v.status }, 403);
    const old = await env.DB.prepare('SELECT data, updated FROM cosmetics WHERE uuid = ?1').bind(id).first();
    if (old && now - old.updated < 3000) return json({ error: 'too fast' }, 429);
    const data = cleanData(body.data);
    let capeHash = '';
    try { capeHash = old ? (JSON.parse(old.data).capeHash || '') : ''; } catch { /* none */ }

    if (typeof body.cape === 'string' && body.cape) {
      let bytes;
      try { bytes = Uint8Array.from(atob(body.cape), c => c.charCodeAt(0)); } catch { return json({ error: 'bad cape' }, 400); }
      const size = pngSize(bytes);
      if (!size || bytes.length > 256 * 1024 || size.w !== size.h * 2 || size.w < 64 || size.w > 1024) {
        return json({ error: 'cape must be a PNG, twice as wide as tall, 64-1024 wide, up to 256KB' }, 400);
      }
      capeHash = await sha1(bytes);
      await env.DB.prepare('INSERT OR IGNORE INTO capes (hash, png, owner, created) VALUES (?1, ?2, ?3, ?4)').bind(capeHash, bytes, id, now).run();
    }
    if (data.cape === 'custom' && capeHash) data.capeHash = capeHash;
    else if (data.cape === 'custom') data.cape = '';
    await env.DB.prepare('INSERT INTO cosmetics (uuid, data, updated) VALUES (?1, ?2, ?3) ON CONFLICT(uuid) DO UPDATE SET data = ?2, updated = ?3')
      .bind(id, JSON.stringify(data), now).run();
    // custom capes nobody uses any more
    await env.DB.prepare('DELETE FROM capes WHERE owner = ?1 AND hash != ?2').bind(id, data.capeHash || '').run();
    return json({ ok: true, capeHash: data.capeHash || '', mojang: v.status });
  }

  if (url.pathname === '/cos/admin/remove') {
    if (!env.ADMIN_KEY || req.headers.get('x-admin-key') !== env.ADMIN_KEY) return json({ error: 'forbidden' }, 403);
    const id = clean(body.uuid);
    if (!id) return json({ error: 'bad uuid' }, 400);
    const row = await env.DB.prepare('SELECT data FROM cosmetics WHERE uuid = ?1').bind(id).first();
    if (row) {
      const d = JSON.parse(row.data);
      if (d.capeHash) await env.DB.prepare('DELETE FROM capes WHERE hash = ?1').bind(d.capeHash).run();
      d.cape = ''; delete d.capeHash;
      await env.DB.prepare('UPDATE cosmetics SET data = ?2 WHERE uuid = ?1').bind(id, JSON.stringify(d)).run();
    }
    return json({ ok: true });
  }
  return json({ error: 'not found' }, 404);
}

// ---------------------------------------------------------------- short preset codes

// 6 characters from 31 that are hard to mix up (no 0/O, 1/I/L): about 887 million codes.
const SHORT_CHARS = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
const SHORT = /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$/;
const LONG = /^FZ[12]-[A-Za-z0-9_-]{1,6000}$/;

let presetsReady = false;
async function presetTable(env) {
  if (presetsReady) return;
  await env.DB.batch([
    env.DB.prepare('CREATE TABLE IF NOT EXISTS presets (id TEXT PRIMARY KEY, hash TEXT NOT NULL UNIQUE, code TEXT NOT NULL, created INTEGER NOT NULL)'),
  ]);
  presetsReady = true;
}

function shortId() {
  const b = crypto.getRandomValues(new Uint8Array(6));
  let s = '';
  for (const x of b) s += SHORT_CHARS[x % SHORT_CHARS.length];
  return s;
}

/** POST /preset/put {code} -> {id}. The same code always gets the same id. */
async function presetPut(env, body) {
  const code = String(body.code || '').trim();
  if (!LONG.test(code)) return json({ error: 'bad code' }, 400);
  await presetTable(env);
  const hash = await sha1(new TextEncoder().encode(code));
  const old = await env.DB.prepare('SELECT id FROM presets WHERE hash = ?1').bind(hash).first();
  if (old) return json({ id: old.id });
  for (let i = 0; i < 6; i++) {
    const id = shortId();
    const r = await env.DB.prepare('INSERT OR IGNORE INTO presets (id, hash, code, created) VALUES (?1, ?2, ?3, ?4)')
      .bind(id, hash, code, Date.now()).run();
    if (r.meta && r.meta.changes > 0) return json({ id });
    const again = await env.DB.prepare('SELECT id FROM presets WHERE hash = ?1').bind(hash).first();
    if (again) return json({ id: again.id });   // saved by someone else at the same moment
  }
  return json({ error: 'busy' }, 503);
}

/** POST /preset/get {id} -> {code} */
async function presetGet(env, body) {
  const id = String(body.id || '').trim().toUpperCase();
  if (!SHORT.test(id)) return json({ error: 'bad id' }, 400);
  await presetTable(env);
  const row = await env.DB.prepare('SELECT code FROM presets WHERE id = ?1').bind(id).first();
  return row ? json({ code: row.code }) : json({ error: 'not found' }, 404);
}

// ------------------------------------------------------------------ build height limits

const HL_KEY = /^[A-Z_?]{1,24}:[^\u0000-\u001f"\\]{1,40}$/;
let hlReady = false;
async function hlTable(env) {
  if (hlReady) return;
  await env.DB.batch([
    env.DB.prepare('CREATE TABLE IF NOT EXISTS heightlimits (key TEXT NOT NULL, uuid TEXT NOT NULL, y INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (key, uuid))'),
    env.DB.prepare('CREATE INDEX IF NOT EXISTS heightlimits_uuid ON heightlimits (uuid, at)'),
  ]);
  hlReady = true;
}

async function heightLimits(req, env, url) {
  await hlTable(env);
  if (url.pathname === '/hl/all') {
    const r = await env.DB.prepare('SELECT key, y, COUNT(*) AS n FROM heightlimits GROUP BY key, y').all();
    const best = {};
    for (const row of r.results) {
      const b = best[row.key];
      // agreed on by two or more players beats a lone report; then the lower one
      const agreed = row.n >= 2;
      if (!b || (agreed && !b.agreed) || (agreed === b.agreed && row.y < b.y)) best[row.key] = { y: row.y, agreed };
    }
    const limits = {};
    for (const k of Object.keys(best)) limits[k] = best[k].y;
    return json({ limits });
  }
  if (url.pathname === '/hl/report' && req.method === 'POST') {
    let body;
    try { body = await req.json(); } catch { return json({ error: 'bad json' }, 400); }
    const v = await verify(env, body.name, body.serverId);
    const id = v.id;
    if (!id) return json({ error: 'not verified', mojang: v.status }, 403);
    const now = Date.now();
    const recent = await env.DB.prepare('SELECT COUNT(*) AS n FROM heightlimits WHERE uuid = ?1 AND at > ?2').bind(id, now - 20000).first();
    if (recent && recent.n > 0) return json({ error: 'too fast' }, 429);
    const total = await env.DB.prepare('SELECT COUNT(*) AS n FROM heightlimits WHERE uuid = ?1').bind(id).first();
    const room = Math.max(0, 1000 - (total ? total.n : 0));
    const src = body.limits && typeof body.limits === 'object' ? body.limits : {};
    const rows = [];
    for (const k of Object.keys(src).slice(0, 50)) {
      const y = src[k];
      if (!HL_KEY.test(k) || !Number.isInteger(y) || y < 1 || y > 255) continue;
      rows.push(env.DB.prepare('INSERT INTO heightlimits (key, uuid, y, at) VALUES (?1, ?2, ?3, ?4) ON CONFLICT(key, uuid) DO UPDATE SET y = ?3, at = ?4')
        .bind(k, id, y, now));
    }
    if (rows.length > room) rows.length = room;
    if (rows.length) await env.DB.batch(rows);
    return json({ ok: true, saved: rows.length });
  }
  return json({ error: 'not found' }, 404);
}

// ------------------------------------------------------------------ shared denick answers

// One name per nick and one nick per name, the newest answer winning either way (the same rule as Denick.resolve
// in the mod). "Newest" = when the answer was found (the client sends its age), not when it got here.
// Only skin and numbers answers (public data); party and by-hand answers stay in the game that found them.
const DN_NAME = /^[A-Za-z0-9_]{1,16}$/;
const DN_SKIN = /^[0-9a-f]{0,64}$/;
const DN_HOW = new Set(['skin', 'numbers']);
const DN_DAY = 86400000;
const DN_TTL = 7 * DN_DAY;            // an answer nobody confirmed for a week is not given out any more
const DN_MAX_AGE = 10 * 60 * 1000;   // how old a reported find may say it is
const DN_ITEMS_DAY = 200;             // per player per day
const DN_ITEMS_ALL = 20000;           // everyone together per day (keeps D1's free daily writes safe)
const DN_LAST = new Map();            // per IP: last request (a light brake, per worker instance)

let dnReady = false;
async function dnTables(env) {
  if (dnReady) return;
  await env.DB.batch([
    // nick / name_l lower case; name as written; name NULL = no answer any more (gone = the name that left)
    env.DB.prepare('CREATE TABLE IF NOT EXISTS denicks (nick TEXT PRIMARY KEY, name TEXT, name_l TEXT, gone TEXT, how TEXT NOT NULL, skin TEXT NOT NULL, at INTEGER NOT NULL, seen INTEGER NOT NULL, uuid TEXT NOT NULL)'),
    env.DB.prepare('CREATE UNIQUE INDEX IF NOT EXISTS denicks_name ON denicks (name_l)'),
    env.DB.prepare('CREATE INDEX IF NOT EXISTS denicks_seen ON denicks (seen)'),
    env.DB.prepare('CREATE TABLE IF NOT EXISTS dn_quota (uuid TEXT NOT NULL, day INTEGER NOT NULL, n INTEGER NOT NULL, last INTEGER NOT NULL, PRIMARY KEY (uuid, day))'),
    env.DB.prepare('CREATE TABLE IF NOT EXISTS dn_banned (uuid TEXT PRIMARY KEY, at INTEGER NOT NULL)'),
  ]);
  dnReady = true;
}

function dnBrake(req, gap) {
  const ip = (req.headers.get('cf-connecting-ip') || '') + '|' + gap;
  const now = Date.now();
  if (now - (DN_LAST.get(ip) || 0) < gap) return true;
  DN_LAST.set(ip, now);
  if (DN_LAST.size > 5000) DN_LAST.clear();
  return false;
}

const dnStr = (v, re) => (typeof v === 'string' && re.test(v) ? v : null);

async function denicks(req, env, ctx, url) {
  if (req.method !== 'POST') return json({ error: 'post' }, 405);
  let body;
  try { body = await req.json(); } catch { return json({ error: 'bad json' }, 400); }
  if (!body || typeof body !== 'object') return json({ error: 'bad json' }, 400);
  await dnTables(env);

  if (url.pathname === '/dn/get') {
    if (dnBrake(req, 300)) return json({ error: 'too fast' }, 429);
    const nicks = [...new Set((Array.isArray(body.nicks) ? body.nicks : []).map(v => dnStr(v, DN_NAME)).filter(Boolean)
      .map(v => v.toLowerCase()))].slice(0, 100);
    const now = Date.now();
    const found = [];
    for (let i = 0; i < nicks.length; i += 90) {   // D1 allows up to 100 bound values per query
      const part = nicks.slice(i, i + 90);
      const r = await env.DB.prepare('SELECT nick, name, gone, how, skin, at FROM denicks WHERE seen > ? AND nick IN ('
        + part.map(() => '?').join(',') + ')').bind(now - DN_TTL, ...part).all();
      for (const row of r.results) found.push({ nick: row.nick, name: row.name, gone: row.gone, how: row.how, skin: row.skin, at: row.at });
    }
    return json({ now, found });
  }

  if (url.pathname === '/dn/report') {
    if (dnBrake(req, 3000)) return json({ error: 'too fast' }, 429);
    // the items first: nothing usable = no identity check
    const byNick = new Map();   // in order; a later item for the same nick replaces an earlier one
    for (const it of (Array.isArray(body.items) ? body.items : []).slice(0, 20)) {
      if (!it || typeof it !== 'object') continue;
      const nick = dnStr(it.nick, DN_NAME);
      const age = Number(it.age);
      if (!nick || !Number.isFinite(age)) continue;
      const k = nick.toLowerCase();
      const a = Math.min(DN_MAX_AGE, Math.max(0, Math.floor(age)));
      if (it.how === 'reset') {
        byNick.delete(k);
        byNick.set(k, { k, reset: true, age: a });
        continue;
      }
      const real = dnStr(it.real, DN_NAME);
      const skin = typeof it.skin === 'string' && DN_SKIN.test(it.skin) ? it.skin : null;
      if (!real || real.toLowerCase() === k || !DN_HOW.has(it.how) || skin == null) continue;
      byNick.delete(k);
      byNick.set(k, { k, real, rl: real.toLowerCase(), how: it.how, skin, age: a });
    }
    const items = [...byNick.values()];
    if (!items.length) return json({ error: 'nothing' }, 400);
    const v = await verify(env, body.name, body.serverId);
    const id = v.id;
    if (!id) return json({ error: 'not verified', mojang: v.status }, 403);
    if (await env.DB.prepare('SELECT uuid FROM dn_banned WHERE uuid = ?1').bind(id).first()) return json({ error: 'blocked' }, 403);
    const now = Date.now();
    const day = Math.floor(now / DN_DAY);
    const mine = await env.DB.prepare('SELECT n, last FROM dn_quota WHERE uuid = ?1 AND day = ?2').bind(id, day).first();
    if (mine && now - mine.last < 10000) return json({ error: 'too fast' }, 429);
    if (mine && mine.n + items.length > DN_ITEMS_DAY) return json({ error: 'daily limit' }, 429);
    const all = await env.DB.prepare('SELECT n FROM dn_quota WHERE uuid = ?1 AND day = ?2').bind('*', day).first();
    if (all && all.n + items.length > DN_ITEMS_ALL) return json({ error: 'busy' }, 503);
    // counted in one step each (two reports at the same moment both count), then checked on the result
    const upQuota = 'INSERT INTO dn_quota (uuid, day, n, last) VALUES (?1, ?2, ?3, ?4) ON CONFLICT(uuid, day) DO UPDATE SET n = dn_quota.n + excluded.n, last = excluded.last';
    await env.DB.batch([
      env.DB.prepare(upQuota).bind(id, day, items.length, now),
      env.DB.prepare(upQuota).bind('*', day, items.length, now),
    ]);
    const counted = await env.DB.prepare('SELECT n FROM dn_quota WHERE uuid = ?1 AND day = ?2').bind(id, day).first();
    if (counted && counted.n > DN_ITEMS_DAY) return json({ error: 'daily limit' }, 429);

    const results = [];
    for (const it of items) {
      try {
        results.push(await dnApply(env, it, id, now));
      } catch (e) {
        results.push('error');   // e.g. another report changed the same rows at the same moment
      }
    }
    if (Math.random() < 0.02) {
      ctx.waitUntil(env.DB.batch([
        env.DB.prepare('DELETE FROM denicks WHERE seen < ?1').bind(now - DN_TTL),
        env.DB.prepare('DELETE FROM dn_quota WHERE day < ?1').bind(day - 1),
      ]).catch(() => {}));
    }
    return json({ ok: true, now, results });
  }

  if (url.pathname === '/dn/admin/purge') {
    // takes back everything one player sent (they become "no answer" for everyone) and refuses their reports
    if (!env.ADMIN_KEY || req.headers.get('x-admin-key') !== env.ADMIN_KEY) return json({ error: 'forbidden' }, 403);
    const id = clean(body.uuid);
    if (!id) return json({ error: 'bad uuid' }, 400);
    const now = Date.now();
    const r = await env.DB.batch([
      env.DB.prepare('INSERT OR IGNORE INTO dn_banned (uuid, at) VALUES (?1, ?2)').bind(id, now),
      env.DB.prepare("UPDATE denicks SET gone = name_l, name = NULL, name_l = NULL, how = 'purged', at = ?2, seen = ?2 WHERE uuid = ?1 AND name_l IS NOT NULL").bind(id, now),
    ]);
    return json({ ok: true, removed: r[1].meta.changes });
  }
  return json({ error: 'not found' }, 404);
}

/** One reported item, by the rule above. The answer: 'new', 'same', 'older', 'moved', 'reset' or 'ignored'. */
async function dnApply(env, it, id, now) {
  const row = await env.DB.prepare('SELECT name_l, gone, uuid, at FROM denicks WHERE nick = ?1').bind(it.k).first();
  if (it.reset) {
    // taking back an answer: only one you sent yourself
    if (!row || row.name_l == null || row.uuid !== id) return 'ignored';
    const when = now - it.age;   // when it was typed
    if (row.at > when) return 'older';
    await env.DB.prepare("UPDATE denicks SET gone = name_l, name = NULL, name_l = NULL, how = 'reset', at = ?2, seen = ?4 WHERE nick = ?1 AND uuid = ?3 AND at <= ?2")
      .bind(it.k, when, id, now).run();
    return 'reset';
  }
  const at = now - it.age;
  if (row && row.name_l === it.rl) {
    // the same answer again: the name stays as it is; it now counts from this newer find (as in the game) and does
    // not run out
    await env.DB.batch([
      env.DB.prepare('UPDATE denicks SET at = ?2, skin = ?3 WHERE nick = ?1 AND at < ?2').bind(it.k, at, it.skin),
      env.DB.prepare('UPDATE denicks SET seen = ?2 WHERE nick = ?1').bind(it.k, now),
    ]);
    return 'same';
  }
  // something newer is known about this nick (another answer, or this very name leaving it)
  if (row && row.at > at && (row.name_l != null || row.gone === it.rl)) return 'older';
  const holder = await env.DB.prepare('SELECT nick, at FROM denicks WHERE name_l = ?1').bind(it.rl).first();
  if (holder && holder.at > at) {   // a tie: the later report (or item) wins
    // this player was found under another nick later: in time order this answer came and went again, so the
    // nick's earlier answer is gone too
    if (row && row.name_l != null) {
      await env.DB.prepare("UPDATE denicks SET gone = name_l, name = NULL, name_l = NULL, how = 'moved', at = ?2, seen = ?3 WHERE nick = ?1 AND at <= ?2")
        .bind(it.k, at, now).run();
    }
    return 'moved';
  }
  await env.DB.batch([
    // the same player under another nick: that nick loses the name
    // (the times are checked again in the writes: another report may have changed the rows since they were read)
    env.DB.prepare("UPDATE denicks SET gone = name_l, name = NULL, name_l = NULL, how = 'moved', at = ?3, seen = ?4 WHERE name_l = ?1 AND nick <> ?2 AND at <= ?3")
      .bind(it.rl, it.k, at, now),
    env.DB.prepare('INSERT INTO denicks (nick, name, name_l, gone, how, skin, at, seen, uuid) VALUES (?1, ?2, ?3, NULL, ?4, ?5, ?6, ?7, ?8) '
      + 'ON CONFLICT(nick) DO UPDATE SET name = excluded.name, name_l = excluded.name_l, gone = NULL, how = excluded.how, '
      + 'skin = excluded.skin, at = excluded.at, seen = excluded.seen, uuid = excluded.uuid WHERE denicks.at <= excluded.at OR denicks.name_l IS NULL')
      .bind(it.k, it.real, it.rl, it.how, it.skin, at, now, id),
  ]);
  return 'new';
}

// ------------------------------------------------------------------ Discord voice chat HUD

const DVC_REDIRECT = 'http://localhost';
const DVC_LAST = new Map();   // per IP: last token request (a light brake, per worker instance)

async function discordVc(req, env, url) {
  if (req.method !== 'POST') return json({ error: 'post' }, 405);
  const id = String(env.DISCORD_CLIENT_ID || '');
  if (!/^\d{17,20}$/.test(id) || !env.DISCORD_SECRET) return json({ error: 'not set up' }, 503);
  if (url.pathname === '/dvc/id') return json({ id });
  if (url.pathname !== '/dvc/token') return json({ error: 'not found' }, 404);
  const ip = req.headers.get('cf-connecting-ip') || '';
  const now = Date.now();
  if (now - (DVC_LAST.get(ip) || 0) < 2000) return json({ error: 'slow down' }, 429);
  DVC_LAST.set(ip, now);
  if (DVC_LAST.size > 5000) DVC_LAST.clear();
  let body;
  try { body = await req.json(); } catch { return json({ error: 'bad json' }, 400); }
  const p = new URLSearchParams();
  p.set('client_id', id);
  p.set('client_secret', env.DISCORD_SECRET);
  if (body.grant_type === 'authorization_code' && typeof body.code === 'string' && /^[\w-]{10,100}$/.test(body.code)) {
    p.set('grant_type', 'authorization_code');
    p.set('code', body.code);
    p.set('redirect_uri', DVC_REDIRECT);
  } else if (body.grant_type === 'refresh_token' && typeof body.refresh_token === 'string' && /^[\w-]{10,200}$/.test(body.refresh_token)) {
    p.set('grant_type', 'refresh_token');
    p.set('refresh_token', body.refresh_token);
  } else {
    return json({ error: 'bad request' }, 400);
  }
  const r = await fetch('https://discord.com/api/oauth2/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'FzClient (voice hud)' },
    body: p.toString(),
  });
  const text = await r.text();
  return new Response(text, { status: r.status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } });
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (url.pathname.startsWith('/cf/')) return curseforge(req, env, ctx, url);
    if (url.pathname.startsWith('/cos/')) return cosmetics(req, env, url);
    if (url.pathname.startsWith('/hl/')) return heightLimits(req, env, url);
    if (url.pathname.startsWith('/dvc/')) return discordVc(req, env, url);
    if (url.pathname.startsWith('/dn/')) return denicks(req, env, ctx, url);
    if (req.method !== 'POST') return new Response('FzClient users', { status: 200 });
    let body;
    try {
      body = await req.json();
    } catch {
      return json({ error: 'bad json' }, 400);
    }
    const now = Date.now();

    if (url.pathname === '/preset/put') return presetPut(env, body);
    if (url.pathname === '/preset/get') return presetGet(env, body);

    if (url.pathname === '/hello') {
      const id = clean(body.uuid);
      if (!id) return json({ error: 'bad uuid' }, 400);
      await env.DB.prepare('INSERT INTO users (uuid, seen) VALUES (?1, ?2) ON CONFLICT(uuid) DO UPDATE SET seen = ?2')
        .bind(id, now).run();
      return json({ ok: true });
    }

    if (url.pathname === '/bye') {
      const id = clean(body.uuid);
      if (id) await env.DB.prepare('DELETE FROM users WHERE uuid = ?1').bind(id).run();
      return json({ ok: true });
    }

    if (url.pathname === '/check') {
      const ids = [...new Set((Array.isArray(body.uuids) ? body.uuids : []).map(clean).filter(Boolean))].slice(0, 300);
      const users = [];
      for (let i = 0; i < ids.length; i += 90) {   // D1 allows up to 100 bound values per query
        const part = ids.slice(i, i + 90);
        const q = 'SELECT uuid FROM users WHERE seen > ? AND uuid IN (' + part.map(() => '?').join(',') + ')';
        const r = await env.DB.prepare(q).bind(now - ONLINE_MS, ...part).all();
        for (const row of r.results) users.push(row.uuid);
      }
      return json({ users });
    }

    return json({ error: 'not found' }, 404);
  },
};
