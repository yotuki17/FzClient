package dev.fzkc.fzhud.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One player's stats as last fetched: rank, and every game's counters ("bw.fk" = Bed Wars final kills). */
public final class PlayerData {
    public final UUID id;
    public final String name;
    /** "§b[MVP§c+§b] " or "" for players without a rank. */
    public final String rankPrefix;
    /** Colour code of the rank ("§b"), "§7" without a rank. */
    public final String rankColor;
    /** Hypixel has no such player: almost always a nick. */
    public final boolean nicked;
    /** Which service answered ("Hypixel" or "Bordic"). Bordic only knows Bed Wars and Duels. */
    public final String source;
    public final Map<String, Long> counters;
    public final long fetchedAt;
    /** When the service took this copy from Hypixel (Bordic keeps copies), else when we fetched it. */
    public volatile long dataAt;

    PlayerData(UUID id, String name, String rankPrefix, String rankColor, boolean nicked, String source,
               Map<String, Long> counters) {
        this.id = id;
        this.name = name;
        this.rankPrefix = rankPrefix;
        this.rankColor = rankColor;
        this.nicked = nicked;
        this.source = source;
        this.counters = Collections.unmodifiableMap(counters);
        this.fetchedAt = System.currentTimeMillis();
        this.dataAt = fetchedAt;
    }

    /** One game's (or mode's) counters, worked out once: the tab list asks for them many times a second. */
    private final Map<Object, Map<String, Long>> slices = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, Long> of(Game g) {
        Map<String, Long> s = slices.get(g);
        if (s == null) {
            s = java.util.Collections.unmodifiableMap(g.slice(counters));
            slices.put(g, s);
        }
        return s;
    }

    public Map<String, Long> of(Game g, Game.Mode m) {
        if (m == null || m.overall()) return of(g);
        String k = g.name() + "|" + m.key;
        Map<String, Long> s = slices.get(k);
        if (s == null) {
            s = java.util.Collections.unmodifiableMap(g.slice(counters, m));
            slices.put(k, s);
        }
        return s;
    }

    /** Made-up numbers for every game and mode, for the developer check of the /pv window (see DevTest). */
    public static PlayerData sample(UUID id, String name) {
        Map<String, Long> c = new HashMap<>();
        put(c, "bw", "star", 523, "fk", 8432, "fd", 1720, "w", 2150, "l", 1010, "bb", 4120, "bl", 1302,
                "k", 15230, "d", 12011, "ws", 7);
        put(c, "duels", "w", 4210, "l", 2051, "k", 5120, "d", 2533, "ws", 12, "bws", 43);
        put(c, "tnttag", "w", 37, "k", 812, "d", 655);
        put(c, "bowspleef", "w", 54, "l", 120, "t", 3021);
        put(c, "mm", "w", 610, "g", 1530, "k", 2210, "d", 905, "dw", 120, "mw", 210, "bk", 330);
        for (Game g : Game.values()) {
            Game.Mode[] ms = g.modes();
            for (int i = 1; i < ms.length; i++) {
                for (int f = 0; f < g.fields.length; f += 2) {
                    String k = g.fields[f];
                    Long v = c.get(g.key + "." + k);
                    if (v == null) continue;
                    long mv = "star".equals(k) ? v : g.states.contains(k) ? v / (i + 1)
                            : Math.round(v * (0.55 / i) * (0.7 + 0.06 * ((i * 7 + f * 3) % 10)));
                    c.put(g.modeKey(ms[i]) + k, mv);
                }
            }
        }
        return new PlayerData(id, name, "§6[MVP§c++§6] ", "§6", false, "Sample", c);
    }

    private static void put(Map<String, Long> c, String game, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) c.put(game + "." + kv[i], ((Number) kv[i + 1]).longValue());
    }

    /** Coloured "[MVP+] Name". */
    public String display() { return rankPrefix + rankColor + name; }

    static PlayerData nick(UUID id, String name, String source) {
        return new PlayerData(id, name, "", "§7", true, source, new HashMap<String, Long>());
    }

    static PlayerData parse(UUID id, String fallbackName, JsonObject player, String source) {
        String name = str(player, "displayname");
        if (name == null) name = fallbackName;
        Map<String, Long> counters = new HashMap<>();
        for (Game g : Game.values()) {
            g.read(player, counters);
            g.readModes(player, counters);
        }
        String[] rank = rank(player);
        return new PlayerData(id, name, rank[0], rank[1], false, source, counters);
    }

    /** {prefix, colour} the way Hypixel shows the rank in chat. */
    private static String[] rank(JsonObject p) {
        String custom = str(p, "prefix");
        if (custom != null && custom.length() > 2) {
            String c = custom.charAt(0) == '§' ? custom.substring(0, 2) : "§f";
            return new String[]{custom + " ", c};
        }
        String staff = str(p, "rank");
        if (staff != null) {
            switch (staff) {
                case "ADMIN": return new String[]{"§c[ADMIN] ", "§c"};
                case "GAME_MASTER": return new String[]{"§2[GM] ", "§2"};
                case "YOUTUBER": return new String[]{"§c[§fYOUTUBE§c] ", "§c"};
                case "STAFF": return new String[]{"§c[§6ዞ§c] ", "§c"};
                default: break;
            }
        }
        String plus = color(str(p, "rankPlusColor"), "§c");
        if ("SUPERSTAR".equals(str(p, "monthlyPackageRank"))) {
            String m = color(str(p, "monthlyRankColor"), "§6");
            return new String[]{m + "[MVP" + plus + "++" + m + "] ", m};
        }
        String pkg = str(p, "newPackageRank");
        if (pkg == null || "NONE".equals(pkg)) pkg = str(p, "packageRank");
        if (pkg == null) return new String[]{"", "§7"};
        switch (pkg) {
            case "MVP_PLUS": return new String[]{"§b[MVP" + plus + "+§b] ", "§b"};
            case "MVP": return new String[]{"§b[MVP] ", "§b"};
            case "VIP_PLUS": return new String[]{"§a[VIP§6+§a] ", "§a"};
            case "VIP": return new String[]{"§a[VIP] ", "§a"};
            default: return new String[]{"", "§7"};
        }
    }

    private static String color(String name, String def) {
        if (name == null) return def;
        try {
            return EnumChatFormatting.valueOf(name).toString();
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    static String str(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
