package dev.fzkc.fzhud.cosmetic;

import com.google.gson.JsonObject;

/**
 * What one player wears. The same shape is stored on the FzClient server (see fzusers/worker.js), so every
 * FzClient user draws the same thing. Ids, not indexes, so new options can be added later without mixing up
 * what older versions saved.
 */
public final class Equip {
    public static final String[] CAPES = {"", "fzclient", "aurora", "midnight", "sakura", "ocean", "ember", "galaxy", "neon", "royal"};
    public static final String[] AURAS = {"", "hearts", "flame", "magic", "notes", "snow", "rainbow", "stars", "petals", "bubbles", "souls",
            "ender", "fireflies", "leaves", "electric", "galaxy", "love", "shadow", "emerald"};
    public static final String[] HALOS = {"", "gold", "rainbow", "dark", "ice"};
    public static final String[] PETS = {"", "dog", "cat", "chick", "pig", "sheep", "slime", "blaze", "ghast", "snowman", "creeper",
            "axolotl", "axolotl_gold", "axolotl_blue", "parrot", "parrot_blue"};

    public String cape = "", capeHash = "", aura = "", halo = "", pet = "";

    public boolean empty() {
        return cape.isEmpty() && aura.isEmpty() && halo.isEmpty() && pet.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("cape", cape);
        if (!capeHash.isEmpty()) o.addProperty("capeHash", capeHash);
        o.addProperty("aura", aura);
        if (!halo.isEmpty()) o.addProperty("halo", halo);
        if (!pet.isEmpty()) o.addProperty("pet", pet);
        return o;
    }

    /** Reads what the server sent; anything unknown (or from a newer version) is ignored. */
    public static Equip fromJson(JsonObject o) {
        Equip e = new Equip();
        if (o == null) return e;
        e.cape = pick(o, "cape", CAPES);
        e.aura = pick(o, "aura", AURAS);
        e.halo = pick(o, "halo", HALOS);
        e.pet = pick(o, "pet", PETS);
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
