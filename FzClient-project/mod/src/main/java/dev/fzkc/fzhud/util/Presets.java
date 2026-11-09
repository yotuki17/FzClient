package dev.fzkc.fzhud.util;

import com.google.gson.*;
import dev.fzkc.fzhud.Config;
import dev.fzkc.fzhud.FzHud;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.HeaderSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.Setting;
import dev.fzkc.fzhud.hud.HudElement;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Preset codes: the whole FzClient setup (which features are on, every setting, HUD positions) as one short text
 * like "FZ1-eJy...". Only what differs from the defaults is stored, then compressed, so codes stay short.
 * Personal things (DeepL key, your own mention words, the client language) are never put in a code.
 * <p>
 * Saved presets live in config/fzclient-presets.json (name + code).
 */
public final class Presets {
    private Presets() {}

    public static final String PREFIX = "FZ1-";
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Gson GSON = new Gson();

    /** Settings that stay yours: not exported, not overwritten when a code is loaded. */
    private static boolean personal(Module m, Setting s) {
        if (m == Modules.language) return true;
        // API keys never go into a code someone else could load
        if (s instanceof dev.fzkc.fzhud.config.TextSetting && ((dev.fzkc.fzhud.config.TextSetting) s).secret) return true;
        return m == Modules.chat && (s.key.equals("deeplkey") || s.key.equals("mentionwords"));
    }

    private static List<Module> all() {
        List<Module> all = new ArrayList<>(Modules.FEATURES);
        all.addAll(FzHud.elements);
        return all;
    }

    /** Whether a setting is still at its default (sliders compared with a little tolerance). */
    private static boolean isDefault(Setting s) {
        if (s instanceof dev.fzkc.fzhud.config.BoolSetting) {
            dev.fzkc.fzhud.config.BoolSetting b = (dev.fzkc.fzhud.config.BoolSetting) s;
            return b.value == b.def;
        }
        if (s instanceof dev.fzkc.fzhud.config.SliderSetting) {
            dev.fzkc.fzhud.config.SliderSetting sl = (dev.fzkc.fzhud.config.SliderSetting) s;
            return Math.abs(sl.get() - sl.def) < 1e-6;
        }
        if (s instanceof dev.fzkc.fzhud.config.ChoiceSetting) {
            dev.fzkc.fzhud.config.ChoiceSetting c = (dev.fzkc.fzhud.config.ChoiceSetting) s;
            return c.index == c.def;
        }
        if (s instanceof dev.fzkc.fzhud.config.TextSetting) {
            dev.fzkc.fzhud.config.TextSetting t = (dev.fzkc.fzhud.config.TextSetting) s;
            return t.value.equals(t.def);
        }
        if (s instanceof dev.fzkc.fzhud.config.ColorSetting) {
            dev.fzkc.fzhud.config.ColorSetting c = (dev.fzkc.fzhud.config.ColorSetting) s;
            return (c.rgb & 0xFFFFFF) == c.def && c.chroma == c.defChroma;
        }
        return false;
    }

    private static float round(float v) {
        return Math.round(v * 10000f) / 10000f;
    }

    // ------------------------------------------------------------------ export / import

    // Code format "FZ2-...": one byte version, then (maybe compressed) a list of changes. Every setting is
    // referred to by a 21-bit hash of "module.key" with a 3-bit type, so a change costs 3 bytes + its value
    // (a switch: nothing, a slider / choice: 1-2 bytes, a colour: 3 bytes). Unknown entries (from a newer
    // version) are skipped safely. "FZ1-" codes (the first, JSON based format) are still accepted.

    public static final String PREFIX2 = "FZ2-";
    private static final int T_ON = 0, T_OFF = 1, T_TRUE = 2, T_FALSE = 3, T_NUM = 4, T_RGB = 5, T_CHROMA = 6, T_TEXT = 7;

    static int hash21(String id) {
        int h = 0x811C9DC5; // FNV-1a
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x01000193;
        }
        return (h ^ (h >>> 21) ^ (h >>> 11)) & 0x1FFFFF;
    }

    private static void header(ByteArrayOutputStream out, String id, int type) {
        int v = hash21(id) << 3 | type;
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private static void varint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** The current setup as a code. */
    public static String export() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (Module m : all()) {
            if (m == Modules.language) continue;
            if (m.toggleable && m.enabled != m.defaultEnabled) {
                header(entries, "m:" + m.id, m.enabled ? T_ON : T_OFF);
                count++;
            }
            for (Setting st : m.settings) {
                if (st instanceof HeaderSetting || personal(m, st) || isDefault(st)) continue;
                String id = m.id + "." + st.key;
                if (st instanceof dev.fzkc.fzhud.config.BoolSetting) {
                    header(entries, id, ((dev.fzkc.fzhud.config.BoolSetting) st).value ? T_TRUE : T_FALSE);
                } else if (st instanceof dev.fzkc.fzhud.config.ChoiceSetting) {
                    header(entries, id, T_NUM);
                    varint(entries, ((dev.fzkc.fzhud.config.ChoiceSetting) st).index);
                } else if (st instanceof dev.fzkc.fzhud.config.SliderSetting) {
                    dev.fzkc.fzhud.config.SliderSetting sl = (dev.fzkc.fzhud.config.SliderSetting) st;
                    header(entries, id, T_NUM);
                    varint(entries, (int) Math.max(0, Math.round((sl.value - sl.min) / sl.step)));
                } else if (st instanceof dev.fzkc.fzhud.config.ColorSetting) {
                    dev.fzkc.fzhud.config.ColorSetting c = (dev.fzkc.fzhud.config.ColorSetting) st;
                    if (c.chroma) {
                        header(entries, id, T_CHROMA);
                    } else {
                        header(entries, id, T_RGB);
                        entries.write(c.rgb >>> 16);
                        entries.write(c.rgb >>> 8);
                        entries.write(c.rgb);
                    }
                } else if (st instanceof dev.fzkc.fzhud.config.TextSetting) {
                    byte[] b = ((dev.fzkc.fzhud.config.TextSetting) st).value.getBytes(UTF8);
                    header(entries, id, T_TEXT);
                    varint(entries, b.length);
                    entries.write(b, 0, b.length);
                } else {
                    continue;
                }
                count++;
            }
        }
        varint(body, count);
        byte[] e = entries.toByteArray();
        body.write(e, 0, e.length);
        // HUD positions: hash + anchor, then x / y as 0..65535
        int pc = 0;
        ByteArrayOutputStream pos = new ByteArrayOutputStream();
        for (HudElement el : FzHud.elements) {
            if (el.relX == el.defX && el.relY == el.defY && el.anchor == el.defAnchor) continue;
            header(pos, "p:" + el.id, el.anchor);
            int x = Math.round(Math.max(0f, Math.min(1f, el.relX)) * 65535f), y = Math.round(Math.max(0f, Math.min(1f, el.relY)) * 65535f);
            pos.write(x >>> 8);
            pos.write(x);
            pos.write(y >>> 8);
            pos.write(y);
            pc++;
        }
        varint(body, pc);
        byte[] p = pos.toByteArray();
        body.write(p, 0, p.length);

        byte[] raw = body.toByteArray();
        byte[] packed = deflate(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (packed.length < raw.length) {
            out.write(0x82);
            out.write(packed, 0, packed.length);
        } else {
            out.write(0x02);
            out.write(raw, 0, raw.length);
        }
        return PREFIX2 + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** Reads a code without applying it. Returns null when it isn't a valid FzClient code. */
    public static JsonObject parse(String code) {
        if (code == null) return null;
        code = code.trim().replaceAll("\\s+", "");
        try {
            if (code.startsWith(PREFIX2)) return parse2(Base64.getUrlDecoder().decode(code.substring(PREFIX2.length())));
            if (!code.startsWith(PREFIX)) return null;
            byte[] raw = inflate(Base64.getUrlDecoder().decode(code.substring(PREFIX.length())));
            JsonElement e = new JsonParser().parse(new String(raw, UTF8));
            if (!e.isJsonObject() || !e.getAsJsonObject().has("v")) return null;
            return e.getAsJsonObject();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Binary code -> the same JSON shape FZ1 uses, so apply() handles both. */
    private static JsonObject parse2(byte[] data) throws Exception {
        if (data.length < 1) return null;
        int ver = data[0] & 0xFF;
        byte[] body;
        if (ver == 0x82) body = inflate(java.util.Arrays.copyOfRange(data, 1, data.length));
        else if (ver == 0x02) body = java.util.Arrays.copyOfRange(data, 1, data.length);
        else return null;

        // what each hash means in this version
        java.util.Map<Integer, Object[]> byHash = new java.util.HashMap<>();
        for (Module m : all()) {
            byHash.put(hash21("m:" + m.id), new Object[]{m, null});
            for (Setting st : m.settings) byHash.put(hash21(m.id + "." + st.key), new Object[]{m, st});
        }
        java.util.Map<Integer, HudElement> posByHash = new java.util.HashMap<>();
        for (HudElement el : FzHud.elements) posByHash.put(hash21("p:" + el.id), el);

        int[] at = {0};
        JsonObject root = new JsonObject();
        root.addProperty("v", 2);
        JsonObject mods = new JsonObject();
        int count = readVarint(body, at);
        for (int i = 0; i < count; i++) {
            int h = readHeader(body, at);
            int type = h & 7;
            Object[] target = byHash.get(h >>> 3);
            JsonElement value = null;
            switch (type) {
                case T_ON: case T_TRUE: value = new com.google.gson.JsonPrimitive(true); break;
                case T_OFF: case T_FALSE: value = new com.google.gson.JsonPrimitive(false); break;
                case T_CHROMA: value = new com.google.gson.JsonPrimitive("chroma"); break;
                case T_NUM: {
                    int n = readVarint(body, at);
                    if (target != null && target[1] instanceof dev.fzkc.fzhud.config.SliderSetting) {
                        dev.fzkc.fzhud.config.SliderSetting sl = (dev.fzkc.fzhud.config.SliderSetting) target[1];
                        value = new com.google.gson.JsonPrimitive(sl.min + n * sl.step);
                    } else {
                        value = new com.google.gson.JsonPrimitive(n);
                    }
                    break;
                }
                case T_RGB: {
                    int rgb = (body[at[0]] & 0xFF) << 16 | (body[at[0] + 1] & 0xFF) << 8 | (body[at[0] + 2] & 0xFF);
                    at[0] += 3;
                    value = new com.google.gson.JsonPrimitive(String.format("#%06X", rgb));
                    break;
                }
                case T_TEXT: {
                    int len = readVarint(body, at);
                    value = new com.google.gson.JsonPrimitive(new String(body, at[0], len, UTF8));
                    at[0] += len;
                    break;
                }
                default: break;
            }
            if (target == null) continue; // a setting this version doesn't have: skipped
            Module m = (Module) target[0];
            JsonObject mo = mods.has(m.id) ? mods.getAsJsonObject(m.id) : new JsonObject();
            mods.add(m.id, mo);
            if (target[1] == null) {
                if (type == T_ON || type == T_OFF) mo.add("e", value);
            } else {
                JsonObject so = mo.has("s") ? mo.getAsJsonObject("s") : new JsonObject();
                mo.add("s", so);
                so.add(((Setting) target[1]).key, value);
            }
        }
        root.add("m", mods);
        JsonObject pos = new JsonObject();
        int pc = at[0] < body.length ? readVarint(body, at) : 0;
        for (int i = 0; i < pc; i++) {
            int h = readHeader(body, at);
            int x = (body[at[0]] & 0xFF) << 8 | (body[at[0] + 1] & 0xFF);
            int y = (body[at[0] + 2] & 0xFF) << 8 | (body[at[0] + 3] & 0xFF);
            at[0] += 4;
            HudElement el = posByHash.get(h >>> 3);
            if (el == null) continue;
            JsonArray a = new JsonArray();
            a.add(new com.google.gson.JsonPrimitive(x / 65535f));
            a.add(new com.google.gson.JsonPrimitive(y / 65535f));
            a.add(new com.google.gson.JsonPrimitive(h & 7));
            pos.add(el.id, a);
        }
        root.add("p", pos);
        return root;
    }

    private static int readHeader(byte[] b, int[] at) {
        int v = (b[at[0]] & 0xFF) << 16 | (b[at[0] + 1] & 0xFF) << 8 | (b[at[0] + 2] & 0xFF);
        at[0] += 3;
        return v;
    }

    private static int readVarint(byte[] b, int[] at) {
        int v = 0, shift = 0;
        while (true) {
            int x = b[at[0]++] & 0xFF;
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
            if (shift > 28) throw new IllegalStateException("bad number");
        }
    }

    /**
     * Makes the setup exactly what the code describes: everything goes back to default first (except your
     * personal settings), then the code's values are put on top. Saves the config.
     */
    public static void apply(JsonObject root) {
        JsonObject mods = root.has("m") ? root.getAsJsonObject("m") : new JsonObject();
        for (Module m : all()) {
            if (m == Modules.language) continue;
            JsonObject o = mods.has(m.id) ? mods.getAsJsonObject(m.id) : new JsonObject();
            if (m.toggleable) m.enabled = o.has("e") ? o.get("e").getAsBoolean() : m.defaultEnabled;
            JsonObject st = o.has("s") ? o.getAsJsonObject("s") : new JsonObject();
            for (Setting s : m.settings) {
                if (s instanceof HeaderSetting || personal(m, s)) continue;
                if (st.has(s.key)) {
                    try {
                        s.load(st.get(s.key));
                    } catch (Exception ex) {
                        s.reset();
                    }
                } else {
                    s.reset();
                }
            }
        }
        JsonObject pos = root.has("p") ? root.getAsJsonObject("p") : new JsonObject();
        for (HudElement e : FzHud.elements) {
            e.resetPosition();
            if (!pos.has(e.id)) continue;
            JsonArray a = pos.getAsJsonArray(e.id);
            e.relX = Math.max(0f, Math.min(1f, a.get(0).getAsFloat()));
            e.relY = Math.max(0f, Math.min(1f, a.get(1).getAsFloat()));
            int an = a.get(2).getAsInt();
            if (an >= 0 && an <= 2) e.anchor = an;
        }
        Config.save();
    }

    private static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] in) throws Exception {
        Inflater i = new Inflater(true);
        i.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!i.finished()) {
            int n = i.inflate(buf);
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break;
            out.write(buf, 0, n);
            if (out.size() > 1 << 20) throw new IllegalStateException("too big");
        }
        i.end();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ short codes (6 characters)

    // A long code is kept on the FzClient server and shared as 6 characters like "K7QF3M". The characters
    // leave out 0/O and 1/I/L so a code read out loud or typed by hand still works.

    private static final java.util.regex.Pattern SHORT = java.util.regex.Pattern.compile("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}");
    private static final java.util.Map<String, String> idByCode = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> codeById = new java.util.concurrent.ConcurrentHashMap<>();

    /** The 6 character id inside the text (spaces and case ignored), or null. */
    public static String shortId(String text) {
        if (text == null) return null;
        String s = text.trim().replaceAll("[\\s-]+", "").toUpperCase(java.util.Locale.ROOT);
        if (s.startsWith("FZ") && s.length() == 8) s = s.substring(2);   // "FZ-K7QF3M" also works
        return SHORT.matcher(s).matches() ? s : null;
    }

    /** Stores a long code on the server and returns its 6 character id. Network: call off the game thread. */
    public static String shorten(String code) throws Exception {
        String id = idByCode.get(code);
        if (id != null) return id;
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        JsonObject r = dev.fzkc.fzhud.integration.FzUsers.post("/preset/put", body);
        if (r == null || !r.has("id")) throw new IOException("no id");
        id = shortId(r.get("id").getAsString());
        if (id == null) throw new IOException("bad id");
        idByCode.put(code, id);
        codeById.put(id, code);
        return id;
    }

    /** The long code for a 6 character id, or null when there is none. Network: call off the game thread. */
    public static String expand(String id) throws Exception {
        String code = codeById.get(id);
        if (code != null) return code;
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        JsonObject r = dev.fzkc.fzhud.integration.FzUsers.post("/preset/get", body);
        if (r == null) throw new IOException("no answer");
        if (!r.has("code")) return null;
        code = r.get("code").getAsString();
        codeById.put(id, code);
        idByCode.put(code, id);
        return code;
    }

    // ------------------------------------------------------------------ saved presets

    public static final class Saved {
        public String name;
        public String code;

        Saved(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    private static List<Saved> saved;

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-presets.json");
    }

    public static List<Saved> saved() {
        if (saved != null) return saved;
        saved = new ArrayList<>();
        File f = file();
        if (!f.exists()) return saved;
        Reader r = null;
        try {
            r = new InputStreamReader(Files.newInputStream(f.toPath()), UTF8);
            JsonElement e = new JsonParser().parse(r);
            if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    JsonObject o = x.getAsJsonObject();
                    if (o.has("name") && o.has("code")) saved.add(new Saved(o.get("name").getAsString(), o.get("code").getAsString()));
                }
            }
        } catch (Exception ex) {
            System.err.println("[FzClient] Could not read presets: " + ex);
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return saved;
    }

    private static void write() {
        JsonArray arr = new JsonArray();
        for (Saved s : saved()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name);
            o.addProperty("code", s.code);
            arr.add(o);
        }
        File f = file();
        f.getParentFile().mkdirs();
        Writer w = null;
        try {
            w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), UTF8);
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(arr, w);
        } catch (Exception ex) {
            System.err.println("[FzClient] Could not save presets: " + ex);
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Saves the current setup under a date-time name (newest first): the backup taken before a preset is loaded.
     * Nothing is added when the current setup is already one of the saved ones (loading a saved preset and then
     * another one made a copy of the first every time), and there is only ever one backup: a new one replaces it.
     */
    public static void saveCurrent(String label) {
        String code = export();
        for (Saved x : saved()) if (x.code.equals(code)) return;   // nothing would be lost
        String prefix = label == null || label.isEmpty() ? null : label + " ";
        if (prefix != null) saved().removeIf(x -> x.name.startsWith(prefix));
        String name = (prefix == null ? "" : prefix) + new SimpleDateFormat("MM/dd HH:mm").format(new Date());
        Saved s = new Saved(name.trim(), code);
        saved().add(0, s);
        while (saved().size() > 20) saved().remove(saved().size() - 1);
        write();
    }

    /** Saves the current setup under the given name (a numbered name when empty). */
    public static Saved saveAs(String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) name = dev.fzkc.fzhud.util.Lang.f("プリセット {0}", saved().size() + 1);
        Saved s = new Saved(name, export());
        saved().add(0, s);
        while (saved().size() > 20) saved().remove(saved().size() - 1);
        write();
        return s;
    }

    public static void rename(Saved s, String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.equals(s.name)) return;
        s.name = name;
        write();
    }

    public static void delete(Saved s) {
        saved().remove(s);
        write();
    }
}
