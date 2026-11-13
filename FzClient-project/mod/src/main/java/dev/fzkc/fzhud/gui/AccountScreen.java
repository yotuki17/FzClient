package dev.fzkc.fzhud.gui;

import com.mojang.authlib.GameProfile;
import dev.fzkc.fzhud.account.Accounts;
import dev.fzkc.fzhud.account.MsAuth;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account switcher (opened from the face in the title screen's corner): the launcher's account, saved Microsoft
 * accounts and offline names. Clicking a row makes it the game's account; servers joined afterwards see it.
 */
public class AccountScreen extends FzScreen {
    private static final float ROW = 30;
    private static final int NAME_MAX = 16;

    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private final Anim scroll = new Anim(0f, 16f);
    private float scrollTarget;
    private final Map<Object, Anim> anims = new HashMap<Object, Anim>();

    private volatile String message = "";
    private volatile boolean messageError;
    private volatile long messageAt;
    /** Something is running in the background (switching / signing in): rows ignore clicks meanwhile. */
    private volatile boolean busy;

    /** Microsoft sign-in in progress: the code to type at the link. */
    private volatile MsAuth.DeviceCode code;
    private volatile boolean signInCancelled;

    private boolean typing;
    private String nameBuf = "";

    public AccountScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        signInCancelled = true;
    }

    private float W() { return Ui.menuW(vw, 380); }
    private float H() { return Ui.menuH(vh, 310); }
    private float px() { return Ui.menuX(vw, W()); }
    private float py() { return (vh - H()) / 2f; }
    private float listY() { return py() + 48; }
    private float listH() { return H() - 48 - 84; }
    private float bottomY() { return py() + H() - 78; }

    private float hover(Object key, int mx, int my, float x, float y, float w, float h) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0, 14f);
            anims.put(key, a);
        }
        return a.update(Ui.inside(mx, my, x, y, w, h) ? 1 : 0);
    }

    // ------------------------------------------------------------------ rows

    private static final class Row {
        final String name, uuid, kind;
        final Accounts.Account saved;     // null = the launcher's account
        final Session session;           // launcher's account only

        Row(String name, String uuid, String kind, Accounts.Account saved, Session session) {
            this.name = name;
            this.uuid = uuid.replace("-", "");
            this.kind = kind;
            this.saved = saved;
            this.session = session;
        }
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<Row>();
        Session launch = Accounts.launchSession();
        if (launch != null) {
            String kind = "0".equals(launch.getToken()) || "legacy".equalsIgnoreCase(String.valueOf(launch.getSessionType()))
                    ? "ランチャー（オフライン）" : "ランチャー";
            out.add(new Row(launch.getUsername(), launch.getPlayerID(), kind, null, launch));
        }
        for (Accounts.Account a : Accounts.saved()) {
            if (launch != null && a.uuid.equalsIgnoreCase(launch.getPlayerID().replace("-", ""))) continue;
            out.add(new Row(a.name, a.uuid, a.microsoft() ? "Microsoft" : "オフライン", a, null));
        }
        return out;
    }

    private boolean current(Row r) {
        Session s = mc.getSession();
        return s != null && s.getPlayerID() != null && s.getPlayerID().replace("-", "").equalsIgnoreCase(r.uuid);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);

        R.textScaled("アカウント", px() + 14, py() + 12, 1.5f, R.alpha(Ui.text, o));
        R.text(Ui.trim("クリックで切り替え（次に入るサーバーから反映）", W() - 28), px() + 14, py() + 30, R.alpha(Ui.MUTED, o));

        if (code != null) {
            renderSignIn(mx, my, o);
        } else {
            renderList(mx, my, o);
            renderBottom(mx, my, o);
        }

        long age = System.currentTimeMillis() - messageAt;
        if (!message.isEmpty() && (busy || age < 6000L)) {
            float a = busy ? o : o * Math.min(1f, (6000L - age) / 600f);
            R.textCentered(message, px() + W() / 2f, py() + H() - 46, R.alpha(messageError ? 0xFFFF7A7A : Ui.accent, a));
        }

        float cx = px() + W() / 2f - 40, cy = py() + H() - 28;
        Ui.button(cx, cy, 80, 20, "閉じる", hover("close", mx, my, cx, cy, 80, 20), o);
    }

    private void renderList(int mx, int my, float o) {
        List<Row> list = rows();
        float content = list.size() * (ROW + 4);
        float maxScroll = Math.max(0, content - listH());
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        float sc = scroll.update(scrollTarget);
        Ui.scissor(px() + 8, listY(), W() - 16, listH());
        for (int i = 0; i < list.size(); i++) {
            Row r = list.get(i);
            float x = px() + 12, w = W() - 24, y = listY() + i * (ROW + 4) - sc;
            if (y + ROW < listY() || y > listY() + listH()) continue;
            boolean cur = current(r);
            boolean inList = my >= listY() && my < listY() + listH();
            float h = anims(r.uuid).update(inList && Ui.inside(mx, my, x, y, w - (r.saved != null ? 30 : 0), ROW) && !busy ? 1 : 0);
            Ui.card(x, y, w, ROW, h, cur ? 1f : 0f, o);
            if (cur) R.rounded(x + 1, y + 7, 2.5f, ROW - 14, 1.2f, R.alpha(Ui.accent, o));
            face(r.uuid, r.name, x + 8, y + 7, 16, o);
            R.text(r.name, x + 30, y + 6, R.alpha(Ui.text, o));
            R.text(r.kind, x + 30, y + 17, R.alpha(Ui.MUTED, o));
            float right = x + w - 8;
            if (r.saved != null) {
                float bx = x + w - 26, by = y + 7;
                Ui.button(bx, by, 18, 16, "×", inList ? hover("del" + r.uuid, mx, my, bx, by, 18, 16) : 0f, o);
                right = bx - 6;
            }
            if (cur) {
                String tag = Lang.t("使用中");
                R.text(tag, right - R.width(tag), y + 11, R.alpha(Ui.accent, o));
            }
        }
        Ui.endScissor();
    }

    private Anim anims(Object key) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0, 14f);
            anims.put(key, a);
        }
        return a;
    }

    private void renderBottom(int mx, int my, float o) {
        float y = bottomY(), x = px() + 14, w = W() - 28;
        float msW = (w - 6) / 2f;
        Ui.primaryButton(x, y, msW, 20, "Microsoft でログイン", hover("ms", mx, my, x, y, msW, 20), o);
        float ox = x + msW + 6, addW = 40, boxW = msW - addW - 4;
        if (typing) {
            Ui.textBox(ox, y, boxW, 20, nameBuf, "オフラインの名前", true, o);
            Ui.button(ox + boxW + 4, y, addW, 20, "追加", hover("add", mx, my, ox + boxW + 4, y, addW, 20), o);
        } else {
            Ui.button(ox, y, msW, 20, "オフラインを追加", hover("off", mx, my, ox, y, msW, 20), o);
        }
    }

    private void renderSignIn(int mx, int my, float o) {
        MsAuth.DeviceCode dc = code;
        if (dc == null) return;
        float cx = px() + W() / 2f, y = listY() + 8;
        R.textCentered(Lang.f("ブラウザで {0} を開いて、このコードを入力してください", dc.url), cx, y, R.alpha(Ui.text, o));
        R.textScaledCentered(dc.userCode, cx, y + 22, 2.4f, R.alpha(Ui.accent, o));
        R.textCentered("コードはコピー済みです。入力が終わると自動で切り替わります", cx, y + 52, R.alpha(Ui.MUTED, o));
        float bw = 110, bx1 = cx - bw - 4, bx2 = cx + 4, by = y + 72;
        Ui.button(bx1, by, bw, 20, "ブラウザを開く", hover("browser", mx, my, bx1, by, bw, 20), o);
        Ui.button(bx2, by, bw, 20, "キャンセル", hover("cancel", mx, my, bx2, by, bw, 20), o);
    }

    // ------------------------------------------------------------------ faces

    private static final Map<String, ResourceLocation> SKINS = new HashMap<String, ResourceLocation>();
    private static final java.util.Set<String> REQUESTED = new java.util.HashSet<String>();

    private void face(String uuid, String name, float x, float y, float size, float alpha) {
        UUID id = null;
        try {
            id = UUID.fromString(uuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (Exception ignored) {
        }
        if (id != null && REQUESTED.add(uuid)) {
            final String key = uuid;
            try {
                mc.getSkinManager().loadProfileTextures(new GameProfile(id, name), (type, loc, tex) -> {
                    if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) SKINS.put(key, loc);
                }, false);
            } catch (Throwable ignored) {
            }
        }
        ResourceLocation tex = SKINS.get(uuid);
        if (tex == null) tex = id != null ? DefaultPlayerSkin.getDefaultSkin(id) : DefaultPlayerSkin.getDefaultSkinLegacy();
        R.rect(x - 1, y - 1, size + 2, size + 2, R.alpha(0x60000000, alpha));
        mc.getTextureManager().bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, alpha);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(size / 8f, size / 8f, 1f);
        Gui.drawScaledCustomSizeModalRect(0, 0, 8, 8, 8, 8, 8, 8, 64, 64);
        Gui.drawScaledCustomSizeModalRect(0, 0, 40, 8, 8, 8, 8, 8, 64, 64);
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ actions

    private void say(String text, boolean error) {
        message = text;
        messageError = error;
        messageAt = System.currentTimeMillis();
    }

    private boolean canSwitch() {
        if (mc.theWorld != null) {
            say("ワールドから出てから切り替えてください", true);
            return false;
        }
        return !busy;
    }

    private void switchTo(Row r) {
        if (!canSwitch() || current(r)) return;
        if (r.session != null) {
            Accounts.apply(r.session);
            say(Lang.f("{0} に切り替えました", r.name), false);
            return;
        }
        busy = true;
        say("切り替え中…", false);
        final Accounts.Account a = r.saved;
        Thread t = new Thread(() -> {
            try {
                final Session s = Accounts.sessionFor(a);
                mc.addScheduledTask(() -> {
                    Accounts.apply(s);
                    busy = false;
                    say(Lang.f("{0} に切り替えました", s.getUsername()), false);
                });
            } catch (Exception e) {
                busy = false;
                say(errorText(e), true);
            }
        }, "FzClient-Account");
        t.setDaemon(true);
        t.start();
    }

    private void startMicrosoft() {
        if (!canSwitch()) return;
        busy = true;
        signInCancelled = false;
        say("Microsoft に接続中…", false);
        Thread t = new Thread(() -> {
            try {
                final MsAuth.DeviceCode dc = MsAuth.startDeviceCode();
                mc.addScheduledTask(() -> {
                    code = dc;
                    say("", false);
                    GuiScreen.setClipboardString(dc.userCode);
                    openBrowser(dc.url);
                });
                MsAuth.Result r = MsAuth.pollDeviceCode(dc, () -> signInCancelled);
                mc.addScheduledTask(() -> {
                    Session s = Accounts.addMicrosoft(r);
                    code = null;
                    busy = false;
                    if (mc.theWorld == null) {
                        Accounts.apply(s);
                        say(Lang.f("{0} でログインしました", r.name), false);
                    } else {
                        say(Lang.f("{0} を追加しました", r.name), false);
                    }
                });
            } catch (Exception e) {
                mc.addScheduledTask(() -> {
                    code = null;
                    busy = false;
                    if (!signInCancelled) say(errorText(e), true);
                });
            }
        }, "FzClient-MicrosoftLogin");
        t.setDaemon(true);
        t.start();
    }

    /** Back to the list right away; the background wait notices within a few seconds. */
    private void cancelSignIn() {
        signInCancelled = true;
        code = null;
        say("キャンセルしました", false);
    }

    private static String errorText(Exception e) {
        if (e instanceof MsAuth.AuthError) return Lang.t(e.getMessage());
        return Lang.t("接続できませんでした。ネットにつながっているか確認してください");
    }

    private static void openBrowser(String url) {
        dev.fzkc.fzhud.util.Os.openUrl(url);
    }

    private void addOffline() {
        String n = nameBuf.trim();
        if (!n.matches("[A-Za-z0-9_]{3,16}")) {
            say("名前は英数字と _ の3〜16文字にしてください", true);
            return;
        }
        Session s = Accounts.addOffline(n);
        typing = false;
        nameBuf = "";
        if (mc.theWorld == null) {
            Accounts.apply(s);
            say(Lang.f("{0} に切り替えました（オフライン）", n), false);
        } else {
            say(Lang.f("{0} を追加しました", n), false);
        }
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        float cx = px() + W() / 2f - 40, cy = py() + H() - 28;
        if (Ui.inside(mx, my, cx, cy, 80, 20)) {
            UiSound.cancel();
            mc.displayGuiScreen(parent);
            return;
        }
        if (code != null) {
            float bcx = px() + W() / 2f, y = listY() + 8, bw = 110, by = y + 72;
            if (Ui.inside(mx, my, bcx - bw - 4, by, bw, 20)) openBrowser(code.url);
            else if (Ui.inside(mx, my, bcx + 4, by, bw, 20)) {
                cancelSignIn();
                UiSound.cancel();
            }
            return;
        }
        // bottom buttons
        float y = bottomY(), x = px() + 14, w = W() - 28, msW = (w - 6) / 2f;
        float ox = x + msW + 6, addW = 40, boxW = msW - addW - 4;
        if (Ui.inside(mx, my, x, y, msW, 20)) {
            startMicrosoft();
            return;
        }
        if (typing) {
            if (Ui.inside(mx, my, ox + boxW + 4, y, addW, 20)) {
                addOffline();
                return;
            }
            if (Ui.within(mx, my, ox, y, boxW, 20)) return;
            typing = false;
        } else if (Ui.inside(mx, my, ox, y, msW, 20)) {
            typing = true;
            nameBuf = "";
            return;
        }
        // rows
        if (!Ui.within(mx, my, px() + 8, listY(), W() - 16, listH())) return;
        List<Row> list = rows();
        for (int i = 0; i < list.size(); i++) {
            Row r = list.get(i);
            float rx = px() + 12, rw = W() - 24, ry = listY() + i * (ROW + 4) - scroll.value;
            if (r.saved != null && Ui.inside(mx, my, rx + rw - 26, ry + 7, 18, 16)) {
                if (current(r)) {
                    say("使用中のアカウントは削除できません", true);
                    return;
                }
                Accounts.remove(r.saved);
                UiSound.cancel();
                say(Lang.f("{0} を削除しました", r.name), false);
                return;
            }
            if (Ui.inside(mx, my, rx, ry, rw, ROW)) {
                switchTo(r);
                return;
            }
        }
    }

    @Override
    protected void scroll(int mx, int my, int amount) {
        scrollTarget += amount * 30;
    }

    @Override
    protected void key(char c, int keyCode) {
        if (typing) {
            if (keyCode == Keyboard.KEY_ESCAPE) typing = false;
            else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) addOffline();
            else if (keyCode == Keyboard.KEY_BACK) {
                if (!nameBuf.isEmpty()) nameBuf = nameBuf.substring(0, nameBuf.length() - 1);
            } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
                for (char ch : String.valueOf(GuiScreen.getClipboardString()).toCharArray()) typeChar(ch);
            } else typeChar(c);
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (code != null) cancelSignIn();
            else mc.displayGuiScreen(parent);
        }
    }

    private void typeChar(char ch) {
        if (nameBuf.length() < NAME_MAX && ChatAllowedCharacters.isAllowedCharacter(ch) && (Character.isLetterOrDigit(ch) || ch == '_') && ch < 128) {
            nameBuf += ch;
        }
    }
}
