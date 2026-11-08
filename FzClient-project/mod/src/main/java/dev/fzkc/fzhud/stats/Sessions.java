package dev.fzkc.fzhud.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Modules;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bed Wars changes over the last day / week / month from Aurora (Bordic's API, needs the Aurora key). Unlike the
 * history on this PC, this works for anyone, also for people FzClient has never seen before.
 */
public final class Sessions {
    private Sessions() {}

    public static final class Result {
        /** Raw Hypixel Bed Wars field -> change (only numbers; a field that did not change is missing = 0). */
        final Map<String, Long> delta;
        final long start, end;

        Result(Map<String, Long> delta, long start, long end) {
            this.delta = delta;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Entry {
        volatile Result result;
        volatile String error;
        volatile long at;
        volatile boolean loading;
    }

    private static final long FRESH_MS = 15 * 60_000L, RETRY_MS = 2 * 60_000L;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FzClient-Sessions");
        t.setDaemon(true);
        return t;
    });
    /** Goes up whenever an answer arrives (screens use it to redraw their numbers). */
    public static volatile int version;

    static String key() { return Modules.denickAuroraKey.get().trim(); }

    public static boolean available() { return !key().isEmpty(); }

    private static String path(History.Period p) {
        switch (p) {
            case DAILY: return "daily";
            case WEEKLY: return "weekly";
            case MONTHLY: return "monthly";
            default: return null;
        }
    }

    /** The answer if known (asks in the background when missing or old). */
    static Result get(UUID id, History.Period period) {
        Entry e = entry(id, period);
        if (e == null) return null;
        return e.result;
    }

    static boolean loading(UUID id, History.Period period) {
        Entry e = CACHE.get(id + "|" + period);
        return e != null && e.loading;
    }

    static String error(UUID id, History.Period period) {
        Entry e = CACHE.get(id + "|" + period);
        return e == null ? null : e.error;
    }

    private static Entry entry(UUID id, History.Period period) {
        String p = path(period);
        if (p == null || !available()) return null;
        Entry e = CACHE.computeIfAbsent(id + "|" + period, k -> new Entry());
        long now = System.currentTimeMillis();
        boolean old = e.result == null ? now - e.at > RETRY_MS : now - e.at > FRESH_MS;
        if (old && !e.loading) {
            e.loading = true;
            POOL.execute(() -> load(id, p, e));
        }
        return e;
    }

    private static void load(UUID id, String p, Entry e) {
        try {
            String body = Http.get("Aurora", "https://api.bordic.xyz/v4/sessions/" + p + "?uuid="
                    + id.toString().replace("-", "") + "&key=" + URLEncoder.encode(key(), "UTF-8"), null, null);
            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
            JsonElement ok = o.get("success");
            if (ok != null && ok.isJsonPrimitive() && !ok.getAsBoolean()) {
                throw new java.io.IOException("Aurora: " + PlayerData.str(o, "cause"));
            }
            Map<String, Long> delta = new HashMap<>();
            JsonElement d = o.get("delta");
            if (d != null && d.isJsonObject()) {
                for (Map.Entry<String, JsonElement> f : d.getAsJsonObject().entrySet()) {
                    JsonElement v = f.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) delta.put(f.getKey(), (long) v.getAsDouble());
                }
            }
            long start = o.has("start") && o.get("start").isJsonPrimitive() ? o.get("start").getAsLong() : 0;
            long end = o.has("end") && o.get("end").isJsonPrimitive() ? o.get("end").getAsLong() : 0;
            // seconds or milliseconds, depending on the API version
            if (start > 0 && start < 100_000_000_000L) start *= 1000;
            if (end > 0 && end < 100_000_000_000L) end *= 1000;
            e.result = new Result(delta, start, end);
            e.error = null;
        } catch (Http.Status s) {
            // 404 = Aurora has nothing for this player in that time: the history on this PC is used instead
            if (s.code == 404) {
                e.error = "Aurora にこの期間の記録がありません";
            } else {
                e.error = s.code == 401 || s.code == 403 ? "Aurora: API キーが違います"
                        : s.code == 429 ? "Aurora: 回数制限" : "Aurora: HTTP " + s.code;
            }
        } catch (Exception ex) {
            e.error = "Aurora: " + (ex.getMessage() == null ? "error" : ex.getMessage());
        } finally {
            e.at = System.currentTimeMillis();
            e.loading = false;
            version++;
        }
    }
}
