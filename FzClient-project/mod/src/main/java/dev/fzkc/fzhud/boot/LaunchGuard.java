package dev.fzkc.fzhud.boot;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * FzClient only runs when the FzClient Launcher started the game. In any other launcher the jar stays in mods/
 * but does nothing: no mixins (see FzMixinPlugin), no features, and a note on the title screen.
 * <p>
 * The launcher proves it started the game in one of two ways:
 * <ul>
 *   <li>a launch ticket: it writes %APPDATA%\FzClient Launcher\launch.ticket ("token time") and passes the same
 *       token as -Dfzclient.ticket; a ticket is only good for a few minutes and is removed once the game read it,
 *       so copying the command line into another launcher does not work later</li>
 *   <li>the link of the running launcher: -Dfzclient.link=port:token and 127.0.0.1:port really answering (launchers
 *       before 1.1.0 only have this; they may start a newer FzClient jar before updating themselves)</li>
 * </ul>
 * No Minecraft classes here: this runs while mixins are being set up, before the game exists.
 */
public final class LaunchGuard {
    private LaunchGuard() {}

    private static final long TICKET_LIFE_MS = 20 * 60 * 1000L;
    private static Boolean ok;

    public static synchronized boolean ok() {
        if (ok == null) ok = check();
        return ok;
    }

    /** The launcher's data folder, as Electron's app.getPath('appData') (self-contained: runs before the game). */
    private static File ticketFile() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");
        File appData;
        if (os.startsWith("windows")) {
            String a = System.getenv("APPDATA");
            if (a == null) return null;
            appData = new File(a);
        } else if (os.startsWith("mac")) {
            appData = new File(home, "Library/Application Support");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            appData = xdg != null && !xdg.isEmpty() ? new File(xdg) : new File(home, ".config");
        }
        return new File(new File(appData, "FzClient Launcher"), "launch.ticket");
    }

    private static boolean check() {
        try {
            if (devEnvironment()) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (ticketValid()) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (linkAnswers()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** gradle runClient (the deobfuscated development game). */
    private static boolean devEnvironment() {
        Object v = net.minecraft.launchwrapper.Launch.blackboard == null ? null
                : net.minecraft.launchwrapper.Launch.blackboard.get("fml.deobfuscatedEnvironment");
        return Boolean.TRUE.equals(v);
    }

    private static boolean ticketValid() throws Exception {
        String want = System.getProperty("fzclient.ticket", "");
        if (!want.matches("[0-9a-f]{32,64}")) return false;
        File f = ticketFile();
        if (f == null || !f.isFile()) return false;
        String[] parts = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split("\\s+");
        if (parts.length < 2 || !parts[0].equals(want)) return false;
        long age = System.currentTimeMillis() - Long.parseLong(parts[1]);
        return age >= -60000L && age < TICKET_LIFE_MS;
    }

    private static boolean linkAnswers() throws Exception {
        String link = System.getProperty("fzclient.link", "");
        int colon = link.indexOf(':');
        if (colon <= 0 || !link.substring(colon + 1).matches("[0-9a-f]{16,64}")) return false;
        int port = Integer.parseInt(link.substring(0, colon));
        if (port <= 0 || port > 65535) return false;
        // only connect and close: the launcher drops a connection that does not say its token
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        }
    }

    /** Called once the mod is running: the ticket is used up. */
    public static void consumeTicket() {
        try {
            File f = ticketFile();
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
