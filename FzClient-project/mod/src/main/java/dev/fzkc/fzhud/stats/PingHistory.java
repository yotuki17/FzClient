package dev.fzkc.fzhud.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Other players' Hypixel ping from Aurora's ping history (POST /v3/player/ping, no key needed). Hypixel itself
 * sends 1 ms for everyone in the tab list, so this daily average is the best guess there is.
 */
public final class PingHistory {
    private PingHistory() {}

    private static final long FOUND_MS = 6 * 3600_000L, MISSING_MS = 30 * 60_000L, FAIL_MS = 2 * 60_000L;
    /** uuid -> {avg ms or -1, time asked} */
    private static final Map<UUID, long[]> CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> WANTED = ConcurrentHashMap.newKeySet();
    private static volatile Thread thread;

    /** The player's recent average ping, or -1 when unknown (asks in the background). */
    public static int get(UUID id) {
        if (id == null || id.version() != 4) return -1;
        long[] c = CACHE.get(id);
        long now = System.currentTimeMillis();
        if (c == null || now - c[1] > (c[0] >= 0 ? FOUND_MS : c[0] == -1 ? MISSING_MS : FAIL_MS)) {
            WANTED.add(id);
            start();
        }
        return c == null || c[0] < 0 ? -1 : (int) c[0];
    }

    private static synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(PingHistory::loop, "FzClient-Ping");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        try {
            while (true) {
                Thread.sleep(1500);   // gather the whole tab list into one request
                if (WANTED.isEmpty()) return;
                List<UUID> batch = new ArrayList<>();
                for (UUID id : WANTED) {
                    batch.add(id);
                    if (batch.size() == 100) break;
                }
                WANTED.removeAll(batch);
                load(batch);
            }
        } catch (InterruptedException ignored) {
        } finally {
            thread = null;
            if (!WANTED.isEmpty()) start();
        }
    }

    private static void load(List<UUID> ids) {
        long now = System.currentTimeMillis();
        try {
            JsonArray arr = new JsonArray();
            for (UUID id : ids) arr.add(new JsonPrimitive(id.toString().replace("-", "")));
            JsonObject req = new JsonObject();
            req.add("uuids", arr);
            String body = Http.post("Aurora ping", "https://api.bordic.xyz/v3/player/ping", req.toString(), null, null);
            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
            for (UUID id : ids) {
                long avg = -1;
                JsonElement p = o.get(id.toString().replace("-", ""));
                if (p != null && p.isJsonObject()) {
                    JsonElement data = p.getAsJsonObject().get("data");
                    if (data != null && data.isJsonArray()) {
                        // newest day first; skip days without a number
                        for (JsonElement day : data.getAsJsonArray()) {
                            if (!day.isJsonObject()) continue;
                            JsonElement a = day.getAsJsonObject().get("avg");
                            if (a != null && a.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber()) {
                                avg = Math.round(a.getAsDouble());
                                break;
                            }
                        }
                    }
                }
                CACHE.put(id, new long[]{avg, now});
            }
        } catch (Exception e) {
            for (UUID id : ids) CACHE.put(id, new long[]{-2, now});   // -2 = failed: ask again soon
        }
    }
}
