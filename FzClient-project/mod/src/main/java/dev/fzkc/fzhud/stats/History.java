package dev.fzkc.fzhud.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hypixel only gives all-time totals. To show today's / this week's / this month's numbers, FzClient keeps
 * snapshots of the counters of every player it has looked up (on this PC only) and subtracts the one from
 * before the period started.
 */
public final class History {
    private History() {}

    private static final long KEEP_MS = 40L * 24 * 3600_000L, STEP_MS = 30 * 60_000L;
    private static final int MAX_PLAYERS = 3000;

    private static final class Snap {
        final long t;
        final Map<String, Long> c;

        Snap(long t, Map<String, Long> c) {
            this.t = t;
            this.c = c;
        }
    }

    private static final class Player {
        String name;
        final List<Snap> snaps = new ArrayList<>();
    }

    private static final Map<String, Player> PLAYERS = new HashMap<>();
    private static boolean loaded, dirty;
    private static long savedAt;

    public enum Period {
        TOTAL("total", "全期間"), DAILY("daily", "24時間"), WEEKLY("weekly", "1週間"), MONTHLY("monthly", "1か月");

        public final String key, label;

        Period(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public static Period byName(String s) {
            if (s == null) return null;
            switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "total": case "all": case "overall": return TOTAL;
                case "daily": case "day": case "today": case "d": case "24h": return DAILY;
                case "weekly": case "week": case "w": return WEEKLY;
                case "monthly": case "month": case "m": return MONTHLY;
                default: return null;
            }
        }

        /** When this period started: the last 24 hours / 7 days / 30 days (like Aurora). */
        long start() {
            long day = 24 * 3600_000L, now = System.currentTimeMillis();
            switch (this) {
                case DAILY: return now - day;
                case WEEKLY: return now - 7 * day;
                case MONTHLY: return now - 30 * day;
                default: return 0;
            }
        }
    }

    /** Starting values for a period, and the time of the oldest snapshot used (later than the start = partial). */
    public static final class Base {
        public final Map<String, Long> counters;
        public final long since;
        public final boolean partial;

        Base(Map<String, Long> counters, long since, boolean partial) {
            this.counters = counters;
            this.since = since;
            this.partial = partial;
        }
    }

    static synchronized void record(PlayerData d) {
        if (!Modules.statsHistory.get()) return;
        load();
        String key = d.id.toString().replace("-", "");
        Player p = PLAYERS.get(key);
        if (p == null) {
            p = new Player();
            PLAYERS.put(key, p);
        }
        p.name = d.name;
        long now = System.currentTimeMillis();
        Map<String, Long> c = new HashMap<>();
        // only the overall counters (per-mode ones would make the file many times bigger)
        for (Map.Entry<String, Long> e : d.counters.entrySet()) if (e.getKey().indexOf('@') < 0) c.put(e.getKey(), e.getValue());
        int n = p.snaps.size();
        Snap last = n == 0 ? null : p.snaps.get(n - 1);
        if (last != null && now - last.t < STEP_MS && sameDay(last.t, now)
                && n >= 2 && sameDay(p.snaps.get(n - 2).t, now) && last.c.keySet().containsAll(c.keySet())) {
            // a recent snapshot from today that is not today's first one: just refresh it
            p.snaps.set(n - 1, new Snap(now, c));
        } else if (last == null || !last.c.equals(c) || now - last.t >= STEP_MS) {
            p.snaps.add(new Snap(now, c));
        }
        prune(p, now);
        dirty = true;
    }

    /** Starting values for {@code period}, or null when there is nothing older than right now. */
    public static synchronized Base base(UUID id, Period period) {
        load();
        Player p = PLAYERS.get(id.toString().replace("-", ""));
        if (p == null || p.snaps.isEmpty()) return null;
        long start = period.start();
        Map<String, Long> out = new HashMap<>();
        long since = Long.MAX_VALUE;
        // each counter: the last value from before the start, else the first one seen after it
        Map<String, Long> before = new HashMap<>(), after = new HashMap<>();
        Map<String, Long> afterT = new HashMap<>();
        long beforeT = Long.MIN_VALUE;
        for (Snap s : p.snaps) {
            if (s.t <= start) {
                before.putAll(s.c);
                beforeT = Math.max(beforeT, s.t);
            } else {
                for (Map.Entry<String, Long> e : s.c.entrySet()) {
                    if (!after.containsKey(e.getKey())) {
                        after.put(e.getKey(), e.getValue());
                        afterT.put(e.getKey(), s.t);
                    }
                }
            }
        }
        boolean partial = false;
        for (String k : after.keySet()) if (!before.containsKey(k)) {
            out.put(k, after.get(k));
            since = Math.min(since, afterT.get(k));
            partial = true;
        }
        out.putAll(before);
        if (!partial) since = start;
        return new Base(out, since, partial);
    }

    /** Called every few seconds: writes the file when something changed (at most once a minute). */
    static void tick() {
        long now = System.currentTimeMillis();
        if (!dirty || now - savedAt < 60_000L) return;
        savedAt = now;
        IO.execute(History::save);
    }

    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FzClient-StatsHistory");
        t.setDaemon(true);
        return t;
    });

    static synchronized void save() {
        if (!loaded || !dirty) return;
        dirty = false;
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonObject players = new JsonObject();
        for (Map.Entry<String, Player> e : PLAYERS.entrySet()) {
            JsonObject p = new JsonObject();
            p.addProperty("n", e.getValue().name);
            JsonArray snaps = new JsonArray();
            for (Snap s : e.getValue().snaps) {
                JsonArray a = new JsonArray();
                a.add(new com.google.gson.JsonPrimitive(s.t));
                JsonObject c = new JsonObject();
                for (Map.Entry<String, Long> ce : s.c.entrySet()) c.addProperty(ce.getKey(), ce.getValue());
                a.add(c);
                snaps.add(a);
            }
            p.add("s", snaps);
            players.add(e.getKey(), p);
        }
        root.add("players", players);
        File f = file(), tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(root.toString());
        } catch (Exception e) {
            return;
        }
        if (f.exists() && !f.delete()) return;
        tmp.renameTo(f);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonObject players = new JsonParser().parse(r).getAsJsonObject().getAsJsonObject("players");
            for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                Player p = new Player();
                p.name = o.has("n") ? o.get("n").getAsString() : "?";
                for (JsonElement se : o.getAsJsonArray("s")) {
                    JsonArray a = se.getAsJsonArray();
                    Map<String, Long> c = new HashMap<>();
                    for (Map.Entry<String, JsonElement> ce : a.get(1).getAsJsonObject().entrySet()) {
                        c.put(ce.getKey(), ce.getValue().getAsLong());
                    }
                    p.snaps.add(new Snap(a.get(0).getAsLong(), c));
                }
                PLAYERS.put(e.getKey(), p);
            }
        } catch (Exception ignored) {
            // a broken file: start over (it only holds numbers we can fetch again)
        }
    }

    private static void prune(Player p, long now) {
        // keep the last snapshot older than the window, so a month can still be measured from it
        while (p.snaps.size() > 1 && now - p.snaps.get(1).t > KEEP_MS) p.snaps.remove(0);
        // older than two days: only the first and last snapshot of each day
        for (int i = p.snaps.size() - 2; i >= 1; i--) {
            Snap s = p.snaps.get(i);
            if (now - s.t < 2L * 24 * 3600_000L) continue;
            if (sameDay(p.snaps.get(i - 1).t, s.t) && sameDay(p.snaps.get(i + 1).t, s.t)) p.snaps.remove(i);
        }
        if (PLAYERS.size() > MAX_PLAYERS) {
            String oldest = null;
            long ot = Long.MAX_VALUE;
            for (Map.Entry<String, Player> e : PLAYERS.entrySet()) {
                List<Snap> s = e.getValue().snaps;
                long t = s.isEmpty() ? 0 : s.get(s.size() - 1).t;
                if (t < ot) {
                    ot = t;
                    oldest = e.getKey();
                }
            }
            if (oldest != null) PLAYERS.remove(oldest);
        }
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-stats-history.json");
    }
}
