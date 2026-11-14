package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;

import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Features found in the well-known PvP clients: zoom, block outline, chat timestamps / compact chat,
 * Auto GG, unfocused FPS limit, and the numbers behind the Reach / Combo / Memory HUD elements.
 */
public final class Extras {

    public static final KeyBinding zoomKey = new KeyBinding("Zoom", Keyboard.KEY_C, "FzClient");

    // ============================================================ Zoom

    /** Current FOV multiplier (1 = no zoom). Read by MixinEntityRenderer. */
    public static float zoomFov = 1f;
    private static boolean zoomToggled, zoomWasDown, cinematicSet, cinematicBefore;
    private static float zoomExtra = 1f;
    private static long lastFrame;

    private static boolean ofZoomChecked;

    /**
     * OptiFine has its own zoom, also on C by default. With both on the same key the picture zooms twice, so
     * OptiFine's key is unbound (it can be bound again in the controls; only the very same key is taken away).
     */
    private static void unbindOptiFineZoom(Minecraft mc) {
        ofZoomChecked = true;
        for (KeyBinding k : mc.gameSettings.keyBindings) {
            if (!"of.key.zoom".equals(k.getKeyDescription())) continue;
            if (k.getKeyCode() != 0 && k.getKeyCode() == zoomKey.getKeyCode()) {
                k.setKeyCode(0);
                KeyBinding.resetKeyBindingArrayAndHash();
                mc.gameSettings.saveOptions();
            }
            return;
        }
    }

    private static boolean zooming() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Modules.zoom.enabled || mc.thePlayer == null) {
            zoomToggled = false;
            return false;
        }
        if (!ofZoomChecked) unbindOptiFineZoom(mc);
        boolean down = zoomKey.getKeyCode() != 0 && mc.currentScreen == null && zoomKey.isKeyDown();
        if (Modules.zoomToggle.get()) {
            if (down && !zoomWasDown) zoomToggled = !zoomToggled;
            zoomWasDown = down;
            return zoomToggled;
        }
        zoomWasDown = down;
        zoomToggled = false;
        return down;
    }

    /** Called at the start of every frame. */
    public static void frame() {
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1.0e9f);
        lastFrame = now;

        boolean z = zooming();
        if (!z) zoomExtra = 1f;
        float target = z ? 1f / (Modules.zoomLevel.get() * zoomExtra) : 1f;
        if (!Modules.zoomSmooth.get() || dt == 0f) zoomFov = target;
        else zoomFov += (target - zoomFov) * (1f - (float) Math.exp(-Modules.zoomSpeed.get() * dt));
        if (Math.abs(zoomFov - target) < 0.0005f) zoomFov = target;

        // cinematic camera only while zoomed; give the player's own setting back afterward
        boolean wantCine = z && Modules.zoomCinematic.get();
        if (wantCine && !cinematicSet) {
            cinematicBefore = mc.gameSettings.smoothCamera;
            mc.gameSettings.smoothCamera = true;
            cinematicSet = true;
        } else if (!wantCine && cinematicSet) {
            mc.gameSettings.smoothCamera = cinematicBefore;
            cinematicSet = false;
        }

        // limit FPS while another window has focus (also on the title screen), and even more while minimized
        // not while the launcher's overlay is open: it has the focus then, but the game is right under it
        if (Modules.bgFps.enabled && !Display.isActive() && !LauncherLink.watching()) {
            int limit = (int) Modules.bgFpsLimit.get();
            if (!Display.isVisible()) limit = Math.min(limit, 5);
            Display.sync(limit);
        }
    }

    /** Mouse look multiplier while zoomed (MixinEntityRenderer). */
    public static float sensitivity() {
        return Modules.zoom.enabled && Modules.zoomSens.get() ? zoomFov : 1f;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMouse(MouseEvent event) {
        if (event.dwheel == 0 || zoomFov >= 1f || !Modules.zoomScroll.get() || !zooming()) return;
        zoomExtra *= event.dwheel > 0 ? 1.25f : 0.8f;
        zoomExtra = Math.max(0.5f, Math.min(4f, zoomExtra));
        event.setCanceled(true);
    }

    // ============================================================ Block outline

    /**
     * Block outline. The look (colour, width, fill, through walls) is applied inside vanilla's own outline drawing
     * (MixinRenderGlobal), which keeps OptiFine's shader handling around it, so it is visible with shader packs.
     * Here we only make sure there is something to outline: vanilla sometimes points at nothing even though a block
     * is in reach (e.g. a player standing in the line of sight); then we look with your real block reach
     * (4.5 survival / 5 creative) and let vanilla draw that block.
     */
    @SubscribeEvent
    public void onBlockHighlight(DrawBlockHighlightEvent event) {
        if (!Modules.blockOutline.enabled) return;
        MovingObjectPosition hit = event.target;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return; // vanilla draws it
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.theWorld == null || view == null || mc.playerController == null || mc.playerController.isSpectator()) return;
        hit = view.rayTrace(mc.playerController.getBlockReachDistance(), event.partialTicks);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        event.setCanceled(true);
        event.context.drawSelectionBox(event.player, hit, 0, event.partialTicks);
    }

    public static void outlineColor(int argb, float alpha) {
        int c = R.solid(argb);
        GlStateManager.color(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, alpha);
    }

    public static void fillBox(AxisAlignedBB b) {
        fill(b);
    }

    private static void fill(AxisAlignedBB b) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        // bottom, top
        w.pos(b.minX, b.minY, b.minZ).endVertex(); w.pos(b.maxX, b.minY, b.minZ).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).endVertex(); w.pos(b.minX, b.minY, b.maxZ).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).endVertex(); w.pos(b.minX, b.maxY, b.maxZ).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).endVertex(); w.pos(b.maxX, b.maxY, b.minZ).endVertex();
        // north, south
        w.pos(b.minX, b.minY, b.minZ).endVertex(); w.pos(b.minX, b.maxY, b.minZ).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).endVertex(); w.pos(b.maxX, b.minY, b.minZ).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).endVertex(); w.pos(b.maxX, b.minY, b.maxZ).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).endVertex(); w.pos(b.minX, b.maxY, b.maxZ).endVertex();
        // west, east
        w.pos(b.minX, b.minY, b.minZ).endVertex(); w.pos(b.minX, b.minY, b.maxZ).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).endVertex(); w.pos(b.minX, b.maxY, b.minZ).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).endVertex(); w.pos(b.maxX, b.maxY, b.minZ).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).endVertex(); w.pos(b.maxX, b.minY, b.maxZ).endVertex();
        t.draw();
    }

    // ============================================================ Reach / Combo

    public static float lastReach = -1f;
    public static long lastReachTime;
    public static int combo;
    private static long lastComboHit;
    private static Entity lastTarget;
    private static long lastAttack;
    private static int prevTargetHurt, prevSelfHurt;

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.entityPlayer != mc.thePlayer || event.target == null) return;
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop != null && mop.entityHit == event.target && mop.hitVec != null) {
            Vec3 eyes = mc.thePlayer.getPositionEyes(1f);
            lastReach = (float) eyes.distanceTo(mop.hitVec);
            lastReachTime = System.currentTimeMillis();
        }
        if (event.target != lastTarget) {
            lastTarget = event.target;
            prevTargetHurt = event.target instanceof EntityLivingBase ? ((EntityLivingBase) event.target).hurtTime : 0;
        }
        lastAttack = System.currentTimeMillis();
    }

    private static void combatTick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (mc.thePlayer == null) {
            combo = 0;
            lastTarget = null;
            return;
        }
        // the target got hurt shortly after we swung at it -> one more hit in the combo
        if (lastTarget instanceof EntityLivingBase) {
            int hurt = ((EntityLivingBase) lastTarget).hurtTime;
            if (hurt > prevTargetHurt && now - lastAttack < 1000L) {
                combo++;
                lastComboHit = now;
            }
            prevTargetHurt = hurt;
            if (lastTarget.isDead) lastTarget = null;
        }
        // getting hit or 2.5s without a hit ends it
        int selfHurt = mc.thePlayer.hurtTime;
        if (selfHurt > prevSelfHurt) combo = 0;
        prevSelfHurt = selfHurt;
        if (combo > 0 && now - lastComboHit > 2500L) combo = 0;
    }

    // ============================================================ Chat

    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm");
    private static final SimpleDateFormat HMS = new SimpleDateFormat("HH:mm:ss");
    private static String lastKey;
    private static int repeat;
    private static IChatComponent lastShown;
    private static Field chatLinesField, drawnLinesField;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2 || event.message == null) return;
        String plain = event.message.getUnformattedText();
        autoGG(plain);
        if (!Modules.chatTweaksEnabled()) return;

        IChatComponent msg = event.message;
        if (Modules.chatCompact.get() && !plain.trim().isEmpty() && plain.equals(lastKey) && removeNewest(lastShown)) {
            repeat++;
            msg = msg.createCopy().appendSibling(new ChatComponentText(EnumChatFormatting.GRAY + " (x" + repeat + ")"));
        } else {
            repeat = 1;
        }
        lastKey = plain;

        if (Modules.chatTimestamps.get()) {
            String ts = (Modules.chatSeconds.get() ? HMS : HM).format(new Date());
            msg = new ChatComponentText(EnumChatFormatting.GRAY + "[" + ts + "] " + EnumChatFormatting.RESET).appendSibling(msg);
        }
        event.message = msg;
        lastShown = msg;
    }

    /**
     * Another feature (romaji conversion) shows a changed copy of the line printed last: stacking keeps working
     * with the copy that is really in the chat.
     */
    public static void shownAs(IChatComponent old, IChatComponent now) {
        if (old != null && lastShown == old) lastShown = now;
    }

    /** Removes the newest chat line if it is the one we printed last (for "(x2)" stacking). */
    @SuppressWarnings("unchecked")
    private static boolean removeNewest(IChatComponent shown) {
        if (shown == null) return false;
        try {
            GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
            if (chatLinesField == null) {
                for (String n : new String[]{"chatLines", "field_146252_h"}) {
                    try {
                        chatLinesField = GuiNewChat.class.getDeclaredField(n);
                        chatLinesField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (chatLinesField == null) return false;
            }
            @SuppressWarnings("unchecked")
            List<ChatLine> lines = (List<ChatLine>) chatLinesField.get(chat);
            if (lines.isEmpty() || lines.get(0).getChatComponent() != shown) return false;
            ChatLine gone = lines.remove(0);
            // take its wrapped pieces off the top of the drawn lines; refreshChat() would re-wrap the whole
            // scrollback (thousands of lines) for every stacked message
            List<ChatLine> drawn = null;
            if (drawnLinesField == null) {
                for (String n : new String[]{"drawnChatLines", "field_146253_i"}) {
                    try {
                        drawnLinesField = GuiNewChat.class.getDeclaredField(n);
                        drawnLinesField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            if (drawnLinesField != null) drawn = (List<ChatLine>) drawnLinesField.get(chat);
            if (drawn == null) {
                chat.refreshChat();
                return true;
            }
            while (!drawn.isEmpty() && drawn.get(0).getUpdatedCounter() == gone.getUpdatedCounter()
                    && drawn.get(0).getChatLineID() == gone.getChatLineID()) {
                drawn.remove(0);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================ Auto GG

    private static final String[] GAME_END = {
            "1st Killer - ", "1st Place - ", "Winner: ", "Winners: ", " won the game!", "Winning Team: ",
            "Top Survivors", "Reward Summary", "1st - ", "YOUR STATS",
            // Duels ("[MVP++] Fzkc WINNER!  [VIP] Name"), team Duels and others
            " WINNER!", "Most Kills - ", "Winner - ", "Winning Team - "
    };
    private static long ggAt, gg2At, lastGG;

    private static void autoGG(String plain) {
        if (!Modules.autoGG.enabled || ggAt != 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (Modules.autoGGHypixelOnly.get()) {
            ServerData sd = mc.getCurrentServerData();
            if (sd == null || sd.serverIP == null || !sd.serverIP.toLowerCase().contains("hypixel")) return;
        }
        String s = EnumChatFormatting.getTextWithoutFormattingCodes(plain);
        // Hypixel's end-of-game summary lines are centred with leading spaces; player chat never is,
        // so typing "Winner: " in chat can't trigger it
        if (s == null || !s.startsWith(" ")) return;
        long now = System.currentTimeMillis();
        if (now - lastGG < 10000L) return;
        for (String k : GAME_END) {
            if (s.contains(k)) {
                ggAt = now + (long) (Modules.autoGGDelay.get() * 1000f);
                return;
            }
        }
    }

    private static void ggTick(Minecraft mc) {
        if (ggAt == 0 || System.currentTimeMillis() < ggAt) return;
        ggAt = 0;
        lastGG = System.currentTimeMillis();
        String text = Modules.autoGGText.get().trim();
        if (mc.thePlayer != null && !text.isEmpty()) mc.thePlayer.sendChatMessage("/ac " + text);
        if (Modules.autoGG2.get()) gg2At = lastGG + Math.max(50L, (long) (Modules.autoGGDelay2.get() * 1000f));
    }

    private static void gg2Tick(Minecraft mc) {
        if (gg2At == 0 || System.currentTimeMillis() < gg2At) return;
        gg2At = 0;
        String text = Modules.autoGGText2.get().trim();
        if (mc.thePlayer != null && !text.isEmpty()) mc.thePlayer.sendChatMessage("/ac " + text);
    }

    // ============================================================ ticks

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        combatTick(mc);
        ggTick(mc);
        gg2Tick(mc);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) frame();
    }
}
