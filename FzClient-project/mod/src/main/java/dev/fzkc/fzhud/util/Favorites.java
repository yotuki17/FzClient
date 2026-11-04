package dev.fzkc.fzhud.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Favourite servers (starred in the multiplayer list, shown on the title screen).
 * Stored by address in config/fzclient-favorites.json; the name and icon come from the server list.
 */
public final class Favorites {
    private Favorites() {}

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static List<String> ips;

    /** Lower-cased addresses, remembered: the server list asks for every row on every frame. */
    private static final Map<String, String> KEYS = new HashMap<>();

    private static String key(String ip) {
        if (ip == null) return "";
        String k = KEYS.get(ip);
        if (k == null) {
            if (KEYS.size() > 512) KEYS.clear();
            k = ip.trim().toLowerCase(Locale.ROOT);
            KEYS.put(ip, k);
        }
        return k;
    }

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-favorites.json");
    }

    public static List<String> list() {
        if (ips != null) return ips;
        ips = new ArrayList<>();
        File f = file();
        if (!f.exists()) return ips;
        Reader r = null;
        try {
            r = new InputStreamReader(Files.newInputStream(f.toPath()), UTF8);
            JsonElement e = new JsonParser().parse(r);
            if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) ips.add(key(x.getAsString()));
        } catch (Exception ex) {
            System.err.println("[FzClient] Could not read favourites: " + ex);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return ips;
    }

    private static void save() {
        JsonArray arr = new JsonArray();
        for (String ip : list()) arr.add(new com.google.gson.JsonPrimitive(ip));
        File f = file();
        f.getParentFile().mkdirs();
        Writer w = null;
        try {
            w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), UTF8);
            w.write(arr.toString());
        } catch (Exception ex) {
            System.err.println("[FzClient] Could not save favourites: " + ex);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean is(ServerData sd) {
        return sd != null && list().contains(key(sd.serverIP));
    }

    /** Star / unstar. Returns the new state. */
    public static boolean toggle(ServerData sd) {
        if (sd == null || key(sd.serverIP).isEmpty()) return false;
        String k = key(sd.serverIP);
        boolean now;
        if (list().remove(k)) now = false;
        else {
            list().add(k);
            now = true;
        }
        save();
        return now;
    }

    /** The favourites as ServerData from the saved server list (in favourite order); missing ones are left out. */
    public static List<ServerData> servers() {
        List<ServerData> out = new ArrayList<>();
        if (list().isEmpty()) return out;
        ServerList sl = new ServerList(Minecraft.getMinecraft());
        sl.loadServerList();
        Map<String, ServerData> byIp = new HashMap<>();
        for (int i = 0; i < sl.countServers(); i++) {
            ServerData sd = sl.getServerData(i);
            if (!byIp.containsKey(key(sd.serverIP))) byIp.put(key(sd.serverIP), sd);
        }
        for (String ip : list()) {
            ServerData sd = byIp.get(ip);
            if (sd != null) out.add(sd);
        }
        return out;
    }

    // ------------------------------------------------------------------ server icons (64x64 PNG from the ping)

    private static final Map<String, ResourceLocation> icons = new HashMap<>();
    private static final Map<String, DynamicTexture> iconTex = new HashMap<>();
    private static final Map<String, String> iconData = new HashMap<>();
    private static final Map<String, Boolean> iconOk = new HashMap<>();

    /**
     * Texture of the server's icon, or null if it has none (or it can't be read).
     * Each server keeps one texture that is refilled when the icon changes (never freed and re-made, so the
     * texture number can't end up shared with another texture).
     */
    public static ResourceLocation icon(ServerData sd) {
        String data = sd.getBase64EncodedIconData();
        if (data == null || data.isEmpty()) return null;
        String k = key(sd.serverIP);
        if (data.equals(iconData.get(k))) return Boolean.TRUE.equals(iconOk.get(k)) ? icons.get(k) : null;
        iconData.put(k, data);
        iconOk.put(k, false);
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
            if (img == null || img.getWidth() != 64 || img.getHeight() != 64) return null;
            DynamicTexture t = iconTex.get(k);
            if (t == null) {
                t = new DynamicTexture(64, 64);
                iconTex.put(k, t);
                icons.put(k, Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_fav", t));
            }
            img.getRGB(0, 0, 64, 64, t.getTextureData(), 0, 64);
            t.updateDynamicTexture();
            iconOk.put(k, true);
            return icons.get(k);
        } catch (Exception e) {
            return null;
        }
    }
}
