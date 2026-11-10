package dev.fzkc.fzhud.cosmetic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * The axolotl and parrot textures of today's Minecraft for the shoulder pets. Minecraft 1.8.9 has no such mobs and
 * FzClient can't ship Mojang's pictures, so each player's game fetches them once from Mojang's own download server:
 * only these few small files, read straight out of the official 1.21.1 game jar with range requests (about 2 MB in
 * all, most of it the jar's table of contents), then kept in .minecraft/fzclient/mobs. Until they are there (or
 * offline) the pets use FzClient's own painted look.
 */
final class MobTextures {
    private MobTextures() {}

    private static final String JAR = "https://piston-data.mojang.com/v1/objects/30c73b1c5da787909b2f73340419fdf13b9def88/client.jar";
    private static final long JAR_SIZE = 26836906L;
    static final String[] NAMES = {
            "axolotl/axolotl_lucy", "axolotl/axolotl_gold", "axolotl/axolotl_blue", "axolotl/axolotl_wild", "axolotl/axolotl_cyan",
            "parrot/parrot_red_blue", "parrot/parrot_blue", "parrot/parrot_green", "parrot/parrot_yellow_blue", "parrot/parrot_grey"};

    private static final Map<String, ResourceLocation> LOADED = new HashMap<String, ResourceLocation>();
    private static volatile boolean started, ready;

    private static File dir() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "fzclient"), "mobs");
    }

    private static File file(String name) {
        return new File(dir(), name.substring(name.indexOf('/') + 1) + ".png");
    }

    /** The texture (e.g. "axolotl/axolotl_lucy"), or null while it isn't here yet. Render thread only. */
    static ResourceLocation get(String name) {
        if (!started) start();
        if (!ready) return null;
        ResourceLocation r = LOADED.get(name);
        if (r == null && !LOADED.containsKey(name)) {
            r = load(name);
            LOADED.put(name, r);
        }
        return r;
    }

    private static ResourceLocation load(String name) {
        try {
            BufferedImage img = ImageIO.read(file(name));
            if (img == null) return null;
            DynamicTexture t = new DynamicTexture(img);
            return Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_mob_" + name.replace('/', '_'), t);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean allCached() {
        for (String n : NAMES) if (!file(n).isFile()) return false;
        return true;
    }

    private static void start() {
        started = true;
        if (allCached()) {
            ready = true;
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    fetch();
                    ready = true;
                } catch (Throwable ignored) {
                    // offline or blocked: keep the painted look (tried again next launch)
                }
            }
        }, "FzClient-MobTextures");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] range(long from, long to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(JAR).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        c.setRequestProperty("User-Agent", "FzClient");
        if (c.getResponseCode() != 206) throw new IllegalStateException("no range support: " + c.getResponseCode());
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) (to - from + 1));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Reads the jar's table of contents from its end, then just the wanted entries. */
    private static void fetch() throws Exception {
        byte[] tail = range(JAR_SIZE - 65536, JAR_SIZE - 1);
        int e = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                e = i;
                break;
            }
        }
        if (e < 0) throw new IllegalStateException("no zip end");
        ByteBuffer t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        long cdSize = t.getInt(e + 12) & 0xFFFFFFFFL, cdOff = t.getInt(e + 16) & 0xFFFFFFFFL;
        if (cdSize <= 0 || cdSize > 8_000_000L) throw new IllegalStateException("odd zip");
        ByteBuffer cd = ByteBuffer.wrap(range(cdOff, cdOff + cdSize - 1)).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, long[]> want = new HashMap<String, long[]>();
        for (String n : NAMES) want.put("assets/minecraft/textures/entity/" + n + ".png", null);
        int p = 0;
        while (p + 46 <= cd.limit() && cd.getInt(p) == 0x02014b50) {
            int method = cd.getShort(p + 10) & 0xFFFF;
            long csize = cd.getInt(p + 20) & 0xFFFFFFFFL, usize = cd.getInt(p + 24) & 0xFFFFFFFFL;
            int nl = cd.getShort(p + 28) & 0xFFFF, xl = cd.getShort(p + 30) & 0xFFFF, cl = cd.getShort(p + 32) & 0xFFFF;
            long loff = cd.getInt(p + 42) & 0xFFFFFFFFL;
            byte[] nb = new byte[nl];
            for (int i = 0; i < nl; i++) nb[i] = cd.get(p + 46 + i);
            String name = new String(nb, "UTF-8");
            if (want.containsKey(name)) want.put(name, new long[]{method, csize, usize, loff});
            p += 46 + nl + xl + cl;
        }
        dir().mkdirs();
        for (String n : NAMES) {
            long[] w = want.get("assets/minecraft/textures/entity/" + n + ".png");
            if (w == null || w[1] > 1_000_000L || w[2] > 1_000_000L) throw new IllegalStateException("missing " + n);
            byte[] h = range(w[3], w[3] + 30 + 1024 + w[1]);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            if (hb.getInt(0) != 0x04034b50) throw new IllegalStateException("bad entry " + n);
            int start = 30 + (hb.getShort(26) & 0xFFFF) + (hb.getShort(28) & 0xFFFF);
            byte[] data = new byte[(int) w[1]];
            System.arraycopy(h, start, data, 0, data.length);
            byte[] png;
            if (w[0] == 8) {
                Inflater inf = new Inflater(true);
                inf.setInput(data);
                png = new byte[(int) w[2]];
                int got = 0;
                while (got < png.length && !inf.finished()) {
                    int k = inf.inflate(png, got, png.length - got);
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    got += k;
                }
                inf.end();
                if (got != png.length) throw new IllegalStateException("short " + n);
            } else {
                png = data;
            }
            // must really be a picture before it is kept
            if (ImageIO.read(new ByteArrayInputStream(png)) == null) throw new IllegalStateException("not a png " + n);
            File tmp = new File(dir(), file(n).getName() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try {
                o.write(png);
            } finally {
                o.close();
            }
            Files.move(tmp.toPath(), file(n).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
