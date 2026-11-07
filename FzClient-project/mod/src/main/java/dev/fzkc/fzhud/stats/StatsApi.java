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
 * Sources (chosen in the Overlay settings, one for games and one for lobbies): the official Hypixel API (key,
 * every game), Bedlify's cache of it (no key) or Bordic's public cache (no key, Bed Wars / Duels / SkyWars).
 * When the chosen one cannot answer, Bordic does. Many players at once go to Bordic in one request.
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

    /** Where stats come from (the order of the choices in the settings). */
    enum Source {
        HYPIXEL("Hypixel"), BORDIC("Bordic"), BEDLIFY("Bedlify");
        final String label;

        Source(String label) { this.label = label; }

        /** How complete the data is: Hypixel and Bedlify know every game, Bordic only a few. */
        int rank() { return this == BORDIC ? 0 : 1; }

        static int rankOf(String label) { return "Bordic".equals(label) ? 0 : 1; }
    }

    /** Bedlify did not answer: use Bordic until this time. */
    private static volatile long bedlifyPausedUntil;

    /** The source chosen for where you are now (lobby or game). */
    static Source chosen(boolean forCommand) {
        int i = !forCommand && Stats.lobby ? Modules.statsLobbySource.get() : Modules.statsSource.get();
        Source[] all = Source.values();
        return all[Math.max(0, Math.min(all.length - 1, i))];
    }

    /** The source that will really be asked (the chosen one, unless it has no key or is paused). */
    static Source effective(boolean forCommand) {
        Source s = chosen(forCommand);
        long now = System.currentTimeMillis();
        if (s == Source.HYPIXEL && !useHypixel()) return Source.BORDIC;
        if (s == Source.BEDLIFY && now < bedlifyPausedUntil) return Source.BORDIC;
        return s;
    }

    /** True when the cached copy should be replaced: too old, or from a weaker source than the one in use now. */
    private static boolean stale(PlayerData d, long now) {
        if (d == null || now - d.fetchedAt > FRESH_MS) return true;
        return Source.rankOf(d.source) < effective(false).rank() && now - d.fetchedAt > 60_000L;
    }

    /** Cached stats (can be a few minutes old) or null; asks for them in the background when missing or old. */
    public static PlayerData get(UUID id, String name) {
        Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
        long now = System.currentTimeMillis();
        PlayerData d = e.data;
        boolean old = stale(d, now);
        if (old && !e.loading && now - e.failedAt > RETRY_MS && queued.get() < MAX_QUEUED) {
            e.loading = true;
            queued.incrementAndGet();
            POOL.execute(() -> {
                try {
                    load(id, name, e, effective(false));
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
        Source src = effective(true);
        if (d != null && System.currentTimeMillis() - d.fetchedAt < 60_000L && Source.rankOf(d.source) >= src.rank()) {
            done.accept(d);
            return;
        }
        POOL.execute(() -> {
            load(id, name, e, src);
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
            if (stale(d, now) && !e.loading && now - e.failedAt > RETRY_MS) {
                e.loading = true;
                need.add(ids.get(i));
                needNames.add(names.get(i));
            }
        }
        if (need.isEmpty()) return;
        Source src = effective(false);
        if (src != Source.BORDIC) {
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
                        load(id, nm, e, src);
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

    /** Cached stats without asking for anything. */
    public static PlayerData peek(UUID id) {
        Entry e = CACHE.get(id);
        return e == null ? null : e.data;
    }

    /** True while a lookup for this player is still running. */
    public static boolean pending(UUID id) {
        Entry e = CACHE.get(id);
        return e != null && e.loading;
    }

    // ------------------------------------------------------------------ name -> UUID (Mojang), kept for the session

    private static final UUID NONE = new UUID(0, 0);
    private static final Map<String, UUID> NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> NAME_LOADING = new ConcurrentHashMap<>();

    /**
     * The UUID for a name: from the tab list, else from Mojang (in the background; null until it is known).
     * {@link #nameUnknown} tells a name Mojang does not know apart from one still loading.
     */
    public static UUID uuidOf(String name) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
            net.minecraft.client.network.NetworkPlayerInfo i = mc.thePlayer.sendQueue.getPlayerInfo(name);
            if (i != null && i.getGameProfile().getId() != null) return i.getGameProfile().getId();
        }
        String k = name.toLowerCase(java.util.Locale.ROOT);
        UUID id = NAMES.get(k);
        if (id != null) return id == NONE ? null : id;
        if (NAME_LOADING.putIfAbsent(k, Boolean.TRUE) == null) {
            POOL.execute(() -> {
                try {
                    UUID found = lookupName(name);
                    NAMES.put(k, found == null ? NONE : found);
                } finally {
                    NAME_LOADING.remove(k);
                }
            });
        }
        return null;
    }

    public static boolean nameUnknown(String name) {
        return NAMES.get(name.toLowerCase(java.util.Locale.ROOT)) == NONE;
    }

    /** Name -> UUID from Mojang right now (call off the game thread). */
    static UUID lookupName(String name) {
        try {
            String body = Http.get("Mojang", "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name, null, null);
            String id = PlayerData.str(new JsonParser().parse(body).getAsJsonObject(), "id");
            if (id == null || id.length() != 32) return null;
            return UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Why the Hypixel key is not being used right now, or null. */
    public static String hypixelProblem() { return hypixelProblem; }

    static String hypixelKey() { return Modules.statsHypixelKey.get().trim(); }

    static boolean useHypixel() {
        return !hypixelKey().isEmpty() && System.currentTimeMillis() > hypixelPausedUntil;
    }

    // ------------------------------------------------------------------ loading

    private static void load(UUID id, String name, Entry e, Source src) {
        String hex = id.toString().replace("-", "");
        if (src == Source.HYPIXEL && useHypixel()) {
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
        if (src == Source.BEDLIFY && System.currentTimeMillis() > bedlifyPausedUntil) {
            try {
                String body = Http.get("Bedlify", "https://api.bedlify.xyz/v1/player/cache?uuid=" + hex, null, null);
                JsonObject o = new JsonParser().parse(body).getAsJsonObject();
                if (!o.has("player") && o.has("stats")) {
                    // answered with the player object itself
                    JsonObject wrap = new JsonObject();
                    wrap.add("player", o);
                    o = wrap;
                }
                accept(id, name, e, o, "Bedlify");
                return;
            } catch (Exception ignored) {
                // Bedlify is down or slow: Bordic for a while
                bedlifyPausedUntil = System.currentTimeMillis() + 5 * 60_000L;
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
