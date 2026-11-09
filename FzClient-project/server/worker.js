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
//   Identity check: Mojang refuses requests from Cloudflare (403), so with the secrets VERIFY_URL (the Deno Deploy
//        address of verify-deno.ts) and VERIFY_SECRET set, the check is done there and its signed answer used.
//   GET  /cf/v1/...                             -> CurseForge API for the launcher (Minecraft mods only).
//        The CurseForge API key stays here as the secret CF_API_KEY, so it is never inside the launcher.

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
 * The same check done by verify-deno.ts on Deno Deploy (Mojang turns Cloudflare away). Its answer is signed with
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

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (url.pathname.startsWith('/cf/')) return curseforge(req, env, ctx, url);
    if (url.pathname.startsWith('/cos/')) return cosmetics(req, env, url);
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
