package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.cosmetic.AuraFx;
import dev.fzkc.fzhud.cosmetic.HaloFx;
import dev.fzkc.fzhud.cosmetic.Cosmetics;
import dev.fzkc.fzhud.cosmetic.Equip;
import dev.fzkc.fzhud.cosmetic.PlayerPreview;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cosmetics: your player turning on the left (drag to turn it yourself), capes and auras to pick on the right.
 * Opened from the title screen and the Right Shift menu. Picks are saved with the rest of the FzClient settings
 * and sent to the FzClient server, so other FzClient users see them too.
 */
public class CosmeticsScreen extends FzScreen {
    private static final String[] CAPE_NAMES = {"なし", "FzClient", "オーロラ", "ミッドナイト", "サクラ", "オーシャン", "エンバー", "ギャラクシー", "ネオン", "ロイヤル"};
    private static final String[] AURA_NAMES = {"なし", "ハート", "炎", "魔法", "音符", "雪", "虹", "星", "花びら", "泡", "ソウル"};
    private static final String[] HALO_NAMES = {"なし", "ゴールド", "虹", "ダーク", "アイス"};
    private static final String[] TABS = {"マント", "オーラ", "ヘイロー"};
    /** Sprite and tint for each aura tile (none has no icon). */
    private static final int[][] AURA_ICON = {{-1, 0}, {0, 0xFF4F7E}, {5, 0xFFA63C}, {2, 0xB07CFF}, {3, 0x5CE1E6}, {4, 0xEEF6FF}, {2, 0xFFD84A},
            {7, 0xFFE36B}, {6, 0xFFB7D5}, {1, 0x9FE6FF}, {5, 0x63E6FF}};
    /** The open tab (0 capes, 1 auras, 2 halos), kept while the game runs. */
    private static int tab;
    private static final float PW = 170, CTW = 46, CTH = 66, ATW = 40, ATH = 50, GAP = 6;

    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private final Map<String, Anim> anims = new HashMap<String, Anim>();
    private final List<AuraFx.P> fx = new ArrayList<AuraFx.P>();
    private final Random rnd = new Random();
    private long lastStep;
    private int fxTick;
    private float yaw = 200f, dragX = Float.NaN;
    private long lastDrag;

    public CosmeticsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private float W() { return Ui.menuW(vw, 580); }
    private float H() { return Ui.menuH(vh, 330); }
    private float px() { return Ui.menuX(vw, W()); }
    private float py() { return (vh - H()) / 2f; }
    private float cx0() { return px() + PW + 20; }
    private float cw() { return W() - PW - 34; }

    private Anim anim(String k) {
        Anim a = anims.get(k);
        if (a == null) anims.put(k, a = new Anim(0f, 14f));
        return a;
    }

    // ------------------------------------------------------------------ layout

    private float tabY() { return py() + 58; }
    private float capeY() { return tabY() + 26; }
    private float auraY() { return capeY(); }
    private float showY() { return py() + H() - 26; }
    private float[] tabBtn(int i) {
        float x = cx0();
        for (int k = 0; k < i; k++) x += R.width(Lang.t(TABS[k])) + 22;
        return new float[]{x, tabY(), R.width(Lang.t(TABS[i])) + 16, 18};
    }
    private int capeCols() { return Math.max(1, (int) ((cw() + GAP) / (CTW + GAP))); }
    private int auraCols() { return Math.max(1, (int) ((cw() + GAP) / (ATW + GAP))); }
    private float[] capeTile(int i) {
        int c = capeCols();
        return new float[]{cx0() + (i % c) * (CTW + GAP), capeY() + 14 + (i / c) * (CTH + GAP), CTW, CTH};
    }
    private float[] auraTile(int i) {
        int c = auraCols();
        return new float[]{cx0() + (i % c) * (ATW + GAP), auraY() + 14 + (i / c) * (ATH + GAP), ATW, ATH};
    }
    private float[] closeBtn() {
        float w = R.width(Lang.t("閉じる")) + 18;
        return new float[]{px() + W() - 12 - w, py() + 12, w, 18};
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);
        step();

        // left: preview
        float lx = px() + 10, ly = py() + 10, lw = PW, lh = H() - 20;
        R.rounded(lx, ly, lw, lh, Ui.rad(8f), R.alpha(0x38000000, o));
        if (Float.isNaN(dragX) && System.currentTimeMillis() - lastDrag > 2500L) yaw += 0.6f;
        Equip mine = Cosmetics.mine();
        ResourceLocation cape = mine.cape.isEmpty() ? null : Cosmetics.capeTexture(mine.cape);
        float size = Math.min(lw * 0.36f, (lh - 40) / 2.3f);
        float footX = lx + lw / 2f, footY = ly + lh - 26;
        float age = (System.currentTimeMillis() % 1000000L) / 50f;
        // aura behind and in front of the player
        AuraFx.draw2D(fx, footX, footY, size, 0f);
        PlayerPreview.draw(footX, footY, size, yaw, cape, age);
        HaloFx.draw2D(mine.halo, footX, footY, size);
        R.textCentered(Lang.t("ドラッグで回転"), footX, ly + lh - 14, R.alpha(Ui.MUTED, o));

        // header
        R.textScaled("Cosmetics", cx0(), py() + 14, 1.6f, R.alpha(Ui.text, o));
        R.text(Lang.t("マント・オーラ・ヘイロー。ほかの FzClient ユーザーにも見えます"), cx0(), py() + 34, R.alpha(Ui.MUTED, o));
        float[] cb = closeBtn();
        Ui.button(cb[0], cb[1], cb[2], cb[3], Lang.t("閉じる"), anim("close").update(Ui.within(mx, my, cb[0], cb[1], cb[2], cb[3]) ? 1 : 0), o);
        R.rect(cx0(), py() + 50, cw(), 1, R.alpha(Ui.LINE, o));

        // tabs
        for (int i = 0; i < TABS.length; i++) {
            float[] b = tabBtn(i);
            float h = anim("tab" + i).update(Ui.within(mx, my, b[0], b[1], b[2], b[3]) ? 1 : 0);
            boolean sel = i == tab;
            R.rounded(b[0], b[1], b[2], b[3], Ui.rad(5f), R.alpha(sel ? Ui.accentA(0x50) : R.lerpColor(0x18FFFFFF, 0x30FFFFFF, h), o));
            if (sel) R.roundedOutline(b[0], b[1], b[2], b[3], Ui.rad(5f), 1f, R.alpha(Ui.accent, o));
            R.textCentered(Lang.t(TABS[i]), b[0] + b[2] / 2f, b[1] + 5, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
        }

        // capes
        if (tab == 0) {
        int capeSel = Modules.cosCape.get();
        for (int i = 0; i < Equip.CAPES.length; i++) {
            float[] t = capeTile(i);
            boolean sel = i == capeSel;
            float h = anim("c" + i).update(Ui.within(mx, my, t[0], t[1], t[2], t[3]) ? 1 : 0);
            tile(t, sel, h, o);
            float iw = 18, ih = 29, ix = t[0] + (t[2] - iw) / 2f, iy = t[1] + 6;
            if (i == 0) {
                R.textCentered("—", t[0] + t[2] / 2f, iy + 10, R.alpha(Ui.MUTED, o));
            } else {
                ResourceLocation tex = Cosmetics.capeTexture(Equip.CAPES[i]);
                if (tex != null) capeFace(tex, ix, iy, iw, ih, o);
                else R.textCentered("+", t[0] + t[2] / 2f, iy + 10, R.alpha(Ui.MUTED, o));
            }
            R.textCentered(Lang.t(CAPE_NAMES[i]), t[0] + t[2] / 2f, t[1] + t[3] - 13, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
        }

        }

        // auras
        if (tab == 1) {
        int auraSel = Modules.cosAura.get();
        for (int i = 0; i < Equip.AURAS.length; i++) {
            float[] t = auraTile(i);
            boolean sel = i == auraSel;
            float h = anim("a" + i).update(Ui.within(mx, my, t[0], t[1], t[2], t[3]) ? 1 : 0);
            tile(t, sel, h, o);
            if (AURA_ICON[i][0] < 0) R.textCentered("—", t[0] + t[2] / 2f, t[1] + 12, R.alpha(Ui.MUTED, o));
            else sprite(AURA_ICON[i][0], AURA_ICON[i][1], t[0] + t[2] / 2f - 9, t[1] + 5, 18, i == 6, o);
            R.textCentered(Lang.t(AURA_NAMES[i]), t[0] + t[2] / 2f, t[1] + t[3] - 13, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
        }
        }

        // halos
        if (tab == 2) {
        int haloSel = Modules.cosHalo.get();
        for (int i = 0; i < Equip.HALOS.length; i++) {
            float[] t = auraTile(i);
            boolean sel = i == haloSel;
            float h = anim("h" + i).update(Ui.within(mx, my, t[0], t[1], t[2], t[3]) ? 1 : 0);
            tile(t, sel, h, o);
            if (i == 0) R.textCentered("—", t[0] + t[2] / 2f, t[1] + 12, R.alpha(Ui.MUTED, o));
            else HaloFx.draw2D(Equip.HALOS[i], t[0] + t[2] / 2f, t[1] + 15 + 2.12f * 40, 40);
            R.textCentered(Lang.t(HALO_NAMES[i]), t[0] + t[2] / 2f, t[1] + t[3] - 13, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
        }
        }

        // who sees what
        toggleRow("others", "ほかの FzClient ユーザーのコスメを表示", Modules.cosOthers.get(), showY(), mx, my, o);

        Decoration.corner(vw, vh, o);
    }

    private void tile(float[] t, boolean sel, float hover, float o) {
        R.rounded(t[0], t[1], t[2], t[3], Ui.rad(6f), R.alpha(sel ? Ui.accentA(0x40) : R.lerpColor(0x22FFFFFF, 0x38FFFFFF, hover), o));
        if (sel) R.roundedOutline(t[0], t[1], t[2], t[3], Ui.rad(6f), 1.2f, R.alpha(Ui.accent, o));
    }

    private void toggleRow(String key, String label, boolean on, float y, int mx, int my, float o) {
        float t = anim("t" + key).update(on ? 1 : 0);
        Ui.toggle(cx0(), y, t, o);
        R.text(Lang.t(label), cx0() + 30, y + 2, R.alpha(Ui.text, o));
    }

    /** The back of a cape (what others see behind you): (1,1)-(11,17) of the 64×32 layout, at any resolution. */
    private static void capeFace(ResourceLocation tex, float x, float y, float w, float h, float o) {
        net.minecraft.client.Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, o);
        float u0 = 1 / 64f, v0 = 1 / 32f, u1 = 11 / 64f, v1 = 17 / 32f;
        WorldRenderer r = Tessellator.getInstance().getWorldRenderer();
        r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        r.pos(x, y + h, 0).tex(u0, v1).endVertex();
        r.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        r.pos(x + w, y, 0).tex(u1, v0).endVertex();
        r.pos(x, y, 0).tex(u0, v0).endVertex();
        Tessellator.getInstance().draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static final ResourceLocation ATLAS = new ResourceLocation("fzclient", "textures/cosmetics/aura.png");

    private static void sprite(int cell, int rgb, float x, float y, float s, boolean rainbow, float o) {
        net.minecraft.client.Minecraft.getMinecraft().getTextureManager().bindTexture(ATLAS);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        if (rainbow) rgb = java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 3000L) / 3000f, 0.8f, 1f);
        GlStateManager.color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, o);
        float u0 = cell / 8f, u1 = (cell + 1) / 8f;
        WorldRenderer r = Tessellator.getInstance().getWorldRenderer();
        r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        r.pos(x, y + s, 0).tex(u0, 1).endVertex();
        r.pos(x + s, y + s, 0).tex(u1, 1).endVertex();
        r.pos(x + s, y, 0).tex(u1, 0).endVertex();
        r.pos(x, y, 0).tex(u0, 0).endVertex();
        Tessellator.getInstance().draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** The preview's aura moves at game speed (20 steps a second). */
    private void step() {
        long now = System.currentTimeMillis();
        if (lastStep == 0) lastStep = now;
        int n = 0;
        while (now - lastStep >= 50L && n++ < 10) {
            lastStep += 50L;
            fxTick++;
            AuraFx.update(fx);
            String aura = Equip.AURAS[Math.max(0, Math.min(Equip.AURAS.length - 1, Modules.cosAura.get()))];
            if (!aura.isEmpty()) AuraFx.emit(fx, aura, 0, 0, 0, fxTick, rnd);
        }
        if (now - lastStep > 1000L) lastStep = now;
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        float[] cb = closeBtn();
        if (Ui.inside(mx, my, cb[0], cb[1], cb[2], cb[3])) {
            mc.displayGuiScreen(parent);
            return;
        }
        for (int i = 0; i < TABS.length; i++) {
            float[] b = tabBtn(i);
            if (Ui.inside(mx, my, b[0], b[1], b[2], b[3])) {
                tab = i;
                return;
            }
        }
        for (int i = 0; tab == 0 && i < Equip.CAPES.length; i++) {
            float[] t = capeTile(i);
            if (Ui.inside(mx, my, t[0], t[1], t[2], t[3])) {
                Modules.cosCape.index = i;
                return;
            }
        }
        for (int i = 0; tab == 1 && i < Equip.AURAS.length; i++) {
            float[] t = auraTile(i);
            if (Ui.inside(mx, my, t[0], t[1], t[2], t[3])) {
                Modules.cosAura.index = i;
                fx.clear();
                return;
            }
        }
        for (int i = 0; tab == 2 && i < Equip.HALOS.length; i++) {
            float[] t = auraTile(i);
            if (Ui.inside(mx, my, t[0], t[1], t[2], t[3])) {
                Modules.cosHalo.index = i;
                return;
            }
        }
        if (Ui.inside(mx, my, cx0(), showY() - 2, cw(), 16)) Modules.cosOthers.value = !Modules.cosOthers.get();
        else if (Ui.within(mx, my, px() + 10, py() + 10, PW, H() - 20)) dragX = mx;
    }

    @Override
    protected void drag(int mx, int my) {
        if (Float.isNaN(dragX)) return;
        yaw += (mx - dragX) * 2f;
        dragX = mx;
        lastDrag = System.currentTimeMillis();
    }

    @Override
    protected void release(int mx, int my) {
        if (!Float.isNaN(dragX)) lastDrag = System.currentTimeMillis();
        dragX = Float.NaN;
    }

    @Override
    protected void key(char c, int keyCode) {
        if (keyCode == 1) mc.displayGuiScreen(parent);
    }
}
