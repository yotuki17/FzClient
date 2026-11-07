package dev.fzkc.fzhud.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fetches player stats in the background and keeps them for a while.
 * Sources: the official Hypixel API when a key is set (every game), otherwise Bordic's public cache
 * (no key, Bed Wars and Duels only). Many players at once (a lobby's tab list) go to Bordic in one request.
 */
public final class StatsApi {
    private StatsApi() {}

    private static final long FRESH_MS = 10 * 60_000L, RETRY_MS = 90_000L;
    private static final int MAX_QUEUED = 40;

    private static final class Entry {
        volatile PlayerData data;
        volatile long failedAt;
        volatile String error;
        volatile boolean loading;
    }

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger queued = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "FzClient-Stats");
        t.setDaemon(true);
        return t;
    });
    /** Hypixel answered 429 (or the key is wrong): stay on Bordic until this time. */
    private static volatile long hypixelPausedUntil;
    private static volatile String hypixelProblem;

    /** Cached stats (can be a few minutes old) or null; asks for them in the background when missing or old. */
    public static PlayerData get(UUID id, String name) {
        Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
        long now = System.currentTimeMillis();
        PlayerData d = e.data;
        boolean old = d == null || now - d.fetchedAt > FRESH_MS;
        if (old && !e.loading && now - e.failedAt > RETRY_MS && queued.get() < MAX_QUEUED) {
            e.loading = true;
            queued.incrementAndGet();
            POOL.execute(() -> {
                try {
                    load(id, name, e);
                } finally {
                    e.loading = false;
                    queued.decrementAndGet();
                }
            });
        }
        return d;
    }

    /** Fetches now (unless the cached copy is under a minute old) and hands the result to the game thread. */
    public static void fetch(UUID id, String name, Consumer<PlayerData> done, Consumer<String> failed) {
        Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
        PlayerData d = e.data;
        if (d != null && System.currentTimeMillis() - d.fetchedAt < 60_000L) {
            done.accept(d);
            return;
        }
        POOL.execute(() -> {
            load(id, name, e);
            PlayerData r = e.data;   // a failed refresh keeps the older copy, which is still worth showing
            String err = e.error;
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                if (r != null) done.accept(r);
                else failed.accept(err == null ? "?" : err);
            });
        });
    }

    /** Prefetch a whole list (tab list) in as few requests as possible. */
    public static void prefetch(List<UUID> ids, List<String> names) {
        long now = System.currentTimeMillis();
        List<UUID> need = new ArrayList<>();
        List<String> needNames = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Entry e = CACHE.computeIfAbsent(ids.get(i), k -> new Entry());
            PlayerData d = e.data;
            if ((d == null || now - d.fetchedAt > FRESH_MS) && !e.loading && now - e.failedAt > RETRY_MS) {
                e.loading = true;
                need.add(ids.get(i));
                needNames.add(names.get(i));
            }
        }
        if (need.isEmpty()) return;
        if (useHypixel()) {
            // one request per player; the pool keeps it to two at a time
            for (int i = 0; i < need.size(); i++) {
                UUID id = need.get(i);
                String nm = needNames.get(i);
                Entry e = CACHE.get(id);
                if (queued.get() >= MAX_QUEUED) {
                    e.loading = false;
                    continue;
                }
                queued.incrementAndGet();
                POOL.execute(() -> {
                    try {
                        load(id, nm, e);
                    } finally {
                        e.loading = false;
                        queued.decrementAndGet();
                    }
                });
            }
            return;
        }
        for (int from = 0; from < need.size(); from += 50) {
            List<UUID> part = new ArrayList<>(need.subList(from, Math.min(need.size(), from + 50)));
            List<String> partNames = new ArrayList<>(needNames.subList(from, Math.min(need.size(), from + 50)));
            POOL.execute(() -> loadBatch(part, partNames));
        }
    }

    public static void clear() { CACHE.clear(); }

    /** Why the Hypixel key is not being used right now, or null. */
    public static String hypixelProblem() { return hypixelProblem; }

    static String hypixelKey() { return Modules.statsHypixelKey.get().trim(); }

    static boolean useHypixel() {
        return !hypixelKey().isEmpty() && System.currentTimeMillis() > hypixelPausedUntil;
    }

    // ------------------------------------------------------------------ loading

    private static void load(UUID id, String name, Entry e) {
        String hex = id.toString().replace("-", "");
        if (useHypixel()) {
            try {
                String body = Http.get("Hypixel", "https://api.hypixel.net/v2/player?uuid=" + hex, "API-Key", hypixelKey());
                accept(id, name, e, new JsonParser().parse(body).getAsJsonObject(), "Hypixel");
                hypixelProblem = null;
                return;
            } catch (Http.Status s) {
                if (s.code == 403) {
                    hypixelProblem = "Hypixel API キーが無効です（期限切れかも）";
                    hypixelPausedUntil = System.currentTimeMillis() + 10 * 60_000L;
                } else if (s.code == 429) {
                    hypixelProblem = "Hypixel API の回数制限に達しました";
                    hypixelPausedUntil = System.currentTimeMillis() + 60_000L;
                }
            } catch (Exception ignored) {
                // fall through to Bordic
            }
        }
        try {
            String body = Http.get("Bordic", "https://api.bordic.xyz/v3/cache/hypixel?uuid=" + hex, null, null);
            accept(id, name, e, new JsonParser().parse(body).getAsJsonObject(), "Bordic");
        } catch (Exception ex) {
            e.failedAt = System.currentTimeMillis();
            e.error = ex.getMessage();
        }
    }

    private static void loadBatch(List<UUID> ids, List<String> names) {
        try {
            StringBuilder sb = new StringBuilder("{\"uuids\":[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(ids.get(i).toString().replace("-", "")).append('"');
            }
            sb.append("]}");
            String body = Http.post("Bordic", "https://api.bordic.xyz/v3/cache/hypixel", sb.toString(), null, null);
            JsonObject all = new JsonParser().parse(body).getAsJsonObject();
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                Entry e = CACHE.get(id);
                JsonElement one = all.get(id.toString().replace("-", ""));
                if (one != null && one.isJsonObject()) accept(id, names.get(i), e, one.getAsJsonObject(), "Bordic");
                else {
                    e.failedAt = System.currentTimeMillis();
                    e.error = "no data";
                }
            }
        } catch (Exception ex) {
            for (UUID id : ids) {
                Entry e = CACHE.get(id);
                e.failedAt = System.currentTimeMillis();
                e.error = ex.getMessage();
            }
        } finally {
            for (UUID id : ids) CACHE.get(id).loading = false;
        }
    }

    private static void accept(UUID id, String name, Entry e, JsonObject response, String source) {
        JsonElement ok = response.get("success");
        if (ok != null && ok.isJsonPrimitive() && !ok.getAsBoolean()) {
            e.failedAt = System.currentTimeMillis();
            e.error = source + ": " + PlayerData.str(response, "cause");
            return;
        }
        JsonElement p = response.get("player");
        PlayerData d = p == null || !p.isJsonObject()
                ? PlayerData.nick(id, name, source)
                : PlayerData.parse(id, name, p.getAsJsonObject(), source);
        e.data = d;
        e.error = null;
        if (!d.nicked) History.record(d);
    }
}
