package dev.fzkc.fzhud.cosmetic;

import com.google.gson.JsonObject;

/**
 * What one player wears. The same shape is stored on the FzClient server (see fzusers/worker.js), so every
 * FzClient user draws the same thing. Ids, not indexes, so new options can be added later without mixing up
 * what older versions saved.
 */
public final class Equip {
    public static final String[] CAPES = {"", "fzclient", "aurora", "midnight", "sakura", "custom"};
    public static final String[] AURAS = {"", "hearts", "flame", "magic", "notes", "snow", "rainbow"};

    public String cape = "", capeHash = "", aura = "";

    public boolean empty() {
        return cape.isEmpty() && aura.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("cape", cape);
        if (!capeHash.isEmpty()) o.addProperty("capeHash", capeHash);
        o.addProperty("aura", aura);
        return o;
    }

    /** Reads what the server sent; anything unknown (or from a newer version) is ignored. */
    public static Equip fromJson(JsonObject o) {
        Equip e = new Equip();
        if (o == null) return e;
        e.cape = pick(o, "cape", CAPES);
        e.aura = pick(o, "aura", AURAS);
        try { if (o.has("capeHash")) e.capeHash = o.get("capeHash").getAsString().replaceAll("[^0-9a-f]", ""); } catch (Exception ignored) { }
        return e;
    }

    private static String pick(JsonObject o, String key, String[] allowed) {
        try {
            String v = o.has(key) ? o.get(key).getAsString() : "";
            for (String a : allowed) if (a.equals(v)) return v;
        } catch (Exception ignored) {
        }
        return "";
    }
}
