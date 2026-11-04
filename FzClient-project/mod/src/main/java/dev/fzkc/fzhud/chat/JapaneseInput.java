package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.util.Lang;

import dev.fzkc.fzhud.Modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.opengl.Display;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;

/**
 * Minecraft 1.8.9 (LWJGL2) cannot show the Windows IME composition, so Japanese can't be typed
 * directly. This opens a small native text box under the game where the IME works normally,
 * and hands the finished text back to the chat.
 */
public final class JapaneseInput {
    private JapaneseInput() {}

    private static JDialog dialog;
    private static JTextField field;
    private static Field inputField;
    /** Set when we open the chat ourselves, so the box doesn't pop up again right away. */
    public static boolean skipAutoOpen;

    public static boolean isOpen() {
        return dialog != null && dialog.isVisible();
    }

    /** Opens (or focuses) the input box. Safe to call from the game thread. */
    public static void open(final String initial) {
        final int gx = Display.getX(), gy = Display.getY();
        final int gw = Display.getWidth(), gh = Display.getHeight();
        SwingUtilities.invokeLater(() -> {
            if (dialog == null) create();
            field.setText(initial == null ? "" : initial);
            int w = Math.max(360, Math.min(900, (int) (gw * 0.6)));
            dialog.setSize(w, 64);
            dialog.setLocation(gx + 12, gy + gh - 64 - 60);
            dialog.setVisible(true);
            dialog.toFront();
            field.requestFocusInWindow();
            field.setCaretPosition(field.getText().length());
        });
    }

    public static void close() {
        SwingUtilities.invokeLater(() -> {
            if (dialog != null) dialog.setVisible(false);
        });
    }

    private static void create() {
        dialog = new JDialog();
        dialog.setTitle(Lang.t("FzClient 日本語入力"));
        dialog.setAlwaysOnTop(true);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setBackground(new Color(0x0C1215));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x5CE1E6), 2),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel hint = new JLabel(Lang.t("日本語入力  Enter: 送信   Esc: 閉じる"));
        hint.setForeground(new Color(0x8FA5AA));
        hint.setFont(new Font("Dialog", Font.PLAIN, 11));

        field = new JTextField();
        field.setFont(new Font("Dialog", Font.PLAIN, 18));
        field.setBackground(new Color(0x070C0E));
        field.setForeground(Color.WHITE);
        field.setCaretColor(new Color(0x5CE1E6));
        field.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        // Enter (after the IME has committed the text) sends it
        field.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final String text = field.getText();
                dialog.setVisible(false);
                Minecraft.getMinecraft().addScheduledTask(() -> deliver(text));
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) dialog.setVisible(false);
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                // keep it simple: clicking back into the game leaves the box open
            }
        });

        panel.add(hint, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        dialog.setContentPane(panel);
    }

    /** Runs on the game thread. */
    private static void deliver(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (text == null) return;
        text = text.replace("§", "").trim();
        if (text.length() > 100) text = text.substring(0, 100);
        GuiScreen screen = mc.currentScreen;

        if (Modules.imeSendDirect.get()) {
            if (text.isEmpty() || mc.thePlayer == null) return;
            text = ChatExtras.outgoing(text);   // shortcuts + the selected chat tab's channel
            if (screen instanceof GuiChat) {
                screen.sendChatMessage(text); // also handles client-side commands and history
                mc.displayGuiScreen(null);
            } else {
                mc.ingameGUI.getChatGUI().addToSentMessages(text);
                mc.thePlayer.sendChatMessage(text);
            }
            return;
        }
        // otherwise just put the text into the chat box so it can still be edited
        if (!(screen instanceof GuiChat)) {
            skipAutoOpen = true;
            mc.displayGuiScreen(new GuiChat(text));
            return;
        }
        GuiTextField tf = chatField((GuiChat) screen);
        if (tf != null) {
            tf.setText(text);
            tf.setCursorPositionEnd();
        }
    }

    public static String chatText(GuiChat chat) {
        GuiTextField tf = chatField(chat);
        return tf == null ? "" : tf.getText();
    }

    public static GuiTextField chatField(GuiChat chat) {
        try {
            if (inputField == null) {
                for (String n : new String[]{"inputField", "field_146415_a"}) {
                    try {
                        inputField = GuiChat.class.getDeclaredField(n);
                        break;
                    } catch (Exception ignored) {
                    }
                }
                if (inputField == null) {
                    for (Field f : GuiChat.class.getDeclaredFields()) {
                        if (f.getType() == GuiTextField.class) {
                            inputField = f;
                            break;
                        }
                    }
                }
                if (inputField == null) return null;
                inputField.setAccessible(true);
            }
            return (GuiTextField) inputField.get(chat);
        } catch (Exception e) {
            return null;
        }
    }
}
