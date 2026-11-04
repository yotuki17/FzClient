package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.Display;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * FzClient branding: window title and icon, the loading-screen logo and colours (vanilla and Forge),
 * the main menu title and the version line in the corner.
 * <p>
 * The window and the loading screen exist before mods load their settings, so for those the
 * settings are read straight from config/fzclient.json.
 */
public final class Branding {
    private Branding() {}

    /**
     * The mod's version, read from mcmod.info inside the jar (Gradle writes gradle.properties' "version" there),
     * so gradle.properties is the only place to change it. Not a compile-time constant on purpose: a constant
     * would be copied into every class that uses it and could stay old after a partial rebuild.
     */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (java.io.InputStream in = Branding.class.getResourceAsStream("/mcmod.info")) {
            if (in != null) {
                com.google.gson.JsonElement e = new com.google.gson.JsonParser().parse(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                com.google.gson.JsonObject o = (e.isJsonArray() ? e.getAsJsonArray().get(0) : e).getAsJsonObject();
                String v = o.has("version") ? o.get("version").getAsString() : "";
                if (!v.isEmpty() && !v.startsWith("$")) return v;
            }
        } catch (Throwable ignored) {
        }
        return "dev";
    }

    /** True once the normal settings are loaded (from then on the Modules values are used). */
    public static boolean loaded;

    private static boolean early;
    private static boolean earlyEnabled = true;
    private static String earlyTitle;
    private static final Map<String, Boolean> earlyBools = new HashMap<>();

    private static void early() {
        if (early) return;
        early = true;
        try {
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "config");
            File f = new File(dir, "fzclient.json");
            if (!f.exists()) f = new File(dir, "fzhud.json");
            if (!f.exists()) return;
            try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
                JsonObject root = new JsonParser().parse(r).getAsJsonObject();
                JsonObject m = root.getAsJsonObject("modules").getAsJsonObject("branding");
                if (m == null) return;
                if (m.has("enabled")) earlyEnabled = m.get("enabled").getAsBoolean();
                if (m.has("settings")) {
                    for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("settings").entrySet()) {
                        JsonElement v = e.getValue();
                        if (!v.isJsonPrimitive()) continue;
                        if (v.getAsJsonPrimitive().isBoolean()) earlyBools.put(e.getKey(), v.getAsBoolean());
                        else if (e.getKey().equals("title")) earlyTitle = v.getAsString();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** "enabled", "splash", "icon", "menutitle", "brandline". */
    public static boolean on(String key) {
        if (loaded) {
            if (!Modules.branding.enabled) return false;
            switch (key) {
                case "splash":
                    return Modules.brandSplash.get();
                case "icon":
                    return Modules.brandIcon.get();
                case "menutitle":
                    return Modules.brandMenuTitle.get();
                case "custommenu":
                    return Modules.brandCustomMenu.get();
                case "brandline":
                    return Modules.brandLine.get();
            }
            return true;
        }
        early();
        if (!earlyEnabled) return false;
        Boolean b = earlyBools.get(key);
        return b == null || b;
    }

    public static String title() {
        String t;
        if (loaded) {
            t = Modules.brandTitle.get();
        } else {
            early();
            t = earlyTitle;
        }
        return t == null || t.trim().isEmpty() ? "FzClient 1.8.9" : t;
    }

    public static InputStream logo() {
        return Branding.class.getResourceAsStream("/assets/fzclient/textures/logo.png");
    }

    /** Forge's loading-screen background; the logo is drawn untinted only when this exact colour is set. */
    public static final int SPLASH_BG = 0x0B1416;

    /** A fully transparent image (used to hide Forge's anvil on the loading screen). */
    public static InputStream emptyImage() {
        return Branding.class.getResourceAsStream("/assets/fzclient/textures/empty.png");
    }

    /** path like "icons/icon_16x16.png" */
    public static InputStream icon(String path) {
        return Branding.class.getResourceAsStream("/assets/fzclient/" + path);
    }

    /** Colours for Forge's loading screen (same names as config/splash.properties). */
    public static Integer splashColor(String name) {
        switch (name) {
            case "background":
                return SPLASH_BG;
            case "font":
                return 0xE6F7F8;
            case "barBorder":
            case "bar":
                return 0x5CE1E6;
            case "barBackground":
                return 0x0F1C20;
        }
        return null;
    }

    private static String lastTitle;

    /** Client tick: keep the window title in sync with the setting. */
    public static void tick() {
        String want = on("enabled") ? title() : "Minecraft 1.8.9";
        if (!want.equals(lastTitle)) {
            lastTitle = want;
            if (!want.equals(Display.getTitle())) Display.setTitle(want);
        }
    }

    private static boolean branded;

    /**
     * Adds "FzClient <version>" to Forge's version lines (main menu corner). FML is loaded before mixins
     * can touch it, so this edits its list by reflection when the main menu opens.
     */
    @SuppressWarnings("unchecked")
    public static void patchBrandings() {
        boolean want = on("brandline");
        if (want == branded) return;
        try {
            net.minecraftforge.fml.common.FMLCommonHandler h = net.minecraftforge.fml.common.FMLCommonHandler.instance();
            h.getBrandings(true); // make sure the list exists
            java.lang.reflect.Field f = net.minecraftforge.fml.common.FMLCommonHandler.class.getDeclaredField("brandings");
            f.setAccessible(true);
            java.util.List<String> list = new java.util.ArrayList<>((java.util.List<String>) f.get(h));
            String line = "FzClient " + VERSION;
            list.remove(line);
            if (want) list.add(0, line);
            f.set(h, list);
            branded = want;
        } catch (Throwable t) {
            branded = want; // don't retry every time
        }
    }

    private static final net.minecraft.util.ResourceLocation TITLE =
            new net.minecraft.util.ResourceLocation("fzclient", "textures/title.png");

    /** Main menu: our script "FzClient" title (an image, tinted with the colour setting) instead of MINECRAFT. */
    public static void drawMenuTitle(int screenWidth) {
        int color = R.solid(loaded ? Modules.brandMenuColor.get() : R.ACCENT);
        int w = 160, h = 60; // title.png is 512x191
        int x = screenWidth / 2 - w / 2, y = 22;
        Minecraft.getMinecraft().getTextureManager().bindTexture(TITLE);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        // soft shadow, then the title
        GlStateManager.color(0f, 0f, 0f, 0.45f);
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(x + 1, y + 2, 0, 0, w, h, w, h);
        GlStateManager.color(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, 1f);
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, w, h, w, h);
        GlStateManager.color(1f, 1f, 1f, 1f);
        R.textScaledCentered("PVP CLIENT  ·  1.8.9", screenWidth / 2f, y + h + 2, 1f, 0xFFB4C8CC);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
