package dev.fzkc.fzhud.gui;

import com.google.gson.JsonObject;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Presets;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Presets: copy the whole setup as a code, load a code from the clipboard, and keep saved setups to switch back.
 * Loading always saves the current setup first ("読み込み前"), so nothing is lost by mistake.
 */
public class PresetScreen extends FzScreen {
    private static final float ROW = 26;

    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private final Anim scroll = new Anim(0f, 16f);
    private float scrollTarget;
    private final Map<Object, Anim> anims = new HashMap<>();

    private String message = "";
    private boolean messageError;
    private long messageAt;
    /** Name being typed: for a new preset (naming) or for renaming a saved one (renaming). */
    private boolean naming;
    private Presets.Saved renaming;
    private String nameBuf = "";
    private static final int NAME_MAX = 32;

    /** Two-step confirm for anything that overwrites or deletes. */
    private Object pending;
    private long pendingAt;

    public PresetScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private float W() { return Ui.menuW(vw, 480); }
    private float H() { return Ui.menuH(vh, 320); }
    private float px() { return Ui.menuX(vw, W()); }
    private float py() { return (vh - H()) / 2f; }
    private float btnY() { return py() + 46; }
    private float bw() { return (W() - 24 - 12) / 3f; }
    private float bx(int i) { return px() + 12 + i * (bw() + 6); }
    private float listY() { return py() + 98; }
    private float listH() { return H() - 98 - 34; }

    private Anim anim(Object key) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0f, 14f);
            anims.put(key, a);
        }
        return a;
    }

    private float hover(Object key, int mx, int my, float x, float y, float w, float h) {
        return anim(key).update(Ui.inside(mx, my, x, y, w, h) ? 1 : 0);
    }

    private void say(String text, boolean error) {
        message = text;
        messageError = error;
        messageAt = System.currentTimeMillis();
    }

    /**
     * True if the same thing was pressed in the last 3 seconds.
     * Uses equals(), not ==: the delete key is a new String built on every click, so == never matched
     * and the delete button could never get past its first press.
     */
    private boolean confirming(Object what) {
        return pending != null && pending.equals(what)
                && System.currentTimeMillis() - pendingAt < 3000L;
    }

    /** Key used for the two-step delete of one saved preset. */
    private static String deleteKey(Presets.Saved s) {
        return "d" + s.name + s.code.hashCode();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);

        R.textScaled("プリセット", px() + 14, py() + 12, 1.5f, R.alpha(Ui.text, o));
        R.text(Ui.trim("設定とHUDの位置を1つのコードに。共有やバックアップに使えます", W() - 28),
                px() + 14, py() + 30, R.alpha(Ui.MUTED, o));

        Ui.primaryButton(bx(0), btnY(), bw(), 22, "コードをコピー", hover("copy", mx, my, bx(0), btnY(), bw(), 22), o);
        Ui.button(bx(1), btnY(), bw(), 22, confirming("paste") ? "もう一度押すと上書き" : "貼り付けて読み込む",
                hover("paste", mx, my, bx(1), btnY(), bw(), 22), o);
        Ui.button(bx(2), btnY(), bw(), 22, "今の設定を保存", hover("save", mx, my, bx(2), btnY(), bw(), 22), o);

        // name box for a new preset (Enter = save, Esc = cancel)
        if (naming) {
            float tw = W() - 28 - 64;
            Ui.textBox(px() + 14, btnY() + 27, tw, 18, nameBuf, "プリセットの名前", true, o);
            Ui.primaryButton(px() + 14 + tw + 6, btnY() + 27, 58, 18, "保存",
                    hover("namesave", mx, my, px() + 14 + tw + 6, btnY() + 27, 58, 18), o);
        }

        // status line
        long age = System.currentTimeMillis() - messageAt;
        if (!naming && !message.isEmpty() && age < 5000L) {
            float a = o * Math.min(1f, (5000L - age) / 600f);
            R.text(Ui.trim(message, W() - 28), px() + 14, btnY() + 30, R.alpha(messageError ? 0xFFFF7A7A : Ui.accent, a));
        }

        // saved presets
        R.text("保存したプリセット（名前をクリックで変更）", px() + 14, listY() - 12, R.alpha(Ui.MUTED, o));
        List<Presets.Saved> list = Presets.saved();
        float content = list.size() * (ROW + 4);
        float maxScroll = Math.max(0, content - listH());
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        float sc = scroll.update(scrollTarget);
        Ui.scissor(px() + 8, listY(), W() - 16, listH());
        if (list.isEmpty()) {
            R.textCentered("まだありません。「今の設定を保存」で追加できます", px() + W() / 2f, listY() + 14, R.alpha(Ui.MUTED, o));
        }
        for (int i = 0; i < list.size(); i++) {
            Presets.Saved s = list.get(i);
            float y = listY() + i * (ROW + 4) - sc;
            if (y + ROW < listY() || y > listY() + listH()) continue;
            float x = px() + 12, w = W() - 24;
            Ui.card(x, y, w, ROW, 0f, -1f, o);
            if (renaming == s) {
                Ui.textBox(x + 4, y + 4, w - 182, 18, nameBuf, "名前", true, o);
            } else {
                boolean overName = Ui.inside(mx, my, x, y, w - 178, ROW) && my >= listY() && my < listY() + listH();
                R.text(Ui.trim(s.name, w - 190), x + 8, y + 9, R.alpha(overName ? Ui.text : Ui.textA(0xE0), o));
                if (overName) R.text("✎", x + 12 + Math.min(R.width(s.name), w - 190), y + 9, R.alpha(Ui.MUTED, o));
            }
            float b3 = x + w - 44, b2 = b3 - 50, b1 = b2 - 76;
            Ui.button(b1, y + 4, 72, 18, confirming(s) ? "もう一度押す" : "読み込む", hover("l" + i, mx, my, b1, y + 4, 72, 18), o);
            Ui.button(b2, y + 4, 46, 18, "コピー", hover("c" + i, mx, my, b2, y + 4, 46, 18), o);
            Ui.button(b3, y + 4, 40, 18, confirming(deleteKey(s)) ? "本当?" : "削除",
                    hover("d" + i, mx, my, b3, y + 4, 40, 18), o);
        }
        Ui.endScissor();

        Ui.button(px() + W() / 2f - 40, py() + H() - 28, 80, 20, "閉じる",
                hover("close", mx, my, px() + W() / 2f - 40, py() + H() - 28, 80, 20), o);

        // decoration (bottom right) - drawn every frame
        Decoration.corner(vw, vh, o);
    }

    // ---------------------------------------------------------------- input

    private void load(JsonObject data, Object confirmKey, String doneText) {
        if (!confirming(confirmKey)) {
            pending = confirmKey;
            pendingAt = System.currentTimeMillis();
            say("今の設定は上書きされます（自動でバックアップを取ります）。もう一度押すと読み込みます", false);
            return;
        }
        pending = null;
        Presets.saveCurrent(dev.fzkc.fzhud.util.Lang.t("読み込み前"));
        Presets.apply(data);
        say(doneText, false);
    }

    private void commitName() {
        if (naming) {
            Presets.Saved s = Presets.saveAs(nameBuf);
            scrollTarget = 0;
            say(dev.fzkc.fzhud.util.Lang.f("「{0}」として保存しました", s.name), false);
        } else if (renaming != null) {
            Presets.rename(renaming, nameBuf);
        }
        naming = false;
        renaming = null;
    }

    private void cancelName() {
        naming = false;
        renaming = null;
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        if (naming) {
            float tw = W() - 28 - 64;
            if (Ui.inside(mx, my, px() + 14 + tw + 6, btnY() + 27, 58, 18)) { commitName(); return; }
            if (Ui.within(mx, my, px() + 14, btnY() + 27, tw, 18)) return; // clicking the box keeps typing
            cancelName();
        } else if (renaming != null) {
            commitName(); // clicking anywhere else finishes the rename
        }
        if (Ui.inside(mx, my, bx(0), btnY(), bw(), 22)) {
            copyShort(Presets.export(), null);
            return;
        }
        if (Ui.inside(mx, my, bx(1), btnY(), bw(), 22)) {
            String clip = GuiScreen.getClipboardString();
            String id = Presets.shortId(clip);
            if (id != null) {
                pasteShort(id);
                return;
            }
            JsonObject data = Presets.parse(clip);
            if (data == null) {
                pending = null;
                say("クリップボードに FzClient のコード（6文字か、FZ で始まる文字）がありません", true);
                return;
            }
            load(data, "paste", "コードを読み込みました");
            return;
        }
        if (Ui.inside(mx, my, bx(2), btnY(), bw(), 22)) {
            naming = true;
            nameBuf = "";
            return;
        }
        if (Ui.inside(mx, my, px() + W() / 2f - 40, py() + H() - 28, 80, 20)) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (my < listY() || my >= listY() + listH()) return;
        List<Presets.Saved> list = Presets.saved();
        for (int i = 0; i < list.size(); i++) {
            Presets.Saved s = list.get(i);
            float y = listY() + i * (ROW + 4) - scroll.value;
            float x = px() + 12, w = W() - 24;
            float b3 = x + w - 44, b2 = b3 - 50, b1 = b2 - 76;
            if (Ui.inside(mx, my, x, y, w - 178, ROW)) {
                renaming = s;
                nameBuf = s.name;
                return;
            }
            if (Ui.inside(mx, my, b1, y + 4, 72, 18)) {
                JsonObject data = Presets.parse(s.code);
                if (data == null) say("このプリセットは壊れているようです", true);
                else load(data, s, dev.fzkc.fzhud.util.Lang.f("「{0}」を読み込みました", s.name));
                return;
            }
            if (Ui.inside(mx, my, b2, y + 4, 46, 18)) {
                copyShort(s.code, s.name);
                return;
            }
            if (Ui.inside(mx, my, b3, y + 4, 40, 18)) {
                String key = deleteKey(s);
                if (!confirming(key)) {
                    pending = key;
                    pendingAt = System.currentTimeMillis();
                    say(dev.fzkc.fzhud.util.Lang.f("もう一度「本当?」を押すと「{0}」を削除します", s.name), false);
                } else {
                    pending = null;
                    Presets.delete(s);
                    say("削除しました", false);
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- short codes

    private volatile boolean busy;

    /** Copies a code as its 6 character id (made on the FzClient server); the long code when that fails. */
    private void copyShort(final String code, final String name) {
        if (busy) return;
        busy = true;
        say("短いコードを作っています…", false);
        Thread t = new Thread(() -> {
            String id = null;
            try {
                id = Presets.shorten(code);
            } catch (Exception ignored) {
            }
            final String got = id;
            mc.addScheduledTask(() -> {
                busy = false;
                if (got != null) {
                    GuiScreen.setClipboardString(got);
                    say(name == null ? dev.fzkc.fzhud.util.Lang.f("コピーしました: {0}（Discord などに貼れば共有できます）", got)
                            : dev.fzkc.fzhud.util.Lang.f("「{0}」のコードをコピーしました: {1}", name, got), false);
                } else {
                    GuiScreen.setClipboardString(code);
                    say(dev.fzkc.fzhud.util.Lang.f("サーバーにつながらないので長いコードをコピーしました（{0} 文字）", code.length()), true);
                }
            });
        }, "FzClient-Preset");
        t.setDaemon(true);
        t.start();
    }

    /** Fetches the long code for a 6 character id, then loads it like a pasted code. */
    private void pasteShort(final String id) {
        if (busy) return;
        busy = true;
        say("コードを取りに行っています…", false);
        Thread t = new Thread(() -> {
            String code = null;
            boolean failed = false;
            try {
                code = Presets.expand(id);
            } catch (Exception e) {
                failed = true;
            }
            final String got = code;
            final boolean err = failed;
            mc.addScheduledTask(() -> {
                busy = false;
                if (err) {
                    say("サーバーにつながりませんでした", true);
                    return;
                }
                JsonObject data = got == null ? null : Presets.parse(got);
                if (data == null) {
                    pending = null;
                    say(dev.fzkc.fzhud.util.Lang.f("コード {0} は見つかりませんでした", id), true);
                    return;
                }
                load(data, "paste", dev.fzkc.fzhud.util.Lang.f("コード {0} を読み込みました", id));
            });
        }, "FzClient-Preset");
        t.setDaemon(true);
        t.start();
    }

    private void typeText(String t) {
        if (t == null) return;
        for (char ch : t.toCharArray()) {
            if (nameBuf.length() >= NAME_MAX) break;
            if (net.minecraft.util.ChatAllowedCharacters.isAllowedCharacter(ch)) nameBuf += ch;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    protected void scroll(int mx, int my, int amount) {
        scrollTarget += amount * 30;
    }

    @Override
    protected void key(char c, int keyCode) {
        if (naming || renaming != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) cancelName();
            else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) commitName();
            else if (keyCode == Keyboard.KEY_BACK) {
                if (!nameBuf.isEmpty()) nameBuf = nameBuf.substring(0, nameBuf.length() - 1);
            } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
                typeText(GuiScreen.getClipboardString());
            } else if (net.minecraft.util.ChatAllowedCharacters.isAllowedCharacter(c)) {
                typeText(String.valueOf(c));
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
            click((int) (bx(1) + 2), (int) (btnY() + 2), 0); // Ctrl+V = paste & load
        } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_C) {
            click((int) (bx(0) + 2), (int) (btnY() + 2), 0); // Ctrl+C = copy code
        }
    }


}
