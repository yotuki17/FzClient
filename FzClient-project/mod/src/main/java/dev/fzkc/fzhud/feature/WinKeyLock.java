package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows Key Lock: while the game window is in front, the Windows keys do nothing (no Start menu popping up in a
 * fight). A key switches the lock on and off. Windows only.
 *
 * The keyboard hook runs in a small process of its own (WinKeyHelper, the game's Java with JNA and this jar): in the
 * game's process Windows kept dropping it whenever the game's Java paused. That process ends with the game (its
 * input closes), changes no Windows setting, and Win + L still locks the PC (Windows lets no one stop that).
 */
public final class WinKeyLock {
    private static final Logger LOG = LogManager.getLogger("FzClient-WinKey");

    public static final KeyBinding KEY = new KeyBinding("Windows キーの無効 切り替え", 0, "FzClient");

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private static final boolean TEST = System.getProperty("fzclient.winkeytest") != null;
    /** Starts of the helper allowed in one game session (it should never die; don't loop if it can't run). */
    private static final int MAX_STARTS = 5;

    private static boolean locked, startDone, failed;
    private static Process helper;
    private static OutputStream helperIn;
    private static int starts, sent = -1, ticks;
    private static volatile boolean ready;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !WINDOWS) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean on = Modules.winKey.enabled;
        if (on && !startDone) {
            startDone = true;
            locked = Modules.winKeyStart.get();
        }
        if (!on) startDone = false;
        while (KEY.isPressed()) {
            if (!on) continue;
            locked = !locked;
            LOG.info("Windows key lock " + (locked ? "on" : "off") + " (switch key)");
            if (Modules.winKeyMsg.get() && mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] "
                        + EnumChatFormatting.YELLOW + Lang.t(locked ? "Windows キーを無効にしました" : "Windows キーを使えるようにしました")));
            }
        }
        if (failed) return;
        if (on && (helper == null || ++ticks % 40 == 0 && !alive())) start();
        int want = on && locked ? 1 : 0;
        if (helperIn != null && want != sent) send(want);
    }

    /** True while the Windows keys are swallowed (for the settings / HUD). */
    public static boolean active() {
        return Modules.winKey.enabled && locked && ready && alive();
    }

    private static boolean alive() {
        if (helper == null) return false;
        try {
            helper.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private static void send(int v) {
        try {
            helperIn.write((v + "\n").getBytes(StandardCharsets.UTF_8));
            helperIn.flush();
            sent = v;
        } catch (Exception e) {
            helperIn = null;   // it died; started again on a later tick
        }
    }

    private static void start() {
        if (starts++ >= MAX_STARTS) {
            failed = true;
            LOG.warn("Windows key lock: the helper keeps stopping, giving up for this session");
            return;
        }
        try {
            String javaHome = System.getProperty("java.home");
            File java = new File(javaHome, "bin/javaw.exe");
            if (!java.isFile()) java = new File(javaHome, "bin/java.exe");
            String cp = jarOf(com.sun.jna.Native.class) + File.pathSeparator + jarOf(WinKeyHelper.class);
            List<String> cmd = new ArrayList<String>();
            cmd.add(java.getAbsolutePath());
            cmd.add("-Xms8m");   // with -Xmx: a big -Xms from _JAVA_OPTIONS would stop it starting
            cmd.add("-Xmx24m");
            cmd.add("-XX:+UseSerialGC");
            if (TEST) cmd.add("-Dfzclient.winkeytest");
            cmd.add("-cp");
            cmd.add(cp);
            cmd.add(WinKeyHelper.class.getName());
            cmd.add(String.valueOf(pid()));
            final Process p = new ProcessBuilder(cmd).start();
            helper = p;
            helperIn = p.getOutputStream();
            sent = -1;
            ready = false;
            Thread log = new Thread(() -> {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("ready")) ready = true;
                        LOG.info(line);
                    }
                } catch (Exception ignored) {
                    // ended
                }
                LOG.info("Windows key lock: helper ended");
            }, "FzClient-WinKey");
            log.setDaemon(true);
            log.start();
            p.getInputStream().close();   // it writes nothing there
        } catch (Throwable t) {
            failed = true;
            LOG.warn("Windows key lock unavailable: " + t);
        }
    }

    /** The jar (or folder) a class was loaded from. */
    private static String jarOf(Class<?> c) throws Exception {
        URL u = c.getProtectionDomain().getCodeSource().getLocation();
        String s = u.toString();
        if (s.startsWith("jar:")) {
            s = s.substring(4);
            int bang = s.indexOf("!/");
            if (bang >= 0) s = s.substring(0, bang);
        }
        return new File(new URL(s).toURI()).getAbsolutePath();
    }

    /** This process's id ("1234@host"). */
    private static int pid() {
        String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        return Integer.parseInt(n.substring(0, n.indexOf('@')));
    }
}
