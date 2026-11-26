package dev.fzkc.fzhud.feature;

import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
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
import org.lwjgl.opengl.Display;

/**
 * Windows Key Lock: while the game window has the focus, the Windows keys do nothing (no Start menu popping up in a
 * fight). A key switches the lock on and off. Windows only; it is a keyboard hook of this program alone (gone when
 * the game closes), no Windows setting is changed. Win + L still locks the PC: Windows does not let anyone stop that.
 */
public final class WinKeyLock {
    private static final Logger LOG = LogManager.getLogger("FzClient-WinKey");

    public static final KeyBinding KEY = new KeyBinding("Windows キーの無効 切り替え", 0, "FzClient");

    /** Pointer-sized integer (WPARAM / LRESULT). */
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
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer GetModuleHandleW(WString name);
        int GetCurrentThreadId();
    }

    private static final int WH_KEYBOARD_LL = 13, VK_LWIN = 0x5B, VK_RWIN = 0x5C, LLKHF_UP = 0x80;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    /** Locked now (the switch key flips it). */
    private static volatile boolean locked;
    /** The game window has the focus (set every tick; read on the hook's thread). */
    private static volatile boolean focused;
    /** The module is on (read on the hook's thread: once started, the hook stays until the game closes). */
    private static volatile boolean enabled;
    /** The hook is installed and its thread is running. */
    private static volatile boolean hookOk;
    private static boolean started, failed, startDone;
    /** Left / right Windows key went down while locked (only then is its key-up swallowed too). Hook thread only. */
    private static final boolean[] swallowed = new boolean[2];
    /** Kept here so the callback is never garbage collected while Windows still calls it (set once). */
    private static LowLevelProc proc;

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
            if (!on || failed) continue;
            locked = !locked;
            if (Modules.winKeyMsg.get() && mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] "
                        + EnumChatFormatting.YELLOW + Lang.t(locked ? "Windows キーを無効にしました" : "Windows キーを使えるようにしました")));
            }
        }
        focused = Display.isActive();
        enabled = on;
        if (on && !started) start();
    }

    /** True while the Windows keys are swallowed (for the settings / HUD). */
    public static boolean active() {
        return Modules.winKey.enabled && locked && hookOk;
    }

    /**
     * Started once, the first time the module is on; the hook then stays until the game closes and does nothing
     * while the module is off (no start / stop races, and the callback is never freed while installed).
     */
    private static void start() {
        started = true;
        final User32 user32;
        final Kernel32 kernel32;
        try {
            user32 = (User32) Native.loadLibrary("user32", User32.class);
            kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
        } catch (Throwable t) {
            failed = true;
            LOG.warn("Windows key lock unavailable: " + t);
            return;
        }
        Thread thread = new Thread(() -> {
            final Pointer[] hook = new Pointer[1];
            proc = new LowLevelProc() {   // a class, not a lambda: JNA looks the method up by reflection
                @Override
                public LongPtr callback(int code, LongPtr wParam, Pointer lParam) {
                    try {
                        if (code >= 0 && lParam != null) {
                            int vk = lParam.getInt(0);
                            int side = vk == VK_LWIN ? 0 : vk == VK_RWIN ? 1 : -1;
                            if (side >= 0) {
                                boolean up = (lParam.getInt(8) & LLKHF_UP) != 0;
                                if (!up) {
                                    if (enabled && locked && focused) {
                                        swallowed[side] = true;
                                        return new LongPtr(1);
                                    }
                                    swallowed[side] = false;
                                } else if (swallowed[side]) {
                                    // only the key-up of a key-down we swallowed: Windows never saw it go down.
                                    // A key-up of a key-down Windows did see always goes through (no stuck Win).
                                    swallowed[side] = false;
                                    return new LongPtr(1);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // pass the key on
                    }
                    return user32.CallNextHookEx(hook[0], code, wParam, lParam);
                }
            };
            hook[0] = user32.SetWindowsHookExW(WH_KEYBOARD_LL, proc, kernel32.GetModuleHandleW(null), 0);
            if (hook[0] == null) {
                failed = true;
                LOG.warn("Windows key lock: the keyboard hook was refused");
                return;
            }
            hookOk = true;
            LOG.info("Windows key lock ready");
            Memory msg = new Memory(64);
            // the hook is called through this thread's message loop
            while (user32.GetMessageW(msg, null, 0, 0) > 0) { }
            hookOk = false;
            user32.UnhookWindowsHookEx(hook[0]);
        }, "FzClient-WinKey");
        thread.setDaemon(true);
        thread.start();
    }
}
