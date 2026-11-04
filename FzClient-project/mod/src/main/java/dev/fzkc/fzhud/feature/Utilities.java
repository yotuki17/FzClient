package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.util.Lang;

import dev.fzkc.fzhud.Modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Small everyday helpers: reconnect button / auto reconnect, screenshot to clipboard, low-health warning,
 * hit particles, death coordinates, hiding the pumpkin / boss bar overlays, and Ctrl+click chat copy.
 */
public final class Utilities {

    private static final int RECONNECT_ID = 0x7A01;

    // ============================================================ Reconnect

    private static ServerData lastServer;
    private static long reconnectAt;
    private static GuiButton reconnectButton;

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiDisconnected) || !Modules.reconnect.enabled || lastServer == null) return;
        int y = event.gui.height / 2 + 30;
        for (GuiButton b : event.buttonList) y = Math.max(y, b.yPosition + 24);
        reconnectButton = new GuiButton(RECONNECT_ID, event.gui.width / 2 - 100, y, Lang.t("再接続"));
        event.buttonList.add(reconnectButton);
        reconnectAt = Modules.reconnectAuto.get()
                ? System.currentTimeMillis() + (long) (Modules.reconnectDelay.get() * 1000f) : 0;
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.gui instanceof GuiDisconnected && event.button.id == RECONNECT_ID) {
            reconnect();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.gui instanceof GuiDisconnected) || reconnectButton == null) return;
        if (reconnectAt > 0) {
            long left = reconnectAt - System.currentTimeMillis();
            if (left <= 0) {
                reconnectAt = 0;
                reconnect();
                return;
            }
            reconnectButton.displayString = Lang.t("再接続 (") + (left / 1000 + 1) + ")";
        }
    }

    private static void reconnect() {
        if (lastServer == null) return;
        reconnectAt = 0;
        FMLClientHandler.instance().connectToServer(new GuiMultiplayer(new GuiMainMenu()), lastServer);
    }

    // ============================================================ Death coordinates / GUI changes

    private static boolean deathShown;

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(event.gui instanceof GuiDisconnected)) reconnectButton = null;
        if (event.gui instanceof GuiGameOver) {
            if (!deathShown && Modules.deathCoords.enabled && mc.thePlayer != null) {
                deathShown = true;
                int x = MathHelper.floor_double(mc.thePlayer.posX), y = MathHelper.floor_double(mc.thePlayer.posY),
                        z = MathHelper.floor_double(mc.thePlayer.posZ);
                String dim = mc.thePlayer.dimension == -1 ? Lang.t("ネザー") : mc.thePlayer.dimension == 1 ? Lang.t("エンド") : Lang.t("オーバーワールド");
                mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] "
                        + EnumChatFormatting.GRAY + Lang.t("死んだ場所: ") + EnumChatFormatting.WHITE + x + ", " + y + ", " + z
                        + EnumChatFormatting.GRAY + " (" + dim + ")"));
            }
        } else {
            deathShown = false;
        }
    }

    // ============================================================ Screenshot to clipboard

    private static boolean shotKeyWasDown;
    private static long shotCheckAt;
    private static long shotStarted;

    private static void screenshotTick(Minecraft mc) {
        // the background saver copies the image itself
        if (!Modules.screenshot.enabled || !Modules.screenshotCopy.get() || AsyncScreenshot.active()) return;
        int key = mc.gameSettings.keyBindScreenshot.getKeyCode();
        boolean down = key > 0 && Keyboard.isKeyDown(key);
        if (down && !shotKeyWasDown) {
            shotStarted = System.currentTimeMillis();
            shotCheckAt = shotStarted + 400L;
        }
        shotKeyWasDown = down;
        if (shotCheckAt == 0 || System.currentTimeMillis() < shotCheckAt) return;
        shotCheckAt = 0;

        final File dir = new File(mc.mcDataDir, "screenshots");
        final long since = shotStarted - 2000L;
        Thread t = new Thread(() -> {
            File newest = null;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".png") && f.lastModified() >= since
                            && (newest == null || f.lastModified() > newest.lastModified())) newest = f;
                }
            }
            if (newest == null) return;
            try {
                final Image img = ImageIO.read(newest);
                if (img == null) return;
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                    @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
                    @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
                    @Override public @NotNull Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                        if (!DataFlavor.imageFlavor.equals(f)) throw new UnsupportedFlavorException(f);
                        return img;
                    }
                }, null);
                final File shot = newest;
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    ChatComponentText msg = new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] "
                            + EnumChatFormatting.GRAY + Lang.t("スクショをクリップボードにコピーしました "));
                    ChatComponentText open = new ChatComponentText(EnumChatFormatting.AQUA + Lang.t("[フォルダを開く]"));
                    open.setChatStyle(new ChatStyle().setChatClickEvent(
                            new ClickEvent(ClickEvent.Action.OPEN_FILE, shot.getParentFile().getAbsolutePath())));
                    msg.appendSibling(open);
                    Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(msg);
                });
            } catch (Throwable ignored) {
            }
        }, "FzClient-Screenshot");
        t.setDaemon(true);
        t.start();
    }

    // ============================================================ Particles

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Modules.particles.enabled || event.entityPlayer != mc.thePlayer || !(event.target instanceof EntityLivingBase)) return;
        int extra = Math.round(Modules.particleMult.get()) - 1;
        if (Modules.particleSharp.get()) {
            for (int i = 0; i < Math.max(1, extra + 1); i++) mc.effectRenderer.emitParticleAtEntity(event.target, EnumParticleTypes.CRIT_MAGIC);
        }
        if (Modules.particleCrit.get()) {
            for (int i = 0; i < Math.max(1, extra + 1); i++) mc.effectRenderer.emitParticleAtEntity(event.target, EnumParticleTypes.CRIT);
        }
    }

    // ============================================================ Overlays: low HP, pumpkin, boss bar

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!Modules.overlays.enabled) return;
        if (event.type == RenderGameOverlayEvent.ElementType.HELMET && Modules.hidePumpkin.get()) event.setCanceled(true);
        if (event.type == RenderGameOverlayEvent.ElementType.BOSSHEALTH && Modules.hideBossbar.get()) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !Modules.lowHp.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.capabilities.isCreativeMode || mc.thePlayer.isSpectator()) return;
        float hp = mc.thePlayer.getHealth();
        float limit = Modules.lowHpHearts.get() * 2f;
        if (hp <= 0 || hp > limit) return;
        float strength = 1f - hp / (limit + 0.001f) * 0.6f;
        if (Modules.lowHpPulse.get()) strength *= 0.65f + 0.35f * MathHelper.sin(System.currentTimeMillis() / 160f);
        vignette(event.resolution, strength);
    }

    private static void vignette(ScaledResolution sr, float strength) {
        int w = sr.getScaledWidth(), h = sr.getScaledHeight();
        float edge = Math.min(w, h) * 0.22f;
        int a = Math.max(0, Math.min(255, (int) (strength * 150)));
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer r = t.getWorldRenderer();
        r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // top, bottom, left, right: red at the edge fading to clear
        quad(r, 0, 0, w, edge, a, a, 0, 0);
        quad(r, 0, h - edge, w, h, 0, 0, a, a);
        quadH(r, 0, edge, h, a, 0);
        quadH(r, w - edge, w, h, 0, a);
        t.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor(); // per-vertex colours leave the GL colour changed behind GlStateManager's back
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Vertical gradient: alpha at top-left, top-right, bottom-right, bottom-left. */
    private static void quad(WorldRenderer r, float x1, float y1, float x2, float y2, int aTL, int aTR, int aBR, int aBL) {
        r.pos(x1, y1, 0).color(220, 20, 30, aTL).endVertex();
        r.pos(x1, y2, 0).color(220, 20, 30, aBL).endVertex();
        r.pos(x2, y2, 0).color(220, 20, 30, aBR).endVertex();
        r.pos(x2, y1, 0).color(220, 20, 30, aTR).endVertex();
    }

    /** Horizontal gradient: alpha on the left and right side. */
    private static void quadH(WorldRenderer r, float x1, float x2, float y2, int aL, int aR) {
        quad(r, x1, (float) 0, x2, y2, aL, aR, aR, aL);
    }

    // ============================================================ Ctrl+click chat copy

    private static Field drawnLinesField, chatLinesField, scrollField;

    @SubscribeEvent
    public void onChatMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!(event.gui instanceof GuiChat) || !Modules.chatTweaksEnabled() || !Modules.chatCopy.get()) return;
        if (!Mouse.getEventButtonState() || Mouse.getEventButton() != 0 || !GuiScreen.isCtrlKeyDown()) return;
        String text = lineUnderMouse();
        if (text == null) return;
        GuiScreen.setClipboardString(text);
        Minecraft.getMinecraft().ingameGUI.setRecordPlaying(EnumChatFormatting.AQUA + Lang.t("チャットをコピーしました"), false);
        event.setCanceled(true);
    }

    @SuppressWarnings("unchecked")
    private static String lineUnderMouse() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            GuiNewChat chat = mc.ingameGUI.getChatGUI();
            if (chat instanceof dev.fzkc.fzhud.chat.FzChat && dev.fzkc.fzhud.chat.FzChat.active()) {
                // tabs / heads / line height move the lines: ask the chat box itself
                return ((dev.fzkc.fzhud.chat.FzChat) chat).messageAt(Mouse.getX(), Mouse.getY());
            }
            if (drawnLinesField == null) {
                drawnLinesField = field("drawnChatLines", "field_146253_i");
                chatLinesField = field("chatLines", "field_146252_h");
                scrollField = field("scrollPos", "field_146250_j");
            }
            List<ChatLine> drawn = (List<ChatLine>) drawnLinesField.get(chat);
            List<ChatLine> all = (List<ChatLine>) chatLinesField.get(chat);
            int scroll = scrollField.getInt(chat);

            ScaledResolution sr = new ScaledResolution(mc);
            int sf = sr.getScaleFactor();
            float cs = chat.getChatScale();
            int mx = MathHelper.floor_float(((float) Mouse.getX() / sf - 3) / cs);
            int my = MathHelper.floor_float(((float) Mouse.getY() / sf - 27) / cs);
            if (mx < 0 || my < 0) return null;
            int lines = Math.min(chat.getLineCount(), drawn.size());
            if (mx > MathHelper.floor_float(chat.getChatWidth() / cs) || my >= mc.fontRendererObj.FONT_HEIGHT * lines + lines) return null;
            int idx = my / mc.fontRendererObj.FONT_HEIGHT + scroll;
            if (idx < 0 || idx >= drawn.size()) return null;
            ChatLine piece = drawn.get(idx);
            // the full (unwrapped) message has the same counter
            for (ChatLine l : all) {
                if (l.getUpdatedCounter() == piece.getUpdatedCounter() && l.getChatLineID() == piece.getChatLineID()) {
                    return EnumChatFormatting.getTextWithoutFormattingCodes(l.getChatComponent().getUnformattedText());
                }
            }
            return EnumChatFormatting.getTextWithoutFormattingCodes(piece.getChatComponent().getUnformattedText());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(String... names) throws NoSuchFieldException {
        for (String n : names) {
            try {
                Field f = GuiNewChat.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(names[0]);
    }

    // ============================================================ tick

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        ServerData sd = mc.getCurrentServerData();
        if (sd != null && mc.theWorld != null) lastServer = sd;
        screenshotTick(mc);
    }
}
