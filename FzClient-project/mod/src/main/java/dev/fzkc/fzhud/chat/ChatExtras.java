package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.TextSetting;
import dev.fzkc.fzhud.gui.UiSound;
import dev.fzkc.fzhud.gui.Ui;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The parts of the FzClient chat that live around the chat box:
 * <ul>
 *   <li>installs {@link FzChat} on the first tick;</li>
 *   <li>the tab row above the chat input (click to switch, a dot marks tabs with new messages);</li>
 *   <li>shortcuts: when a sent message is exactly one of your short words, the long text is sent instead.</li>
 * </ul>
 */
public final class ChatExtras {
    private static final int TAB_H = 11;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) FzChat.install();
    }

    static boolean tabsOn() {
        return FzChat.active() && Modules.chatTabs.get();
    }

    private static float[] tabRect(GuiChat gui, int i) {
        Minecraft mc = Minecraft.getMinecraft();
        float x = 2;
        for (int j = 0; j < i; j++) x += mc.fontRendererObj.getStringWidth(Lang.t(FzChat.TABS[j])) + 12 + 2;
        float w = mc.fontRendererObj.getStringWidth(Lang.t(FzChat.TABS[i])) + 12;
        return new float[]{x, gui.height - 14 - TAB_H - 1, w, TAB_H};
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.gui instanceof GuiChat) || !tabsOn()) return;
        GuiChat gui = (GuiChat) event.gui;
        Minecraft mc = Minecraft.getMinecraft();
        Ui.refresh();

        // the input leaves room for the channel command, and says where the message goes
        GuiTextField tf = JapaneseInput.chatField(gui);
        String ch = channel();
        if (tf != null) {
            boolean command = tf.getText().startsWith("/");
            tf.setMaxStringLength(ch == null || command ? 100 : 100 - ch.length());
            if (ch != null && !command) {
                String hint = "→ " + ch.trim();
                int w = mc.fontRendererObj.getStringWidth(hint);
                mc.fontRendererObj.drawStringWithShadow(hint, gui.width - 6 - w, gui.height - 12, Ui.accent);
            }
        }
        for (int i = 0; i < FzChat.TABS.length; i++) {
            float[] r = tabRect(gui, i);
            boolean sel = i == FzChat.tab;
            boolean over = event.mouseX >= r[0] && event.mouseX < r[0] + r[2] && event.mouseY >= r[1] && event.mouseY < r[1] + r[3];
            int bg = sel ? Ui.accent : over ? 0xA0404850 : 0x90000000;
            R.rounded(r[0], r[1], r[2], r[3], 3f, bg);
            String label = Lang.t(FzChat.TABS[i]);
            int color = sel ? 0xFF0A1114 : 0xFFE6EEF0;
            mc.fontRendererObj.drawString(label, (int) (r[0] + 6), (int) (r[1] + 2), color);
            if (FzChat.unread[i] && !sel) R.rounded(r[0] + r[2] - 4, r[1] - 1, 4, 4, 2f, 0xFFFF5A5A);
        }
        net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @SubscribeEvent
    public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!(event.gui instanceof GuiChat) || !tabsOn()) return;
        if (Mouse.getEventButton() != 0 || !Mouse.getEventButtonState()) return;
        GuiChat gui = (GuiChat) event.gui;
        Minecraft mc = Minecraft.getMinecraft();
        int mx = Mouse.getEventX() * gui.width / mc.displayWidth;
        int my = gui.height - Mouse.getEventY() * gui.height / mc.displayHeight - 1;
        for (int i = 0; i < FzChat.TABS.length; i++) {
            float[] r = tabRect(gui, i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (FzChat.tab != i) {
                    FzChat.tab = i;
                    mc.ingameGUI.getChatGUI().resetScroll();
                    UiSound.click();
                }
                event.setCanceled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ shortcuts

    /**
     * Runs before the chat sends (and before the Japanese input code), so the changed text is what goes out:
     * shortcuts first, then the tab's channel ("/pc ", "/gc ", "/r ").
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!(event.gui instanceof GuiChat) || !Modules.chat.enabled) return;
        if (!Keyboard.getEventKeyState()) return;
        int key = Keyboard.getEventKey();
        if (key != Keyboard.KEY_RETURN && key != Keyboard.KEY_NUMPADENTER) return;
        GuiTextField tf = JapaneseInput.chatField((GuiChat) event.gui);
        if (tf == null) return;
        String out = outgoing(tf.getText());
        if (!out.equals(tf.getText())) {
            tf.setMaxStringLength(100);
            tf.setText(out);
        }
    }

    // ------------------------------------------------------------------ tab = channel

    /** Command that sends to the selected tab's channel, or null for "すべて". */
    public static String channel() {
        if (!tabsOn() || !Modules.chatTabSend.get()) return null;
        switch (FzChat.tab) {
            case FzChat.PARTY: return "/pc ";
            case FzChat.GUILD: return "/gc ";
            case FzChat.PM: return "/r ";
            default: return null;
        }
    }

    /**
     * What is really sent for typed text: a shortcut is expanded, then plain text (not a command) goes to the
     * selected tab's channel. Used by the chat box and by the separate Japanese input window.
     */
    public static String outgoing(String typed) {
        if (typed == null) return "";
        String text = typed;
        if (Modules.chat.enabled && Modules.chatShortcutsOn.get()) {
            String e = expand(text);
            if (e != null) text = e;
        }
        String ch = channel();
        if (ch != null && !text.trim().isEmpty() && !text.startsWith("/")) {
            text = ch + text.trim();
            if (text.length() > 100) text = text.substring(0, 100);   // 1.8.9 servers take 100 characters
        }
        return text;
    }

    /** The long text for a message that is exactly one of the short words (ignoring case), else null. */
    public static String expand(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty()) return null;
        for (TextSetting s : Modules.chatShortcuts) {
            String v = s.get();
            int eq = v.indexOf('=');
            if (eq <= 0) continue;
            String from = v.substring(0, eq).trim(), to = v.substring(eq + 1).trim();
            if (!from.isEmpty() && !to.isEmpty() && from.equalsIgnoreCase(t)) return to.length() > 100 ? to.substring(0, 100) : to;   // 1.8.9 servers take 100 characters
        }
        return null;
    }
}
