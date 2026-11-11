package dev.fzkc.fzhud.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Borderless the way the borderlessfullscreen mod does it, which recorders and the mouse get along with: the game
 * keeps its normal window, only the title bar and frame are taken off and it is laid over the whole screen, in
 * front of the taskbar.
 *
 * Unlike that mod the window is "always on top" only while the game has the focus: when another window is
 * clicked or Alt+Tab is pressed it goes behind that window, like any other window.
 */
public final class Win32Window {
    private Win32Window() {}

    private interface U32 extends Library {
        int GetWindowLongW(Pointer hwnd, int index);
        int SetWindowLongW(Pointer hwnd, int index, int value);
        boolean SetWindowPos(Pointer hwnd, Pointer after, int x, int y, int cx, int cy, int flags);
        Pointer GetForegroundWindow();
    }

    private static final int GWL_STYLE = -16, GWL_EXSTYLE = -20;
    private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000, WS_EX_TOPMOST = 0x8;
    private static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10, SWP_FRAMECHANGED = 0x20;
    private static final Pointer TOPMOST = new Pointer(-1), NOTOPMOST = new Pointer(-2);

    private static java.lang.reflect.Field implField, hwndField;
    private static long hwndValue, updatedAt;
    private static Pointer hwndPointer;

    private static U32 user32;
    private static boolean loaded;

    private static U32 user32() {
        if (!loaded) {
            loaded = true;
            try {
                if (System.getProperty("os.name", "").startsWith("Windows")) user32 = (U32) Native.loadLibrary("user32", U32.class);
            } catch (Throwable t) {
                System.err.println("[FzClient] user32 not available: " + t);
            }
        }
        return user32;
    }

    /** The game's window (LWJGL keeps it in Display.display_impl.hwnd), or null. */
    public static Pointer hwnd() {
        try {
            if (implField == null) {
                implField = org.lwjgl.opengl.Display.class.getDeclaredField("display_impl");
                implField.setAccessible(true);
            }
            Object d = implField.get(null);
            if (hwndField == null || hwndField.getDeclaringClass() != d.getClass()) {
                hwndField = d.getClass().getDeclaredField("hwnd");
                hwndField.setAccessible(true);
            }
            long v = hwndField.getLong(d);
            if (v == 0) return null;
            if (hwndPointer == null || v != hwndValue) {
                hwndValue = v;
                hwndPointer = new Pointer(v);
            }
            return hwndPointer;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the game is borderless now (the window over the whole screen without its frame). */
    private static boolean active;
    /** Whether the window is in front of everything right now. */
    private static boolean onTop;

    /** Takes the frame off and lays the window over the screen, in front. False if that can't be done here. */
    public static boolean enter(int x, int y, int w, int h) {
        U32 u = user32();
        Pointer hwnd = hwnd();
        if (u == null || hwnd == null) return false;
        u.SetWindowLongW(hwnd, GWL_STYLE, u.GetWindowLongW(hwnd, GWL_STYLE) & ~WS_OVERLAPPEDWINDOW);
        u.SetWindowPos(hwnd, TOPMOST, x, y, w, h, SWP_FRAMECHANGED);
        active = true;
        onTop = true;
        return true;
    }

    /** Back to a normal window (the window itself is made again by LWJGL right after, with its frame). */
    public static void leave() {
        U32 u = user32();
        Pointer hwnd = hwnd();
        if (u != null && hwnd != null && active) {
            u.SetWindowPos(hwnd, NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            u.SetWindowLongW(hwnd, GWL_STYLE, u.GetWindowLongW(hwnd, GWL_STYLE) | WS_OVERLAPPEDWINDOW);
        }
        active = false;
        onTop = false;
    }

    /**
     * Every frame: in front while the game has the focus, behind the window that took it otherwise (directly
     * under it, so the windows that were behind the game stay behind it).
     */
    public static void update(boolean focused) {
        if (!active) return;
        // asked every frame: 20 looks a second are plenty (each one is a call into Windows)
        long now = System.currentTimeMillis();
        if (now - updatedAt < 50L && now >= updatedAt) return;
        updatedAt = now;
        U32 u = user32();
        Pointer hwnd = hwnd();
        if (u == null || hwnd == null) return;
        // what Windows says is in front: LWJGL's "active" alone stayed true at times (the game kept being on
        // top behind another window)
        Pointer fg = u.GetForegroundWindow();
        focused = focused && hwnd.equals(fg);
        if (focused == onTop) return;
        onTop = focused;
        int flags = SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE;
        if (focused) {
            u.SetWindowPos(hwnd, TOPMOST, 0, 0, 0, 0, flags);
            return;
        }
        u.SetWindowPos(hwnd, NOTOPMOST, 0, 0, 0, 0, flags);
        // then right under the window that took the focus, so the windows that were behind the game stay
        // behind it. A window that is on top itself (the overlay, the task manager...) is in front anyway.
        boolean fgOnTop = fg != null && (u.GetWindowLongW(fg, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
        if (fg != null && !fgOnTop && !fg.equals(hwnd)) u.SetWindowPos(hwnd, fg, 0, 0, 0, 0, flags);
    }
}
