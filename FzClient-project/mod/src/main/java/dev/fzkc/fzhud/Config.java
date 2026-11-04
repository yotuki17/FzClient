package dev.fzkc.fzhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.Setting;
import dev.fzkc.fzhud.hud.HudElement;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves everything to .minecraft/config/fzclient.json:
 * every module's on/off + settings, and HUD element positions.
 */
public final class Config {
    private Config() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Bump when a default needs to be re-applied to existing config files. */
    private static final int VERSION = 2;

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient.json");
    }

    /** Settings from before the rename (FzHUD) are picked up once if there is no FzClient file yet. */
    private static File readFile() {
        File f = file();
        if (f.exists()) return f;
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp"); // a save that was cut off half-way
        if (tmp.exists()) return tmp;
        File old = new File(f.getParentFile(), "fzhud.json");
        return old.exists() ? old : f;
    }

    private static List<Module> allModules() {
        List<Module> all = new ArrayList<Module>(Modules.FEATURES);
        all.addAll(FzHud.elements);
        return all;
    }

    public static void load() {
        File f = readFile();
        if (!f.exists()) return;
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), UTF8);
            JsonElement rootEl = new JsonParser().parse(r);
            if (!rootEl.isJsonObject()) return;
            JsonObject root = rootEl.getAsJsonObject();

            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            if (!modules.has("chat")) migrateChat(modules);
            migrateGlass(modules);
            for (Module m : allModules()) {
                if (!modules.has(m.id)) continue;
                JsonObject o = modules.getAsJsonObject(m.id);
                if (o.has("enabled") && m.toggleable) m.enabled = o.get("enabled").getAsBoolean();
                if (o.has("settings")) {
                    JsonObject st = o.getAsJsonObject("settings");
                    for (Setting s : m.settings) {
                        if (st.has(s.key)) s.load(st.get(s.key));
                    }
                }
            }

            // v2: Nametags now starts OFF because it clashed with other nametag mods
            if (version < 2) Modules.nametags.enabled = false;

            JsonObject elements = root.has("elements") ? root.getAsJsonObject("elements") : new JsonObject();
            for (HudElement e : FzHud.elements) {
                if (!elements.has(e.id)) continue;
                JsonObject o = elements.getAsJsonObject(e.id);
                if (o.has("x")) e.relX = o.get("x").getAsFloat();
                if (o.has("y")) e.relY = o.get("y").getAsFloat();
                if (o.has("anchor")) {
                    int a = o.get("anchor").getAsInt();
                    if (a >= 0 && a <= 2) e.anchor = a;
                }
                // files from older versions kept scale/enabled here
                if (!modules.has(e.id)) {
                    if (o.has("scale")) e.scaleSetting.set(o.get("scale").getAsDouble());
                    if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
                }
            }
        } catch (Exception ex) {
            System.err.println("[FzClient] Could not read config: " + ex);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /** The five chat modules became one "chat" module: carry their on/off states and settings over. */
    /**
     * "Liquid Glass UI" used to be switched ON/OFF as a module; it is now the "UI" page (always on) and the glass
     * look is its own switch. An old file with the module OFF keeps the classic look.
     */
    private static void migrateGlass(JsonObject modules) {
        if (!modules.has("glassui")) return;
        JsonObject o = modules.getAsJsonObject("glassui");
        JsonObject st = o.has("settings") ? o.getAsJsonObject("settings") : new JsonObject();
        if (o.has("enabled") && !st.has("glass") && !st.has("style")) st.add("glass", o.get("enabled"));
        // the glass ON/OFF switch became the "スタイル" choice (0 = liquid glass, 1 = classic, 2 = dashboard)
        if (st.has("glass") && !st.has("style")) {
            boolean glass = true;
            try { glass = st.get("glass").getAsBoolean(); } catch (Exception ignored) { }
            st.addProperty("style", glass ? 0 : 1);
        }
        o.add("settings", st);
    }

    private static void migrateChat(JsonObject modules) {
        JsonObject st = new JsonObject();
        // old module id, old key -> new key
        String[][] map = {
                {"smoothchat", "speed", "smoothspeed"},
                {"chattweaks", "timestamps", "timestamps"}, {"chattweaks", "seconds", "seconds"},
                {"chattweaks", "compact", "compact"}, {"chattweaks", "copy", "copy"},
                {"translator", "mode", "trmode"}, {"translator", "scope", "trscope"}, {"translator", "target", "trtarget"},
                {"translator", "sendlang", "trsendlang"}, {"translator", "provider", "trprovider"}, {"translator", "deeplkey", "deeplkey"},
                {"chathistory", "keep", "keep"}, {"chathistory", "sent", "sent"}, {"chathistory", "restore", "restore"},
                {"chathistory", "lines", "lines"}, {"chathistory", "log", "log"},
                {"ime", "mode", "imemode"}, {"ime", "cmdascii", "cmdascii"}, {"ime", "auto", "imeauto"}, {"ime", "send", "imesend"},
        };
        boolean any = false;
        for (String[] m : map) {
            JsonObject old = modules.has(m[0]) ? modules.getAsJsonObject(m[0]) : null;
            if (old == null || !old.has("settings")) continue;
            JsonObject os = old.getAsJsonObject("settings");
            if (os.has(m[1])) {
                st.add(m[2], os.get(m[1]));
                any = true;
            }
        }
        // each old module's ON/OFF becomes that section's switch
        String[][] on = {{"smoothchat", "smooth"}, {"translator", "translate"}, {"chathistory", "history"}, {"ime", "ime"}};
        for (String[] m : on) {
            if (modules.has(m[0]) && modules.getAsJsonObject(m[0]).has("enabled")) {
                st.add(m[1], modules.getAsJsonObject(m[0]).get("enabled"));
                any = true;
            }
        }
        // Chat Tweaks had no switches of its own: if it was off, its options were effectively off
        if (modules.has("chattweaks") && modules.getAsJsonObject("chattweaks").has("enabled")
                && !modules.getAsJsonObject("chattweaks").get("enabled").getAsBoolean()) {
            for (String k : new String[]{"timestamps", "compact", "copy"}) st.addProperty(k, false);
        }
        if (!any) return;
        JsonObject chat = new JsonObject();
        chat.addProperty("enabled", true);
        chat.add("settings", st);
        modules.add("chat", chat);
    }

    /** JSON of the last successful save (skip writing when nothing changed). */
    private static String lastSaved;

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject modules = new JsonObject();
        for (Module m : allModules()) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.enabled);
            JsonObject st = new JsonObject();
            for (Setting s : m.settings) st.add(s.key, s.save());
            o.add("settings", st);
            modules.add(m.id, o);
        }
        root.add("modules", modules);

        JsonObject elements = new JsonObject();
        for (HudElement e : FzHud.elements) {
            JsonObject o = new JsonObject();
            o.addProperty("x", e.relX);
            o.addProperty("y", e.relY);
            o.addProperty("anchor", e.anchor);
            elements.add(e.id, o);
        }
        root.add("elements", elements);

        // nothing changed since the last save -> don't touch the disk (menus save every time they close)
        String json = GSON.toJson(root);
        if (json.equals(lastSaved)) return;

        // write to a temp file and swap it in, so a crash mid-write can't leave a broken config
        File f = file();
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            w.write(json);
            w.close();
            w = null;
            if (f.exists() && !f.delete()) throw new java.io.IOException("cannot replace " + f);
            if (!tmp.renameTo(f)) throw new java.io.IOException("cannot rename " + tmp);
            lastSaved = json;
        } catch (Exception ex) {
            System.err.println("[FzClient] Could not save config: " + ex);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }
}
