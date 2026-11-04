package dev.fzkc.fzhud.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cover art for the Spotify HUD. Spotify's window only tells us "Artist - Song", so the picture is looked up by
 * name in Apple's free music search (no account / key needed), on a background thread, once per song.
 */
public final class AlbumArt {
    private AlbumArt() {}

    private static volatile String wantKey = "";
    private static String loadedKey = "";
    private static ResourceLocation texture;
    /** One texture for every cover, refilled in place. Freeing and re-creating textures let OpenGL hand the old
     *  number to another texture (e.g. a player's skin), which then showed the cover on the player. */
    private static DynamicTexture dyn;
    private static boolean hasImage;
    /** Small memory of recent covers so skipping back and forth doesn't search again. */
    private static final Map<String, BufferedImage> cache = new LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > 24;
        }
    };
    private static final Map<String, Boolean> notFound = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 64;
        }
    };

    /** Song lengths found by the search (for the progress bar when Windows can't tell us). */
    private static final Map<String, Long> lengths = new java.util.concurrent.ConcurrentHashMap<>();

    /** Length of the current song in ms, or 0 if unknown. */
    public static long currentLengthMs() {
        Long l = lengths.get(wantKey);
        return l == null ? 0 : l;
    }

    /** The cover of the current song, or null while loading / when none was found. */
    public static ResourceLocation current() {
        return hasImage && wantKey.equals(loadedKey) ? texture : null;
    }

    /** The exact strings of the last call (the HUD calls this every frame with the same ones). */
    private static String lastArtist, lastTitle;

    /** Called with the song now playing; starts a lookup when it changed. */
    public static void want(String artist, String title) {
        if (artist.equals(lastArtist) && title.equals(lastTitle)) return; // same as last frame: nothing to do
        lastArtist = artist;
        lastTitle = title;
        if (title == null || artist.isEmpty() || title.isEmpty()) return;
        final String key = artist + "\n" + title;
        if (key.equals(wantKey)) return;
        wantKey = key;
        BufferedImage hit;
        boolean missing;
        synchronized (cache) {
            hit = cache.get(key);
            missing = notFound.containsKey(key);
        }
        if (hit != null) {
            upload(key, hit);
            return;
        }
        if (missing) return;
        Thread t = createT(artist, title, key);
        t.start();
    }

    private static @NotNull Thread createT(String artist, String title, String key) {
        Thread t = new Thread(() -> {
            BufferedImage img = null;
            try {
                img = lookup(artist, title, key);
            } catch (Throwable ignored) {
            }
            if (img == null) {
                synchronized (cache) {
                    notFound.put(key, true);
                }
                return;
            }
            synchronized (cache) {
                cache.put(key, img);
            }
            final BufferedImage done = img;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (key.equals(wantKey)) upload(key, done);
            });
        }, "FzClient-AlbumArt");
        t.setDaemon(true);
        return t;
    }

    private static void upload(String key, BufferedImage img) {
        if (img.getWidth() != 64 || img.getHeight() != 64) return;
        if (dyn == null) {
            dyn = new DynamicTexture(64, 64);
            texture = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_album", dyn);
        }
        img.getRGB(0, 0, 64, 64, dyn.getTextureData(), 0, 64);
        dyn.updateDynamicTexture();
        hasImage = true;
        loadedKey = key;
    }

    // ---------------------------------------------------------------- search

    /** "Song (Remastered 2011)" / "Song - Live" -> "Song" for a cleaner search. */
    private static String clean(String title) {
        String t = title.replaceAll("\\s*[(\\[].*?[)\\]]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        return t.trim().isEmpty() ? title : t.trim();
    }

    private static BufferedImage lookup(String artist, String title, String key) throws Exception {
        String song = clean(title);
        String first = artist.split(",")[0].trim();
        for (String country : new String[]{"JP", "US"}) {
            String q = URLEncoder.encode(first + " " + song, "UTF-8");
            String json = get("https://itunes.apple.com/search?media=music&entity=song&limit=8&country=" + country + "&term=" + q);
            if (json == null) continue;
            String art = best(json, first, song, key);
            if (art != null) {
                byte[] data = getBytes(art.replace("100x100bb", "200x200bb"));
                if (data != null) {
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) return square(img);
                }
            }
        }
        return null;
    }

    /** Picks the result whose song and artist match best. */
    private static String best(String json, String artist, String song, String key) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("results")) return null;
        JsonArray results = root.getAsJsonObject().getAsJsonArray("results");
        String a = artist.toLowerCase(Locale.ROOT), s = song.toLowerCase(Locale.ROOT);
        String pick = null;
        long length = 0;
        int bestScore = 0;
        for (JsonElement e : results) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("artworkUrl100")) continue;
            String ra = o.has("artistName") ? o.get("artistName").getAsString().toLowerCase(Locale.ROOT) : "";
            String rs = o.has("trackName") ? o.get("trackName").getAsString().toLowerCase(Locale.ROOT) : "";
            int score = 0;
            if (ra.equals(a)) score += 4;
            else if (ra.contains(a) || a.contains(ra)) score += 2;
            if (rs.equals(s)) score += 4;
            else if (rs.contains(s) || s.contains(rs)) score += 2;
            if (score > bestScore) {
                bestScore = score;
                pick = o.get("artworkUrl100").getAsString();
                length = o.has("trackTimeMillis") ? o.get("trackTimeMillis").getAsLong() : 0;
            }
        }
        if (bestScore < 4) return null; // at least the artist or the song must really match
        if (length > 0) lengths.put(key, length);
        return pick;
    }

    private static BufferedImage square(BufferedImage img) {
        int s = Math.min(img.getWidth(), img.getHeight());
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, 64, 64, (img.getWidth() - s) / 2, (img.getHeight() - s) / 2,
                (img.getWidth() + s) / 2, (img.getHeight() + s) / 2, null);
        g.dispose();
        return out;
    }

    private static String get(String url) throws Exception {
        byte[] b = getBytes(url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FzClient/1.0");
        if (c.getResponseCode() != 200) return null;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 << 20) return null;
            }
            return out.toByteArray();
        }
    }
}
