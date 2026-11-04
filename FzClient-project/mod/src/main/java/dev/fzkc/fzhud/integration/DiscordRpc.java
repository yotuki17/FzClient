package dev.fzkc.fzhud.integration;

import dev.fzkc.fzhud.util.Lang;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.feature.Branding;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.util.EnumChatFormatting;

import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Discord Rich Presence ("FzClient をプレイ中"), talking to the Discord app over its local pipe.
 * No extra library or DLL. Uses FzClient's own Discord application (built in, nothing to set up).
 * Everything network-ish happens on a background thread; the game only hands over the text.
 * <p>
 * Every step writes one short "[FzClient] Discord: ..." line to the game log (logs/latest.log), so when the
 * status doesn't show up on someone's PC the log says why (no Discord, wrong OS, Discord refused...).
 */
public final class DiscordRpc {
    private DiscordRpc() {}

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    /** FzClient's Discord application and the image uploaded to it (Rich Presence assets). */
    private static final String APP_ID = "1560601539482091620";
    private static final String IMAGE_KEY = "fzclient_discord";
    /** FzClient website (GitHub Pages), opened by the "Website" button under the Discord status. */
    private static final String WEBSITE = "https://yotuki17.github.io/FzClient/";
    private static final long START = System.currentTimeMillis() / 1000L;

    private static volatile String wantedKey = "";
    private static volatile JsonObject wantedActivity;
    private static volatile String wantedClientId = "";
    private static Thread worker;
    private static long nextCompute;

    /** The last line written, so the same message isn't repeated every few seconds. */
    private static volatile String lastLog = "";

    private static void log(String msg) {
        if (msg.equals(lastLog)) return;
        lastLog = msg;
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }

    /** Client tick: work out what to show (cheap), the worker sends it when it changes. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now < nextCompute) return;
        nextCompute = now + 2000L;

        String id = APP_ID;
        if (!Modules.discord.enabled) {
            wantedClientId = "";
            log("off (the Discord module is switched off)");
            return;
        }
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            wantedClientId = "";
            log("not supported on this system (" + System.getProperty("os.name", "?") + "), Windows only");
            return;
        }
        wantedClientId = id;

        Minecraft mc = Minecraft.getMinecraft();
        String details, state = null;
        if (mc.theWorld == null) {
            details = Lang.t("メニュー");
        } else if (mc.isSingleplayer()) {
            details = Lang.t("シングルプレイ");
        } else {
            ServerData sd = mc.getCurrentServerData();
            String ip = sd == null || sd.serverIP == null ? "" : sd.serverIP;
            details = Modules.discordServer.get() && !ip.isEmpty() ? (Lang.english() ? "Playing on " + ip : ip + " でプレイ中") : Lang.t("マルチプレイ");
            if (Modules.discordMode.get()) {
                ScoreObjective obj = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
                if (obj != null) {
                    String t = EnumChatFormatting.getTextWithoutFormattingCodes(obj.getDisplayName());
                    if (t != null && !t.trim().isEmpty()) state = t.trim();
                }
            }
        }

        String key = id + "|" + details + "|" + state;
        if (key.equals(wantedKey)) return;

        JsonObject act = new JsonObject();
        act.addProperty("details", details);
        if (state != null) act.addProperty("state", state);
        JsonObject ts = new JsonObject();
        ts.addProperty("start", START);
        act.add("timestamps", ts);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", IMAGE_KEY);
        assets.addProperty("large_text", "FzClient " + Branding.VERSION);
        act.add("assets", assets);
        // the "Website" button under the status (Discord shows it to other people, not on your own profile)
        com.google.gson.JsonArray buttons = new com.google.gson.JsonArray();
        JsonObject site = new JsonObject();
        site.addProperty("label", "Website");
        site.addProperty("url", WEBSITE);
        buttons.add(site);
        act.add("buttons", buttons);
        wantedActivity = act;
        wantedKey = key;
        ensureWorker();
    }

    private static void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(DiscordRpc::run, "FzClient-Discord");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- worker -------------------------------------------------------------------------------------

    private static void run() {
        RandomAccessFile pipe = null;
        String connectedId = null, sentKey = null;
        long retryAt = 0;
        while (true) {
            try {
                String id = wantedClientId;
                if (id.isEmpty()) {
                    // turned off: closing the pipe clears the status in Discord
                    if (pipe != null) { close(pipe); pipe = null; connectedId = null; sentKey = null; }
                    Thread.sleep(2000L);
                    continue;
                }
                if (pipe != null && !id.equals(connectedId)) { close(pipe); pipe = null; sentKey = null; }
                if (pipe == null) {
                    if (System.currentTimeMillis() < retryAt) { Thread.sleep(1000L); continue; }
                    pipe = open();
                    if (pipe == null) {
                        // Discord not running (or it is the browser version, or running as administrator)
                        log("could not find Discord's pipe (is the Discord desktop app open? it must be started "
                                + "normally, not as administrator, and not the browser version)");
                        retryAt = System.currentTimeMillis() + 15000L;
                        continue;
                    }
                    JsonObject hello = new JsonObject();
                    hello.addProperty("v", 1);
                    hello.addProperty("client_id", id);
                    write(pipe, 0, hello.toString());
                    String ready = read(pipe); // READY (or an error if the ID is wrong)
                    log("connected: " + cut(ready));
                    connectedId = id;
                }
                String key = wantedKey;
                JsonObject act = wantedActivity;
                if (act != null && !key.equals(sentKey)) {
                    JsonObject args = new JsonObject();
                    args.addProperty("pid", pid());
                    args.add("activity", act);
                    JsonObject cmd = new JsonObject();
                    cmd.addProperty("cmd", "SET_ACTIVITY");
                    cmd.add("args", args);
                    cmd.addProperty("nonce", UUID.randomUUID().toString());
                    write(pipe, 1, cmd.toString());
                    String reply = read(pipe);
                    // an error here ("evt":"ERROR") is the reason the status does not show up
                    log("status sent, Discord answered: " + cut(reply));
                    sentKey = key;
                    Thread.sleep(4000L); // Discord allows about 5 updates per 20 seconds
                } else {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // Discord closed or restarted: reconnect later
                log("connection lost, trying again in 15 s: " + t);
                if (pipe != null) close(pipe);
                pipe = null;
                connectedId = null;
                sentKey = null;
                retryAt = System.currentTimeMillis() + 15000L;
            }
        }
    }

    private static RandomAccessFile open() {
        for (int i = 0; i < 10; i++) {
            try {
                return new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void write(RandomAccessFile pipe, int op, String json) throws Exception {
        byte[] data = json.getBytes(UTF8);
        byte[] frame = new byte[8 + data.length];
        le(frame, 0, op);
        le(frame, 4, data.length);
        System.arraycopy(data, 0, frame, 8, data.length);
        pipe.write(frame);
    }

    private static String read(RandomAccessFile pipe) throws Exception {
        byte[] head = new byte[8];
        pipe.readFully(head);
        int op = (head[0] & 0xFF) | (head[1] & 0xFF) << 8 | (head[2] & 0xFF) << 16 | (head[3] & 0xFF) << 24;
        int len = (head[4] & 0xFF) | (head[5] & 0xFF) << 8 | (head[6] & 0xFF) << 16 | (head[7] & 0xFF) << 24;
        if (len < 0 || len > 1 << 20) throw new IllegalStateException("bad frame");
        byte[] body = new byte[len];
        pipe.readFully(body);
        if (op == 2) throw new IllegalStateException("closed by Discord: " + new String(body, UTF8));
        return new String(body, UTF8);
    }

    private static void le(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static int pid() {
        try {
            String n = ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(n.substring(0, n.indexOf('@')));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void close(RandomAccessFile f) {
        try {
            f.close();
        } catch (Exception ignored) {
        }
    }
}
