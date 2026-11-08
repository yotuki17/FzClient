package dev.fzkc.fzhud.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.fzkc.fzhud.Modules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bed Wars changes over the past 24 hours / 7 days / 30 days. Asked from Urchin (Coral key) first, like the Urchin
 * bot's "Past 24 Hours", then from Aurora (Bordic). Unlike the history on this PC, this works for anyone.
 */
public final class Sessions {
    private Sessions() {}

    private static final Logger LOG = LogManager.getLogger("FzClient-Sessions");

    public static final class Result {
        /** Raw Hypixel Bed Wars field -> change (a field that did not change is missing = 0). */
        final Map<String, Long> delta;
        final long start, end;
        final String source;

        Result(Map<String, Long> delta, long start, long end, String source) {
            this.delta = delta;
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }

    private static final class Entry {
        volatile Result result;
        volatile String error;
        volatile long at;
        volatile boolean loading;
    }

    private static final long FRESH_MS = 5 * 60_000L, RETRY_MS = 60_000L;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FzClient-Sessions");
        t.setDaemon(true);
        return t;
    });
    /** Goes up whenever an answer arrives (screens use it to redraw their numbers). */
    public static volatile int version;

    /** /refresh: ask again. */
    public static void refresh() {
        CACHE.values().removeIf(e -> !e.loading);
        version++;
    }

    static String auroraKey() { return Modules.denickAuroraKey.get().trim(); }

    static String urchinKey() { return Modules.statsCoralKey.get().trim(); }

    public static boolean available() { return !urchinKey().isEmpty() || !auroraKey().isEmpty(); }

    private static String urchinDuration(History.Period p) {
        switch (p) {
            case DAILY: return "24h";
            case WEEKLY: return "7d";
            case MONTHLY: return "30d";
            default: return null;
        }
    }

    private static String auroraPath(History.Period p) {
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
        return e == null ? null : e.result;
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
        if (period == History.Period.TOTAL || !available()) return null;
        Entry e = CACHE.computeIfAbsent(id + "|" + period, k -> new Entry());
        long now = System.currentTimeMillis();
        boolean old = e.result == null ? now - e.at > RETRY_MS : now - e.at > FRESH_MS;
        if (old && !e.loading) {
            e.loading = true;
            POOL.execute(() -> load(id, period, e));
        }
        return e;
    }

    private static void load(UUID id, History.Period period, Entry e) {
        String urchinWhy = null, auroraWhy = null;
        try {
            if (!urchinKey().isEmpty()) {
                try {
                    e.result = urchin(id, period);
                    e.error = null;
                    return;
                } catch (Http.Status s) {
                    urchinWhy = s.code == 404 ? "Urchin にこの期間の記録がありません"
                            : s.code == 401 || s.code == 403 ? "Urchin: API キーが違います"
                            : s.code == 429 ? "Urchin: 回数制限" : "Urchin: HTTP " + s.code;
                } catch (Exception ex) {
                    urchinWhy = "Urchin: " + (ex.getMessage() == null ? "error" : ex.getMessage());
                }
                LOG.info("Urchin session " + period.key + " for " + id + " failed: " + urchinWhy);
            }
            if (!auroraKey().isEmpty()) {
                try {
                    e.result = aurora(id, period);
                    e.error = null;
                    return;
                } catch (Http.Status s) {
                    auroraWhy = s.code == 404 ? "Aurora にこの期間の記録がありません"
                            : s.code == 401 || s.code == 403 ? "Aurora: API キーが違います"
                            : s.code == 429 ? "Aurora: 回数制限" : "Aurora: HTTP " + s.code;
                } catch (Exception ex) {
                    auroraWhy = "Aurora: " + (ex.getMessage() == null ? "error" : ex.getMessage());
                }
                LOG.info("Aurora session " + period.key + " for " + id + " failed: " + auroraWhy);
            }
            e.result = null;
            e.error = urchinWhy != null ? urchinWhy : auroraWhy;
        } finally {
            e.at = System.currentTimeMillis();
            e.loading = false;
            version++;
        }
    }

    // ------------------------------------------------------------------ Urchin

    private static Result urchin(UUID id, History.Period period) throws Exception {
        String body = Http.get("Urchin", "https://api.urchin.gg/v3/player/sessions/custom?player="
                + id.toString().replace("-", "") + "&duration=" + urchinDuration(period), "X-API-Key", urchinKey());
        JsonObject o = new JsonParser().parse(body).getAsJsonObject();
        if (o.has("error")) throw new java.io.IOException(PlayerData.str(o, "error"));
        Map<String, Long> delta = new HashMap<>();
        JsonElement d = o.get("delta");
        if (d != null && d.isJsonObject()) flatten("", d.getAsJsonObject(), delta);
        long from = o.has("from") && o.get("from").isJsonPrimitive() ? o.get("from").getAsLong() : 0;
        if (from > 0 && from < 100_000_000_000L) from *= 1000;
        String sample = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        LOG.info("Urchin session " + period.key + " for " + id + ": " + delta.size() + " Bed Wars field(s) " + sample.replaceAll("[\\r\\n]+", " "));
        return new Result(delta, from, System.currentTimeMillis(), "Urchin");
    }

    /**
     * Urchin's delta mirrors the Hypixel player data (nested objects or "a.b.c" keys). A changed number is the bare
     * difference; a field that appeared is {old: null, new: x} (lifetime totals of a returning player, skipped).
     * Only Bed Wars fields are kept, under their plain Hypixel names ("wins_bedwars", "eight_one_wins_bedwars").
     */
    private static void flatten(String path, JsonObject o, Map<String, Long> out) {
        for (Map.Entry<String, JsonElement> f : o.entrySet()) {
            String p = path.isEmpty() ? f.getKey() : path + "." + f.getKey();
            JsonElement v = f.getValue();
            if (v.isJsonObject()) {
                JsonObject c = v.getAsJsonObject();
                if (c.has("new") && c.entrySet().size() <= 2 && (c.has("old") || c.entrySet().size() == 1)) {
                    JsonElement a = c.get("old"), b = c.get("new");
                    if (num(a) && num(b)) put(p, b.getAsDouble() - a.getAsDouble(), out);
                } else {
                    flatten(p, c, out);
                }
            } else if (num(v)) {
                put(p, v.getAsDouble(), out);
            }
        }
    }

    private static boolean num(JsonElement v) {
        return v != null && v.isJsonPrimitive() && ((JsonPrimitive) v).isNumber();
    }

    private static void put(String path, double v, Map<String, Long> out) {
        String leaf = path.substring(path.lastIndexOf('.') + 1);
        String low = path.toLowerCase(Locale.ROOT);
        boolean bedwars = low.contains("bedwars") || leaf.endsWith("_bedwars") || leaf.equals("bedwars_level");
        if (bedwars) out.put(leaf, (long) v);
    }

    // ------------------------------------------------------------------ Aurora

    private static Result aurora(UUID id, History.Period period) throws Exception {
        String body = Http.get("Aurora", "https://api.bordic.xyz/v4/sessions/" + auroraPath(period) + "?uuid="
                + id.toString().replace("-", "") + "&key=" + URLEncoder.encode(auroraKey(), "UTF-8"), null, null);
        JsonObject o = new JsonParser().parse(body).getAsJsonObject();
        JsonElement ok = o.get("success");
        if (ok != null && ok.isJsonPrimitive() && !ok.getAsBoolean()) {
            throw new java.io.IOException(PlayerData.str(o, "cause"));
        }
        Map<String, Long> delta = new HashMap<>();
        JsonElement d = o.get("delta");
        if (d != null && d.isJsonObject()) {
            for (Map.Entry<String, JsonElement> f : d.getAsJsonObject().entrySet()) {
                if (num(f.getValue())) delta.put(f.getKey(), (long) f.getValue().getAsDouble());
            }
        }
        long start = o.has("start") && o.get("start").isJsonPrimitive() ? o.get("start").getAsLong() : 0;
        long end = o.has("end") && o.get("end").isJsonPrimitive() ? o.get("end").getAsLong() : 0;
        // seconds or milliseconds, depending on the API version
        if (start > 0 && start < 100_000_000_000L) start *= 1000;
        if (end > 0 && end < 100_000_000_000L) end *= 1000;
        return new Result(delta, start, end, "Aurora");
    }
}
