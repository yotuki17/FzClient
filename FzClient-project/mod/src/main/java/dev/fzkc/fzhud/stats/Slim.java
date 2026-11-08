package dev.fzkc.fzhud.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads a stats answer keeping only what the overlay uses. A Hypixel player object can be several hundred KB
 * (quests, challenges, housing, pets, every game ever played); building all of that as a JSON tree for each player
 * in a lobby made a lot of garbage at once, which the game then had to stop for. Inside a player object ("player"
 * or "hypixel", at any depth) only its plain values, "achievements" and the "stats" sections of the known games
 * are kept; everything else in the answer is read normally (it is small).
 */
final class Slim {
    private Slim() {}

    private static volatile Set<String> sections;

    private static Set<String> sections() {
        Set<String> s = sections;
        if (s == null) {
            s = new HashSet<>();
            for (Game g : Game.values()) if (g.section != null) s.add(g.section);
            sections = s;
        }
        return s;
    }

    static JsonObject parse(String body) throws IOException {
        JsonReader r = new JsonReader(new StringReader(body));
        r.setLenient(true);
        JsonElement e = any(r);
        if (!e.isJsonObject()) throw new IOException("not a JSON object");
        return e.getAsJsonObject();
    }

    /** Any value; objects named "player" / "hypixel" are read as player objects. */
    private static JsonElement any(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                JsonObject o = new JsonObject();
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if (("player".equals(k) || "hypixel".equals(k)) && r.peek() == JsonToken.BEGIN_OBJECT) o.add(k, player(r));
                    else o.add(k, any(r));
                }
                r.endObject();
                return o;
            }
            case BEGIN_ARRAY: {
                JsonArray a = new JsonArray();
                r.beginArray();
                while (r.hasNext()) a.add(any(r));
                r.endArray();
                return a;
            }
            default:
                return primitive(r);
        }
    }

    private static JsonObject player(JsonReader r) throws IOException {
        JsonObject o = new JsonObject();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            JsonToken t = r.peek();
            if (t == JsonToken.BEGIN_OBJECT) {
                if ("stats".equals(k)) o.add(k, stats(r));
                else if ("achievements".equals(k) || "player".equals(k)) o.add(k, "player".equals(k) ? player(r) : full(r));
                else r.skipValue();
            } else if (t == JsonToken.BEGIN_ARRAY) {
                r.skipValue();
            } else {
                o.add(k, primitive(r));
            }
        }
        r.endObject();
        return o;
    }

    private static JsonObject stats(JsonReader r) throws IOException {
        Set<String> keep = sections();
        JsonObject o = new JsonObject();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (keep.contains(k)) o.add(k, full(r));
            else r.skipValue();
        }
        r.endObject();
        return o;
    }

    /** A value read whole (no player rules inside). */
    private static JsonElement full(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                JsonObject o = new JsonObject();
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    o.add(k, full(r));
                }
                r.endObject();
                return o;
            }
            case BEGIN_ARRAY: {
                JsonArray a = new JsonArray();
                r.beginArray();
                while (r.hasNext()) a.add(full(r));
                r.endArray();
                return a;
            }
            default:
                return primitive(r);
        }
    }

    private static JsonElement primitive(JsonReader r) throws IOException {
        switch (r.peek()) {
            case STRING: return new JsonPrimitive(r.nextString());
            case NUMBER: return new JsonPrimitive(new LazilyParsedNumber(r.nextString()));
            case BOOLEAN: return new JsonPrimitive(r.nextBoolean());
            case NULL:
                r.nextNull();
                return JsonNull.INSTANCE;
            default:
                r.skipValue();
                return JsonNull.INSTANCE;
        }
    }
}
