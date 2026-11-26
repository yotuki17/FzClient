package dev.fzkc.fzhud.feature;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import dev.fzkc.fzhud.Modules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Mouse side buttons (Button 4 / Button 5) on Windows.
 *
 * Minecraft 1.8.9's LWJGL decides which side button went down by checking that the message's key flags are exactly
 * "side button 1". The flags also hold every other button and Ctrl / Shift that is down, so a side button 1
 * press while holding a click or Shift (most of PvP) came in as Button 5: Button 4 did nothing, and Button 5 was
 * left held (its release came in correctly as Button 4). Letting go was already right.
 *
 * A message hook on the game's own thread (no other program is touched) rewrites those flags just before LWJGL
 * reads the message, so it sees the side button that really went down. It also keeps which side button is held,
 * for mice whose driver reports fewer than 5 buttons (LWJGL then says Button 4 / 5 are never held).
 */
public final class SideButtons {
    private SideButtons() {}

    private static final Logger LOG = LogManager.getLogger("FzClient-SideButtons");

    public interface MsgProc extends StdCallLibrary.StdCallCallback {
        WinKeyHelper.LongPtr callback(int code, WinKeyHelper.LongPtr wParam, Pointer lParam);
    }

    interface User32 extends StdCallLibrary {
        Pointer SetWindowsHookExW(int id, MsgProc proc, Pointer module, int thread);
        WinKeyHelper.LongPtr CallNextHookEx(Pointer hook, int code, WinKeyHelper.LongPtr wParam, Pointer lParam);
        boolean UnhookWindowsHookEx(Pointer hook);
    }

    interface Kernel32 extends StdCallLibrary {
        int GetCurrentThreadId();
    }

    private static final int WH_GETMESSAGE = 3, WM_XBUTTONDOWN = 0x020B, WM_XBUTTONUP = 0x020C;
    private static final int MK_XBUTTON1 = 0x20, MK_XBUTTON2 = 0x40;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private static User32 user32;
    private static Pointer hook;
    private static MsgProc proc;   // strong reference while the hook is installed
    private static boolean failed;
    /** Side button 1 / 2 held (seen by the hook). */
    private static final boolean[] held = new boolean[2];

    /** Each client tick (the game's thread, the one that reads the window's messages). */
    public static void tick() {
        if (!WINDOWS || failed) return;
        boolean want = Modules.fixes.enabled && Modules.fixSideButtons.get();
        if (want && hook == null) install();
        else if (!want && hook != null) uninstall();
        if (hook != null && !Display.isActive()) {
            held[0] = held[1] = false;   // a release outside the window never reaches it
        }
    }

    private static void install() {
        try {
            if (user32 == null) user32 = (User32) Native.loadLibrary("user32", User32.class);
            Kernel32 kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
            if (proc == null) {
                proc = new MsgProc() {   // a class, not a lambda: JNA looks the method up by reflection
                    @Override
                    public WinKeyHelper.LongPtr callback(int code, WinKeyHelper.LongPtr wParam, Pointer lParam) {
                        try {
                            if (code >= 0 && lParam != null) fix(lParam);
                        } catch (Throwable ignored) {
                            // leave the message as it was
                        }
                        return user32.CallNextHookEx(hook, code, wParam, lParam);
                    }
                };
            }
            hook = user32.SetWindowsHookExW(WH_GETMESSAGE, proc, null, kernel32.GetCurrentThreadId());
            if (hook == null) {
                failed = true;
                LOG.warn("Side button fix unavailable (hook refused)");
            } else {
                LOG.info("Side button fix on");
            }
        } catch (Throwable t) {
            failed = true;
            LOG.warn("Side button fix unavailable: " + t);
        }
    }

    private static void uninstall() {
        try {
            user32.UnhookWindowsHookEx(hook);
        } catch (Throwable ignored) {
            // gone anyway
        }
        hook = null;
        held[0] = held[1] = false;
    }

    /** lParam = the MSG: hwnd, message, wParam, lParam (wParam starts two pointer sizes in). */
    private static void fix(Pointer msg) {
        int message = msg.getInt(Pointer.SIZE);
        if (message != WM_XBUTTONDOWN && message != WM_XBUTTONUP) return;
        int off = 2 * Pointer.SIZE;
        long w = Pointer.SIZE == 8 ? msg.getLong(off) : msg.getInt(off) & 0xFFFFFFFFL;
        int which = (int) ((w >> 16) & 0xFFFF);   // 1 = side button 1 (Button 4), 2 = side button 2 (Button 5)
        if (which != 1 && which != 2) return;
        boolean down = message == WM_XBUTTONDOWN;
        held[which - 1] = down;
        if (!down) return;   // LWJGL reads the release right
        long fixed = ((long) which << 16) | (which == 1 ? MK_XBUTTON1 : MK_XBUTTON2);
        if (Pointer.SIZE == 8) msg.setLong(off, fixed);
        else msg.setInt(off, (int) fixed);
    }

    /** A mouse button (0 = left ... 3 = Button 4, 4 = Button 5) is held now. */
    public static boolean isDown(int button) {
        if (button < 0) return false;
        if ((button == 3 || button == 4) && hook != null && held[button - 3]) return true;
        try {
            return Mouse.isButtonDown(button);
        } catch (Throwable e) {
            return false;
        }
    }
}
