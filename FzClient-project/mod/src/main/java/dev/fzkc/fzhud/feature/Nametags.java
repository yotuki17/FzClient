package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;

import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import org.lwjgl.opengl.GL11;

/** Player nametags with a soft rounded "bubble" background. Same visibility rules as vanilla. */
public final class Nametags {
    private Nametags() {}

    /** Mirrors vanilla canRenderName for players. */
    public static boolean canRender(EntityPlayer entity) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP self = mc.thePlayer;
        if (self == null) return false;
        if (entity != self) {
            Team team = entity.getTeam();
            Team own = self.getTeam();
            if (team != null) {
                switch (team.getNameTagVisibility()) {
                    case NEVER: return false;
                    case HIDE_FOR_OTHER_TEAMS: return own == null || team.isSameTeam(own);
                    case HIDE_FOR_OWN_TEAM: return own == null || !team.isSameTeam(own);
                    case ALWAYS:
                    default: return true;
                }
            }
        }
        RenderManager rm = mc.getRenderManager();
        boolean selfOk = entity != rm.livingPlayer
                || (Modules.tagSelf.get() && mc.gameSettings.thirdPersonView != 0);
        return Minecraft.isGuiEnabled() && selfOk && !entity.isInvisibleToPlayer(self)
                && entity.riddenByEntity == null;
    }

    /**
     * A player's tag texts, rebuilt once per tick instead of every frame. Getting a player's display name builds
     * new chat components with a click and an NBT hover text each time, which adds up with many players in view.
     */
    private static final class Tag {
        int tick = Integer.MIN_VALUE;
        String name;
        int belowTick = Integer.MIN_VALUE;
        ScoreObjective belowObjective;
        String below;
        int lastSeen;
    }

    private static final java.util.IdentityHashMap<EntityPlayer, Tag> TAGS = new java.util.IdentityHashMap<>();
    private static net.minecraft.world.World tagsWorld;
    private static int tagsCleanedAt;

    private static Tag tag(EntityPlayer entity) {
        Minecraft mc = Minecraft.getMinecraft();
        int now = dev.fzkc.fzhud.util.Stamp.ticks;
        if (mc.theWorld != tagsWorld) {
            TAGS.clear();
            tagsWorld = mc.theWorld;
        }
        if (now - tagsCleanedAt >= 200 || now < tagsCleanedAt) {
            // forget players that haven't been drawn for 5 seconds (they left or went out of view)
            tagsCleanedAt = now;
            TAGS.values().removeIf(tag -> now - tag.lastSeen > 100);
        }
        Tag t = TAGS.get(entity);
        if (t == null) {
            t = new Tag();
            TAGS.put(entity, t);
        }
        t.lastSeen = now;
        if (t.tick != now) {
            t.tick = now;
            t.name = entity.getDisplayName().getFormattedText();
        }
        return t;
    }

    public static void render(EntityPlayer entity, double x, double y, double z) {
        if (!canRender(entity)) return;
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager rm = mc.getRenderManager();
        double distSq = entity == rm.livingPlayer ? 0 : entity.getDistanceSqToEntity(rm.livingPlayer);
        float range = entity.isSneaking() ? 32f : 64f;
        if (distSq >= range * range) return;

        double baseY = y - (entity.isChild() ? entity.height / 2.0 : 0.0);
        Tag tag = tag(entity);
        String name = tag.name;

        boolean icon = Modules.tagFzIcon.get() && (entity == mc.thePlayer || dev.fzkc.fzhud.integration.FzUsers.uses(entity.getUniqueID()));
        if (entity.isSneaking()) {
            label(entity, name, x, baseY, z, true, icon);
            return;
        }
        float lift = 0f;
        if (distSq < 100.0) {
            Scoreboard sb = entity.getWorldScoreboard();
            ScoreObjective below = sb.getObjectiveInDisplaySlot(2);
            if (below != null) {
                if (tag.belowTick != tag.tick || tag.belowObjective != below) {
                    Score score = sb.getValueFromObjective(entity.getName(), below);
                    tag.below = score.getScorePoints() + " " + below.getDisplayName();
                    tag.belowTick = tag.tick;
                    tag.belowObjective = below;
                }
                label(entity, tag.below, x, baseY, z, false, false);
                lift = mc.fontRendererObj.FONT_HEIGHT * 1.15f * 0.02666667f;
            }
        }
        label(entity, name, x, baseY + lift, z, false, icon);
    }

    /** Background and border, in the style picked in the settings (chroma works for both colours). */
    private static void drawBackground(int half) {
        int bg = (Math.round(Modules.tagOpacity.get() * 2.55f) << 24) | (Modules.tagBgColor.get() & 0xFFFFFF);
        float r = Modules.tagRadius.get();
        float x, y, w, h;
        if (r > 0.01f) { x = -half - 3; y = -2; w = half * 2 + 6; h = 11; }
        else { x = -half - 1; y = -1; w = half * 2 + 2; h = 9; }
        if (!Modules.tagBorder.get()) {
            if (r > 0.01f) R.rounded(x, y, w, h, r, bg);
            else R.rect(x, y, w, h, bg);
            return;
        }
        int b = (Math.round(Modules.tagBorderAlpha.get() * 255f) << 24) | (Modules.tagBorderColor.get() & 0xFFFFFF);
        float t = Modules.tagBorderWidth.get() * 0.5f;
        if (r > 0.01f) {
            R.roundedWithOutline(x, y, w, h, r, bg, Modules.tagBorderWidth.get(), b);
        } else {
            // background and the four border strips in one draw
            R.batchBegin();
            R.batchRect(x, y, w, h, bg);
            R.batchRect(x - t, y - t, w + t * 2, t, b);
            R.batchRect(x - t, y + h, w + t * 2, t, b);
            R.batchRect(x - t, y, t, h, b);
            R.batchRect(x + w, y, t, h, b);
            R.batchEnd();
        }
    }

    /** FzClient bird (128x71, transparent) in front of your own name. */
    private static final net.minecraft.util.ResourceLocation ICON =
            new net.minecraft.util.ResourceLocation("fzclient", "textures/tab_icon.png");
    private static final float ICON_H = 7f, ICON_W = ICON_H * 128f / 71f, ICON_GAP = 2f;

    private static void label(EntityPlayer entity, String text, double x, double y, double z, boolean sneaking, boolean icon) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = mc.fontRendererObj;
        float scale = 0.02666667f;
        int textW = R.measure(text);
        float extra = icon ? ICON_W + ICON_GAP : 0f;
        int half = (int) ((textW + extra) / 2f);
        int tx = -half + Math.round(extra); // text starts after the icon

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + entity.height + 0.5f, (float) z);
        GL11.glNormal3f(0f, 1f, 0f);
        GlStateManager.rotate(-rm.playerViewY, 0f, 1f, 0f);
        // in front-facing third person (F5 twice) the pitch has to be mirrored, like later vanilla versions do
        GlStateManager.rotate(rm.playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1f : 1f), 1f, 0f, 0f);
        GlStateManager.scale(-scale, -scale, scale);
        if (sneaking) GlStateManager.translate(0f, 9.374999f, 0f);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        if (!sneaking) GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        drawBackground(half);
        boolean shadow = Modules.tagShadow.get();

        GlStateManager.enableTexture2D();
        if (sneaking) {
            GlStateManager.depthMask(true);
            fr.drawString(text, tx, 0, 0x20FFFFFF, false);
        } else {
            // see-through pass, then the normal pass on top
            fr.drawString(text, tx, 0, 0x20FFFFFF, false);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            fr.drawString(text, tx, 0, 0xFFFFFFFF, shadow);
        }
        if (icon) {
            mc.getTextureManager().bindTexture(ICON);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.pushMatrix();
            GlStateManager.translate(-half, (8f - ICON_H) / 2f - 0.5f, 0f);
            GlStateManager.scale(ICON_W / 128f, ICON_H / 71f, 1f);
            net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 128, 71, 128, 71);
            GlStateManager.popMatrix();
        }
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }
}
