package dev.fzkc.fzhud.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Modules;

import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Player tags from Watcher and Xadia (community lists of cheaters, snipers and so on). Both need the player's
 * own API key from that service. Results are kept for ten minutes.
 */
public final class Tags {
    private Tags() {}

    /** One tag: a short badge for the tab list ("[BC]") and a longer text for the chat. */
    public static final class Tag {
        public final String source, badge, text;

        Tag(String source, String badge, String text) {
            this.source = source;
            this.badge = badge;
            this.text = text;
        }
    }

    private static final long FRESH_MS = 10 * 60_000L, RETRY_MS = 2 * 60_000L;

    private static final class Entry {
        volatile List<Tag> watcher, xadia, coral;
        volatile long watcherAt, xadiaAt, watcherFail, xadiaFail, coralAt, coralFail;
        volatile boolean watcherLoading, xadiaLoading, coralLoading;
        volatile String watcherError, xadiaError, coralError;
    }

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("FzClient-Tags");
    private static final Map<String, Long> LOGGED = new ConcurrentHashMap<>();
    /** Single lookups (/pv, chat) whose Watcher answer goes to the log. */
    private static final java.util.Set<UUID> verbose = java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    /** Writes a lookup problem to the game log (the same message at most once every 5 minutes). */
    private static void logProblem(String msg) {
        if (msg == null) return;
        long now = System.currentTimeMillis();
        Long last = LOGGED.get(msg);
        if (last != null && now - last < 5 * 60_000L) return;
        LOGGED.put(msg, now);
        LOG.warn("Tag lookup failed: " + msg);
    }
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FzClient-Tags");
        t.setDaemon(true);
        return t;
    });
    /** Watcher allows 60 lookups a minute per key; we stay a little below that. */
    private static final Deque<Long> watcherCalls = new ArrayDeque<>();
    private static volatile long watcherPausedUntil;

    static boolean watcherOn() { return Modules.statsWatcher.get() && !Modules.statsWatcherKey.get().trim().isEmpty(); }

    static boolean xadiaOn() { return Modules.statsXadia.get() && !Modules.statsXadiaKey.get().trim().isEmpty(); }

    static boolean coralOn() { return Modules.statsCoral.get() && !Modules.statsCoralKey.get().trim().isEmpty(); }

    /** The tags known right now (empty while loading); asks for missing ones in the background. */
    public static List<Tag> get(UUID id) {
        Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
        long now = System.currentTimeMillis();
        if (watcherOn() && !e.watcherLoading && now - e.watcherAt > FRESH_MS && now - e.watcherFail > RETRY_MS) {
            e.watcherLoading = true;
            verbose.add(id);
            POOL.execute(() -> loadWatcher(id, e));
        }
        if (xadiaOn() && !e.xadiaLoading && now - e.xadiaAt > FRESH_MS && now - e.xadiaFail > RETRY_MS) {
            e.xadiaLoading = true;
            List<UUID> one = new ArrayList<>();
            one.add(id);
            POOL.execute(() -> loadXadia(one));
        }
        if (coralOn() && !e.coralLoading && now - e.coralAt > FRESH_MS && now - e.coralFail > RETRY_MS) {
            e.coralLoading = true;
            List<UUID> one = new ArrayList<>();
            one.add(id);
            POOL.execute(() -> loadCoral(one));
        }
        return known(e);
    }

    /** Many players at once: Xadia takes up to 100 in one request. */
    public static void prefetch(List<UUID> ids) {
        long now = System.currentTimeMillis();
        List<UUID> xadia = new ArrayList<>(), coral = new ArrayList<>();
        for (UUID id : ids) {
            Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
            if (watcherOn() && !e.watcherLoading && now - e.watcherAt > FRESH_MS && now - e.watcherFail > RETRY_MS) {
                e.watcherLoading = true;
                POOL.execute(() -> loadWatcher(id, e));
            }
            if (xadiaOn() && !e.xadiaLoading && now - e.xadiaAt > FRESH_MS && now - e.xadiaFail > RETRY_MS) {
                e.xadiaLoading = true;
                xadia.add(id);
            }
            if (coralOn() && !e.coralLoading && now - e.coralAt > FRESH_MS && now - e.coralFail > RETRY_MS) {
                e.coralLoading = true;
                coral.add(id);
            }
        }
        for (int from = 0; from < coral.size(); from += 100) {
            List<UUID> part = new ArrayList<>(coral.subList(from, Math.min(coral.size(), from + 100)));
            POOL.execute(() -> loadCoral(part));
        }
        for (int from = 0; from < xadia.size(); from += 100) {
            List<UUID> part = new ArrayList<>(xadia.subList(from, Math.min(xadia.size(), from + 100)));
            POOL.execute(() -> loadXadia(part));
        }
    }

    /** Errors from the last lookups (for the stats command), or an empty list. */
    public static List<String> problems(UUID id) {
        Entry e = CACHE.get(id);
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        if (watcherOn() && e.watcherError != null) out.add(e.watcherError);
        if (xadiaOn() && e.xadiaError != null) out.add(e.xadiaError);
        if (coralOn() && e.coralError != null) out.add(e.coralError);
        return out;
    }

    public static boolean loading(UUID id) {
        Entry e = CACHE.get(id);
        return e != null && (e.watcherLoading || e.xadiaLoading || e.coralLoading);
    }

    public static void clear() { CACHE.clear(); }

    private static List<Tag> known(Entry e) {
        List<Tag> w = watcherOn() ? e.watcher : null, x = xadiaOn() ? e.xadia : null, c = coralOn() ? e.coral : null;
        if ((w == null || w.isEmpty()) && (x == null || x.isEmpty()) && (c == null || c.isEmpty())) return Collections.emptyList();
        List<Tag> out = new ArrayList<>();
        if (c != null) out.addAll(c);
        if (w != null) out.addAll(w);
        if (x != null) out.addAll(x);
        return out;
    }

    // ------------------------------------------------------------------ Watcher

    private static void loadWatcher(UUID id, Entry e) {
        try {
            if (!reserveWatcher()) {
                e.watcherFail = System.currentTimeMillis() - RETRY_MS + 15_000L;   // try again in 15 s
                return;
            }
            String key = Modules.statsWatcherKey.get().trim();
            String body = Http.get("Watcher", "https://api.watchertag.com/api/v1/cubelify?key="
                    + URLEncoder.encode(key, "UTF-8") + "&uuid=" + id.toString().replace("-", ""), null, null);
            JsonObject r = new JsonParser().parse(body).getAsJsonObject();
            JsonElement err = r.get("error");
            boolean failed = err != null && err.isJsonPrimitive()
                    && (err.getAsJsonPrimitive().isBoolean() ? err.getAsBoolean() : !err.getAsString().isEmpty() && !"false".equals(err.getAsString()));
            if (failed) {
                String code = PlayerData.str(r, "code");
                throw new java.io.IOException("Watcher: " + (code == null ? "error" : code));
            }
            List<Tag> tags = new ArrayList<>();
            JsonElement tagsEl = r.get("tags");
            JsonArray arr = tagsEl != null && tagsEl.isJsonArray() ? tagsEl.getAsJsonArray() : null;
            if (arr != null) {
                for (JsonElement te : arr) {
                    if (!te.isJsonObject()) continue;
                    JsonObject t = te.getAsJsonObject();
                    String label = clean(PlayerData.str(t, "text"));
                    if (label.isEmpty()) continue;
                    String color = colorOf(t.get("color"));
                    String tip = clean(PlayerData.str(t, "tooltip"));
                    tags.add(new Tag("Watcher", badge(watcherIcon(label), color),
                            color + "§l" + label + "§r" + (tip.isEmpty() ? "" : " §7(" + tip + ")")));
                }
            }
            if (verbose.remove(id)) {
                // the answer has no key in it; keep the log line short
                String shortBody = body.length() > 400 ? body.substring(0, 400) + "…" : body;
                LOG.info("Watcher answered for " + id + ": " + tags.size() + " tag(s) " + shortBody.replaceAll("[\r\n]+", " "));
            }
            e.watcher = tags;
            e.watcherAt = System.currentTimeMillis();
            e.watcherError = null;
        } catch (Http.Status s) {
            if (s.code == 429) watcherPausedUntil = System.currentTimeMillis() + 60_000L;
            e.watcherFail = System.currentTimeMillis();
            e.watcherError = s.code == 401 || s.code == 403 ? "Watcher: API キーが違います" : s.getMessage();
            logProblem("Watcher HTTP " + s.code);
        } catch (Exception ex) {
            e.watcherFail = System.currentTimeMillis();
            String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            e.watcherError = m.startsWith("Watcher") ? m : "Watcher: " + m;
            logProblem(e.watcherError);
        } finally {
            e.watcherLoading = false;
        }
    }

    private static synchronized boolean reserveWatcher() {
        long now = System.currentTimeMillis();
        if (now < watcherPausedUntil) return false;
        while (!watcherCalls.isEmpty() && now - watcherCalls.peekFirst() >= 60_000L) watcherCalls.removeFirst();
        if (watcherCalls.size() >= 50) return false;
        watcherCalls.addLast(now);
        return true;
    }

    private static String watcherIcon(String label) {
        switch (label.toLowerCase(Locale.ROOT)) {
            case "blatant": case "bc": return "BC";
            case "closet": case "cc": return "CC";
            case "boosting": case "booster": return "B";
            case "sniper": return "S";
            default: return label.length() > 3 ? label.substring(0, 3) : label;
        }
    }

    // ------------------------------------------------------------------ Xadia

    private static void loadXadia(List<UUID> ids) {
        try {
            JsonArray players = new JsonArray();
            for (UUID id : ids) players.add(new com.google.gson.JsonPrimitive(id.toString().replace("-", "")));
            JsonObject req = new JsonObject();
            req.add("players", players);
            req.addProperty("verified_only", Modules.statsXadiaVerified.get());
            String body = Http.post("Xadia", "https://xadia.club/v1/players", req.toString(), "X-API-Key",
                    Modules.statsXadiaKey.get().trim());
            JsonArray results = new JsonParser().parse(body).getAsJsonObject().getAsJsonArray("results");
            if (results == null) throw new java.io.IOException("Xadia: bad answer");
            long now = System.currentTimeMillis();
            for (JsonElement re : results) {
                JsonObject p = re.getAsJsonObject();
                String q = PlayerData.str(p, "query");
                UUID id = q == null ? null : match(ids, q);
                if (id == null) continue;
                List<Tag> tags = new ArrayList<>();
                JsonElement found = p.get("found");
                JsonArray arr = p.getAsJsonArray("tags");
                if (found != null && found.getAsBoolean() && arr != null) {
                    for (JsonElement te : arr) {
                        JsonObject t = te.getAsJsonObject();
                        String type = PlayerData.str(t, "type");
                        String label = clean(PlayerData.str(t, "label"));
                        String reason = clean(PlayerData.str(t, "reason"));
                        JsonElement v = t.get("verified");
                        String color = xadiaColor(type);
                        if ("possibly_cheating".equals(type)) label = "Risky";
                        String text = color + "§l" + label + "§r";
                        if (v != null && !v.isJsonNull()) text += v.getAsBoolean() ? " §a[Verified]" : " §e[Unverified]";
                        if (!reason.isEmpty()) text += " §7(" + reason + ")";
                        tags.add(new Tag("Xadia", badge(xadiaIcon(type, label), color), text));
                    }
                }
                Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
                e.xadia = tags;
                e.xadiaAt = now;
                e.xadiaError = null;
            }
        } catch (Exception ex) {
            String msg = ex instanceof Http.Status && (((Http.Status) ex).code == 401 || ((Http.Status) ex).code == 403)
                    ? "Xadia: API キーが違います" : ex.getMessage();
            for (UUID id : ids) {
                Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
                e.xadiaFail = System.currentTimeMillis();
                e.xadiaError = msg == null ? "Xadia: error" : msg;
            }
            logProblem(msg == null ? "Xadia: error" : msg);
        } finally {
            for (UUID id : ids) {
                Entry e = CACHE.get(id);
                if (e != null) {
                    e.xadiaLoading = false;
                    if (e.xadiaAt == 0 && e.xadiaFail == 0) e.xadiaAt = System.currentTimeMillis();   // not in the answer: no tags
                }
            }
        }
    }

    private static UUID match(List<UUID> ids, String q) {
        String s = q.replace("-", "").toLowerCase(Locale.ROOT);
        for (UUID id : ids) if (id.toString().replace("-", "").equals(s)) return id;
        return null;
    }

    private static String xadiaColor(String type) {
        if (type == null) return "§d";
        switch (type) {
            case "hacker": return "§c";
            case "sniper": return "§4";
            case "legit_sniper": return "§3";
            case "caution": return "§e";
            case "possibly_cheating": return "§6";
            default: return "§d";
        }
    }

    private static String xadiaIcon(String type, String label) {
        if (type != null) {
            switch (type) {
                case "hacker": return "H";
                case "sniper": return "S";
                case "caution": return "C";
                case "possibly_cheating": return "R";
                case "legit_sniper": return "LS";
                case "alt": return "A";
                default: break;
            }
        }
        return label.length() > 3 ? label.substring(0, 3) : label;
    }

    // ------------------------------------------------------------------ Coral (Urchin)

    private static void loadCoral(List<UUID> ids) {
        try {
            JsonArray uuids = new JsonArray();
            for (UUID id : ids) uuids.add(new com.google.gson.JsonPrimitive(id.toString().replace("-", "")));
            JsonObject req = new JsonObject();
            req.add("uuids", uuids);
            String body = Http.post("Coral", "https://api.urchin.gg/v3/players", req.toString(), "X-API-Key",
                    Modules.statsCoralKey.get().trim());
            JsonObject players = new JsonParser().parse(body).getAsJsonObject().getAsJsonObject("players");
            if (players == null) throw new java.io.IOException("Coral: bad answer");
            long now = System.currentTimeMillis();
            for (UUID id : ids) {
                JsonElement arr = players.get(id.toString().replace("-", ""));
                List<Tag> tags = new ArrayList<>();
                if (arr != null && arr.isJsonArray()) {
                    for (JsonElement te : arr.getAsJsonArray()) {
                        JsonObject t = te.getAsJsonObject();
                        String type = PlayerData.str(t, "tag_type");
                        if (type == null) continue;
                        String reason = clean(PlayerData.str(t, "reason"));
                        String[] look = coralLook(type.toLowerCase(Locale.ROOT));
                        tags.add(new Tag("Coral", look[0].isEmpty() ? "" : badge(look[0], look[1]),
                                look[1] + "§l" + look[2] + "§r" + (reason.isEmpty() ? "" : " §7(" + reason + ")")));
                    }
                }
                Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
                e.coral = tags;
                e.coralAt = now;
                e.coralError = null;
            }
        } catch (Exception ex) {
            String msg = ex instanceof Http.Status && (((Http.Status) ex).code == 401 || ((Http.Status) ex).code == 403)
                    ? "Coral: API キーが違います" : ex.getMessage();
            for (UUID id : ids) {
                Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
                e.coralFail = System.currentTimeMillis();
                e.coralError = msg == null ? "Coral: error" : msg;
            }
            logProblem(msg == null ? "Coral: error" : msg);
        } finally {
            for (UUID id : ids) {
                Entry e = CACHE.get(id);
                if (e != null) e.coralLoading = false;
            }
        }
    }

    /** {badge letters ("" = no badge, e.g. info), colour, name} for a Coral tag type. */
    private static String[] coralLook(String type) {
        switch (type) {
            case "sniper": return new String[]{"S", "§4", "Sniper"};
            case "blatant_cheater": return new String[]{"BC", "§4", "Blatant Cheater"};
            case "closet_cheater": return new String[]{"CC", "§6", "Closet Cheater"};
            case "confirmed_cheater": return new String[]{"CC", "§c", "Confirmed Cheater"};
            case "replays_needed": return new String[]{"RN", "§7", "Replays Needed"};
            case "possible_sniper": return new String[]{"PS", "§e", "Possible Sniper"};
            case "legit_sniper": return new String[]{"LS", "§3", "Legit Sniper"};
            case "caution": return new String[]{"C", "§e", "Caution"};
            case "account": return new String[]{"", "§e", "Account"};
            case "info": return new String[]{"", "§f", "Info"};
            default: return new String[]{"", "§7", type};
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A tag colour given as a number (0xRRGGBB), "#RRGGBB" or a Minecraft colour code; red when unknown. */
    private static String colorOf(JsonElement c) {
        if (c == null || !c.isJsonPrimitive()) return "§c";
        try {
            if (c.getAsJsonPrimitive().isNumber()) return nearest(c.getAsInt());
            String s = c.getAsString().trim();
            if (s.startsWith("§") && s.length() >= 2) return s.substring(0, 2);
            if (s.startsWith("#")) s = s.substring(1);
            return nearest(Integer.parseInt(s, 16));
        } catch (RuntimeException ex) {
            return "§c";
        }
    }

    private static String badge(String icon, String color) { return "§8[" + color + icon + "§8]"; }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
    }

    private static final int[] PALETTE = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** The Minecraft colour code closest to an RGB colour. */
    private static String nearest(int rgb) {
        int best = 12, dist = Integer.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int dr = ((rgb >> 16) & 255) - ((PALETTE[i] >> 16) & 255);
            int dg = ((rgb >> 8) & 255) - ((PALETTE[i] >> 8) & 255);
            int db = (rgb & 255) - (PALETTE[i] & 255);
            int d = dr * dr + dg * dg + db * db;
            if (d < dist) {
                dist = d;
                best = i;
            }
        }
        return "§" + "0123456789abcdef".charAt(best);
    }
}
