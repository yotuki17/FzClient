// FzClient - Mojang identity check, run on Deno Deploy (Mojang turns Cloudflare Workers away).
//
//   GET /?username=<name>&serverId=<hex>  -> {id, status, ts, sig}
//
// It asks Mojang's sessionserver "hasJoined" (the same check a Minecraft server does) and signs the answer with
// HMAC-SHA256 using the secret VERIFY_SECRET, which only this program and the FzClient Worker know. So the Worker
// can trust the answer even though it passes through the internet. Stores nothing.
//
// Setup: Deno Deploy -> new project (playground) -> paste this file -> Settings -> Environment Variables ->
// VERIFY_SECRET = the same value as the Worker secret VERIFY_SECRET.

const SECRET = Deno.env.get("VERIFY_SECRET") ?? "";
const enc = new TextEncoder();
const key = SECRET
  ? crypto.subtle.importKey("raw", enc.encode(SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"])
  : null;

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");

Deno.serve(async (req) => {
  if (!key) return Response.json({ error: "VERIFY_SECRET is not set" }, { status: 503 });
  const u = new URL(req.url);
  const name = u.searchParams.get("username") ?? "";
  const serverId = u.searchParams.get("serverId") ?? "";
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^-?[0-9a-f]{1,64}$/.test(serverId)) {
    return Response.json({ error: "bad input" }, { status: 400 });
  }
  let status = -1;
  let id = "";
  try {
    const r = await fetch(
      "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + encodeURIComponent(name) +
        "&serverId=" + encodeURIComponent(serverId),
      { headers: { "User-Agent": "FzClient-Verify" } },
    );
    status = r.status;
    if (r.status === 200) {
      const p = await r.json();
      const s = String(p.id ?? "").toLowerCase().replace(/-/g, "");
      if (/^[0-9a-f]{32}$/.test(s)) id = s;
    } else {
      await r.body?.cancel();
    }
  } catch {
    status = -1;
  }
  const ts = Date.now();
  const sig = hex(await crypto.subtle.sign("HMAC", await key, enc.encode(`${id}|${name}|${serverId}|${status}|${ts}`)));
  return Response.json({ id, status, ts, sig });
});
