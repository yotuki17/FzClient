package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;

import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Japanese (IME) typing straight into the Minecraft chat, Windows only.
 *
 * LWJGL2 doesn't understand IME messages, so we subclass the game window's message handler (via JNA,
 * which Minecraft already ships) and talk to the IME ourselves:
 *  - the IME is only switched on while a text screen (chat, sign, anvil, book) is open, so WASD
 *    in-game is never eaten by the IME
 *  - the text being converted is drawn in-game at the caret; the candidate list is placed next to it
 *  - committed text is typed into the open screen as normal characters
 */
public final class NativeIme {
    private NativeIme() {}

    // ---- Win32 bits ------------------------------------------------------------------------------

    /** Pointer-sized integer (WPARAM / LPARAM / LRESULT / LONG_PTR). */
    public static class LongPtr extends IntegerType {
        public LongPtr() { this(0); }
        public LongPtr(long v) { super(Pointer.SIZE, v); }
    }

    public interface WndProc extends StdCallLibrary.StdCallCallback {
        LongPtr callback(Pointer hwnd, int msg, LongPtr wParam, LongPtr lParam);
    }

    interface User32 extends StdCallLibrary {
        LongPtr SetWindowLongPtrW(Pointer hwnd, int index, WndProc proc);
        LongPtr SetWindowLongW(Pointer hwnd, int index, WndProc proc);
        LongPtr SetWindowLongPtrW(Pointer hwnd, int index, LongPtr value);
        LongPtr SetWindowLongW(Pointer hwnd, int index, LongPtr value);
        LongPtr CallWindowProcW(LongPtr prev, Pointer hwnd, int msg, LongPtr wParam, LongPtr lParam);
        LongPtr GetActiveWindow();
    }

    interface Imm32 extends StdCallLibrary {
        Pointer ImmGetContext(Pointer hwnd);
        boolean ImmReleaseContext(Pointer hwnd, Pointer himc);
        Pointer ImmAssociateContext(Pointer hwnd, Pointer himc);
        boolean ImmAssociateContextEx(Pointer hwnd, Pointer himc, int flags);
        int ImmGetCompositionStringW(Pointer himc, int index, Pointer buf, int len);
        boolean ImmSetCandidateWindow(Pointer himc, Pointer form);
        boolean ImmSetOpenStatus(Pointer himc, boolean open);
        boolean ImmNotifyIME(Pointer himc, int action, int index, int value);
    }

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_KEYDOWN = 0x0100, WM_IME_STARTCOMPOSITION = 0x010D,
            WM_IME_ENDCOMPOSITION = 0x010E, WM_IME_COMPOSITION = 0x010F, WM_IME_SETCONTEXT = 0x0281,
            WM_IME_CHAR = 0x0286;
    private static final int VK_PROCESSKEY = 0xE5;
    private static final int GCS_COMPSTR = 0x0008, GCS_CURSORPOS = 0x0080, GCS_RESULTSTR = 0x0800;
    private static final long ISC_SHOWUICOMPOSITIONWINDOW = 0x80000000L;
    private static final int IACE_DEFAULT = 0x0010;
    private static final int CFS_EXCLUDE = 0x0080;
    private static final int NI_COMPOSITIONSTR = 0x0015, CPS_CANCEL = 0x0004;

    // ---- state -----------------------------------------------------------------------------------

    private static boolean failed;
    private static User32 user32;
    private static Imm32 imm32;
    private static Field implField, hwndField;

    private static long hookedHwnd;
    private static Pointer hwnd;
    private static LongPtr prevProc;
    /** Strong reference: if the callback got garbage collected, the game would crash. */
    private static WndProc proc;

    private static boolean active;
    private static GuiScreen activeFor;

    /** Text currently being converted (shown in-game) and the IME's caret inside it. */
    private static String composition = "";
    private static int compCursor;
    /** Committed text waiting to be typed into the screen. */
    private static final StringBuilder pending = new StringBuilder();

    /** Where the candidate list should go, in window pixels (set while drawing). */
    private static int candX, candTop, candBottom, candRight;

    private static Field scrollField;
    private static Method keyTyped;

    /** Checked once: this runs every frame. */
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    public static boolean supported() {
        return !failed && WINDOWS;
    }

    /** True while the in-chat IME is in use (the old pop-up box isn't needed then). */
    public static boolean inChatMode() {
        return Modules.imeEnabled() && Modules.imeMode.get() == 0 && supported();
    }

    private static boolean wantsText(GuiScreen s) {
        return s instanceof GuiChat || s instanceof GuiEditSign || s instanceof GuiRepair || s instanceof GuiScreenBook;
    }

    /** Every frame (render tick start): keep the hook alive, switch the IME on/off, type committed text. */
    public static void frame() {
        if (!supported()) return;
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        boolean want = inChatMode() && wantsText(screen);
        try {
            if (want || active) ensureHook();
            if (hwnd == null) return;
            if (want != active) {
                setEnabled(want, screen);
            } else if (want && screen != activeFor) {
                activeFor = screen;
                onScreenOpened(screen);
            }
            if (pending.length() > 0) {
                String text = pending.toString();
                pending.setLength(0);
                if (active) type(screen, text);
            }
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static void ensureHook() throws Exception {
        if (user32 == null) {
            user32 = (User32) Native.loadLibrary("user32", User32.class);
            imm32 = (Imm32) Native.loadLibrary("imm32", Imm32.class);
        }
        long h = currentHwnd();
        if (h == 0 || h == hookedHwnd) return;
        // the window is re-created when switching fullscreen: hook the new one
        hookedHwnd = h;
        hwnd = new Pointer(h);
        active = false;
        activeFor = null;
        if (proc == null) {
            proc = new WndProc() {
                @Override
                public LongPtr callback(Pointer w, int msg, LongPtr wParam, LongPtr lParam) {
                    return handle(w, msg, wParam, lParam);
                }
            };
        }
        prevProc = Pointer.SIZE == 8
                ? user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, proc)
                : user32.SetWindowLongW(hwnd, GWLP_WNDPROC, proc);
        if (prevProc == null || prevProc.longValue() == 0) throw new IllegalStateException("could not hook the window");
        // LWJGL starts with the IME usable; keep it off in-game until a text screen opens
        imm32.ImmAssociateContext(hwnd, null);
    }

    private static long currentHwnd() {
        try {
            if (implField == null) {
                implField = Display.class.getDeclaredField("display_impl");
                implField.setAccessible(true);
            }
            Object impl = implField.get(null);
            if (impl != null) {
                if (hwndField == null) {
                    hwndField = impl.getClass().getDeclaredField("hwnd");
                    hwndField.setAccessible(true);
                }
                return hwndField.getLong(impl);
            }
        } catch (Throwable ignored) {
        }
        if (Display.isActive()) {
            LongPtr p = user32.GetActiveWindow();
            return p == null ? 0 : p.longValue();
        }
        return 0;
    }

    private static void setEnabled(boolean on, GuiScreen screen) {
        active = on;
        activeFor = on ? screen : null;
        composition = "";
        if (on) {
            imm32.ImmAssociateContextEx(hwnd, null, IACE_DEFAULT);
            onScreenOpened(screen);
        } else {
            Pointer himc = imm32.ImmGetContext(hwnd);
            if (himc != null) {
                imm32.ImmNotifyIME(himc, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
                imm32.ImmReleaseContext(hwnd, himc);
            }
            imm32.ImmAssociateContext(hwnd, null);
            pending.setLength(0);
        }
    }

    /** Opening the chat with "/" switches the IME to direct (half-width) input for commands. */
    private static void onScreenOpened(GuiScreen screen) {
        if (!(screen instanceof GuiChat) || !Modules.imeCommandAscii.get()) return;
        GuiTextField tf = JapaneseInput.chatField((GuiChat) screen);
        if (tf == null || !tf.getText().startsWith("/")) return;
        Pointer himc = imm32.ImmGetContext(hwnd);
        if (himc != null) {
            imm32.ImmSetOpenStatus(himc, false);
            imm32.ImmReleaseContext(hwnd, himc);
        }
    }

    /** Window message handler. Runs on the game thread inside Display.update(). */
    private static LongPtr handle(Pointer w, int msg, LongPtr wParam, LongPtr lParam) {
        try {
            if (active) {
                switch (msg) {
                    case WM_IME_SETCONTEXT:
                        // we draw the text being converted ourselves
                        lParam = new LongPtr(lParam.longValue() & ~ISC_SHOWUICOMPOSITIONWINDOW);
                        break;
                    case WM_IME_STARTCOMPOSITION:
                        composition = "";
                        placeCandidates(w);
                        return new LongPtr(0);
                    case WM_IME_COMPOSITION:
                        composition(w, lParam.longValue());
                        return new LongPtr(0);
                    case WM_IME_ENDCOMPOSITION:
                        composition = "";
                        return new LongPtr(0);
                    case WM_IME_CHAR:
                        return new LongPtr(0); // already taken from GCS_RESULTSTR
                    case WM_KEYDOWN:
                        // keys the IME is using (Enter/Backspace while converting...) must not reach the game
                        if ((wParam.longValue() & 0xFFFF) == VK_PROCESSKEY) return new LongPtr(0);
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }
        return user32.CallWindowProcW(prevProc, w, msg, wParam, lParam);
    }

    private static void composition(Pointer w, long flags) {
        Pointer himc = imm32.ImmGetContext(w);
        if (himc == null) return;
        try {
            if ((flags & GCS_RESULTSTR) != 0) pending.append(read(himc, GCS_RESULTSTR));
            if ((flags & GCS_COMPSTR) != 0) {
                composition = read(himc, GCS_COMPSTR);
                compCursor = Math.max(0, Math.min(composition.length(),
                        imm32.ImmGetCompositionStringW(himc, GCS_CURSORPOS, null, 0)));
            } else {
                composition = "";
            }
            placeCandidates(w, himc);
        } finally {
            imm32.ImmReleaseContext(w, himc);
        }
    }

    private static String read(Pointer himc, int index) {
        int bytes = imm32.ImmGetCompositionStringW(himc, index, null, 0);
        if (bytes <= 0) return "";
        Memory buf = new Memory(bytes + 2);
        imm32.ImmGetCompositionStringW(himc, index, buf, bytes);
        return new String(buf.getCharArray(0, bytes / 2));
    }

    private static void placeCandidates(Pointer w) {
        Pointer himc = imm32.ImmGetContext(w);
        if (himc == null) return;
        placeCandidates(w, himc);
        imm32.ImmReleaseContext(w, himc);
    }

    private static void placeCandidates(Pointer w, Pointer himc) {
        if (candBottom <= 0) return;
        Memory form = new Memory(32); // CANDIDATEFORM
        form.setInt(0, 0);            // dwIndex
        form.setInt(4, CFS_EXCLUDE);  // dwStyle
        form.setInt(8, candX);        // ptCurrentPos
        form.setInt(12, candTop);
        form.setInt(16, candX);       // rcArea: keep the list off the text we're drawing
        form.setInt(20, candTop);
        form.setInt(24, candRight);
        form.setInt(28, candBottom);
        imm32.ImmSetCandidateWindow(himc, form);
    }

    /** Types committed text into the screen like normal key presses. */
    private static void type(GuiScreen screen, String text) {
        text = text.replace("§", "");
        if (text.isEmpty() || screen == null) return;
        if (screen instanceof GuiChat) {
            GuiTextField tf = JapaneseInput.chatField((GuiChat) screen);
            if (tf != null) {
                tf.writeText(text);
                return;
            }
        }
        try {
            if (keyTyped == null) {
                for (String n : new String[]{"keyTyped", "func_73869_a"}) {
                    try {
                        keyTyped = GuiScreen.class.getDeclaredMethod(n, char.class, int.class);
                        keyTyped.setAccessible(true);
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (keyTyped == null) return;
            for (int i = 0; i < text.length(); i++) keyTyped.invoke(screen, text.charAt(i), 0);
        } catch (Throwable ignored) {
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------

    /** Draws the text being converted at the caret (call after the screen has drawn). */
    public static void draw(GuiScreen screen) {
        if (!active || screen == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRendererObj;
        float scale = mc.displayWidth / (float) Math.max(1, screen.width);

        float x, y;
        GuiTextField tf = screen instanceof GuiChat ? JapaneseInput.chatField((GuiChat) screen) : null;
        if (tf != null) {
            String t = tf.getText();
            int scroll = Math.max(0, Math.min(scroll(tf), t.length()));
            int cur = Math.max(scroll, Math.min(tf.getCursorPosition(), t.length()));
            x = tf.xPosition + fr.getStringWidth(t.substring(scroll, cur));
            y = tf.yPosition;
        } else {
            x = screen.width / 2f - fr.getStringWidth(composition) / 2f;
            y = screen.height - 40;
        }

        if (composition.isEmpty()) {
            // still tell the IME where the caret is, for the first candidate list
            setCandidateRect(x, y, x + 2, y + 10, scale);
            return;
        }

        int w = fr.getStringWidth(composition);
        x = Math.max(2, Math.min(x, screen.width - w - 4));
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, 300);
        R.rect(x - 2, y - 2, w + 4, 12, 0xF0101820);
        fr.drawStringWithShadow(composition, x, y, 0xFFFFFF);
        R.rect(x, y + 9, w, 1, 0xFF5CE1E6);
        // IME caret
        float cx = x + fr.getStringWidth(composition.substring(0, Math.min(compCursor, composition.length())));
        if ((System.currentTimeMillis() / 500L) % 2L == 0L) R.rect(cx, y - 1, 1, 10, 0xFFFFFFFF);
        GlStateManager.popMatrix();

        setCandidateRect(x - 2, y - 2, x + w + 2, y + 10, scale);
    }

    private static void setCandidateRect(float x1, float y1, float x2, float y2, float scale) {
        candX = (int) (x1 * scale);
        candTop = (int) (y1 * scale);
        candRight = (int) (x2 * scale);
        candBottom = (int) (y2 * scale);
    }

    private static int scroll(GuiTextField tf) {
        try {
            if (scrollField == null) {
                for (String n : new String[]{"lineScrollOffset", "field_146225_q"}) {
                    try {
                        scrollField = GuiTextField.class.getDeclaredField(n);
                        scrollField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            return scrollField == null ? 0 : scrollField.getInt(tf);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void disable(Throwable t) {
        failed = true;
        active = false;
        System.err.println("[FzClient] in-chat Japanese input disabled, falling back to the input box: " + t);
        try {
            if (hwnd != null) imm32.ImmAssociateContextEx(hwnd, null, IACE_DEFAULT);
        } catch (Throwable ignored) {
        }
    }
}
