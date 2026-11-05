package dev.fzkc.fzhud.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Config;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.util.FrameStats;
import dev.fzkc.fzhud.util.Lang;
import dev.fzkc.fzhud.util.Tracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The connection to the launcher, which now draws the overlay (Alt+X) in a window of its own over the game and
 * records the game itself. Nothing of that depends on the server or the screen the game shows any more.
 *
 * The launcher listens on 127.0.0.1 and passes -Dfzclient.link=port:token. This side tells it:
 *  - the keys: Alt+X (overlay), record, save the last seconds (they only reach us while the game has the focus)
 *  - whether the game has the focus
 *  - while the overlay is open: the numbers (FPS, 1% low, frame times, CPU, memory, ping...) and the quick switches
 * and it can switch those and show a line in the chat ("saved"). One JSON object per line both ways.
 */
public final class LauncherLink {
    private LauncherLink() {}

    /** Alt and this key open the overlay (the names stay those of the old keys, so changed keys are kept). */
    public static final KeyBinding KEY_OVERLAY = new KeyBinding("オーバーレイ（Alt と一緒に押す）", Keyboard.KEY_X, "FzClient");
    public static final KeyBinding KEY_RECORD = new KeyBinding("録画 開始 / 停止", 0, "FzClient");
    public static final KeyBinding KEY_SAVE = new KeyBinding("録画 直前を保存", 0, "FzClient");

    private static final String ADDRESS = System.getProperty("fzclient.link", "");
    private static final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>(256);
    private static volatile boolean connected;
    /** The overlay is open: send the numbers, and don't let the game pause because it lost the focus. */
    private static volatile boolean watching;
    private static boolean pauseSetting, pauseSaved;
    private static boolean lastActive, lastFs;
    private static int ticks;
    private static boolean started;

    public static boolean connected() { return connected; }

    public static boolean watching() { return watching; }

    /** Started once from the mod's init. */
    public static void start() {
        if (started || ADDRESS.isEmpty()) return;
        started = true;
        Thread t = new Thread(LauncherLink::run, "FzClient-Launcher");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        String[] a = ADDRESS.split(":", 2);
        int port;
        try { port = Integer.parseInt(a[0]); } catch (Exception e) { return; }
        String token = a.length > 1 ? a[1] : "";
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                s.setTcpNoDelay(true);
                OutputStream os = s.getOutputStream();
                JsonObject hello = new JsonObject();
                hello.addProperty("t", "hello");
                hello.addProperty("token", token);
                os.write((hello + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                connected = true;
                attempt = 0;
                out.clear();
                send(state());
                Thread writer = new Thread(() -> {
                    try {
                        while (!s.isClosed()) {
                            String line = out.poll(1, TimeUnit.SECONDS);
                            if (line == null) continue;
                            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    } catch (Exception ignored) { }
                    try { s.close(); } catch (Exception ignored) { }
                }, "FzClient-LauncherOut");
                writer.setDaemon(true);
                writer.start();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    final String l = line;
                    Minecraft.getMinecraft().addScheduledTask(() -> receive(l));
                }
            } catch (Exception ignored) {
            } finally {
                connected = false;
                watching = false;
                Minecraft.getMinecraft().addScheduledTask(LauncherLink::restorePause);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private static void send(JsonObject o) {
        if (connected) out.offer(o.toString());
    }

    private static JsonObject msg(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    private static JsonObject state() {
        JsonObject o = msg("state");
        o.addProperty("active", Display.isActive());
        o.addProperty("fs", Minecraft.getMinecraft().isFullScreen());
        return o;
    }

    // ---------------------------------------------------------------- from the launcher

    private static void receive(String line) {
        JsonObject o;
        try { o = new JsonParser().parse(line).getAsJsonObject(); } catch (Exception e) { return; }
        String t = o.has("t") ? o.get("t").getAsString() : "";
        Minecraft mc = Minecraft.getMinecraft();
        switch (t) {
            case "watch":
                watching = o.get("on").getAsBoolean();
                if (watching) {
                    // the overlay window takes the focus: no pause menu for that
                    if (!pauseSaved) { pauseSetting = mc.gameSettings.pauseOnLostFocus; pauseSaved = true; }
                    mc.gameSettings.pauseOnLostFocus = false;
                    send(stats());
                } else {
                    restorePause();
                }
                break;
            case "set": {
                int i = o.get("i").getAsInt();
                if (i >= 0 && i < SWITCHES.length) {
                    SWITCHES[i].set(o.get("on").getAsBoolean());
                    Config.save();
                    send(stats());
                }
                break;
            }
            case "menu":
                mc.displayGuiScreen(new dev.fzkc.fzhud.gui.ModsScreen(mc.currentScreen));
                break;
            case "chat":
                if (mc.thePlayer != null) {
                    boolean bad = o.has("error") && o.get("error").getAsBoolean();
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] "
                            + (bad ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW) + Lang.t(o.get("text").getAsString())
                            + (o.has("arg") ? o.get("arg").getAsString() : "")));
                }
                break;
            default:
        }
    }

    private static void restorePause() {
        if (!pauseSaved) return;
        pauseSaved = false;
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = pauseSetting;
    }

    // ---------------------------------------------------------------- every client tick / frame

    /** Client tick: keys in the game, the numbers 4 times a second while the overlay is open. */
    public static void tick(Minecraft mc) {
        boolean overlay = false;
        while (KEY_OVERLAY.isPressed()) overlay = true;
        if (overlay && mc.currentScreen == null && altDown()) key("overlay");
        while (KEY_RECORD.isPressed()) key("record");
        while (KEY_SAVE.isPressed()) key("save");
        if (watching && ++ticks % 5 == 0) send(stats());
    }

    /** Every frame: the focus (the launcher listens for its keys only while the game has it). */
    public static void frame() {
        boolean active = Display.isActive(), fs = Minecraft.getMinecraft().isFullScreen();
        if (active != lastActive || fs != lastFs) {
            lastActive = active;
            lastFs = fs;
            send(state());
        }
        if (watching) FrameStats.sampleCpu();
    }

    /** Alt+X while a screen is open (key bindings only work in the game itself). True = it was ours. */
    public static boolean screenKey(int key, boolean controlsScreen) {
        int k = KEY_OVERLAY.getKeyCode();
        if (k == 0 || key != k || !altDown() || controlsScreen) return false;
        key("overlay");
        return true;
    }

    private static boolean altDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    private static void key(String which) {
        JsonObject o = msg("key");
        o.addProperty("k", which);
        send(o);
    }

    // ---------------------------------------------------------------- what the overlay shows

    /** A whole module or one of its settings. */
    private static final class Switch {
        final String label;
        final Module module;
        final BoolSetting setting;
        final Module owner;

        Switch(String label, Module module) { this(label, module, null, null); }
        Switch(String label, BoolSetting setting, Module owner) { this(label, null, setting, owner); }
        private Switch(String label, Module module, BoolSetting setting, Module owner) {
            this.label = label; this.module = module; this.setting = setting; this.owner = owner;
        }

        boolean on() { return module != null ? module.enabled : setting.value; }
        void set(boolean on) { if (module != null) module.enabled = on; else setting.value = on; }
        boolean live() { return owner == null || owner.enabled; }
    }

    /** The switches that change the FPS most. */
    private static final Switch[] SWITCHES = {
            new Switch("Performance（遠く・壁の向こうを描かない）", Modules.performance),
            new Switch("壁の向こうのモブ・アイテムを描かない", Modules.perfCull, Modules.performance),
            new Switch("雨・雪を描かない", Modules.perfNoWeather, Modules.performance),
            new Switch("雲を描かない", Modules.perfNoClouds, Modules.performance),
            new Switch("Optimization（見た目を変えずに軽く）", Modules.optimize),
            new Switch("ホログラム（浮いている文字）を軽く描く", Modules.optHolograms, Modules.optimize),
            new Switch("エンチャントの光沢を描かない", Modules.optNoGlint, Modules.optimize),
            new Switch("モーションブラー", Modules.motionBlur),
            new Switch("Particles（殴った時の粒子を増やす）", Modules.particles),
    };

    private static JsonObject stats() {
        Minecraft mc = Minecraft.getMinecraft();
        JsonObject o = msg("stats");
        o.addProperty("fps", Math.round(FrameStats.fps()));
        o.addProperty("low", Math.round(FrameStats.low1()));
        o.addProperty("cores", FrameStats.gameCores());
        o.addProperty("sys", FrameStats.systemLoad());
        Runtime rt = Runtime.getRuntime();
        o.addProperty("mem", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
        o.addProperty("memMax", rt.maxMemory() / 1048576L);
        boolean world = mc.theWorld != null;
        o.addProperty("ping", world ? Tracker.ping() : "-");
        o.addProperty("entities", world && mc.renderGlobal != null ? shortEntities(mc.renderGlobal.getDebugInfoEntities()) : "-");
        o.addProperty("particles", world && mc.effectRenderer != null ? mc.effectRenderer.getStatistics() : "-");
        // frame times, newest first (the graph)
        JsonArray frames = new JsonArray();
        int n = Math.min(FrameStats.count(), 240);
        for (int i = 0; i < n; i++) frames.add(new com.google.gson.JsonPrimitive(Math.round(FrameStats.ago(i) * 10) / 10f));
        o.add("frames", frames);
        JsonArray sw = new JsonArray();
        for (Switch s : SWITCHES) {
            JsonObject j = new JsonObject();
            j.addProperty("label", Lang.t(s.label));
            j.addProperty("on", s.on());
            j.addProperty("live", s.live());
            j.addProperty("sub", s.setting != null);
            sw.add(j);
        }
        o.add("switches", sw);
        return o;
    }

    /** "E: 12/340, B: 5, I: 0" -> "12 / 340". */
    private static String shortEntities(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("E: (\\d+)/(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? m.group(1) + " / " + m.group(2) : "-";
    }
}
