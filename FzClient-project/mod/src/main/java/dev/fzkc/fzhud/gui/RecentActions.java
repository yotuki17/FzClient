package dev.fzkc.fzhud.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.config.HeaderSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.Setting;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "最近の変更" on the dashboard: which features were turned on / off and which settings changed, newest first.
 * Works by comparing the current values with the last look (a few times a second while a FzClient menu is open),
 * so every way of changing something is caught without hooks in each screen. Kept in
 * .minecraft/config/fzclient-recent.json.
 */
public final class RecentActions {
    private RecentActions() {}

    public static final int SETTING = 0, ON = 1, OFF = 2, MANY = 3;
    private static final int MAX = 40;

    public static final class Entry {
        public long time;
        public final String module;   // module id (null for MANY)
        public final int kind;
        public final String detail;   // setting label, or the number of changes for MANY

        Entry(long time, String module, int kind, String detail) {
            this.time = time;
            this.module = module;
            this.kind = kind;
            this.detail = detail;
        }
    }

    private static final List<Entry> entries = new ArrayList<Entry>();
    private static Map<String, String> last;
    private static long lastLook;
    private static boolean loaded, dirty;

    public static List<Entry> list() {
        load();
        return entries;
    }

    public static Module module(String id) {
        if (id == null) return null;
        for (Module m : ModsScreen.all()) if (m.id.equals(id)) return m;
        for (Module m : dev.fzkc.fzhud.Modules.FEATURES) if (m.id.equals(id)) return m;
        return null;
    }

    public static void track() {
        track(false);
    }

    /** Compares the current values with the last look and records what changed. */
    public static void track(boolean now) {
        long t = System.currentTimeMillis();
        if (!now && t - lastLook < 250L) return;
        lastLook = t;
        load();
        Map<String, String> cur = new HashMap<String, String>();
        List<Module> all = new ArrayList<Module>(ModsScreen.all());
        for (Module m : dev.fzkc.fzhud.Modules.FEATURES) if (!all.contains(m)) all.add(m);
        for (Module m : all) {
            cur.put(m.id + "\u0000#on", String.valueOf(m.enabled));
            for (Setting s : m.settings) {
                if (s instanceof HeaderSetting) continue;
                try {
                    cur.put(m.id + "\u0000" + s.key, String.valueOf(s.save()));
                } catch (Throwable ignored) {
                }
            }
        }
        if (last == null) {   // first look: nothing to compare with yet
            last = cur;
            return;
        }
        List<Entry> found = new ArrayList<Entry>();
        for (Map.Entry<String, String> e : cur.entrySet()) {
            String before = last.get(e.getKey());
            if (before == null || before.equals(e.getValue())) continue;
            String[] k = e.getKey().split("\u0000", 2);
            if (k[1].equals("#on")) {
                found.add(new Entry(t, k[0], "true".equals(e.getValue()) ? ON : OFF, ""));
            } else {
                Module m = module(k[0]);
                String label = k[1];
                if (m != null) for (Setting s : m.settings) if (s.key.equals(k[1])) label = s.label;
                found.add(new Entry(t, k[0], SETTING, label));
            }
        }
        last = cur;
        if (found.isEmpty()) return;
        if (found.size() > 6) {   // a preset or a reset: one line instead of a flood
            entries.add(0, new Entry(t, null, MANY, String.valueOf(found.size())));
        } else {
            for (Entry f : found) {
                Entry top = entries.isEmpty() ? null : entries.get(0);
                // dragging a slider changes it many times: keep one line and move it to "now"
                if (top != null && top.kind == f.kind && f.module.equals(top.module) && f.detail.equals(top.detail)
                        && t - top.time < 10000L) {
                    top.time = t;
                } else if (top != null && (f.kind == ON || f.kind == OFF) && (top.kind == ON || top.kind == OFF)
                        && f.module.equals(top.module) && t - top.time < 10000L) {
                    entries.set(0, f);   // ON then OFF again quickly: just the latest state
                } else {
                    entries.add(0, f);
                }
            }
        }
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        dirty = true;
    }

    // ------------------------------------------------------------------ file

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-recent.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            for (JsonElement el : new JsonParser().parse(r).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                entries.add(new Entry(o.get("t").getAsLong(), o.has("m") ? o.get("m").getAsString() : null,
                        o.get("k").getAsInt(), o.has("d") ? o.get("d").getAsString() : ""));
            }
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }

    public static void save() {
        if (!dirty) return;
        dirty = false;
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("t", e.time);
            if (e.module != null) o.addProperty("m", e.module);
            o.addProperty("k", e.kind);
            o.addProperty("d", e.detail);
            arr.add(o);
        }
        try {
            File f = file();
            f.getParentFile().mkdirs();
            OutputStream out = new FileOutputStream(f);
            try {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** "たった今", "5分前", "3時間前", "2日前" */
    public static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000L);
        if (s < 60) return dev.fzkc.fzhud.util.Lang.t("たった今");
        if (s < 3600) return dev.fzkc.fzhud.util.Lang.f("{0}分前", s / 60);
        if (s < 86400) return dev.fzkc.fzhud.util.Lang.f("{0}時間前", s / 3600);
        return dev.fzkc.fzhud.util.Lang.f("{0}日前", s / 86400);
    }
}
