package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * F2 without the freeze: vanilla encodes and writes the PNG on the game thread (a visible hitch, worse on big
 * screens). Here only the pixel read stays on the game thread; turning it into a PNG, saving it and (optionally)
 * copying it to the clipboard happen in the background.
 * After each shot a small preview slides in above the chat box for a few seconds.
 */
public final class AsyncScreenshot {
    private AsyncScreenshot() {}

    private static final SimpleDateFormat NAME = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
    /** Names handed out but maybe not written yet (two quick F2 presses in the same second). */
    private static final Set<String> reserved = new HashSet<>();

    public static boolean active() {
        return Modules.screenshot.enabled && Modules.screenshotAsync.get();
    }

    /** Replaces ScreenShotHelper.saveScreenshot(gameDir, width, height, framebuffer). */
    public static IChatComponent save(File gameDir, int width, int height, Framebuffer fb) {
        if (!active()) return ScreenShotHelper.saveScreenshot(gameDir, width, height, fb);
        try {
            // ---- game thread: copy the pixels out of OpenGL (fast)
            final boolean useFb = OpenGlHelper.isFramebufferEnabled();
            final int texW = useFb ? fb.framebufferTextureWidth : width;
            final int texH = useFb ? fb.framebufferTextureHeight : height;
            final int outW = useFb ? fb.framebufferWidth : width;
            final int outH = useFb ? fb.framebufferHeight : height;
            final IntBuffer buf = BufferUtils.createIntBuffer(texW * texH);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            if (useFb) {
                GlStateManager.bindTexture(fb.framebufferTexture);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            } else {
                GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            }

            final File dir = new File(gameDir, "screenshots");
            dir.mkdirs();
            final File file = nextFile(dir);
            final boolean copy = Modules.screenshotCopy.get();
            final boolean preview = Modules.screenshotPreview.get();
            if (preview) PreviewHud.ensureRegistered();

            // ---- background: flip, preview, encode, write, copy
            Thread t = new Thread(() -> {
                try {
                    int[] px = new int[texW * texH];
                    buf.get(px);
                    BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
                    // OpenGL rows go bottom-up; the visible part sits at the bottom of the texture
                    int[] row = new int[outW];
                    for (int y = 0; y < outH; y++) {
                        int src = (outH - 1 - y) * texW;
                        System.arraycopy(px, src, row, 0, outW);
                        img.setRGB(0, y, outW, 1, row, 0, outW);
                    }
                    // the preview first, so it shows up before the (slower) PNG is written
                    if (preview) {
                        try {
                            PreviewHud.queue(img);
                        } catch (Throwable ignored) {
                        }
                    }
                    ImageIO.write(img, "png", file);
                    if (copy) copyToClipboard(img, file);
                } catch (Throwable e) {
                    Minecraft.getMinecraft().addScheduledTask(() -> chat(new ChatComponentTranslation(
                            "screenshot.failure", e.getMessage())));
                } finally {
                    synchronized (reserved) {
                        reserved.remove(file.getName());
                    }
                }
            }, "FzClient-Screenshot");
            t.setDaemon(true);
            t.start();

            // same message as vanilla (clickable file name), shown right away
            IChatComponent name = new ChatComponentText(file.getName());
            name.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()));
            name.getChatStyle().setUnderlined(true);
            return new ChatComponentTranslation("screenshot.success", name);
        } catch (Throwable e) {
            // anything odd (driver, OptiFine...) -> vanilla way
            return ScreenShotHelper.saveScreenshot(gameDir, width, height, fb);
        }
    }

    private static File nextFile(File dir) {
        String base = NAME.format(new Date());
        synchronized (reserved) {
            for (int i = 1; ; i++) {
                File f = new File(dir, base + (i == 1 ? "" : "_" + i) + ".png");
                if (!f.exists() && !reserved.contains(f.getName())) {
                    reserved.add(f.getName());
                    return f;
                }
            }
        }
    }

    private static void copyToClipboard(final Image img, final File file) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
                @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
                @Override public @NotNull Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (!DataFlavor.imageFlavor.equals(f)) throw new UnsupportedFlavorException(f);
                    return img;
                }
            }, null);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ChatComponentText msg = new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] "
                        + EnumChatFormatting.GRAY + Lang.t("スクショをクリップボードにコピーしました "));
                ChatComponentText open = new ChatComponentText(EnumChatFormatting.AQUA + Lang.t("[フォルダを開く]"));
                open.setChatStyle(new ChatStyle().setChatClickEvent(
                        new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getParentFile().getAbsolutePath())));
                msg.appendSibling(open);
                chat(msg);
            });
        } catch (Throwable ignored) {
        }
    }

    private static void chat(IChatComponent c) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(c);
    }

    // ---------------------------------------------------------------- preview above the chat

    /**
     * The little picture that slides in above the chat box after a screenshot. One texture is refilled for every
     * shot (re-creating textures can hand an old texture number to something else, e.g. a player's skin).
     */
    public static final class PreviewHud {
        /** Largest size of the preview on screen, in GUI pixels. */
        private static final int PW = 192, PH = 108;
        /** The texture holds 4x as many pixels, so it stays sharp at any GUI scale (and on 4K screens). */
        private static final int SS = 4;
        private static final int TEXW = PW * SS, TEXH = PH * SS;
        private static boolean registered;
        private static DynamicTexture dyn;
        private static ResourceLocation texture;
        /** System.nanoTime() when the current preview appeared (0 = nothing showing). */
        private static long shownAt;
        /** Size of the picture on screen (GUI pixels) and inside the texture (texture pixels). */
        private static int imgW, imgH, texImgW, texImgH;

        static void ensureRegistered() {
            if (registered) return;
            registered = true;
            MinecraftForge.EVENT_BUS.register(new PreviewHud());
        }

        /** Background thread: shrink the shot, then hand it to the game thread. */
        static void queue(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            if (w < 1 || h < 1) return;
            float s = Math.min(PW / (float) w, PH / (float) h);
            final int tw = Math.max(1, Math.round(w * s)), th = Math.max(1, Math.round(h * s));
            // the texture version: SS times bigger (but never bigger than the shot itself)
            final int bw = Math.max(1, Math.min(w, tw * SS)), bh = Math.max(1, Math.min(h, th * SS));
            // halve a few times first: shrinking 1920 -> 768 in looks jagged
            BufferedImage cur = img;
            int cw = w, ch = h;
            while (cw / 2 >= bw && ch / 2 >= bh) {
                cw /= 2;
                ch /= 2;
                BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = next.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cur, 0, 0, cw, ch, null);
                g.dispose();
                cur = next;
            }
            final BufferedImage canvas = new BufferedImage(TEXW, TEXH, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(cur, 0, 0, bw, bh, null);
            g.dispose();
            Minecraft.getMinecraft().addScheduledTask(() -> show(canvas, tw, th, bw, bh));
        }

        /** Game thread: put the picture into the texture and start the timer. */
        private static void show(BufferedImage canvas, int tw, int th, int bw, int bh) {
            if (dyn == null) {
                dyn = new DynamicTexture(TEXW, TEXH);
                texture = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_shot", dyn);
            }
            canvas.getRGB(0, 0, TEXW, TEXH, dyn.getTextureData(), 0, TEXW);
            dyn.updateDynamicTexture();
            // dynamic textures are "nearest" (blocky) by default: smooth them when they are shown smaller
            GlStateManager.bindTexture(dyn.getGlTextureId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            imgW = tw;
            imgH = th;
            texImgW = bw;
            texImgH = bh;
            shownAt = System.nanoTime();
        }

        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Post event) {
            if (event.type != RenderGameOverlayEvent.ElementType.ALL || shownAt == 0 || texture == null) return;
            float dur = Math.max(1f, Modules.screenshotPreviewTime.get());
            float t = (System.nanoTime() - shownAt) / 1.0e9f;
            if (t >= dur) {
                shownAt = 0;
                return;
            }
            float in = Math.min(1f, t / 0.25f), out = Math.min(1f, (dur - t) / 0.4f);
            float a = in * out;
            if (a <= 0.01f) return;

            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution sr = event.resolution;
            // just above the chat box (the chat is about 30px above the bottom edge and as tall as it is when open)
            float chatH = (mc.gameSettings.chatHeightFocused * 160f + 20f) * mc.gameSettings.chatScale;
            int x = 4;
            int y = (int) (sr.getScaledHeight() - 30 - chatH - 4 - imgH + (1f - in) * 6f);
            if (y < 4) y = 4;

            // frame
            Gui.drawRect(x - 2, y - 2, x + imgW + 2, y + imgH + 2, ((int) (a * 0xB0) << 24) | 0x0A1114);
            Gui.drawRect(x - 2, y - 2, x + imgW + 2, y - 1, ((int) (a * 0xFF) << 24) | 0x5CE1E6);
            // the picture
            mc.getTextureManager().bindTexture(texture);
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, a);
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texImgW, texImgH, imgW, imgH, TEXW, TEXH);
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }
}
