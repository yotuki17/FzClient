package dev.fzkc.fzhud.util;

import java.io.File;

/**
 * Which system the game runs on, and the few things that are done differently per system (opening a folder or a web
 * page). On a Mac the game runs as an Intel program under Rosetta, so os.arch says x86_64 there too.
 */
public final class Os {
    private Os() {}

    private static final String NAME = System.getProperty("os.name", "").toLowerCase();
    public static final boolean WIN = NAME.startsWith("windows");
    public static final boolean MAC = NAME.startsWith("mac");

    /** Where Electron keeps the launcher's own files (app.getPath('appData')). */
    public static File appData() {
        if (WIN) {
            String a = System.getenv("APPDATA");
            return a == null ? null : new File(a);
        }
        String home = System.getProperty("user.home", "");
        if (MAC) return new File(home, "Library/Application Support");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return xdg != null && !xdg.isEmpty() ? new File(xdg) : new File(home, ".config");
    }

    /** Shows a folder (or a file) in Explorer / Finder. */
    public static void openFile(File f) {
        try {
            if (WIN) Runtime.getRuntime().exec(new String[] {"explorer.exe", f.getAbsolutePath()});
            else if (MAC) Runtime.getRuntime().exec(new String[] {"/usr/bin/open", f.getAbsolutePath()});
            else Runtime.getRuntime().exec(new String[] {"xdg-open", f.getAbsolutePath()});
        } catch (Exception e) {
            try {
                org.lwjgl.Sys.openURL(f.toURI().toString());
            } catch (Throwable ignored) {
            }
        }
    }

    /** Opens a web page in the default browser. On a Mac, AWT's Desktop can hang next to the game's window. */
    public static void openUrl(String url) {
        if (MAC) {
            try {
                Runtime.getRuntime().exec(new String[] {"/usr/bin/open", url});
                return;
            } catch (Throwable ignored) {
            }
        } else {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            org.lwjgl.Sys.openURL(url);
        } catch (Throwable ignored) {
        }
    }
}
