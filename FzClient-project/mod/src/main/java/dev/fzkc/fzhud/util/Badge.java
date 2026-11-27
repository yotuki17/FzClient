package dev.fzkc.fzhud.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * The FzClient badge shown next to FzClient users (nametags, tab list).
 *
 * It is drawn at only 8 GUI pixels, so a large image squeezed down with plain linear filtering looks jagged and a
 * small image looks blurry. This loads the full image once with every smaller size made in advance (mipmaps), so
 * the graphics card picks the size that fits and it stays sharp at any GUI scale and distance.
 */
public final class Badge {
    private Badge() {}

    private static final ResourceLocation ICON = new ResourceLocation("fzclient", "textures/tab_icon.png");
    private static final int SIZE = 256;
    /** At these sizes and below the thin ring would fade to almost nothing, so it is made a little stronger. */
    private static final int BOOST_BELOW = 64;
    private static final float BOOST = 1.6f;

    private static int texture = -1;   // -1 = not loaded yet, 0 = failed (the plain texture is used instead)

    /** Draws the badge as a size x size square at the current origin, with the current color and blending. */
    public static void draw(float size) {
        bind();
        GlStateManager.pushMatrix();
        GlStateManager.scale(size / SIZE, size / SIZE, 1f);
        Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, SIZE, SIZE, SIZE, SIZE);
        GlStateManager.popMatrix();
    }

    private static void bind() {
        if (texture < 0) texture = load();
        if (texture > 0) {
            GlStateManager.bindTexture(texture);
        } else {
            Minecraft.getMinecraft().getTextureManager().bindTexture(ICON);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }
    }

    private static int load() {
        try (InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(ICON).getInputStream()) {
            BufferedImage img = TextureUtil.readBufferedImage(in);
            int w = img.getWidth(), h = img.getHeight();
            if (w != h || Integer.bitCount(w) != 1) throw new IllegalStateException("badge must be a square power of two");
            // straight (not premultiplied) RGBA as floats, the full size first
            float[] cur = new float[w * w * 4];
            int[] argb = img.getRGB(0, 0, w, w, null, 0, w);
            for (int i = 0; i < argb.length; i++) {
                int c = argb[i];
                cur[i * 4] = (c >> 16 & 255) / 255f;
                cur[i * 4 + 1] = (c >> 8 & 255) / 255f;
                cur[i * 4 + 2] = (c & 255) / 255f;
                cur[i * 4 + 3] = (c >>> 24) / 255f;
            }
            int id = TextureUtil.glGenTextures();
            GlStateManager.bindTexture(id);
            int level = 0;
            for (int s = w; ; s /= 2, level++) {
                upload(level, s, cur, s <= BOOST_BELOW ? BOOST : 1f);
                if (s == 1) break;
                cur = half(cur, s);
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, level);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            return id;
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger("FzClient").warn("FzClient badge: " + t);
            return 0;
        }
    }

    /** Half the size, each pixel the average of four (colors weighted by how opaque they are). */
    private static float[] half(float[] src, int s) {
        int n = s / 2;
        float[] out = new float[n * n * 4];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float r = 0, g = 0, b = 0, a = 0;
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                int k = ((y * 2 + dy) * s + x * 2 + dx) * 4;
                float al = src[k + 3];
                r += src[k] * al;
                g += src[k + 1] * al;
                b += src[k + 2] * al;
                a += al;
            }
            int k = (y * n + x) * 4;
            if (a > 0) {
                out[k] = r / a;
                out[k + 1] = g / a;
                out[k + 2] = b / a;
            } else {
                out[k] = out[k + 1] = out[k + 2] = 1f;
            }
            out[k + 3] = a / 4f;
        }
        return out;
    }

    private static void upload(int level, int s, float[] px, float boost) {
        ByteBuffer buf = BufferUtils.createByteBuffer(s * s * 4);
        for (int i = 0; i < s * s; i++) {
            buf.put((byte) Math.round(px[i * 4] * 255f));
            buf.put((byte) Math.round(px[i * 4 + 1] * 255f));
            buf.put((byte) Math.round(px[i * 4 + 2] * 255f));
            buf.put((byte) Math.round(Math.min(1f, px[i * 4 + 3] * boost) * 255f));
        }
        buf.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, s, s, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }
}
