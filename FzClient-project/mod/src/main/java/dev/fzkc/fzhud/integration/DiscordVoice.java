package dev.fzkc.fzhud.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.fzkc.fzhud.hud.DiscordVcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is in your Discord voice channel and who is talking, read from the Discord app on your PC (its local RPC
 * pipe, like the Rich Presence in DiscordRpc). Nothing goes through any server of ours.
 *
 * Login (first time only):
 *   1. your browser opens Discord's authorization page for YOUR OWN Discord application (its Client ID and Client
 *      Secret go into the "Discord VC" HUD settings),
 *   2. after you press "Authorize" Discord sends the browser to http://localhost/?code=...; a tiny local web server
 *      (only reachable from this PC, only while logging in) picks the code up - or, if port 80 is busy, you paste
 *      the address into the chat with /fzvc <address>,
 *   3. the code is exchanged for an access token, which is kept in config/fzclient_discord_vc.json,
 *   4. the Discord app is told to use that token over its local pipe.
 *
 * "Redirects" of the Discord application must contain exactly: http://localhost
 *
 * Every step writes a "[FzClient] Discord VC: ..." line to the game log, so a failure says why.
 */
public final class DiscordVoice {
    private DiscordVoice() {}

    /** One person in the voice channel. */
    public static final class Member {
        public final String id;
        public volatile String name;
        public volatile boolean mute, deaf, speaking, stream;
        /** The avatar picture's hash (null = Discord's default picture). */
        public volatile String avatar;

        public Member(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Has to be listed under OAuth2 > Redirects in the Discord application, letter for letter. */
    private static final String REDIRECT = "http://localhost";
    private static final String SCOPES = "rpc%20rpc.voice.read";
    private static final JsonObject POISON = new JsonObject();

    private static final Object LOCK = new Object();
    private static final Map<String, Member> members = new LinkedHashMap<String, Member>();
    private static volatile String channelName;
    private static volatile String selfId = "";
    private static volatile boolean connected;
    private static volatile int generation;
    private static volatile String runningFor = "";
    /** Credentials the login gave up on: not retried until the HUD is switched off and on again. */
    private static volatile String halted = "";
    private static volatile String manualCode;
    private static Thread worker;
    private static long nextCheck;
    private static volatile String lastLog = "";

    private static volatile int stalls;

    /** Discord did not answer in time: give up on this connection and start a new one (or stop after 3 tries). */
    private static void stalled(String what) {
        notice(dev.fzkc.fzhud.util.Lang.f("Discordから返事がありません（{0}）。Discordアプリを再起動してから、もう一度お試しください", dev.fzkc.fzhud.util.Lang.t(what)));
        generation++; // the stuck connection ends as soon as it wakes up
        if (++stalls >= 3) {
            halted = runningFor;
            notice(dev.fzkc.fzhud.util.Lang.t("返事がないので止めます。Discordを再起動して、HUDを一度OFFにしてからONにしてください"));
        } else {
            runningFor = "stalled"; // the next tick sees different credentials and starts a new connection
            halted = "";
        }
    }

    /** The login did not work (denied, timed out, token refused). */
    private static final class AuthRejected extends Exception {
        AuthRejected(String msg) { super(msg); }
    }

    // ---- what the HUD reads ----------------------------------------------------------------------

    public static List<Member> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<Member>(members.values());
        }
    }

    public static String channel() { return channelName; }

    public static boolean connected() { return connected; }

    public static String selfId() { return selfId; }

    // ---- client tick -----------------------------------------------------------------------------

    /** Starts / restarts / stops the connection when the HUD is switched on or its credentials change. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 2000L;

        DiscordVcHud hud = DiscordVcHud.instance;
        String cid = "", secret = "";
        boolean on = hud != null && hud.enabled
                && System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        if (on) cid = sharedId();
        String key = cid.isEmpty() ? "" : cid;

        if (key.equals(runningFor)) {
            if (!key.isEmpty() && !key.equals(halted) && (worker == null || !worker.isAlive())) start(cid, secret, key);
            return;
        }
        // switched on / off or the credentials changed: drop the old connection, begin again
        generation++;
        clear();
        runningFor = key;
        halted = "";
        if (!key.isEmpty()) start(cid, secret, key);
    }

    private static volatile String sharedId;
    private static volatile boolean idLoading;
    private static volatile long idFailedAt;

    /** FzClient's Discord application ID, from the FzClient server ("" until it is known). */
    private static String sharedId() {
        String id = sharedId;
        if (id != null) return id;
        if (!idLoading && System.currentTimeMillis() - idFailedAt > 60000L) {
            idLoading = true;
            Thread t = new Thread(() -> {
                try {
                    JsonObject r = FzUsers.post("/dvc/id", new JsonObject());
                    String v = r == null ? null : str(r, "id");
                    if (v != null && v.matches("\\d{17,20}")) sharedId = v;
                    else idFailedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    idFailedAt = System.currentTimeMillis();
                } finally {
                    idLoading = false;
                }
            }, "FzClient-DiscordVC-Id");
            t.setDaemon(true);
            t.start();
        }
        return "";
    }

    private static void start(final String cid, final String secret, final String key) {
        final int gen = generation;
        worker = new Thread(() -> run(gen, cid, secret, key), "FzClient-DiscordVC");
        worker.setDaemon(true);
        worker.start();
    }

    private static void clear() {
        synchronized (LOCK) {
            members.clear();
        }
        channelName = null;
        selfId = "";
        connected = false;
    }

    private static void log(String msg) {
        if (msg.equals(lastLog)) return;
        lastLog = msg;
    }

    private static void chat(final net.minecraft.util.IChatComponent c) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(c);
        });
    }

    private static void notice(String msg) {
        if (msg.equals(lastLog)) return; // the same message again and again would flood the chat
        log(msg);
        chat(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.GRAY + "Discord VC: " + msg));
    }

    // ---- worker ----------------------------------------------------------------------------------

    private static void run(int gen, String cid, String secret, String key) {
        int rejected = 0;
        while (gen == generation) {
            Session s = new Session(gen, cid, secret);
            try {
                s.run();
            } catch (InterruptedException e) {
                return;
            } catch (AuthRejected e) {
                if (gen != generation) return;
                notice(dev.fzkc.fzhud.util.Lang.t("認証できませんでした: ") + e.getMessage());
                if (++rejected >= 2) {
                    notice(dev.fzkc.fzhud.util.Lang.t("認証がうまくいかないので止めます。設定を確認して、HUDを一度OFFにしてからONにしてください"));
                    halted = key;
                    return;
                }
            } catch (Throwable t) {
                if (gen != generation) return; // abandoned on purpose
                notice(dev.fzkc.fzhud.util.Lang.t("接続が切れました。15秒後にやり直します: ") + t);
            } finally {
                connected = false;
                s.abandon();
            }
            try {
                for (int i = 0; i < 15 && gen == generation; i++) Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One connection to the Discord app. */
    private static final class Session {
        final int gen;
        final String cid, secret;
        RandomAccessFile pipe;
        /** What the connection is waiting for and until when (0 = not waiting); the watchdog reads these. */
        volatile long waitUntil;
        volatile String waitFor = "";
        String subscribed;
        boolean needResync;

        Session(int gen, String cid, String secret) {
            this.gen = gen;
            this.cid = cid;
            this.secret = secret;
        }

        boolean alive() { return gen == generation; }

        /** The pipe is not closed here on purpose: closing a pipe another thread is blocked reading can hang. */
        void abandon() {
            pipe = null;
        }

        void run() throws Exception {
            // 1) an access token (from the file, refreshed, or through the browser)
            JsonObject token = obtainToken();

            // 2) talk to the Discord app
            pipe = openPipe();
            if (pipe == null) {
                notice(dev.fzkc.fzhud.util.Lang.t("Discordのデスクトップアプリが見つかりません（開いているか、通常の権限で起動しているか確認してください）"));
                return;
            }
            startWatchdog();

            JsonObject hello = new JsonObject();
            hello.addProperty("v", 1);
            hello.addProperty("client_id", cid);
            write(0, hello.toString());
            waiting("最初のあいさつ", 10000L);
            while (true) { // READY (or an error when the Client ID is wrong)
                JsonObject f = readFrame(pipe);
                if (!alive()) throw new InterruptedException();
                if ("READY".equals(str(f, "evt"))) break;
                if ("ERROR".equals(str(f, "evt"))) throw new IllegalStateException(dev.fzkc.fzhud.util.Lang.t("Client ID が正しくないようです: ") + f);
            }
            waiting(null, 0L);

            // 3) hand the token to the Discord app
            JsonObject a = new JsonObject();
            a.addProperty("access_token", token.get("access_token").getAsString());
            JsonObject r = request("AUTHENTICATE", null, a, 15000);
            if ("ERROR".equals(str(r, "evt"))) {
                deleteToken(); // next try logs in again
                throw new AuthRejected(dev.fzkc.fzhud.util.Lang.t("Discordがトークンを受け付けません: ") + errorText(r));
            }
            JsonObject user = obj(obj(r, "data"), "user");
            String id = str(user, "id");
            selfId = id == null ? "" : id;
            connected = true;
            stalls = 0;
            notice(dev.fzkc.fzhud.util.Lang.t("つながりました"));

            request("SUBSCRIBE", "VOICE_CHANNEL_SELECT", null, 10000);
            resync();

            while (alive()) {
                JsonObject f = readFrame(pipe); // waits until Discord reports something
                if (!alive()) break;
                handle(f);
                if (needResync) {
                    needResync = false;
                    resync();
                }
            }
        }

        // ---- login ----

        JsonObject obtainToken() throws Exception {
            JsonObject tok = loadToken(cid);
            if (tok != null) {
                long expires = tok.get("expires_at").getAsLong();
                if (expires - System.currentTimeMillis() / 1000L >= 120L) return tok; // still good
                if (tok.has("refresh_token")) {
                    try {
                        Map<String, String> p = new LinkedHashMap<String, String>();
                        p.put("grant_type", "refresh_token");
                        p.put("refresh_token", tok.get("refresh_token").getAsString());
                        JsonObject fresh = tokenRequest(p);
                        saveToken(cid, fresh);
                        return fresh;
                    } catch (Exception e) {
                        log("トークンの更新に失敗しました。ブラウザでもう一度許可します: " + e);
                    }
                }
            }
            return browserLogin();
        }

        /** Opens the browser on Discord's authorization page and waits for the code that comes back. */
        JsonObject browserLogin() throws Exception {
            String state = UUID.randomUUID().toString().replace("-", "");
            manualCode = null;
            CodeServer server = new CodeServer(state);
            boolean listening = server.start();

            String url = "https://discord.com/oauth2/authorize?client_id=" + URLEncoder.encode(cid, "UTF-8")
                    + "&response_type=code&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
                    + "&scope=" + SCOPES + "&state=" + state;
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(url));
            } catch (Throwable t) {
                log("ブラウザを開けませんでした: " + t);
            }
            notice(listening
                    ? dev.fzkc.fzhud.util.Lang.t("ブラウザにDiscordの許可画面が出ます。「認証」を押してください（3分以内）。開かないときは下のリンクを押してください")
                    : dev.fzkc.fzhud.util.Lang.t("ブラウザで許可したあと、アドレス欄のURLを /fzvc の後ろに貼ってください（ポート80が使えませんでした）"));
            ChatComponentText link = new ChatComponentText(EnumChatFormatting.AQUA + dev.fzkc.fzhud.util.Lang.t("[許可画面を開く]"));
            link.setChatStyle(new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
            chat(link);

            String code = null;
            try {
                long end = System.currentTimeMillis() + 180000L;
                while (System.currentTimeMillis() < end) {
                    if (!alive()) throw new InterruptedException();
                    if (server.error != null) throw new AuthRejected(dev.fzkc.fzhud.util.Lang.f("許可が拒否されました ({0})", server.error));
                    code = server.code;
                    if (code == null && manualCode != null) {
                        code = manualCode;
                        manualCode = null;
                    }
                    if (code != null) break;
                    Thread.sleep(300L);
                }
            } finally {
                server.stop();
            }
            if (code == null) throw new AuthRejected(dev.fzkc.fzhud.util.Lang.t("3分以内に許可されませんでした"));

            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("grant_type", "authorization_code");
            p.put("code", code);
            p.put("redirect_uri", REDIRECT);
            JsonObject tok;
            try {
                tok = tokenRequest(p);
            } catch (IllegalStateException e) {
                throw new AuthRejected(e.getMessage());
            }
            saveToken(cid, tok);
            notice(dev.fzkc.fzhud.util.Lang.t("許可されました"));
            return tok;
        }

        /** Exchange a code (or refresh token) for an access token. */
        JsonObject tokenRequest(Map<String, String> params) throws Exception {
            // FzClient's server adds the application's secret (it never comes to the game) and asks Discord
            JsonObject body = new JsonObject();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!"redirect_uri".equals(e.getKey())) body.addProperty(e.getKey(), e.getValue());
            }
            HttpURLConnection c = (HttpURLConnection) new URL(FzUsers.ENDPOINT_URL + "/dvc/token").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "FzClient/1.0 (voice hud)");
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.toString().getBytes(UTF8));
            } finally {
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code != 200) {
                throw new IllegalStateException(dev.fzkc.fzhud.util.Lang.f("トークンの取得に失敗 (HTTP {0}): ", code)
                        + (text.length() > 200 ? text.substring(0, 200) : text)
                        );
            }
            JsonObject t = new JsonParser().parse(text).getAsJsonObject();
            long expiresIn = t.has("expires_in") ? t.get("expires_in").getAsLong() : 604800L;
            t.addProperty("expires_at", System.currentTimeMillis() / 1000L + expiresIn);
            return t;
        }

        // ---- the voice channel ----

        void resync() throws Exception {
            JsonObject r = request("GET_SELECTED_VOICE_CHANNEL", null, null, 10000);
            JsonElement data = r.get("data");
            if ("ERROR".equals(str(r, "evt")) || data == null || data.isJsonNull() || !data.isJsonObject()) {
                synchronized (LOCK) {
                    members.clear();
                }
                channelName = null;
                subscribed = null;
                log("今はボイスチャンネルに入っていません");
                return;
            }
            JsonObject ch = data.getAsJsonObject();
            String id = str(ch, "id");
            channelName = str(ch, "name");
            synchronized (LOCK) {
                members.clear();
            }
            JsonElement vs = ch.get("voice_states");
            if (vs != null && vs.isJsonArray()) {
                JsonArray arr = vs.getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i).isJsonObject()) putMember(arr.get(i).getAsJsonObject());
                }
            }
            if (id != null && !id.equals(subscribed)) {
                for (String evt : new String[]{"VOICE_STATE_CREATE", "VOICE_STATE_UPDATE", "VOICE_STATE_DELETE",
                        "SPEAKING_START", "SPEAKING_STOP"}) {
                    JsonObject a = new JsonObject();
                    a.addProperty("channel_id", id);
                    request("SUBSCRIBE", evt, a, 10000);
                }
                subscribed = id;
            }
            log("ボイスチャンネル「" + channelName + "」を表示します");
        }

        void handle(JsonObject f) {
            if (!"DISPATCH".equals(str(f, "cmd"))) return;
            String evt = str(f, "evt");
            if (evt == null) return;
            JsonObject d = obj(f, "data");
            if (d == null) d = new JsonObject();
            switch (evt) {
                case "VOICE_STATE_CREATE":
                case "VOICE_STATE_UPDATE":
                    putMember(d);
                    break;
                case "VOICE_STATE_DELETE": {
                    String id = str(obj(d, "user"), "id");
                    if (id != null) {
                        synchronized (LOCK) {
                            members.remove(id);
                        }
                    }
                    break;
                }
                case "SPEAKING_START":
                    setSpeaking(str(d, "user_id"), true);
                    break;
                case "SPEAKING_STOP":
                    setSpeaking(str(d, "user_id"), false);
                    break;
                case "VOICE_CHANNEL_SELECT":
                    needResync = true; // joined / left / moved: read the channel again
                    break;
                default:
                    break;
            }
        }

        // ---- pipe plumbing ----

        void waiting(String what, long ms) {
            waitFor = what == null ? "" : what;
            waitUntil = ms > 0 ? System.currentTimeMillis() + ms : 0L;
        }

        /**
         * A pipe can't be read and written from two threads at once on Windows (a waiting read blocks every write on
         * the same handle), so everything runs on one thread with plain blocking reads. This thread only watches the
         * clock: if Discord doesn't answer in time it says so and the connection is started again.
         */
        void startWatchdog() {
            Thread t = new Thread(() -> {
                try {
                    while (alive() && pipe != null) {
                        Thread.sleep(1000L);
                        long until = waitUntil;
                        if (until != 0L && System.currentTimeMillis() > until) {
                            stalled(waitFor);
                            return;
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }, "FzClient-DiscordVC-Watchdog");
            t.setDaemon(true);
            t.start();
        }

        void write(int op, String json) throws Exception {
            byte[] data = json.getBytes(UTF8);
            byte[] frame = new byte[8 + data.length];
            le(frame, 0, op);
            le(frame, 4, data.length);
            System.arraycopy(data, 0, frame, 8, data.length);
            pipe.write(frame);
        }

        /** Sends a command and waits for its answer; events that arrive meanwhile are handled. */
        JsonObject request(String cmd, String evt, JsonObject args, long timeoutMs) throws Exception {
            String nonce = UUID.randomUUID().toString();
            JsonObject c = new JsonObject();
            c.addProperty("cmd", cmd);
            if (evt != null) c.addProperty("evt", evt);
            c.add("args", args == null ? new JsonObject() : args);
            c.addProperty("nonce", nonce);
            write(1, c.toString());
            waiting(cmd, timeoutMs);
            try {
                while (alive()) {
                    JsonObject f = readFrame(pipe);
                    if (nonce.equals(str(f, "nonce"))) return f;
                    handle(f);
                }
            } finally {
                waiting(null, 0L);
            }
            throw new IllegalStateException("stopped");
        }
    }

    // ---- the little web server that catches the code ---------------------------------------------

    /** Listens on http://localhost (port 80, this PC only) for Discord's redirect, for a few minutes at most. */
    private static final class CodeServer {
        final String state;
        HttpServer server;
        volatile String code, error;

        CodeServer(String state) { this.state = state; }

        boolean start() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80), 0);
                server.createContext("/", new HttpHandler() {
                    @Override
                    public void handle(HttpExchange ex) throws IOException {
                        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                        String page;
                        if (state.equals(q.get("state")) && q.get("code") != null) {
                            code = q.get("code");
                            page = dev.fzkc.fzhud.util.Lang.t("認証できました。Minecraftに戻ってください。このタブは閉じて大丈夫です。");
                        } else if (state.equals(q.get("state")) && q.get("error") != null) {
                            error = q.get("error");
                            page = dev.fzkc.fzhud.util.Lang.t("許可されませんでした。このタブは閉じて大丈夫です。");
                        } else if (q.get("code") != null) {
                            // a code arrived, but not from the page FzClient opened (no / wrong "state")
                            log("codeは届きましたが state が合いません。ゲームが開いた許可画面からやり直すか、/fzvc でURLを渡してください");
                            page = dev.fzkc.fzhud.util.Lang.t("コードは届きましたが、FzClientが開いた許可画面からの認証ではないようです。")
                                    + dev.fzkc.fzhud.util.Lang.t("ゲームのチャットに出る [許可画面を開く] からもう一度お試しください。")
                                    + dev.fzkc.fzhud.util.Lang.t("（手動で渡すなら、このページのアドレスを、チャットで /fzvc の後ろに貼ってください）");
                        } else {
                            page = dev.fzkc.fzhud.util.Lang.t("FzClient: 認証の待ち受け中です。");
                        }
                        byte[] body = ("<html><head><meta charset=\"utf-8\"><title>FzClient</title></head><body>"
                                + page + "</body></html>").getBytes(UTF8);
                        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        ex.sendResponseHeaders(200, body.length);
                        OutputStream out = ex.getResponseBody();
                        try {
                            out.write(body);
                        } finally {
                            out.close();
                        }
                    }
                });
                server.start();
                return true;
            } catch (Throwable t) {
                log("ポート80で待ち受けできません（ほかのソフトが使っているかも）: " + t);
                server = null;
                return false;
            }
        }

        void stop() {
            if (server != null) {
                try {
                    server.stop(0);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    // ---- /fzvc -----------------------------------------------------------------------------------

    /** /fzvc <address from the browser>  hands over the code by hand;  /fzvc reset  forgets the saved login. */
    public static class CodeCommand extends CommandBase {
        @Override public String getCommandName() { return "fzvc"; }

        @Override public String getCommandUsage(ICommandSender sender) { return dev.fzkc.fzhud.util.Lang.t("/fzvc <ブラウザのURL> | /fzvc reset"); }

        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                chat(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.GRAY
                        + dev.fzkc.fzhud.util.Lang.t("使い方: /fzvc <ブラウザのアドレス欄のURL>   または   /fzvc reset（保存した認証を消してやり直す）")));
                return;
            }
            if ("reset".equalsIgnoreCase(args[0])) {
                tokenFile().delete();
                runningFor = "reset"; // the next tick sees different credentials and starts over
                halted = "";
                notice(dev.fzkc.fzhud.util.Lang.t("保存した認証を消しました。つなぎ直します"));
                return;
            }
            String s = args[0].trim();
            int i = s.indexOf("code=");
            if (i >= 0) {
                s = s.substring(i + 5);
                int amp = s.indexOf('&');
                if (amp >= 0) s = s.substring(0, amp);
            }
            manualCode = s;
            chat(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.GRAY
                    + dev.fzkc.fzhud.util.Lang.t("コードを受け取りました。続きを進めます")));
        }
    }

    // ---- members ---------------------------------------------------------------------------------

    private static void putMember(JsonObject o) {
        JsonObject user = obj(o, "user");
        String id = str(user, "id");
        if (id == null) return;
        String name = str(o, "nick");
        if (name == null) name = str(user, "global_name");
        if (name == null) name = str(user, "username");
        if (name == null) name = id;
        JsonObject vs = obj(o, "voice_state");
        boolean mute = bool(o, "mute") || bool(vs, "mute") || bool(vs, "self_mute");
        boolean deaf = bool(vs, "deaf") || bool(vs, "self_deaf");
        // Discord only sometimes tells whether someone streams (Go Live) here; shown when it does
        boolean stream = bool(vs, "self_stream") || bool(o, "self_stream") || bool(vs, "self_video");
        String avatar = str(user, "avatar");
        synchronized (LOCK) {
            Member m = members.get(id);
            if (m == null) {
                m = new Member(id, name);
                members.put(id, m);
            }
            m.name = name;
            m.mute = mute;
            m.deaf = deaf;
            m.stream = stream;
            m.avatar = avatar;
        }
    }

    private static void setSpeaking(String id, boolean on) {
        if (id == null) return;
        synchronized (LOCK) {
            Member m = members.get(id);
            if (m != null) m.speaking = on;
        }
    }

    // ---- token file ------------------------------------------------------------------------------

    private static File tokenFile() {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "config");
        dir.mkdirs();
        return new File(dir, "fzclient_discord_vc.json");
    }

    private static void deleteToken() {
        tokenFile().delete();
    }

    private static JsonObject loadToken(String cid) {
        File f = tokenFile();
        if (!f.exists()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                JsonObject o = new JsonParser().parse(readAll(in)).getAsJsonObject();
                if (!cid.equals(str(o, "client_id")) || !o.has("access_token") || !o.has("expires_at")) return null;
                return o;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveToken(String cid, JsonObject t) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("client_id", cid);
            o.addProperty("access_token", t.get("access_token").getAsString());
            if (t.has("refresh_token")) o.addProperty("refresh_token", t.get("refresh_token").getAsString());
            o.addProperty("expires_at", t.get("expires_at").getAsLong());
            OutputStream out = new FileOutputStream(tokenFile());
            try {
                out.write(o.toString().getBytes(UTF8));
            } finally {
                out.close();
            }
        } catch (Exception e) {
            log("トークンを保存できませんでした: " + e);
        }
    }

    // ---- small helpers ---------------------------------------------------------------------------

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String errorText(JsonObject r) {
        JsonObject d = obj(r, "data");
        String m = str(d, "message");
        String c = str(d, "code");
        return (c == null ? "" : c + " ") + (m == null ? "" : m);
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), UTF8);
    }

    private static RandomAccessFile openPipe() {
        for (int i = 0; i < 10; i++) {
            try {
                return new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static JsonObject readFrame(RandomAccessFile pipe) throws Exception {
        while (true) {
            byte[] head = new byte[8];
            pipe.readFully(head);
            int op = (head[0] & 0xFF) | (head[1] & 0xFF) << 8 | (head[2] & 0xFF) << 16 | (head[3] & 0xFF) << 24;
            int len = (head[4] & 0xFF) | (head[5] & 0xFF) << 8 | (head[6] & 0xFF) << 16 | (head[7] & 0xFF) << 24;
            if (len < 0 || len > 1 << 20) throw new IllegalStateException("bad frame");
            byte[] body = new byte[len];
            pipe.readFully(body);
            String text = new String(body, UTF8);
            if (op == 2) throw new IllegalStateException("closed by Discord: " + text);
            if (op == 3) continue; // ping: nothing to do
            return new JsonParser().parse(text).getAsJsonObject();
        }
    }

    private static void le(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
