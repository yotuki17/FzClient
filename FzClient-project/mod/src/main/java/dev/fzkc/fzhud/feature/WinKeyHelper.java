package dev.fzkc.fzhud.feature;

import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The Windows key lock's keyboard hook, in a small Java process of its own (started by WinKeyLock with the game's
 * own Java, JNA and this jar; nothing of Minecraft is loaded here).
 *
 * In the game's own process Windows kept dropping the hook: a low-level keyboard hook must answer within a few
 * hundred milliseconds, and the game's Java stops now and then (garbage collection, loading), after which Windows
 * stopped calling the hook for good. This process does nothing else, so it always answers in time.
 *
 * Args: the game's process id. stdin: "1" = lock on, "2" = lock on but Win + another key still works (only the Start
 * menu of a Windows key pressed on its own is stopped), "0" = off; when stdin ends (the game closed) this ends too.
 * stderr: a few lines for the game's log.
 */
public final class WinKeyHelper {
    private WinKeyHelper() {}

    public static class LongPtr extends IntegerType {
        public LongPtr() { this(0); }
        public LongPtr(long v) { super(Pointer.SIZE, v); }
    }

    public interface LowLevelProc extends StdCallLibrary.StdCallCallback {
        LongPtr callback(int code, LongPtr wParam, Pointer lParam);
    }

    interface User32 extends StdCallLibrary {
        Pointer SetWindowsHookExW(int id, LowLevelProc proc, Pointer module, int thread);
        LongPtr CallNextHookEx(Pointer hook, int code, LongPtr wParam, Pointer lParam);
        boolean UnhookWindowsHookEx(Pointer hook);
        int GetMessageW(Pointer msg, Pointer hwnd, int min, int max);
        LongPtr SetTimer(Pointer hwnd, LongPtr id, int ms, Pointer proc);
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer hwnd, Pointer pid);
        short GetAsyncKeyState(int vk);
        int SendInput(int count, Pointer inputs, int size);
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer GetModuleHandleW(WString name);
    }

    private static final int WH_KEYBOARD_LL = 13, VK_LWIN = 0x5B, VK_RWIN = 0x5C, LLKHF_UP = 0x80, WM_TIMER = 0x0113;
    private static final LongPtr SWALLOW = new LongPtr(1);
    /** Dev check: F24 stands in for the Windows key and the game counts as in front. */
    private static final boolean TEST = System.getProperty("fzclient.winkeytest") != null;
    /** Marks the key events this process sends itself (KBDLLHOOKSTRUCT.dwExtraInfo). */
    private static final long MARK = 0x465A4B4CL;
    /** An unassigned key: pressed between the Windows key's down and up, Windows opens no Start menu. */
    private static final int VK_MASK = 0xE8;

    private static volatile boolean locked, combos;
    private static volatile int swallowedCount, passedCount, shortcutCount;
    private static final boolean[] swallowed = new boolean[2];
    /** The key is held (seen going down, not yet up): its auto-repeats keep the first press's decision. */
    private static final boolean[] down = new boolean[2];
    /** Combos mode: this press is watched (its Start menu is stopped at the release unless it became a shortcut). */
    private static final boolean[] watched = new boolean[2];
    /** When each Windows key last came down (the event's time, ms). */
    private static final int[] lastDown = new int[2];
    /** Another key went down while a Windows key was held (Win + X: a shortcut, left to Windows). */
    private static boolean other;
    private static LowLevelProc proc;   // strong reference while the hook is installed

    public static void main(String[] args) throws Exception {
        final int game = Integer.parseInt(args[0]);
        final User32 user32 = (User32) Native.loadLibrary("user32", User32.class);
        final Kernel32 kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);

        // the lock state from the game; the end of stdin = the game is gone
        Thread input = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    String v = line.trim();
                    combos = "2".equals(v);
                    locked = combos || "1".equals(v);
                }
            } catch (Exception ignored) {
                // the game is gone
            }
            System.exit(0);
        }, "input");
        input.setDaemon(true);
        input.start();

        // a line now and then for the game's log (never from inside the hook, which must answer at once)
        Thread report = new Thread(() -> {
            int s = 0, p = 0, c = 0;
            while (true) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                if (s != swallowedCount || p != passedCount || c != shortcutCount) {
                    s = swallowedCount;
                    p = passedCount;
                    c = shortcutCount;
                    System.err.println("Windows key presses: " + s + " stopped, " + c + " used in shortcuts, " + p
                            + " passed (game not in front or lock off)");
                }
            }
        }, "report");
        report.setDaemon(true);
        report.start();

        final Pointer[] hook = new Pointer[1];
        final Memory pid = new Memory(4);
        proc = new LowLevelProc() {   // a class, not a lambda: JNA looks the method up by reflection
            @Override
            public LongPtr callback(int code, LongPtr wParam, Pointer lParam) {
                try {
                    if (code >= 0 && lParam != null && extra(lParam) != MARK) {
                        int vk = lParam.getInt(0);
                        boolean up = (lParam.getInt(8) & LLKHF_UP) != 0;
                        int side = vk == VK_LWIN || TEST && vk == 0x87 ? 0 : vk == VK_RWIN ? 1 : -1;
                        if (side >= 0) {
                            int time = lParam.getInt(12);
                            if (!up) {
                                // a release this hook never saw (Win + L locks the PC, and no hook runs on the
                                // lock screen) must not turn the next press into an "auto-repeat" of the old one:
                                // a key Windows has seen is still down while it repeats, and repeats come fast
                                boolean stale = down[side] && (time - lastDown[side] > 1500
                                        || !swallowed[side] && (user32.GetAsyncKeyState(vk) & 0x8000) == 0);
                                lastDown[side] = time;
                                if (!down[side] || stale) {
                                    // a fresh press: decided once, the auto-repeats follow it. A key Windows already
                                    // has down (held before the game came to the front or this process started)
                                    // is left alone, or Windows would never see it come up.
                                    boolean held = (user32.GetAsyncKeyState(vk) & 0x8000) != 0;
                                    boolean act = !held && locked && (TEST || front(user32, pid, game));
                                    if (!down[1 - side] || stale) other = false;
                                    down[side] = true;
                                    // combos: Windows sees the key, so Win + X still works; only the Start menu
                                    // of a press on its own is stopped, at the release
                                    watched[side] = act && combos;
                                    swallowed[side] = act && !combos;
                                    if (swallowed[side]) swallowedCount++;
                                    else if (!act) passedCount++;
                                }
                                if (swallowed[side]) return SWALLOW;
                            } else {
                                down[side] = false;
                                if (swallowed[side]) {
                                    // the key-up of a key-down we swallowed (Windows never saw it go down)
                                    swallowed[side] = false;
                                    return SWALLOW;
                                }
                                if (watched[side]) {
                                    watched[side] = false;
                                    if (other) {
                                        shortcutCount++;
                                    } else {
                                        // on its own: this release is held back and sent again after an unused
                                        // key, so Windows takes it as a shortcut and opens no Start menu
                                        // (if Windows refuses what we send, the real release goes on instead: a
                                        // Start menu is better than a Windows key left held down)
                                        if (mask(user32, vk)) {
                                            swallowedCount++;
                                            return SWALLOW;
                                        }
                                    }
                                }
                            }
                        } else if (!up && (down[0] || down[1])) {
                            other = true;   // Win + this key
                        }
                    }
                } catch (Throwable ignored) {
                    // pass the key on
                }
                return user32.CallNextHookEx(hook[0], code, wParam, lParam);
            }
        };
        Pointer module = kernel32.GetModuleHandleW(null);
        hook[0] = user32.SetWindowsHookExW(WH_KEYBOARD_LL, proc, module, 0);
        if (hook[0] == null) {
            System.err.println("Windows key lock: the keyboard hook was refused");
            System.exit(2);
        }
        System.err.println("Windows key lock ready (own process)");
        // put back every 2 seconds anyway, in case Windows dropped it: a new one first, then the old one goes
        user32.SetTimer(null, new LongPtr(0), 2000, null);
        Memory msg = new Memory(64);
        while (user32.GetMessageW(msg, null, 0, 0) > 0) {
            if (msg.getInt(Pointer.SIZE) != WM_TIMER) continue;   // MSG: hwnd, then message
            Pointer fresh = user32.SetWindowsHookExW(WH_KEYBOARD_LL, proc, module, 0);
            if (fresh != null) {
                user32.UnhookWindowsHookEx(hook[0]);
                hook[0] = fresh;
            }
        }
        user32.UnhookWindowsHookEx(hook[0]);
    }

    /** KBDLLHOOKSTRUCT: vkCode, scanCode, flags, time, then dwExtraInfo (pointer-sized, at 16 either way). */
    private static long extra(Pointer k) {
        return Pointer.SIZE == 8 ? k.getLong(16) : k.getInt(16) & 0xFFFFFFFFL;
    }

    /** Sends the unused key down and up, then the Windows key's release (all marked as ours). */
    private static boolean mask(User32 user32, int vk) {
        // INPUT: type, then KEYBDINPUT (wVk, wScan, dwFlags, time, dwExtraInfo) after the union's alignment
        int size = Pointer.SIZE == 8 ? 40 : 28, ki = Pointer.SIZE == 8 ? 8 : 4;
        int winFlag = vk == VK_LWIN || vk == VK_RWIN ? 1 : 0;   // KEYEVENTF_EXTENDEDKEY
        int[][] keys = {{VK_MASK, 0}, {VK_MASK, 2}, {vk, 2 | winFlag}};   // 2 = KEYEVENTF_KEYUP
        Memory in = new Memory((long) size * keys.length);
        in.clear();
        for (int i = 0; i < keys.length; i++) {
            long b = (long) i * size;
            in.setInt(b, 1);                              // INPUT_KEYBOARD
            in.setShort(b + ki, (short) keys[i][0]);     // wVk
            in.setInt(b + ki + 4, keys[i][1]);           // dwFlags
            if (Pointer.SIZE == 8) in.setLong(b + ki + 16, MARK);
            else in.setInt(b + ki + 12, (int) MARK);
        }
        return user32.SendInput(keys.length, in, size) == keys.length;
    }

    /** The window in front belongs to the game's process. */
    private static boolean front(User32 user32, Pointer pid, int game) {
        Pointer fg = user32.GetForegroundWindow();
        if (fg == null) return false;
        pid.setInt(0, 0);
        user32.GetWindowThreadProcessId(fg, pid);
        return pid.getInt(0) == game;
    }
}
