package dev.fzkc.fzhud.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who else plays with FzClient: a tiny shared list on the FzClient users server (a Cloudflare Worker, see
 * fzusers/worker.js). Only Minecraft UUIDs and "last seen" times are stored there.
 * <ul>
 *   <li>"hello" every 5 minutes while the game runs: you are using FzClient (switchable in FzClient Branding);</li>
 *   <li>"check" for the players in your tab list (each UUID at most every 2 minutes): the ones using FzClient
 *       get the FzClient logo next to their name tag and in the tab list.</li>
 * </ul>
 * Everything runs on one background thread; the game only reads the finished set.
 */
public final class FzUsers {
    private FzUsers() {}

    /** The Worker's address (workers.dev). Empty = feature off. */
    static final String ENDPOINT = System.getProperty("fzclient.endpoint", "https://fzclient-users.fuzuki-yozakura.workers.dev");
    public static final String ENDPOINT_URL = ENDPOINT;

    private static final Set<UUID> users = Collections.synchronizedSet(new HashSet<UUID>());
    private static final Map<UUID, Long> checkedAt = new HashMap<UUID, Long>();
    private static volatile List<UUID> lobby = new ArrayList<UUID>();
    private static Thread thread;
    private static long helloAt;
    private static boolean saidHello;

    /** True when this player was seen using FzClient (your own player is always true). */
    public static boolean uses(UUID id) {
        if (id == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && id.equals(mc.thePlayer.getUniqueID())) return true;
        return Modules.fzUsersOthers.get() && users.contains(id);
    }

    /** The players seen using FzClient (from the last checks of your tab list). */
    public static List<UUID> known() {
        synchronized (users) {
            return new ArrayList<UUID>(users);
        }
    }

    /** Client tick: hands the current tab list to the background thread and starts it once. */
    private static int listTicks;

    public static void tick() {
        if (ENDPOINT.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        // the list is sent every 5 seconds: gathering it twice a second is plenty
        if (++listTicks % 10 == 0 && mc.getNetHandler() != null && mc.thePlayer != null && Modules.fzUsersOthers.get()) {
            List<UUID> ids = new ArrayList<UUID>();
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info.getGameProfile() != null && info.getGameProfile().getId() != null) ids.add(info.getGameProfile().getId());
            }
            lobby = ids;
        }
        if (thread == null) {
            thread = new Thread(FzUsers::loop, "FzClient-Users");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(5000L);
                long now = System.currentTimeMillis();
                Minecraft mc = Minecraft.getMinecraft();
                UUID me = mc.getSession() != null && mc.getSession().getProfile() != null ? mc.getSession().getProfile().getId() : null;

                // "I am using FzClient" (or, after the switch was turned off, "forget me")
                if (me != null) {
                    if (Modules.fzUsersShowMe.get()) {
                        if (now - helloAt > 5 * 60 * 1000L) {
                            post("/hello", one(me));
                            helloAt = now;
                            saidHello = true;
                        }
                    } else if (saidHello) {
                        post("/bye", one(me));
                        saidHello = false;
                        helloAt = 0;
                    }
                }

                // who in this lobby uses FzClient
                if (!Modules.fzUsersOthers.get()) continue;
                List<UUID> ask = new ArrayList<UUID>();
                for (UUID id : lobby) {
                    Long t = checkedAt.get(id);
                    if (t == null || now - t > 2 * 60 * 1000L) ask.add(id);
                    if (ask.size() >= 300) break;
                }
                if (ask.isEmpty()) continue;
                JsonArray arr = new JsonArray();
                for (UUID id : ask) arr.add(new JsonPrimitive(hex(id)));
                JsonObject body = new JsonObject();
                body.add("uuids", arr);
                JsonObject r = post("/check", body);
                if (r == null || !r.has("users")) continue;
                Set<String> on = new HashSet<String>();
                for (JsonElement e : r.getAsJsonArray("users")) on.add(e.getAsString());
                for (UUID id : ask) {
                    checkedAt.put(id, now);
                    if (on.contains(hex(id))) users.add(id);
                    else users.remove(id);
                }
                if (checkedAt.size() > 5000) checkedAt.clear();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // offline / server down: keep what we know and try again later
            }
        }
    }

    private static JsonObject one(UUID id) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", hex(id));
        return o;
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    /** POST JSON to the FzClient users server; the answer as JSON (null when it is not JSON). */
    public static JsonObject post(String path, JsonObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "FzClient/" + dev.fzkc.fzhud.feature.Branding.VERSION);
        OutputStream out = c.getOutputStream();
        try {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            String text = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
            return text.startsWith("{") ? new JsonParser().parse(text).getAsJsonObject() : null;
        } finally {
            in.close();
            c.disconnect();
        }
    }
}
