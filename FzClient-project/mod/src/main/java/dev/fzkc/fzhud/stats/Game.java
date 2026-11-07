package dev.fzkc.fzhud.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The games FzClient shows stats for. Each one reads a few counters out of the Hypixel player data; everything
 * shown (ratios included) is worked out from those counters, so the same code shows all-time numbers and the
 * difference since the start of the day / week / month.
 */
public enum Game {
    BEDWARS("bw", "Bed Wars", "Bedwars", new String[]{"bedwars", "bed"},
            new String[]{"star", "a:bedwars_level", "fk", "final_kills_bedwars", "fd", "final_deaths_bedwars",
                    "w", "wins_bedwars", "l", "losses_bedwars", "bb", "beds_broken_bedwars", "bl", "beds_lost_bedwars",
                    "k", "kills_bedwars", "d", "deaths_bedwars", "ws", "winstreak"},
            "star", "ws") {
        @Override String prefix(Map<String, Long> c) {
            Long s = c.get("star");
            return s == null ? "" : starColor(s) + "[" + s + "✫]";
        }

        @Override Stat[] define() {
            return new Stat[]{
                    s("FKDR", "FKDR（ファイナルキル÷ファイナルデス）", c -> ratio(c, "fk", "fd", BW_FKDR)),
                    s("WLR", "WLR（勝ち÷負け）", c -> ratio(c, "w", "l", BW_RATIO)),
                    s("WS", "連勝", c -> plain(c, "ws", BW_WS)),
                    s("W", "勝ち", c -> n(c, "w", BW_WINS)),
                    s("L", "負け", c -> n(c, "l")),
                    s("FK", "ファイナルキル", c -> n(c, "fk", BW_FINALS)),
                    s("FD", "ファイナルデス", c -> n(c, "fd")),
                    s("BB", "ベッド破壊", c -> n(c, "bb", BW_BEDS)),
                    s("BBLR", "BBLR（ベッド破壊÷ベッドロスト）", c -> ratio(c, "bb", "bl", BW_RATIO)),
                    s("K", "キル", c -> n(c, "k")),
                    s("KDR", "KDR（キル÷デス）", c -> ratio(c, "k", "d", D_RATIO)),
                    s("★", "星（数字だけ）", c -> c.get("star") == null ? "§8?" : starColor(c.get("star")) + c.get("star")),
                    s("Index", "Index（星×FKDR²）", Game::index),
            };
        }

        @Override int[] defaults() { return new int[]{0, 1, 2, -1}; }

        @Override Mode[] defineModes() {
            return new Mode[]{
                    Mode.OVERALL,
                    new Mode("solo", "Solo", null, new String[]{"eight_one_"}, "solos", "1s", "eight_one"),
                    new Mode("doubles", "Doubles", null, new String[]{"eight_two_"}, "2s", "duos", "eight_two"),
                    new Mode("threes", "3v3v3v3", null, new String[]{"four_three_"}, "3s", "four_three"),
                    new Mode("fours", "4v4v4v4", null, new String[]{"four_four_"}, "4s", "four_four"),
                    new Mode("4v4", "4v4", null, new String[]{"two_four_"}, "two_four"),
            };
        }

        @Override List<String[]> chat(Map<String, Long> c, Map<String, Long> now, boolean period) {
            List<String[]> rows = new ArrayList<>();
            if (period) rows.add(row("星", gained(c, now, "star", "✫")));
            else rows.add(row("星", prefix(c)));
            rows.add(row("勝ち", n(c, "w", BW_WINS), "負け", n(c, "l"), "WLR", ratio(c, "w", "l", BW_RATIO)));
            rows.add(row("FK", n(c, "fk", BW_FINALS), "FD", n(c, "fd"), "FKDR", ratio(c, "fk", "fd", BW_FKDR)));
            rows.add(row("ベッド破壊", n(c, "bb", BW_BEDS), "ベッドロスト", n(c, "bl"), "BBLR", ratio(c, "bb", "bl", BW_RATIO)));
            rows.add(row("キル", n(c, "k"), "デス", n(c, "d"), "KDR", ratio(c, "k", "d", D_RATIO)));
            if (!period) rows.add(row("連勝", plain(now, "ws", BW_WS)));
            return rows;
        }
    },
    DUELS("duels", "Duels", "Duels", new String[]{"duel", "d"},
            new String[]{"w", "wins", "l", "losses", "k", "kills", "d", "deaths",
                    "ws", "current_winstreak", "bws", "best_overall_winstreak"},
            "ws", "bws") {
        @Override Stat[] define() {
            return new Stat[]{
                    s("W", "勝ち", c -> n(c, "w", D_WINS)),
                    s("WLR", "WLR（勝ち÷負け）", c -> ratio(c, "w", "l", D_RATIO)),
                    s("WS", "連勝", c -> plain(c, "ws", D_WS)),
                    s("L", "負け", c -> n(c, "l", D_WINS)),
                    s("K", "キル", c -> n(c, "k", D_KILLS)),
                    s("KDR", "KDR（キル÷デス）", c -> ratio(c, "k", "d", D_RATIO)),
                    s("BWS", "最高連勝", c -> plain(c, "bws", D_WS)),
            };
        }

        @Override int[] defaults() { return new int[]{0, 1, 2, -1}; }

        @Override List<String[]> chat(Map<String, Long> c, Map<String, Long> now, boolean period) {
            List<String[]> rows = new ArrayList<>();
            rows.add(row("勝ち", n(c, "w", D_WINS), "負け", n(c, "l", D_WINS), "WLR", ratio(c, "w", "l", D_RATIO)));
            rows.add(row("キル", n(c, "k", D_KILLS), "デス", n(c, "d", D_KILLS), "KDR", ratio(c, "k", "d", D_RATIO)));
            if (!period) rows.add(row("連勝", plain(now, "ws", D_WS), "最高連勝", plain(now, "bws", D_WS)));
            return rows;
        }

        @Override Mode[] defineModes() {
            return new Mode[]{
                    Mode.OVERALL,
                    duel("classic", "Classic", "classic", "classic_duel", "classic_doubles"),
                    duel("uhc", "UHC", "uhc", "uhc_duel", "uhc_doubles", "uhc_four", "uhc_meetup"),
                    duel("op", "OP", "op", "op_duel", "op_doubles"),
                    duel("sw", "SkyWars", "skywars", "sw_duel", "sw_doubles"),
                    duel("bridge", "Bridge", "bridge", "bridge_duel", "bridge_doubles", "bridge_threes", "bridge_four",
                            "bridge_2v2v2v2", "bridge_3v3v3v3", "capture_threes"),
                    duel("sumo", "Sumo", "sumo", "sumo_duel"),
                    duel("boxing", "Boxing", "boxing", "boxing_duel"),
                    duel("combo", "Combo", "combo", "combo_duel"),
                    duel("nodebuff", "NoDebuff", "no_debuff", "potion_duel"),
                    duel("bow", "Bow", "bow", "bow_duel"),
                    duel("blitz", "Blitz", "blitz", "blitz_duel"),
                    duel("tnt", "TNT", "tnt_games", "tnt_games_duel"),
                    duel("mw", "Mega Walls", "mega_walls", "mw_duel", "mw_doubles"),
                    duel("parkour", "Parkour", "parkour", "parkour_eight"),
            };
        }

        /** Duels modes: counters are "<prefix>_wins" summed over the mode's queues, streaks are per family. */
        @Override void readMode(JsonObject stats, JsonObject ach, Mode m, Map<String, Long> out, String into) {
            String[][] sums = {{"w", "wins"}, {"l", "losses"}, {"k", "kills"}, {"d", "deaths"}};
            for (String[] f : sums) {
                Long total = null;
                for (String p : m.prefixes) {
                    Long v = num(stats, p + "_" + f[1]);
                    if (v == null && "bridge".equals(m.family) && (f[0].equals("k") || f[0].equals("d"))) {
                        v = num(stats, p + "_bridge_" + f[1]);
                    }
                    if (v != null) total = (total == null ? 0 : total) + v;
                }
                if (total != null) out.put(into + f[0], total);
            }
            Long ws = num(stats, "current_" + m.family + "_winstreak"), bws = num(stats, "best_" + m.family + "_winstreak");
            if (ws != null) out.put(into + "ws", ws);
            if (bws != null) out.put(into + "bws", bws);
        }
    },
    TNT_TAG("tnttag", "TNT Tag", "TNTGames", new String[]{"tag", "tntag"},
            new String[]{"w", "wins_tntag", "k", "kills_tntag", "d", "deaths_tntag"}) {
        @Override Stat[] define() {
            return new Stat[]{
                    s("W", "勝ち", c -> n(c, "w")),
                    s("K", "タッチ", c -> n(c, "k")),
                    s("KDR", "KDR（タッチ÷爆発）", c -> ratio(c, "k", "d", WLR)),
                    s("D", "爆発", c -> n(c, "d")),
            };
        }

        @Override int[] defaults() { return new int[]{0, 1, 2, -1}; }

        @Override List<String[]> chat(Map<String, Long> c, Map<String, Long> now, boolean period) {
            List<String[]> rows = new ArrayList<>();
            rows.add(row("勝ち", n(c, "w")));
            rows.add(row("タッチ", n(c, "k"), "爆発", n(c, "d"), "KDR", ratio(c, "k", "d", WLR)));
            return rows;
        }
    },
    BOW_SPLEEF("bowspleef", "Bow Spleef", "TNTGames", new String[]{"spleef", "bs"},
            new String[]{"w", "wins_bowspleef", "l", "deaths_bowspleef", "t", "tags_bowspleef"}) {
        @Override Stat[] define() {
            return new Stat[]{
                    s("W", "勝ち", c -> n(c, "w")),
                    s("WLR", "WLR（勝ち÷負け）", c -> ratio(c, "w", "l", WLR)),
                    s("L", "負け", c -> n(c, "l")),
                    s("A", "撃った矢", c -> n(c, "t")),
            };
        }

        @Override int[] defaults() { return new int[]{0, 1, -1, -1}; }

        @Override List<String[]> chat(Map<String, Long> c, Map<String, Long> now, boolean period) {
            List<String[]> rows = new ArrayList<>();
            rows.add(row("勝ち", n(c, "w"), "負け", n(c, "l"), "WLR", ratio(c, "w", "l", WLR)));
            rows.add(row("撃った矢", n(c, "t")));
            return rows;
        }
    },
    MURDER("mm", "Murder Mystery", "MurderMystery", new String[]{"murder", "murdermystery"},
            new String[]{"w", "wins", "g", "games", "k", "kills", "d", "deaths",
                    "dw", "detective_wins", "mw", "murderer_wins", "bk", "bow_kills"}) {
        @Override Stat[] define() {
            return new Stat[]{
                    s("W", "勝ち", c -> n(c, "w")),
                    s("WR", "勝率", c -> percent(c, "w", "g")),
                    s("K", "キル", c -> n(c, "k")),
                    s("G", "試合", c -> n(c, "g")),
                    s("KDR", "KDR（キル÷デス）", c -> ratio(c, "k", "d", WLR)),
                    s("BK", "弓キル", c -> n(c, "bk")),
                    s("DW", "探偵で勝ち", c -> n(c, "dw")),
                    s("MW", "殺人鬼で勝ち", c -> n(c, "mw")),
            };
        }

        @Override int[] defaults() { return new int[]{0, 1, 2, -1}; }

        @Override List<String[]> chat(Map<String, Long> c, Map<String, Long> now, boolean period) {
            List<String[]> rows = new ArrayList<>();
            rows.add(row("勝ち", n(c, "w"), "試合", n(c, "g"), "勝率", percent(c, "w", "g")));
            rows.add(row("キル", n(c, "k"), "デス", n(c, "d"), "弓キル", n(c, "bk")));
            rows.add(row("探偵で勝ち", n(c, "dw"), "殺人鬼で勝ち", n(c, "mw")));
            return rows;
        }
    };

    public final String key, label, section;
    public final String[] aliases;
    /** Pairs: our short name, then the field in the game's stats section ("a:" = achievements). */
    final String[] fields;
    /** Counters that are a current state (a level, a streak), not a running total: never subtracted. */
    final Set<String> states;

    Game(String key, String label, String section, String[] aliases, String[] fields, String... states) {
        this.key = key;
        this.label = label;
        this.section = section;
        this.aliases = aliases;
        this.fields = fields;
        this.states = new HashSet<>();
        Collections.addAll(this.states, states);
    }

    /** Text in front of the name (the Bed Wars star); empty for most games. */
    String prefix(Map<String, Long> c) { return ""; }

    /** One stat the tab list can show: a short column title, a name for the settings, and how to work it out. */
    public static final class Stat {
        public final String head, label;
        final java.util.function.Function<Map<String, Long>, String> value;

        Stat(String head, String label, java.util.function.Function<Map<String, Long>, String> value) {
            this.head = head;
            this.label = label;
            this.value = value;
        }
    }

    static Stat s(String head, String label, java.util.function.Function<Map<String, Long>, String> value) {
        return new Stat(head, label, value);
    }

    /** Number of tab list columns that can be set per game. */
    public static final int TAB_COLUMNS = 4;

    /** Every stat this game can show in the tab list (the order the settings list them in; only add at the end). */
    abstract Stat[] define();

    /** The stats shown by default in columns 1..4 (index into define(), -1 = empty). */
    abstract int[] defaults();

    private Stat[] stats;

    public Stat[] stats() {
        if (stats == null) stats = define();
        return stats;
    }

    public int[] defaultColumns() { return defaults(); }

    /** The stats picked in the settings for the tab list, in column order (empty columns left out). */
    private List<Stat> columns() {
        List<Stat> out = new ArrayList<>();
        dev.fzkc.fzhud.config.ChoiceSetting[] picks = dev.fzkc.fzhud.Modules.statsColumns[ordinal()];
        for (dev.fzkc.fzhud.config.ChoiceSetting p : picks) {
            int i = p.get() - 1;   // option 0 = none
            if (i >= 0 && i < stats().length) out.add(stats()[i]);
        }
        return out;
    }

    String[] tabHeads() {
        List<Stat> cols = columns();
        String[] h = new String[cols.size()];
        for (int i = 0; i < h.length; i++) h[i] = cols.get(i).head;
        return h;
    }

    String[] tab(Map<String, Long> c) {
        List<Stat> cols = columns();
        String[] v = new String[cols.size()];
        for (int i = 0; i < v.length; i++) v[i] = cols.get(i).value.apply(c);
        return v;
    }

    /** Bed Wars index: star × FKDR², a common way to rank how strong someone is. */
    static String index(Map<String, Long> c) {
        Long star = c.get("star"), fk = c.get("fk"), fd = c.get("fd");
        if (star == null || fk == null) return "§8?";
        double r = fk / (double) Math.max(1, fd == null ? 0 : fd);
        double v = star * r * r;
        String col = v < 100 ? "§7" : v < 500 ? "§f" : v < 1000 ? "§e" : v < 3000 ? "§6" : v < 10000 ? "§c" : "§5";
        return col + (v >= 10000 ? String.format(Locale.US, "%.1fk", v / 1000) : String.format(Locale.US, "%d", Math.round(v)));
    }

    /** Rows of label/value pairs for the chat; {@code c} may be a difference, {@code now} is always the total. */
    abstract List<String[]> chat(Map<String, Long> c, Map<String, Long> now, boolean period);

    /** Reads this game's counters out of a Hypixel player object; a missing field stays missing. */
    void read(JsonObject player, Map<String, Long> out) {
        JsonObject stats = obj(obj(player, "stats"), section);
        JsonObject ach = obj(player, "achievements");
        for (int i = 0; i < fields.length; i += 2) {
            String f = fields[i + 1];
            JsonObject from = f.startsWith("a:") ? ach : stats;
            Long v = num(from, f.startsWith("a:") ? f.substring(2) : f);
            if (v != null) out.put(key + "." + fields[i], v);
        }
    }

    /** This game's counters (short names) out of a snapshot holding every game's. */
    Map<String, Long> slice(Map<String, Long> all) {
        Map<String, Long> m = new java.util.HashMap<>();
        String p = key + ".";
        for (Map.Entry<String, Long> e : all.entrySet()) {
            if (e.getKey().startsWith(p)) m.put(e.getKey().substring(p.length()), e.getValue());
        }
        return m;
    }

    /** now - base for running totals; states keep their current value. */
    Map<String, Long> minus(Map<String, Long> now, Map<String, Long> base) {
        Map<String, Long> m = new java.util.HashMap<>();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            Long b = base.get(e.getKey());
            if (states.contains(e.getKey())) {
                m.put(e.getKey(), e.getValue());
                if (b != null) m.put(e.getKey() + "+", e.getValue() - b);   // how much a level went up
                continue;
            }
            // no starting value (that source did not have this game): unknown rather than the whole total
            if (b != null) m.put(e.getKey(), Math.max(0, e.getValue() - b));
        }
        return m;
    }

    // ------------------------------------------------------------------ modes (Solo, Doubles, UHC, ...)

    /** One way to play a game. Overall = every mode together (the normal counters). */
    public static final class Mode {
        public static final Mode OVERALL = new Mode("overall", "Overall", null, new String[0], "all", "total");

        public final String key, label;
        final String family;
        final String[] prefixes, aliases;

        Mode(String key, String label, String family, String[] prefixes, String... aliases) {
            this.key = key;
            this.label = label;
            this.family = family;
            this.prefixes = prefixes;
            this.aliases = aliases;
        }

        public boolean overall() { return this == OVERALL; }
    }

    static Mode duel(String key, String label, String family, String... prefixes) {
        return new Mode(key, label, family, prefixes, family);
    }

    /** The modes of this game (Overall first); only Overall for games without modes. */
    Mode[] defineModes() { return new Mode[]{Mode.OVERALL}; }

    private Mode[] modes;

    public Mode[] modes() {
        if (modes == null) modes = defineModes();
        return modes;
    }

    public Mode mode(String name) {
        if (name == null) return null;
        String l = name.toLowerCase(Locale.ROOT);
        for (Mode m : modes()) {
            if (m.key.equals(l) || m.label.toLowerCase(Locale.ROOT).replace(" ", "").equals(l)) return m;
            for (String a : m.aliases) if (a.equals(l)) return m;
        }
        return null;
    }

    /**
     * Reads one mode's counters (Bed Wars style: the same fields with the mode's prefix; achievements such as
     * the star are the same in every mode). Keys go into {@code out} as {@code into + short}.
     */
    void readMode(JsonObject stats, JsonObject ach, Mode m, Map<String, Long> out, String into) {
        for (int i = 0; i < fields.length; i += 2) {
            String f = fields[i + 1];
            Long v = f.startsWith("a:") ? num(ach, f.substring(2)) : num(stats, m.prefixes[0] + f);
            if (v != null) out.put(into + fields[i], v);
        }
    }

    /** Key prefix of a mode's counters in PlayerData ("bw@solo."); never saved in the history. */
    String modeKey(Mode m) { return key + "@" + m.key + "."; }

    void readModes(JsonObject player, Map<String, Long> out) {
        JsonObject stats = obj(obj(player, "stats"), section);
        if (stats == null) return;
        JsonObject ach = obj(player, "achievements");
        for (Mode m : modes()) if (!m.overall()) readMode(stats, ach, m, out, modeKey(m));
    }

    Map<String, Long> slice(Map<String, Long> all, Mode m) {
        if (m == null || m.overall()) return slice(all);
        Map<String, Long> out = new java.util.HashMap<>();
        String p = modeKey(m);
        for (Map.Entry<String, Long> e : all.entrySet()) {
            if (e.getKey().startsWith(p)) out.put(e.getKey().substring(p.length()), e.getValue());
        }
        return out;
    }

    // for the tab list (another package)
    public String[] tabHeadsPublic() { return tabHeads(); }

    public String[] tabPublic(Map<String, Long> c) { return tab(c); }

    public String tabPrefix(Map<String, Long> c) {
        return this == BEDWARS && !dev.fzkc.fzhud.Modules.statsStar.get() ? "" : prefix(c);
    }

    public static Game byName(String s) {
        if (s == null) return null;
        String l = s.toLowerCase(Locale.ROOT);
        for (Game g : values()) {
            if (g.key.equals(l)) return g;
            for (String a : g.aliases) if (a.equals(l)) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ formatting

    private static final double[] FKDR = {1, 2, 3, 5, 10}, WLR = {0.5, 1, 2, 4, 8};

    /**
     * Colour steps like Mellow's: grey, white, green, dark green, yellow, gold, red, dark red, pink, purple
     * (the Duels ratio and streak steps skip dark green). The value is coloured by how many steps it reaches.
     */
    static final class Scale {
        final double[] steps;
        final String[] colors;

        Scale(String[] colors, double... steps) {
            this.colors = colors;
            this.steps = steps;
        }

        String color(double v) {
            int i = 0;
            while (i < steps.length && v >= steps[i]) i++;
            return colors[Math.min(i, colors.length - 1)];
        }
    }

    private static final String[] TEN = {"§7", "§f", "§a", "§2", "§e", "§6", "§c", "§4", "§d", "§5"},
            NINE = {"§7", "§f", "§a", "§e", "§6", "§c", "§4", "§d", "§5"};
    static final Scale BW_FKDR = new Scale(TEN, 1, 3, 5, 7, 10, 20, 30, 50, 100),
            BW_RATIO = new Scale(TEN, 0.3, 0.9, 1.5, 2.1, 3, 6, 9, 15, 30),
            BW_WINS = new Scale(TEN, 150, 300, 450, 1500, 2250, 4500, 7500, 15000, 30000),
            BW_BEDS = new Scale(TEN, 250, 500, 1250, 2500, 3750, 7500, 12500, 25000, 50000),
            BW_FINALS = new Scale(TEN, 500, 1000, 2500, 5000, 7500, 15000, 25000, 50000, 100000),
            BW_WS = new Scale(TEN, 5, 15, 25, 40, 50, 75, 100, 250, 500),
            D_RATIO = new Scale(NINE, 0.4, 0.8, 1.2, 2, 3, 5, 7, 10),
            D_WINS = new Scale(TEN, 5, 25, 100, 250, 500, 1000, 2500, 5000, 10000),
            D_KILLS = new Scale(TEN, 25, 100, 300, 1000, 2500, 5000, 10000, 15000, 25000),
            D_WS = new Scale(NINE, 1, 3, 5, 10, 15, 25, 50, 100);

    static String n(Map<String, Long> c, String k, Scale sc) {
        Long v = c.get(k);
        return v == null ? "§8?" : sc.color(v) + String.format(Locale.US, "%,d", v);
    }

    static String plain(Map<String, Long> c, String k, Scale sc) {
        Long v = c.get(k);
        return v == null ? "§8?" : sc.color(v) + v;
    }

    static String ratio(Map<String, Long> c, String a, String b, Scale sc) {
        Long x = c.get(a), y = c.get(b);
        if (x == null && y == null) return "§8?";
        double r = (x == null ? 0 : x) / (double) Math.max(1, y == null ? 0 : y);
        return sc.color(r) + String.format(Locale.US, "%.2f", r);
    }
    private static final String[] SCALE = {"§7", "§f", "§e", "§6", "§c", "§5"};

    static String[] row(String... cells) { return cells; }

    static String n(Map<String, Long> c, String k) {
        Long v = c.get(k);
        return v == null ? "§8?" : "§f" + String.format(Locale.US, "%,d", v);
    }

    static String plain(Map<String, Long> c, String k) {
        Long v = c.get(k);
        return v == null ? "§8?" : "§f" + v;
    }

    static String ratio(Map<String, Long> c, String a, String b, double[] steps) {
        Long x = c.get(a), y = c.get(b);
        if (x == null && y == null) return "§8?";
        double r = (x == null ? 0 : x) / (double) Math.max(1, y == null ? 0 : y);
        int i = 0;
        while (i < steps.length && r >= steps[i]) i++;
        return SCALE[i] + String.format(Locale.US, "%.2f", r);
    }

    static String percent(Map<String, Long> c, String a, String b) {
        Long x = c.get(a), y = c.get(b);
        if (x == null || y == null) return "§8?";
        double r = y == 0 ? 0 : 100.0 * x / y;
        return (r >= 60 ? "§6" : r >= 40 ? "§e" : "§f") + String.format(Locale.US, "%.1f%%", r);
    }

    static String gained(Map<String, Long> diffOrNow, Map<String, Long> now, String k, String unit) {
        Long cur = now.get(k), up = diffOrNow.get(k + "+");
        if (cur == null) return "§8?";
        return starColor(cur) + "[" + cur + unit + "]" + (up == null ? "" : (up > 0 ? " §a+" : " §7+") + up + unit);
    }

    static String starColor(long star) {
        String[] c = {"§7", "§f", "§6", "§b", "§2", "§3", "§4", "§d", "§9", "§5"};
        return star >= 1000 ? "§e§l" : c[(int) Math.max(0, star / 100)];
    }

    static JsonObject obj(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static Long num(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        return (long) e.getAsDouble();
    }
}
