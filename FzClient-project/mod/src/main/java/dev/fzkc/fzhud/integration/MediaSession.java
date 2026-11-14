package dev.fzkc.fzhud.integration;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Now playing from Windows' media controls (what the volume flyout shows), for apps other than Spotify:
 * YouTube Music (its desktop apps, or YouTube Music in a browser / installed from one), Apple Music, or any app.
 * <p>
 * Java can't reach those controls directly, so a small PowerShell helper (assets/fzclient/media.ps1) prints the
 * song once a second and takes play / pause, next and previous on its input. It starts only while the music HUD
 * is drawn (or a music key is pressed), stops by itself when nothing needs it for a while, and quits with the game.
 */
public final class MediaSession {
    private MediaSession() {}

    /** Which apps count: {@link #ANY}, {@link #YOUTUBE_MUSIC}, {@link #APPLE_MUSIC} (the helper's %FILTER%). */
    public static final int ANY = 0, YOUTUBE_MUSIC = 1, APPLE_MUSIC = 2;

    // ---- what the HUD shows
    public static volatile boolean running;
    public static volatile boolean playing;
    public static volatile String artist = "", title = "", album = "", app = "";
    public static volatile int songId;

    // ---- timeline from the last line
    private static volatile long posMs, endMs, receivedAt;

    private static volatile Process helper;
    private static volatile OutputStream helperIn;
    private static volatile int helperFilter = -1;
    private static volatile boolean failed;
    private static volatile long wantedUntil;
    private static long nextCheck, startedAt;
    private static int quickExits;
    private static volatile boolean stoppedIdle;
    private static boolean hookAdded;
    private static String script;

    private static int pid() {
        try {
            String n = ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(n.substring(0, n.indexOf('@')));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String script() throws Exception {
        if (script == null) {
            try (InputStream in = MediaSession.class.getResourceAsStream("/assets/fzclient/media.ps1")) {
                if (in == null) throw new IllegalStateException("media.ps1 missing");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                script = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return script;
    }

    /** Called while the HUD is drawn (and before a command): keeps the helper for this filter running. */
    public static synchronized void keepAlive(int filter) {
        long now = System.currentTimeMillis();
        wantedUntil = now + 5000L;
        if (failed) return;
        if (filter != helperFilter && helper != null) {
            // another app was picked: start over with the new filter
            stoppedIdle = true;
            helper.destroy();
            helper = null;
            clear();
            nextCheck = 0;
        }
        if (now < nextCheck) return;
        nextCheck = now + 1000L;
        if (helper != null) {
            if (helper.isAlive()) return;
            if (!stoppedIdle && now - startedAt < 15000L && ++quickExits >= 3) {
                failed = true;   // something on this PC stops it every time
                return;
            }
        }
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            failed = true;
            return;
        }
        int pid = pid();
        if (pid <= 0) {
            failed = true;
            return;
        }
        try {
            String s = script().replace("%PID%", String.valueOf(pid)).replace("%FILTER%", String.valueOf(filter));
            String encoded = Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            stoppedIdle = false;
            startedAt = now;
            helperFilter = filter;
            final Process p = pb.start();
            helper = p;
            helperIn = p.getOutputStream();
            Thread reader = new Thread(() -> read(p), "FzClient-MediaSession");
            reader.setDaemon(true);
            reader.start();
            if (!hookAdded) {
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    Process h = helper;
                    if (h != null) h.destroy();
                }));
            }
        } catch (Throwable t) {
            failed = true;
            System.err.println("[FzClient] Media controls unavailable: " + t);
        }
    }

    /** No app (or another app picked): forget the song too, so the HUD never shows another app's song. */
    private static void clear() {
        running = false;
        playing = false;
        if (!title.isEmpty() || !artist.isEmpty()) songId++;
        title = "";
        artist = "";
        album = "";
        app = "";
        endMs = 0;
    }

    private static String text(String b64) {
        if (b64 == null || b64.isEmpty() || b64.equals("-")) return "";   // "-" = empty
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void read(Process p) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (helper != p) break;   // replaced by a helper for another app
                // only the line end goes, not the fields (an empty text is "-", but never trim them away)
                while (line.endsWith("\r") || line.endsWith("\n")) line = line.substring(0, line.length() - 1);
                if (line.equals("X")) {
                    failed = true;
                    break;
                }
                if (line.equals("N")) {
                    clear();
                } else if (line.startsWith("S ")) {
                    String[] f = line.split(" ", -1);
                    if (f.length < 8) continue;
                    int status;
                    long pos, end, age;
                    try {
                        status = Integer.parseInt(f[1]);
                        pos = Long.parseLong(f[2]);
                        end = Long.parseLong(f[3]);
                        age = Long.parseLong(f[4]);
                    } catch (NumberFormatException e) {
                        continue;   // one odd line: keep reading
                    }
                    String a = text(f[7]), t = text(f[6]), al = f.length > 8 ? text(f[8]) : "";
                    // Apple Music puts "Artist — Album" in the artist field
                    int dash = a.indexOf(" — ");
                    if (dash > 0 && al.isEmpty()) {
                        al = a.substring(dash + 3).trim();
                        a = a.substring(0, dash).trim();
                    }
                    app = text(f[5]);
                    running = true;
                    playing = status == 4;
                    if (!t.equals(title) || !a.equals(artist)) {
                        title = t;
                        artist = a;
                        songId++;
                    }
                    album = al;
                    endMs = end;
                    posMs = pos + (playing ? Math.max(0, Math.min(age, end)) : 0);
                    receivedAt = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() > wantedUntil) {
                    stoppedIdle = true;   // nobody has looked for a while: stop
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            p.destroy();
            if (helper == p) clear();
        }
    }

    /** 0..1 through the song, or -1 when unknown. */
    public static float progress() {
        long now = System.currentTimeMillis();
        if (endMs <= 0 || now - receivedAt > 4000L) return -1f;
        long pos = posMs + (playing ? now - receivedAt : 0);
        return Math.max(0f, Math.min(1f, pos / (float) endMs));
    }

    /** Sends P (play / pause), N (next) or B (previous) to the app the helper picked. */
    public static void command(int filter, char c) {
        keepAlive(filter);
        OutputStream in = helperIn;
        Process p = helper;
        if (in == null || p == null || !p.isAlive()) return;
        try {
            synchronized (MediaSession.class) {
                in.write(new byte[]{(byte) c, '\n'});
                in.flush();
            }
        } catch (Throwable ignored) {
        }
    }
}
