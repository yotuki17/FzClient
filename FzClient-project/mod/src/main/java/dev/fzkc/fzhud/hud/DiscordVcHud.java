package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.integration.DiscordVoice;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord voice channel on screen, like Discord's own overlay: a round avatar per person (a green ring while they
 * talk), their name in a dark pill with a muted / deafened icon and a red LIVE tag while they stream, and the channel
 * name under the list. The connection itself lives in {@link DiscordVoice}; this only draws.
 *
 * The Discord application (its ID and secret) is FzClient's own: the ID comes from the FzClient server and the
 * secret never leaves it, so there is nothing to set up and no key to show.
 */
public class DiscordVcHud extends HudElement {
    /** DiscordVoice checks this to know whether to connect. */
    public static DiscordVcHud instance;

    private final ChoiceSetting avatarSize, nameMode, userMode;
    private final SliderSetting maxRows;
    private final BoolSetting showSelf, showHeader, showLive;

    private float lastW = 90, lastH = 30;

    public DiscordVcHud(float defX, float defY) {
        super("discordvc", "Discord VC", "VCの参加者を表示（Discordのオーバーレイ風）", defX, defY, LEFT, false);
        avatarSize = add(new ChoiceSetting("avatar", "アバターのサイズ", 2, "小", "中", "大"));
        nameMode = add(new ChoiceSetting("names", "表示名", 0, "常に表示", "話している時だけ", "表示しない"));
        userMode = add(new ChoiceSetting("users", "ユーザーを表示", 0, "常に表示", "話している人だけ"));
        maxRows = add(new SliderSetting("maxusers", "表示するユーザー数の上限（0 = オフ）", 0, 25, 1, 8, ""));
        showSelf = add(new BoolSetting("self", "自分も表示", true));
        showHeader = add(new BoolSetting("header", "チャンネル名を表示", true));
        showLive = add(new BoolSetting("live", "配信中の人に LIVE を表示", true));
        instance = this;
    }

    /** The people to list right now (filters applied). */
    /** Dev check (-Dfzclient.dvctest): the sample list is shown in the game. */
    private static final boolean TEST = System.getProperty("fzclient.dvctest") != null;

    private List<DiscordVoice.Member> rows(boolean preview) {
        if (TEST) preview = true;
        List<DiscordVoice.Member> all = DiscordVoice.snapshot();
        if (all.isEmpty() && preview) {
            // a sample for the HUD editor
            DiscordVoice.Member a = new DiscordVoice.Member("1", "Alice");
            a.speaking = true;
            DiscordVoice.Member b = new DiscordVoice.Member("2", "Bob");
            b.mute = true;
            DiscordVoice.Member c = new DiscordVoice.Member("3", "Charlie");
            c.stream = true;
            DiscordVoice.Member d = new DiscordVoice.Member("4", "Dave");
            d.deaf = true;
            all.add(a);
            all.add(b);
            all.add(c);
            all.add(d);
        }
        String me = DiscordVoice.selfId();
        boolean self = showSelf.get(), talk = userMode.get() == 1;
        List<DiscordVoice.Member> out = new ArrayList<DiscordVoice.Member>();
        for (DiscordVoice.Member m : all) {
            if (!self && m.id.equals(me)) continue;
            if (talk && !m.speaking) continue;
            out.add(m);
        }
        return out;
    }

    /** Hidden while you are not in a voice channel (or nobody is left to list). */
    @Override
    public boolean active() {
        return TEST || DiscordVoice.connected() && DiscordVoice.channel() != null && !rows(false).isEmpty();
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    @Override
    public void draw(float alpha, boolean preview) {
        List<DiscordVoice.Member> list = rows(preview);
        if (list.isEmpty()) return;
        String channel = DiscordVoice.channel();
        if (channel == null) channel = preview || TEST ? "ボイスチャンネル" : "voice";
        float s = avatarSize.get() == 0 ? 12f : avatarSize.get() == 1 ? 16f : 20f;
        float gap = s * 0.25f, pillH = Math.min(s - 2f, 13f), icon = pillH - 4f;
        int max = (int) maxRows.get();
        int shown = max <= 0 ? list.size() : Math.min(list.size(), max);
        int hidden = list.size() - shown;
        int names = nameMode.get();

        float y = 0f, w = s;
        for (int i = 0; i < shown; i++) {
            DiscordVoice.Member m = list.get(i);
            float x = 0f;
            drawAvatar(m, x, y, s, alpha);
            x += s + 3f;
            boolean showName = names == 0 || names == 1 && m.speaking;
            boolean muteIcon = m.deaf || m.mute;
            boolean live = showLive.get() && m.stream;
            if (showName || muteIcon || live) {
                float pw = 5f + (showName ? R.width(m.name) : 0f) + (muteIcon ? icon + (showName ? 3f : 0f) : 0f)
                        + (live ? R.width("LIVE") * 0.75f + 6f + 3f : 0f) + 5f;
                float py = y + (s - pillH) / 2f;
                R.rounded(x, py, pw, pillH, pillH / 2f, R.alpha(0xE01E1F22, alpha));
                float tx = x + 5f;
                if (showName) {
                    R.text(m.name, tx, py + (pillH - 8f) / 2f + 0.5f, R.alpha(m.speaking ? 0xFFFFFFFF : 0xFFDBDEE1, alpha));
                    tx += R.width(m.name) + 3f;
                }
                if (muteIcon) {
                    Icon.draw(m.deaf ? Icon.DEAF : Icon.MUTE, tx, py + 2f, icon, R.alpha(0xFFDBDEE1, alpha));
                    tx += icon + 3f;
                }
                if (live) {
                    float lw = R.width("LIVE") * 0.75f + 6f;
                    R.rounded(tx, py + 1.5f, lw, pillH - 3f, 2f, R.alpha(0xFFED4245, alpha));
                    R.textScaled("LIVE", tx + 3f, py + (pillH - 6f) / 2f, 0.75f, R.alpha(0xFFFFFFFF, alpha));
                }
                x += pw;
            }
            w = Math.max(w, x);
            y += s + gap;
        }
        if (hidden > 0) {
            String more = "+" + hidden;
            float pw = R.width(more) + 10f;
            R.rounded(0f, y + (s - pillH) / 2f, pw, pillH, pillH / 2f, R.alpha(0xE01E1F22, alpha));
            R.text(more, 5f, y + (s - 8f) / 2f + 0.5f, R.alpha(0xFFDBDEE1, alpha));
            w = Math.max(w, pw);
            y += s + gap;
        }
        if (showHeader.get()) {
            float pw = R.width(channel) + 10f;
            R.rounded(0f, y, pw, 12f, 6f, R.alpha(0xE01E1F22, alpha));
            R.text(channel, 5f, y + 2.5f, R.alpha(0xFFFFFFFF, alpha));
            w = Math.max(w, pw);
            y += 12f;
        } else {
            y -= gap;
        }
        lastW = w;
        lastH = Math.max(1f, y);
    }

    private static void drawAvatar(DiscordVoice.Member m, float x, float y, float s, float alpha) {
        ResourceLocation tex = Avatars.get(m.id, m.avatar);
        if (tex != null) {
            quad(tex, x, y, s, R.alpha(0xFFFFFFFF, alpha));
        } else {
            R.rounded(x, y, s, s, s / 2f, R.alpha(0xFF5865F2, alpha));   // Discord's blurple while loading
        }
        if (m.speaking) R.roundedOutline(x - 0.5f, y - 0.5f, s + 1f, s + 1f, (s + 1f) / 2f, 1.5f, R.alpha(0xFF23A55A, alpha));
    }

    /** A texture as a square, tinted with argb. */
    static void quad(ResourceLocation tex, float x, float y, float size, int argb) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer r = t.getWorldRenderer();
        r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        r.pos(x, y + size, 0).tex(0, 1).endVertex();
        r.pos(x + size, y + size, 0).tex(1, 1).endVertex();
        r.pos(x + size, y, 0).tex(1, 0).endVertex();
        r.pos(x, y, 0).tex(0, 0).endVertex();
        t.draw();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ avatars

    /** Round Discord avatars, loaded once per person and picture (each keeps its own texture, never freed). */
    private static final class Avatars {
        private static final Map<String, ResourceLocation> TEX = new ConcurrentHashMap<String, ResourceLocation>();
        private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<String, Boolean>();

        /** The picture found last for each person (game thread only): no checks and no key building every frame. */
        private static final Map<String, Object[]> LAST = new java.util.HashMap<String, Object[]>();

        static ResourceLocation get(String id, String hash) {
            if (id == null) return null;
            Object[] last = LAST.get(id);
            if (last != null && (hash == null ? last[0] == null : hash.equals(last[0]))) return (ResourceLocation) last[1];
            ResourceLocation r = find(id, hash);
            if (r != null) LAST.put(id, new Object[]{hash, r});
            return r;
        }

        private static ResourceLocation find(String id, String hash) {
            if (!id.matches("\\d{1,20}")) return null;
            final String key = id + ":" + (hash == null ? "" : hash);
            ResourceLocation r = TEX.get(key);
            if (r != null || LOADING.putIfAbsent(key, Boolean.TRUE) != null || TEX.size() > 200) return r;
            final String url;
            if (hash != null && hash.matches("(a_)?[0-9a-f]{32}")) {
                url = "https://cdn.discordapp.com/avatars/" + id + "/" + hash + ".png?size=64";
            } else {
                long n;
                try { n = (Long.parseLong(id) >> 22) % 6; } catch (Exception e) { n = 0; }
                url = "https://cdn.discordapp.com/embed/avatars/" + n + ".png";
            }
            Thread t = new Thread(() -> {
                try {
                    final BufferedImage img = round(download(url));
                    Minecraft.getMinecraft().addScheduledTask(() -> {
                        DynamicTexture d = new DynamicTexture(64, 64);
                        img.getRGB(0, 0, 64, 64, d.getTextureData(), 0, 64);
                        d.updateDynamicTexture();
                        TEX.put(key, Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_dvc", d));
                    });
                } catch (Throwable ignored) {
                    // stays the blurple circle
                }
            }, "FzClient-DiscordAvatar");
            t.setDaemon(true);
            t.start();
            return null;
        }

        private static BufferedImage download(String url) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "FzClient (voice hud)");
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            try {
                BufferedImage img = ImageIO.read(in);
                if (img == null) throw new IllegalStateException("not an image");
                return img;
            } finally {
                in.close();
            }
        }

        /** 64x64, cut to a circle with a soft edge. */
        private static BufferedImage round(BufferedImage src) {
            BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, 64, 64, null);
            g.dispose();
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    double d = Math.hypot(x + 0.5 - 32, y + 0.5 - 32);
                    double a = Math.max(0, Math.min(1, 32 - d));
                    int p = out.getRGB(x, y);
                    int na = (int) (((p >>> 24) & 0xFF) * a);
                    out.setRGB(x, y, (na << 24) | (p & 0xFFFFFF));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ icons (drawn once in white, tinted when drawn)

    private static final class Icon {
        static final int MUTE = 0, DEAF = 1;
        private static ResourceLocation[] tex;

        static void draw(int which, float x, float y, float size, int argb) {
            if (tex == null) {
                tex = new ResourceLocation[2];
                for (int i = 0; i < 2; i++) {
                    BufferedImage img = paint(i);
                    DynamicTexture d = new DynamicTexture(64, 64);
                    img.getRGB(0, 0, 64, 64, d.getTextureData(), 0, 64);
                    d.updateDynamicTexture();
                    tex[i] = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_dvc_icon", d);
                }
            }
            quad(tex[which], x, y, size, argb);
        }

        private static BufferedImage paint(int which) {
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            BasicStroke line = new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            if (which == MUTE) {
                g.fill(new RoundRectangle2D.Float(22, 4, 20, 34, 20, 20));            // the microphone
                g.setStroke(line);
                g.draw(new Arc2D.Float(13, 14, 38, 34, 180, 180, Arc2D.OPEN));     // its holder
                g.draw(new Line2D.Float(32, 48, 32, 58));
                g.draw(new Line2D.Float(22, 58, 42, 58));
            } else {
                g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Float(8, 8, 48, 48, 0, 180, Arc2D.OPEN));          // the band
                g.draw(new Line2D.Float(11, 32, 11, 44));
                g.draw(new Line2D.Float(53, 32, 53, 44));
                g.fill(new RoundRectangle2D.Float(6, 34, 16, 24, 8, 8));            // the ear cups
                g.fill(new RoundRectangle2D.Float(42, 34, 16, 24, 8, 8));
            }
            // the slash: cut a gap, then the line
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(8, 8, 56, 56));
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.setStroke(line);
            g.draw(new Line2D.Float(8, 8, 56, 56));
            g.dispose();
            return img;
        }
    }
}
