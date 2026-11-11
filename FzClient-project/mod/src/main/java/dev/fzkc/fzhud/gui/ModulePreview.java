package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.HeaderSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.Setting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.config.TextSetting;
import dev.fzkc.fzhud.cosmetic.AuraFx;
import dev.fzkc.fzhud.cosmetic.Cosmetics;
import dev.fzkc.fzhud.cosmetic.Equip;
import dev.fzkc.fzhud.cosmetic.HaloFx;
import dev.fzkc.fzhud.cosmetic.PlayerPreview;
import dev.fzkc.fzhud.hud.HudElement;
import dev.fzkc.fzhud.render.Icons;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The preview on the right of the settings page: how the module looks with the settings as they are now.
 * <ul>
 *   <li>HUD elements: the element itself, drawn by its own code over a small game-like background.</li>
 *   <li>Player things (cosmetics, 3D skin layers, hitbox, nametags): your player model with it on.</li>
 *   <li>Block outline: a block with the outline and fill.</li>
 *   <li>Item Position: the item in your hand as the game draws it, on a small picture of the whole screen.</li>
 *   <li>Everything else: the icon, what it does and its settings at a glance.</li>
 * </ul>
 */
final class ModulePreview {
    private ModulePreview() {}

    private static final List<AuraFx.P> fx = new ArrayList<AuraFx.P>();
    private static final Random rnd = new Random();
    private static long lastStep;
    private static int fxTick;
    private static String fxAura = "";

    static void draw(Module m, float x, float y, float w, float h, float o) {
        Ui.scissor(x, y, w, h);
        panorama(x, y, w, h, o);
        Ui.endScissor();
        R.roundedOutline(x, y, w, h, Ui.rad(8f), 1f, R.alpha(0x30FFFFFF, o));
        R.text(Lang.t("プレビュー"), x + 8, y + 6, R.alpha(0xFFFFFFFF, o));
        float iy = y + 18, ih = h - 22;
        Ui.scissor(x, iy, w, ih);
        try {
            if (m instanceof HudElement && ((HudElement) m).width() > 0) hud((HudElement) m, x, iy, w, ih, o);
            else if (m == Modules.cosmetics || m == Modules.skinLayers || m == Modules.hitbox || m == Modules.nametags) player(m, x, iy, w, ih, o);
            else if (m == Modules.blockOutline) block(x, iy, w, ih, o);
            else if (m == Modules.itemPos && Minecraft.getMinecraft().thePlayer != null) heldItem(x, iy, w, ih, o);
            else card(m, x, iy, w, ih, o);
        } catch (Throwable t) {
            card(m, x, iy, w, ih, o);
        }
        Ui.endScissor();
        if (!m.enabled && m.toggleable) {
            R.textCentered("§7" + Lang.t("（いまは無効）"), x + w / 2f, y + h - 13, R.alpha(0xFFFFFFFF, o));
        }
    }

    // ------------------------------------------------------------------ HUD elements

    private static void hud(HudElement e, float x, float y, float w, float h, float o) {
        float ew = e.width(), eh = e.height();
        float s = Math.min(e.scale(), Math.min((w - 24) / ew, (h - 24) / eh));
        if (s <= 0) return;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x + (w - ew * s) / 2f, y + (h - 6 - eh * s) / 2f, 0f);
            GlStateManager.scale(s, s, 1f);
            e.draw(o, true);
        } finally {
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();
    }

    private static final ResourceLocation PANO = new ResourceLocation("fzclient", "textures/panorama/panorama_0.jpg");

    /** The title screen's panorama standing still (its front picture, cut to the box), a little darker. */
    private static void panorama(float x, float y, float w, float h, float o) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(PANO);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, o);
        // the picture is square: show the middle part with the box's shape
        float a = w / h, u0 = .5f - Math.min(.5f, a / 2f), u1 = .5f + Math.min(.5f, a / 2f);
        float v0 = .5f - Math.min(.5f, 1f / a / 2f), v1 = .5f + Math.min(.5f, 1f / a / 2f);
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.WorldRenderer wr = t.getWorldRenderer();
        wr.begin(org.lwjgl.opengl.GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0).tex(u1, v0).endVertex();
        wr.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
        R.rect(x, y, w, h, R.alpha(0x50000000, o));
    }

    // ------------------------------------------------------------------ the player

    private static void player(Module m, float x, float y, float w, float h, float o) {
        float size = Math.min(w * .42f, (h - 30) / 2.4f);
        float footX = x + w / 2f, footY = y + h - 18;
        float age = (System.currentTimeMillis() % 1000000L) / 50f;
        float yaw = (System.currentTimeMillis() % 12000L) / 12000f * 360f;
        Equip mine = Cosmetics.mine();
        boolean cos = m == Modules.cosmetics;
        if (cos) stepAura(mine.aura);
        if (cos) AuraFx.draw2D(fx, footX, footY, size, 0f);
        ResourceLocation cape = cos && !mine.cape.isEmpty() ? Cosmetics.capeTexture(mine.cape) : null;
        PlayerPreview.draw(footX, footY, size, yaw, cape, age);
        if (cos) HaloFx.draw2D(mine.halo, footX, footY, size);

        if (m == Modules.hitbox) {
            float half = (.3f + (Modules.hitboxMargin.get() ? .1f : 0f)) * size;
            float top = footY - (1.8f + (Modules.hitboxMargin.get() ? .1f : 0f)) * size;
            int c = R.alpha(0xFF000000 | (Modules.hitboxColor.get() & 0xFFFFFF), o * Modules.hitboxAlpha.get());
            float lw = Math.max(1f, Modules.hitboxWidth.get() * .5f);
            R.rect(footX - half, top, half * 2, lw, c);
            R.rect(footX - half, footY - lw, half * 2, lw, c);
            R.rect(footX - half, top, lw, footY - top, c);
            R.rect(footX + half - lw, top, lw, footY - top, c);
            if (Modules.hitboxLines.get()) {
                float eye = footY - 1.62f * size;
                R.rect(footX - half, eye, half * 2, 1, R.alpha(0xFFFF5555, o));
                R.rect(footX, eye - .5f, half * 2.2f, 1, R.alpha(0xFF5555FF, o));
            }
        }
        if (m == Modules.nametags) {
            String name = Minecraft.getMinecraft().getSession() != null ? Minecraft.getMinecraft().getSession().getUsername() : "Player";
            float tw = R.width(name) + 6, ty = footY - 2.25f * size - 12;
            int bg = (Modules.tagBgColor.get() & 0xFFFFFF) | ((int) (Modules.tagOpacity.get() / 100f * 255) << 24);
            R.rounded(footX - tw / 2f, ty, tw, 11, Modules.tagRadius.get(), R.alpha(bg, o));
            if (Modules.tagBorder.get()) {
                int bc = R.alpha(0xFF000000 | (Modules.tagBorderColor.get() & 0xFFFFFF), o * Modules.tagBorderAlpha.get());
                R.roundedOutline(footX - tw / 2f, ty, tw, 11, Modules.tagRadius.get(), Modules.tagBorderWidth.get(), bc);
            }
            R.textCentered((Modules.tagShadow.get() ? "" : "") + name, footX, ty + 2, R.alpha(0xFFFFFFFF, o));
        }
        if (m == Modules.skinLayers) {
            R.textCentered("§7" + Lang.t(m.enabled ? "3D" : "平ら（無効）"), footX, y + 2, R.alpha(0xFFFFFFFF, o));
        }
    }

    /** The cosmetics preview's aura moves at game speed. */
    private static void stepAura(String aura) {
        if (!aura.equals(fxAura)) {
            fx.clear();
            fxAura = aura;
        }
        long now = System.currentTimeMillis();
        if (lastStep == 0 || now - lastStep > 1000L) lastStep = now;
        int n = 0;
        while (now - lastStep >= 50L && n++ < 10) {
            lastStep += 50L;
            fxTick++;
            AuraFx.update(fx);
            if (!aura.isEmpty()) AuraFx.emit(fx, aura, 0, 0, 0, fxTick, rnd);
        }
    }

    // ------------------------------------------------------------------ block outline

    private static void block(float x, float y, float w, float h, float o) {
        float cx = x + w / 2f, cy = y + h / 2f, s = Math.min(w, h) * .28f;
        // an isometric grass block: top, left and right faces
        float[] top = {cx, cy - s, cx + s, cy - s * .5f, cx, cy, cx - s, cy - s * .5f};
        float[] left = {cx - s, cy - s * .5f, cx, cy, cx, cy + s, cx - s, cy + s * .5f};
        float[] right = {cx, cy, cx + s, cy - s * .5f, cx + s, cy + s * .5f, cx, cy + s};
        quad(top, R.alpha(0xFF6BAA4E, o));
        quad(left, R.alpha(0xFF7A5634, o));
        quad(right, R.alpha(0xFF5E4127, o));
        if (Modules.outlineFill.get()) {
            int fc = R.alpha(0xFF000000 | (Modules.outlineFillColor.get() & 0xFFFFFF), o * Modules.outlineFillAlpha.get());
            quad(top, fc);
            quad(left, fc);
            quad(right, fc);
        }
        int c = R.alpha(0xFF000000 | (Modules.outlineColor.get() & 0xFFFFFF), o * Modules.outlineAlpha.get());
        float lw = Math.max(1f, Modules.outlineWidth.get() * .6f);
        float[][] edges = {
                {cx, cy - s, cx + s, cy - s * .5f}, {cx + s, cy - s * .5f, cx, cy}, {cx, cy, cx - s, cy - s * .5f}, {cx - s, cy - s * .5f, cx, cy - s},
                {cx - s, cy - s * .5f, cx - s, cy + s * .5f}, {cx + s, cy - s * .5f, cx + s, cy + s * .5f}, {cx, cy, cx, cy + s},
                {cx - s, cy + s * .5f, cx, cy + s}, {cx, cy + s, cx + s, cy + s * .5f}};
        for (float[] e : edges) line(e[0], e[1], e[2], e[3], lw, c);
    }

    private static void quad(float[] p, int argb) {
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.WorldRenderer wr = t.getWorldRenderer();
        GlStateManager.disableCull();   // the outline's quads go round the other way: they were culled and never showed
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        wr.begin(org.lwjgl.opengl.GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION);
        wr.pos(p[0], p[1], 0).endVertex();
        wr.pos(p[6], p[7], 0).endVertex();
        wr.pos(p[4], p[5], 0).endVertex();
        wr.pos(p[2], p[3], 0).endVertex();
        t.draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
    }

    /** A thick line as a quad. */
    private static void line(float x1, float y1, float x2, float y2, float lw, int argb) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < .01f) return;
        float nx = -dy / len * lw / 2f, ny = dx / len * lw / 2f;
        quad(new float[]{x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny}, argb);
    }

    // ------------------------------------------------------------------ the item in your hand

    private static final java.nio.IntBuffer VIEWPORT = org.lwjgl.BufferUtils.createIntBuffer(16);
    private static net.minecraft.item.ItemStack previewSword;

    /**
     * Item Position: the item as the game draws it in first person, with the position and size that are set now,
     * on a small picture of the whole screen (the cross in the middle is the crosshair). Shows what you hold, or
     * a diamond sword with an empty hand. Only in a world: the game draws the item for a player.
     */
    private static void heldItem(float x, float y, float w, float h, float o) {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.item.ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null || stack.getItem() == net.minecraft.init.Items.filled_map) {
            if (previewSword == null) previewSword = new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond_sword);
            stack = previewSword;
        }
        // the screen's shape, as wide as the box allows
        float aspect = (float) mc.displayWidth / Math.max(1, mc.displayHeight);
        float bw = w - 16, bh = bw / aspect;
        if (bh > h - 30) {
            bh = h - 30;
            bw = bh * aspect;
        }
        float bx = x + (w - bw) / 2f, by = y + 6;
        R.rect(bx, by, bw, bh, R.alpha(0x48000000, o));
        float cx = bx + bw / 2f, cy = by + bh / 2f;
        R.rect(cx - 3, cy, 7, 1, R.alpha(0xD0FFFFFF, o));
        R.rect(cx, cy - 3, 1, 7, R.alpha(0xD0FFFFFF, o));
        R.textCentered(Lang.t("画面全体"), x + w / 2f, by + bh + 5, R.alpha(0xE0FFFFFF, o));
        if (o < 0.75f) return;   // the menu is still fading in, and the item cannot fade with it

        float f = Ui.scaleFactor() * Ui.k;
        int vx = Math.round(bx * f), vy = Math.round(mc.displayHeight - (by + bh) * f);
        int vw = Math.max(1, Math.round(bw * f)), vh = Math.max(1, Math.round(bh * f));
        boolean depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        VIEWPORT.clear();
        org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT, VIEWPORT);
        Ui.scissor(bx, by, bw, bh);
        // the game's first person camera (70 degrees, whatever the FOV setting says) looking at that picture only
        GlStateManager.viewport(vx, vy, vw, vh);
        GlStateManager.matrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        org.lwjgl.util.glu.Project.gluPerspective(70f, (float) vw / vh, 0.05f, 64f);
        GlStateManager.matrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        try {
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);   // inside the scissor box only
            net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.translate(Modules.itemPosX.get(), Modules.itemPosY.get(), -Modules.itemPosZ.get());
            // where the game puts an item at rest (ItemRenderer.transformFirstPersonItem)
            GlStateManager.translate(0.56f, -0.52f, -0.72f);
            GlStateManager.rotate(45f, 0f, 1f, 0f);
            GlStateManager.scale(0.4f, 0.4f, 0.4f);
            float s = Modules.itemPosScale.get() / 100f;
            GlStateManager.scale(s, s, s);
            mc.getItemRenderer().renderItem(mc.thePlayer, stack,
                    net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType.FIRST_PERSON);
        } finally {
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.matrixMode(org.lwjgl.opengl.GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.viewport(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
            if (!depth) GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, 1f);
            Ui.scissor(x, y, w, h);
        }
    }

    // ------------------------------------------------------------------ everything else

    private static void card(Module m, float x, float y, float w, float h, float o) {
        Icons.draw(m.id, x + w / 2f - 14, y + 6, 28, R.alpha(m.enabled ? Ui.accent : 0xFF9DB4B8, o));
        R.textCentered(m.name, x + w / 2f, y + 40, R.alpha(Ui.text, o));
        float ty = y + 54;
        for (String line : wrap(Lang.t(m.description), w - 16)) {
            if (ty > y + h * .5f) break;
            R.textCentered(line, x + w / 2f, ty, R.alpha(Ui.MUTED, o));
            ty += 10;
        }
        ty += 6;
        R.rect(x + 8, ty, w - 16, 1, R.alpha(Ui.LINE, o));
        ty += 6;
        for (Setting s : m.settings) {
            if (ty > y + h - 12) break;
            if (s instanceof HeaderSetting) continue;
            String v = value(s);
            if (v == null) continue;
            String label = Ui.trim(Lang.t(s.label), w - 20 - Math.min(60, R.width(v)) - 6);
            R.text(label, x + 8, ty, R.alpha(Ui.textA(0xB0), o));
            if (s instanceof ColorSetting) {
                R.rounded(x + w - 8 - 12, ty, 12, 8, 2f, R.alpha(((ColorSetting) s).get() | 0xFF000000, o));
            } else {
                v = Ui.trim(v, 60);
                R.text(v, x + w - 8 - R.width(v), ty, R.alpha(Ui.text, o));
            }
            ty += 11;
        }
    }

    private static String value(Setting s) {
        if (s instanceof BoolSetting) return ((BoolSetting) s).get() ? "§aON" : "§7OFF";
        if (s instanceof ChoiceSetting) return ((ChoiceSetting) s).display();
        if (s instanceof SliderSetting) return ((SliderSetting) s).display();
        if (s instanceof ColorSetting) return "";
        if (s instanceof TextSetting) {
            TextSetting t = (TextSetting) s;
            if (t.get().isEmpty()) return "§7—";
            return t.secret ? "••••" : t.get();
        }
        return null;
    }

    /** Splits text into lines that fit (by character, so Japanese wraps too; breaks at spaces when it can). */
    private static List<String> wrap(String text, float max) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            cur.append(text.charAt(i));
            if (R.width(cur.toString()) > max) {
                int cut = cur.lastIndexOf(" ");
                if (cut <= 0) cut = cur.length() - 1;
                out.add(cur.substring(0, cut).trim());
                cur = new StringBuilder(cur.substring(cut).trim());
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
