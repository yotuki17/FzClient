package dev.fzkc.fzhud.integration;

import dev.fzkc.fzhud.chat.NativeIme;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * Spotify desktop app link (Windows): no login, works with Spotify Free.
 *  - now playing: read from the Spotify window title ("Artist - Song" while playing)
 *  - play/pause, next, previous: WM_APPCOMMAND sent straight to the Spotify window,
 *    so it controls Spotify even if a browser is also playing something
 * Polling runs on a background thread once per second and only while it's needed.
 */
public final class Spotify {
    private Spotify() {}

    public static final KeyBinding keyPlay = new KeyBinding("Spotify 再生/一時停止", Keyboard.KEY_NONE, "FzClient");
    public static final KeyBinding keyNext = new KeyBinding("Spotify 次の曲", Keyboard.KEY_NONE, "FzClient");
    public static final KeyBinding keyPrev = new KeyBinding("Spotify 前の曲", Keyboard.KEY_NONE, "FzClient");

    // ---- what the HUD shows
    public static volatile boolean running;
    public static volatile boolean playing;
    public static volatile String artist = "";
    public static volatile String title = "";
    /** Bumped whenever the song changes (for the HUD's slide-in). */
    public static volatile int songId;

    // ---- Win32
    public interface EnumProc extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer hwnd, Pointer data);
    }

    interface User32 extends StdCallLibrary {
        void EnumWindows(EnumProc proc, Pointer data);
        int GetWindowTextW(Pointer hwnd, char[] buf, int max);
        void GetWindowThreadProcessId(Pointer hwnd, int[] pid);
        boolean IsWindowVisible(Pointer hwnd);
        boolean IsWindow(Pointer hwnd);
        void PostMessageW(Pointer hwnd, int msg, NativeIme.LongPtr w, NativeIme.LongPtr l);
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] buf, int[] size);
        void CloseHandle(Pointer handle);
    }

    private static final int WM_APPCOMMAND = 0x0319;
    private static final int APPCOMMAND_NEXT = 11, APPCOMMAND_PREV = 12, APPCOMMAND_PLAY_PAUSE = 14;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private static User32 user32;
    private static Kernel32 kernel32;
    private static boolean failed;
    private static volatile Pointer window;
    private static int spotifyPid = -1;
    private static final char[] buf = new char[512];
    private static Thread poller;
    private static volatile long wantedUntil;

    private static boolean init() {
        if (failed) return false;
        if (user32 != null) return true;
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            failed = true;
            return false;
        }
        try {
            user32 = (User32) Native.loadLibrary("user32", User32.class);
            kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
            return true;
        } catch (Throwable t) {
            failed = true;
            System.err.println("[FzClient] Spotify link unavailable: " + t);
            return false;
        }
    }

    /** Called by the HUD every frame it is drawn: keeps the background poller alive. */
    public static void keepAlive() {
        wantedUntil = System.currentTimeMillis() + 3000L;
        if (poller != null && poller.isAlive()) return;
        if (!init()) return;
        poller = new Thread(() -> {
            while (System.currentTimeMillis() < wantedUntil) {
                try {
                    poll();
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "FzClient-Spotify");
        poller.setDaemon(true);
        poller.start();
    }

    private static synchronized void poll() {
        Pointer w = window;
        if (w == null || !user32.IsWindow(w)) w = find();
        window = w;
        if (w == null) {
            running = false;
            playing = false;
            return;
        }
        running = true;
        int n = user32.GetWindowTextW(w, buf, buf.length);
        String t = n > 0 ? new String(buf, 0, n) : "";
        int dash = t.indexOf(" - ");
        if (dash > 0) {
            String a = t.substring(0, dash).trim(), s = t.substring(dash + 3).trim();
            if (!a.equals(artist) || !s.equals(title)) {
                artist = a;
                title = s;
                songId++;
            }
            playing = true;
        } else {
            // "Spotify Free" / "Spotify Premium" = paused (keep showing the last song); ads have no dash either
            playing = false;
        }
    }

    // window search state (only used while holding the Spotify.class lock: poll() and command() take it)
    private static final Pointer[] found = new Pointer[1];
    private static final int[] foundPid = new int[1];
    /** Processes already checked and found not to be Spotify (forgotten every 30 s, in case a number is reused). */
    private static final java.util.Set<Integer> notSpotify = new java.util.HashSet<>();
    private static long notSpotifyReset;
    /** One callback for every search (JNA makes a native stub for each new callback object). */
    private static EnumProc enumProc;

    /** Finds Spotify's main window: a visible top-level window of Spotify.exe that has a title. */
    private static Pointer find() {
        long now = System.currentTimeMillis();
        if (now > notSpotifyReset) {
            notSpotify.clear();
            notSpotifyReset = now + 30000L;
        }
        if (enumProc == null) {
            enumProc = (hwnd, data) -> {
                if (!user32.IsWindowVisible(hwnd)) return true;
                if (user32.GetWindowTextW(hwnd, buf, buf.length) <= 0) return true;
                user32.GetWindowThreadProcessId(hwnd, foundPid);
                int pid = foundPid[0];
                if (pid == spotifyPid || (!notSpotify.contains(pid) && isSpotifyChecked(pid))) {
                    spotifyPid = pid;
                    found[0] = hwnd;
                    return false;
                }
                return true;
            };
        }
        found[0] = null;
        user32.EnumWindows(enumProc, null);
        return found[0];
    }

    private static boolean isSpotifyChecked(int pid) {
        if (isSpotify(pid)) return true;
        notSpotify.add(pid);
        return false;
    }

    private static boolean isSpotify(int pid) {
        Pointer h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == null) return false;
        try {
            char[] path = new char[520];
            int[] size = {path.length};
            if (!kernel32.QueryFullProcessImageNameW(h, 0, path, size)) return false;
            String p = new String(path, 0, size[0]).toLowerCase();
            return p.endsWith("\\spotify.exe");
        } finally {
            kernel32.CloseHandle(h);
        }
    }

    private static void command(final int appCommand) {
        if (!init()) return;
        Thread t = new Thread(() -> {
            synchronized (Spotify.class) {
                Pointer w = window;
                if (w == null || !user32.IsWindow(w)) w = window = find();
                if (w == null) return;
                user32.PostMessageW(w, WM_APPCOMMAND, new NativeIme.LongPtr(0), new NativeIme.LongPtr((long) appCommand << 16));
            }
            try {
                Thread.sleep(400L);
                poll(); // show the new song right away
            } catch (Throwable ignored) {
            }
        }, "FzClient-Spotify-Cmd");
        t.setDaemon(true);
        t.start();
    }

    public static void playPause() { command(APPCOMMAND_PLAY_PAUSE); }
    public static void next() { command(APPCOMMAND_NEXT); }
    public static void previous() { command(APPCOMMAND_PREV); }

    /** Client tick: hotkeys. */
    public static void tick() {
        while (keyPlay.isPressed()) playPause();
        while (keyNext.isPressed()) next();
        while (keyPrev.isPressed()) previous();
    }
}
