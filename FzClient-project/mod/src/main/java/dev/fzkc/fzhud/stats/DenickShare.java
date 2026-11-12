package dev.fzkc.fzhud.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Denick answers shared between FzClient players through the FzClient server (/dn/get, /dn/report in
 * server/worker.js): the nicks in your tab list are asked about now and then, and what this game finds by skin or
 * numbers is sent (proven with a Mojang session join, like the cosmetics). Party and by-hand answers are not sent
 * (they would give away your friends' nicks, and a typed name is too easy to get wrong). The overwrite rule is the
 * same as in Denick.resolve, on both ends.
 */
final class DenickShare {
    private static final Logger LOG = LogManager.getLogger("FzClient-Denick");
    private static final long ASK_AGAIN = 10 * 60_000L;
    private static final long FIRST_WAIT = 5_000L, GAP = 30_000L;
    /** A find older than this is not sent any more (the server takes ages up to 10 minutes). */
    private static final long MAX_AGE = 9 * 60_000L;
    /** "Long ago" on Denick.now()'s clock (System.nanoTime may start anywhere, even below zero). */
    private static final long NEVER = Long.MIN_VALUE / 4;

    /** One answer from the server, its time already on this game's clock. */
    static final class Row {
        String nick, name, gone, how, skin;
        long at;
    }

    /** Nick -> when it was last asked about (main thread only). */
    private static final Map<String, Long> ASKED = new HashMap<>();
    /** Nicks to ask about (guarded by itself). */
    private static final Set<String> WANT = new LinkedHashSet<>();
    /** Own finds waiting to be sent, and those being sent (guarded by PENDING). */
    private static final List<Denick.Real> PENDING = new ArrayList<>(), SENDING = new ArrayList<>();
    /** Nicks whose sent answer you took back with "/pdenick <nick> reset", with when (guarded by PENDING). */
    private static final Map<String, Long> RESETS = new LinkedHashMap<>();
    /** Counts "sharing turned off": a send that started before it does not put anything back (guarded by PENDING). */
    private static int generation;
    private static volatile long firstPending = NEVER, lastReport = NEVER, askPauseUntil = NEVER, reportPauseUntil = NEVER;
    private static long reportPause = 60_000L;
    /** Set once a second on the main thread: sharing is on and you are on Hypixel. */
    private static volatile boolean active;
    /** The server does not know /dn/ (not updated yet): off for this session. */
    private static volatile boolean dead;
    /** Reports refused for good in this session (offline account, daily limit, blocked). */
    private static volatile boolean reportDead;
    private static Thread thread;
    /** Dev check: the server is a local test server (no Mojang join), and it works outside Hypixel. */
    static volatile boolean devMode;

    private DenickShare() {}

    /** Sharing is on (main thread). */
    static boolean on() {
        return !dead && Modules.denickShare.get() && (devMode || Denick.on());
    }

    /** Once a second from Denick.tick (main thread). */
    static void tick() {
        active = on();
    }

    /** A nick in the tab list (main thread): asked about when it was not asked about in the last 10 minutes. */
    static void saw(String nick) {
        if (!active) return;
        String k = nick.toLowerCase(Locale.ROOT);
        long t = Denick.now();
        Long a = ASKED.get(k);
        if (a != null && t - a < ASK_AGAIN) return;
        ASKED.put(k, t);
        synchronized (WANT) {
            if (WANT.size() < 200) WANT.add(nick);
        }
        start();
    }

    /** Asked about soon again (main thread). */
    static void askAgain(String nick) {
        ASKED.remove(nick.toLowerCase(Locale.ROOT));
    }

    /** An own find: sent when it came from public data (skin, numbers). */
    static void report(Denick.Real r) {
        if (!active || reportDead || !("skin".equals(r.code) || "numbers".equals(r.code))) return;
        String me = Minecraft.getMinecraft().getSession().getUsername();
        if (me != null && me.equalsIgnoreCase(r.name)) return;   // never your own account
        synchronized (PENDING) {
            if (PENDING.size() >= 50) return;
            if (PENDING.isEmpty() && RESETS.isEmpty()) firstPending = Denick.now();
            PENDING.add(r);
        }
        start();
    }

    /**
     * "/pdenick <nick> reset" (main thread): nothing of it is sent, and an answer you sent (or that is being sent right
     * now) is taken back on the server too.
     */
    static void reset(String nick, boolean wasSent) {
        String k = nick.toLowerCase(Locale.ROOT);
        boolean queued = false;
        synchronized (PENDING) {
            PENDING.removeIf(r -> r.nick.equalsIgnoreCase(nick));
            boolean sending = false;
            for (Denick.Real r : SENDING) if (r.nick.equalsIgnoreCase(nick)) sending = true;
            if ((wasSent || sending) && !reportDead && RESETS.size() < 20) {
                if (PENDING.isEmpty() && RESETS.isEmpty()) firstPending = Denick.now();
                RESETS.remove(k);
                RESETS.put(k, Denick.now());
                queued = true;
            }
        }
        if (queued) start();
    }

    /**
     * An own find about this nick or this player is still on its way to the server: shared answers about them wait for
     * it (finds that will not be sent any more do not count).
     */
    static boolean waiting(String k, String name) {
        if (reportDead) return false;
        long t = Denick.now();
        synchronized (PENDING) {
            for (List<Denick.Real> l : java.util.Arrays.asList(PENDING, SENDING)) {
                for (Denick.Real r : l) {
                    if (!Denick.stillMine(r) || t - r.at >= MAX_AGE) continue;
                    if (r.nick.equalsIgnoreCase(k) || r.name.equalsIgnoreCase(name)) return true;
                }
            }
        }
        return false;
    }

    /** Sharing was turned off: nothing more is sent or asked. */
    static void clear() {
        synchronized (PENDING) {
            PENDING.clear();
            RESETS.clear();
            generation++;
        }
        synchronized (WANT) {
            WANT.clear();
        }
        ASKED.clear();
    }

    private static void stopReports(String why) {
        reportDead = true;
        synchronized (PENDING) {
            PENDING.clear();
            RESETS.clear();
        }
        LOG.info("share: sending stopped for this session: " + why);
    }

    private static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(DenickShare::loop, "FzClient-DenickShare");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            // not in a world (menus, logging in elsewhere): nothing is asked or sent
            if (!active || dead || Minecraft.getMinecraft().theWorld == null) continue;
            try {
                ask();
            } catch (Throwable t) {
                LOG.info("share: asking failed: " + t);
                askPauseUntil = Denick.now() + 60_000L;
            }
            try {
                send();
            } catch (Throwable t) {
                LOG.info("share: sending failed: " + t);
                reportPauseUntil = Denick.now() + 60_000L;
            }
        }
    }

    // ------------------------------------------------------------------ asking

    private static void ask() throws Exception {
        if (Denick.now() < askPauseUntil) return;
        List<String> nicks = new ArrayList<>();
        synchronized (WANT) {
            Iterator<String> it = WANT.iterator();
            while (it.hasNext() && nicks.size() < 100) {
                nicks.add(it.next());
                it.remove();
            }
        }
        if (nicks.isEmpty()) return;
        JsonObject body = new JsonObject();
        JsonArray a = new JsonArray();
        for (String n : nicks) a.add(new JsonPrimitive(n));
        body.add("nicks", a);
        long sent = Denick.now();
        JsonObject r;
        try {
            r = dev.fzkc.fzhud.integration.FzUsers.post("/dn/get", body);
        } catch (Exception e) {
            synchronized (WANT) {
                WANT.addAll(nicks);   // asked again after the pause
            }
            throw e;
        }
        long got = Denick.now();
        String error = PlayerData.str(r, "error");
        if ("not found".equals(error)) {
            dead = true;
            LOG.info("share: the FzClient server does not know /dn/ yet; sharing is off for this session");
            return;
        }
        if (r == null || error != null) {
            synchronized (WANT) {
                WANT.addAll(nicks);
            }
            askPauseUntil = got + (r == null ? 60_000L : 3_000L);
            return;
        }
        Long serverNow = Game.num(r, "now");
        JsonElement found = r.get("found");
        if (serverNow == null || found == null || !found.isJsonArray()) return;
        long offset = (sent + got) / 2 - serverNow;   // server time -> this game's clock
        List<Row> rows = new ArrayList<>();
        for (JsonElement e : found.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Row w = new Row();
            w.nick = PlayerData.str(o, "nick");
            w.name = PlayerData.str(o, "name");
            w.gone = PlayerData.str(o, "gone");
            w.how = PlayerData.str(o, "how");
            w.skin = PlayerData.str(o, "skin");
            Long at = Game.num(o, "at");
            // only what could be real: no colour codes or odd text can get into chat or name tags
            if (w.nick == null || !w.nick.matches("\\w{1,16}") || at == null || w.how == null || !w.how.matches("[a-z]{1,16}")) continue;
            if (w.name != null && !w.name.matches("\\w{1,16}")) continue;
            if (w.gone != null && !w.gone.matches("\\w{1,16}")) continue;
            if (w.skin == null || !w.skin.matches("[0-9a-f]{0,64}")) w.skin = "";
            w.at = at + offset;
            rows.add(w);
        }
        StringBuilder sb = new StringBuilder();
        if (devMode) for (Row w : rows) sb.append(' ').append(w.nick).append('=').append(w.name == null ? "-" + w.gone : w.name);
        LOG.info("share: asked about " + nicks.size() + ", " + rows.size() + " answers" + sb);
        if (!rows.isEmpty()) Minecraft.getMinecraft().addScheduledTask(() -> Denick.sharedRows(rows));
    }

    // ------------------------------------------------------------------ sending

    private static void send() {
        if (reportDead) return;
        long t = Denick.now();
        Map<String, Long> resets = new LinkedHashMap<>();
        List<Denick.Real> sending;
        int gen;
        synchronized (PENDING) {
            if (PENDING.isEmpty() && RESETS.isEmpty()) return;
            if (t - firstPending < FIRST_WAIT || t - lastReport < GAP || t < reportPauseUntil) return;
            Iterator<Map.Entry<String, Long>> rs = RESETS.entrySet().iterator();
            while (rs.hasNext() && resets.size() < 20) {
                Map.Entry<String, Long> e = rs.next();
                resets.put(e.getKey(), e.getValue());
                rs.remove();
            }
            Iterator<Denick.Real> it = PENDING.iterator();
            while (it.hasNext() && resets.size() + SENDING.size() < 20) {
                Denick.Real r = it.next();
                it.remove();
                // a newer answer, a reset or sharing turned off since: not sent
                if (Denick.stillMine(r) && t - r.at < MAX_AGE) SENDING.add(r);
            }
            sending = new ArrayList<>(SENDING);
            gen = generation;
        }
        try {
            // resets first: a find for the same nick after its reset is newer and replaces it on the server
            JsonArray items = new JsonArray();
            for (Map.Entry<String, Long> e : resets.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("nick", e.getKey());
                o.addProperty("how", "reset");
                o.addProperty("age", Math.max(0L, t - e.getValue()));
                items.add(o);
            }
            for (Denick.Real r : sending) {
                JsonObject o = new JsonObject();
                o.addProperty("nick", r.nick);
                o.addProperty("real", r.name);
                o.addProperty("how", r.code);
                o.addProperty("skin", r.skin);
                o.addProperty("age", Math.max(0L, t - r.at));
                items.add(o);
            }
            if (items.size() == 0) return;
            Minecraft mc = Minecraft.getMinecraft();
            com.mojang.authlib.GameProfile profile = mc.getSession().getProfile();
            if (profile == null || profile.getId() == null) return;
            String serverId;
            if (devMode) {
                serverId = "dead" + profile.getId().toString().replace("-", "");
            } else {
                String token = mc.getSession().getToken();
                if (token == null || token.length() < 20) {   // offline account: nothing can be proven
                    stopReports("offline account");
                    return;
                }
                serverId = new BigInteger(130, new SecureRandom()).toString(16);
                try {
                    mc.getSessionService().joinServer(profile, token, serverId);
                } catch (Exception e) {
                    LOG.info("share: Mojang join failed: " + e.getMessage());
                    giveBack(resets, sending, true, gen);
                    return;
                }
            }
            lastReport = t;
            JsonObject body = new JsonObject();
            body.addProperty("name", profile.getName());
            body.addProperty("serverId", serverId);
            body.add("items", items);
            JsonObject r;
            try {
                r = dev.fzkc.fzhud.integration.FzUsers.post("/dn/report", body);
            } catch (Exception e) {
                LOG.info("share: sending failed: " + e);
                giveBack(resets, sending, true, gen);
                return;
            }
            String error = PlayerData.str(r, "error");
            if (r != null && r.has("ok")) {
                reportPause = 60_000L;
                List<String> again = new ArrayList<>();
                for (String k : resets.keySet()) Denick.REPORTED.remove(k);
                for (Denick.Real s : sending) {
                    Denick.REPORTED.add(s.nick.toLowerCase(Locale.ROOT));
                    again.add(s.nick);
                }
                LOG.info("share: sent " + items.size() + ": " + r.get("results"));
                // the server's answer for these nicks soon (someone may have found something newer meanwhile)
                mc.addScheduledTask(() -> {
                    for (String n : again) askAgain(n);
                });
            } else if ("not found".equals(error)) {
                dead = true;
                LOG.info("share: the FzClient server does not know /dn/ yet; sharing is off for this session");
            } else if ("daily limit".equals(error) || "blocked".equals(error)) {
                stopReports(error);
            } else if ("too fast".equals(error)) {
                giveBack(resets, sending, false, gen);
                reportPauseUntil = Denick.now() + 15_000L;
            } else if ("nothing".equals(error)) {
                LOG.info("share: the server took none of " + items.size());
            } else {
                LOG.info("share: sending failed: " + (r == null ? "no answer" : r.toString()));
                giveBack(resets, sending, true, gen);
            }
        } finally {
            synchronized (PENDING) {
                SENDING.clear();
            }
        }
    }

    /**
     * Not sent: back in the queue (unless sharing was turned off meanwhile), and maybe a longer pause (doubling up to
     * 30 minutes).
     */
    private static void giveBack(Map<String, Long> resets, List<Denick.Real> sending, boolean pause, int gen) {
        synchronized (PENDING) {
            if (gen == generation && !reportDead) {
                for (Map.Entry<String, Long> e : resets.entrySet()) RESETS.putIfAbsent(e.getKey(), e.getValue());
                List<Denick.Real> back = new ArrayList<>();
                for (Denick.Real r : sending) if (Denick.stillMine(r)) back.add(r);
                PENDING.addAll(0, back);
                if (PENDING.size() > 50) PENDING.subList(50, PENDING.size()).clear();
            }
        }
        if (pause) {
            reportPauseUntil = Denick.now() + reportPause;
            reportPause = Math.min(30 * 60_000L, reportPause * 2);
        }
    }
}
