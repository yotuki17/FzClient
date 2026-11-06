package dev.fzkc.fzhud.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The newest screenshots and clips, for the title menu. With the launcher they come from it (its gallery: clips too,
 * with the small pictures it makes of them); without it, the newest screenshots in the game's screenshots folder.
 * The pictures are read and made small on a thread, then made into textures on the game's thread.
 */
public final class RecentShots {
    private RecentShots() {}

    public static final int MAX = 3;
    private static final int TW = 160, TH = 90;

    public static final class Item {
        public final String file;
        public final boolean clip;
        public final long time;
        final String picture;   // a file to make the small picture from ('' = none)
        public ResourceLocation tex;

        Item(String file, boolean clip, long time, String picture) {
            this.file = file; this.clip = clip; this.time = time; this.picture = picture == null ? "" : picture;
        }
    }

    private static volatile List<Item> items = new ArrayList<Item>();
    /** Textures made so far, by picture file (+ its time), so a refresh doesn't make them again. */
    private static final Map<String, ResourceLocation> made = new HashMap<String, ResourceLocation>();
    private static int texNo;

    public static List<Item> items() { return items; }

    /** Asks again: the launcher when it is connected, else the screenshots folder (on a thread). */
    public static void refresh() {
        if (dev.fzkc.fzhud.feature.LauncherLink.connected()) {
            dev.fzkc.fzhud.feature.LauncherLink.askRecent(MAX);
            return;
        }
        Thread t = new Thread(() -> {
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "screenshots");
            File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png") || n.toLowerCase().endsWith(".jpg"));
            List<Item> list = new ArrayList<Item>();
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 0; i < files.length && i < MAX; i++) {
                    list.add(new Item(files[i].getName(), false, files[i].lastModified(), files[i].getAbsolutePath()));
                }
            }
            use(list);
        }, "FzClient-Recent");
        t.setDaemon(true);
        t.start();
    }

    /** What the launcher sent: { items: [{ file, kind, time, thumb }] } (on the game's thread). */
    public static void fromLauncher(JsonObject o) {
        List<Item> list = new ArrayList<Item>();
        if (o.has("items")) {
            JsonArray a = o.getAsJsonArray("items");
            for (int i = 0; i < a.size() && i < MAX; i++) {
                JsonObject j = a.get(i).getAsJsonObject();
                list.add(new Item(j.get("file").getAsString(), "clip".equals(j.get("kind").getAsString()),
                        j.has("time") ? j.get("time").getAsLong() : 0L, j.has("thumb") ? j.get("thumb").getAsString() : ""));
            }
        }
        Thread t = new Thread(() -> use(list), "FzClient-Recent");
        t.setDaemon(true);
        t.start();
    }

    /** On a thread: the small pictures that are not textures yet are read, then all of it goes to the game's thread. */
    private static void use(List<Item> list) {
        final Map<Item, BufferedImage> fresh = new HashMap<Item, BufferedImage>();
        for (Item it : list) {
            if (it.picture.isEmpty()) continue;
            String key = key(it);
            synchronized (made) {
                if (made.containsKey(key)) continue;
            }
            try {
                BufferedImage img = ImageIO.read(new File(it.picture));
                if (img != null) fresh.put(it, small(img));
            } catch (Throwable ignored) { }
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            for (Map.Entry<Item, BufferedImage> e : fresh.entrySet()) {
                ResourceLocation rl = Minecraft.getMinecraft().getTextureManager()
                        .getDynamicTextureLocation("fzclient_recent_" + (texNo++), new DynamicTexture(e.getValue()));
                synchronized (made) { made.put(key(e.getKey()), rl); }
            }
            java.util.Set<String> keep = new java.util.HashSet<String>();
            for (Item it : list) {
                String key = key(it);
                keep.add(key);
                synchronized (made) { it.tex = made.get(key); }
            }
            // pictures nothing shows any more: their textures go
            synchronized (made) {
                for (java.util.Iterator<Map.Entry<String, ResourceLocation>> i = made.entrySet().iterator(); i.hasNext(); ) {
                    Map.Entry<String, ResourceLocation> e = i.next();
                    if (!keep.contains(e.getKey())) {
                        Minecraft.getMinecraft().getTextureManager().deleteTexture(e.getValue());
                        i.remove();
                    }
                }
            }
            items = list;
        });
    }

    private static String key(Item it) {
        return it.picture + "|" + new File(it.picture).lastModified();
    }

    /** 160x90, filled (the middle of a picture of another shape). */
    private static BufferedImage small(BufferedImage src) {
        BufferedImage out = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        double s = Math.max(TW / (double) src.getWidth(), TH / (double) src.getHeight());
        int w = (int) Math.ceil(src.getWidth() * s), h = (int) Math.ceil(src.getHeight() * s);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // big screenshots: halve first (a single bilinear step from 2560 wide to 160 looks rough)
        BufferedImage cur = src;
        while (cur.getWidth() * s < cur.getWidth() / 2.0 && cur.getWidth() > TW * 2) {
            BufferedImage half = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null);
            hg.dispose();
            s *= 2;
            cur = half;
        }
        w = (int) Math.ceil(cur.getWidth() * s);
        h = (int) Math.ceil(cur.getHeight() * s);
        g.drawImage(cur, (TW - w) / 2, (TH - h) / 2, w, h, null);
        g.dispose();
        return out;
    }
}
