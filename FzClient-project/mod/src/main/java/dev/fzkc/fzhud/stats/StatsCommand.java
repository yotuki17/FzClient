package dev.fzkc.fzhud.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pv [name] [game] [period] - a player's stats in a window (or in the chat, see the Overlay settings). Game: bw, duels, tnttag, bowspleef, mm
 * (default: the game you are in). Period: total, daily, weekly, monthly.
 */
public class StatsCommand extends CommandBase {
    private static final String HEAD = "§b[FzClient] ";

    @Override public String getCommandName() { return "pv"; }

    @Override public List<String> getCommandAliases() { return Arrays.asList("fzstats", "fzs"); }

    @Override public String getCommandUsage(ICommandSender sender) { return "/pv [name] [bw|duels|tnttag|bowspleef|mm] [total|daily|weekly|monthly]"; }

    @Override public int getRequiredPermissionLevel() { return 0; }

    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        List<String> words = new ArrayList<>();
        for (Game g : Game.values()) words.add(g.key);
        for (History.Period p : History.Period.values()) words.add(p.key);
        for (Game g : Game.values()) for (Game.Mode m : g.modes()) if (!m.overall()) words.add(m.key);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
            for (NetworkPlayerInfo i : mc.thePlayer.sendQueue.getPlayerInfoMap()) words.add(i.getGameProfile().getName());
        }
        return getListOfStringsMatchingLastWord(args, words.toArray(new String[0]));
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        String name = null, modeWord = null;
        Game game = null;
        History.Period period = null;
        for (String a : args) {
            Game g = Game.byName(a);
            History.Period p = History.Period.byName(a);
            if (g != null && game == null) game = g;
            else if (p != null && period == null) period = p;
            else if (modeWord == null && anyMode(a)) modeWord = a;
            else if (name == null) name = a;
        }
        if (game == null && modeWord != null) {
            // "/pv uhc": the game that has this mode
            for (Game g : Game.values()) if (g.mode(modeWord) != null) { game = g; break; }
        }
        if (game == null) game = Stats.current != null ? Stats.current : Game.BEDWARS;
        Game.Mode mode = game.mode(modeWord);
        if (mode == null) {
            if (modeWord != null && name == null) name = modeWord;   // a player called "sumo"
            mode = Game.Mode.OVERALL;
        }
        if (period == null) period = History.Period.TOTAL;
        if (name == null) name = mc.thePlayer.getName();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            say("§c" + Lang.t("名前が正しくありません: ") + name);
            return;
        }
        if (dev.fzkc.fzhud.Modules.statsPvMode.get() == 0) {
            // the window opens on the next tick (the chat closes right after a command)
            pendingScreen = new dev.fzkc.fzhud.gui.StatsScreen(name, game, mode, period);
            return;
        }
        final Game g = game;
        final Game.Mode md = mode;
        final History.Period per = period;
        final String nm = name;
        UUID known = uuidInTab(name);
        say("§7" + Lang.f("{0} の統計を取得中…", name));
        if (known != null) show(known, nm, g, md, per);
        else {
            new Thread(() -> {
                UUID id = lookup(nm);
                mc.addScheduledTask(() -> {
                    if (id == null) say("§c" + Lang.f("{0} というプレイヤーが見つかりません", nm));
                    else show(id, nm, g, md, per);
                });
            }, "FzClient-NameLookup").start();
        }
    }

    /** Same as the command, without a player in a world (developer check, see Stats.onTick). */
    static void check(String name, Game game, History.Period period) {
        new Thread(() -> {
            UUID id = lookup(name);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (id == null) say("statstest: no uuid for " + name);
                else show(id, name, game, Game.Mode.OVERALL, period);
            });
        }, "FzClient-StatsCheck").start();
    }

    private static boolean anyMode(String a) {
        for (Game g : Game.values()) {
            Game.Mode m = g.mode(a);
            if (m != null && !m.overall()) return true;
        }
        return false;
    }

    private static void show(UUID id, String name, Game game, Game.Mode mode, History.Period period) {
        Tags.get(id);   // start the tag lookups at the same time
        if (game == Game.BEDWARS) Sessions.get(id, period);
        StatsApi.fetch(id, name, d -> {
            // give the tags a moment (they are fetched in parallel), then print everything together
            new Thread(() -> {
                for (int i = 0; i < 40 && (Tags.loading(id) || Sessions.loading(id, period)); i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Minecraft.getMinecraft().addScheduledTask(() -> print(d, game, mode, period));
            }, "FzClient-StatsWait").start();
        }, err -> say("§c" + Lang.t("統計を取得できませんでした: ") + err));
    }

    /** The window /pv opens on the next tick (see Stats.onTick). */
    public static volatile net.minecraft.client.gui.GuiScreen pendingScreen;

    /** What to show for one player, game and period: rows of label/value pairs, or a message instead. */
    public static final class View {
        /** Rows of label, value, label, value, ... (labels are Japanese, translate with Lang.t). */
        public final List<String[]> rows;
        /** Shown instead of the rows ("§e..." / "§7..."), or null. */
        public final String message;
        /** "(recorded since ...)", or null. */
        public final String note;

        View(List<String[]> rows, String message, String note) {
            this.rows = rows;
            this.message = message;
            this.note = note;
        }
    }

    public static View view(PlayerData d, Game game, History.Period period) {
        return view(d, game, Game.Mode.OVERALL, period);
    }

    public static View view(PlayerData d, Game game, Game.Mode mode, History.Period period) {
        List<String[]> none = new ArrayList<>();
        if (d.nicked) return new View(none, "§c" + Lang.t("Hypixel にこの名前のデータがありません（ニックネームの可能性が高い）"), null);
        if (mode == null) mode = Game.Mode.OVERALL;
        if (!mode.overall()) {
            Map<String, Long> m = d.of(game, mode);
            // Bordic keeps only a few per-mode numbers (no wins): a card full of "?" helps nobody
            if (m.isEmpty() || "Bordic".equals(d.source) && !m.containsKey("w")) {
                if ("Bordic".equals(d.source)) {
                    return new View(none, "§e" + Lang.t("モード別の統計は Bordic にありません（Overlay → データ・API で Hypixel API か Bedlify に）"), null);
                }
                return new View(none, "§7" + Lang.t("このモードの記録がありません"), null);
            }
            if (period != History.Period.TOTAL) {
                View a = aurora(d, game, mode, period, m);
                if (a != null) return a;
                return new View(none, "§7" + Lang.t("モード別の 24時間・1週間・1か月は Bed Wars で Aurora キーがある時だけです"), null);
            }
            return new View(game.chat(m, m, false), null, null);
        }
        Map<String, Long> now = d.of(game);
        if (now.isEmpty()) {
            if ("Bordic".equals(d.source) && game != Game.BEDWARS && game != Game.DUELS) {
                return new View(none, "§e" + Lang.t("このゲームの統計は Bordic にありません（Overlay → データ・API で Hypixel API か Bedlify に）"), null);
            }
            return new View(none, "§7" + Lang.t("このゲームの記録がありません"), null);
        }
        Map<String, Long> shown = now;
        String note = null;
        if (period != History.Period.TOTAL) {
            View a = aurora(d, game, Game.Mode.OVERALL, period, now);
            if (a != null) return a;
            History.Base base = History.base(d.id, period);
            if (base == null) {
                return new View(none, "§7" + Lang.t("まだ記録がありません。今から記録するので、次からこの期間の数字が出ます。"), null);
            }
            shown = game.minus(now, game.slice(base.counters));
            if (base.partial) {
                note = Lang.f("（{0} からの記録）", new SimpleDateFormat("M/d HH:mm").format(new Date(base.since)));
            }
            String why = game == Game.BEDWARS ? Sessions.error(d.id, period) : null;
            if (why != null) note = (note == null ? "" : note + "  ") + Lang.t(why);
        }
        return new View(game.chat(shown, now, period != History.Period.TOTAL), null, note);
    }

    /**
     * Bed Wars changes from Aurora (null when there is no Aurora key, it is not Bed Wars, or Aurora has no
     * answer, so the history on this PC is used instead). A "loading" view while the answer is on its way.
     */
    private static View aurora(PlayerData d, Game game, Game.Mode mode, History.Period period, Map<String, Long> now) {
        if (game != Game.BEDWARS || !Sessions.available()) return null;
        Sessions.Result r = Sessions.get(d.id, period);
        if (r == null) {
            if (Sessions.loading(d.id, period)) return new View(new ArrayList<String[]>(), "§7" + Lang.t("Aurora から取得中…"), null);
            return null;
        }
        Map<String, Long> diff = game.fromDelta(r.delta, mode, now);
        String range = r.start > 0
                ? new SimpleDateFormat("M/d HH:mm").format(new Date(r.start)) + " 〜 " + new SimpleDateFormat("M/d HH:mm").format(new Date(r.end > 0 ? r.end : System.currentTimeMillis()))
                : "";
        String note = "Aurora" + (range.isEmpty() ? "" : " · " + range) + " · " + Lang.t("1時間ごとに更新");
        return new View(game.chat(diff, now, true), null, note);
    }

    private static void print(PlayerData d, Game game, Game.Mode mode, History.Period period) {
        String title = HEAD + "§f" + d.display() + " §8· §b" + game.label + (mode.overall() ? "" : " " + mode.label)
                + " §7(" + Lang.t(period.label) + ")";
        say(title);
        View v = view(d, game, mode, period);
        if (v.message != null) {
            say("  " + v.message);
            tags(d.id);
            if (!d.nicked) buttons(d, game, period);
            return;
        }
        String note = v.note;
        for (String[] row : v.rows) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i + 1 < row.length; i += 2) {
                if (i > 0) sb.append("  ");
                sb.append("§7").append(Lang.t(row[i])).append(' ').append(row[i + 1]);
            }
            say(sb.toString());
        }
        if (note != null) say("  §8" + note);
        tags(d.id);
        buttons(d, game, period);
    }

    private static void tags(UUID id) {
        for (Tags.Tag t : Tags.get(id)) say("  §7" + t.source + ": " + t.text);
        for (String p : Tags.problems(id)) say("  §8" + p);
        String hp = StatsApi.hypixelProblem();
        if (hp != null) say("  §8" + Lang.t(hp));
    }

    /** Clickable periods and games under the stats. */
    private static void buttons(PlayerData d, Game game, History.Period period) {
        ChatComponentText line = new ChatComponentText("  ");
        for (History.Period p : History.Period.values()) {
            line.appendSibling(button(Lang.t(p.label), p == period, "/pv " + d.name + " " + game.key + " " + p.key));
            line.appendSibling(new ChatComponentText(" "));
        }
        line.appendSibling(new ChatComponentText("§8|  "));
        for (Game g : Game.values()) {
            line.appendSibling(button(g.label, g == game, "/pv " + d.name + " " + g.key + " " + period.key));
            line.appendSibling(new ChatComponentText(" "));
        }
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(line);
    }

    private static IChatComponent button(String label, boolean on, String cmd) {
        ChatComponentText b = new ChatComponentText((on ? "§b§l" : "§7") + "[" + label + "]");
        b.setChatStyle(new ChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("§7" + cmd))));
        return b;
    }

    /** The UUID of a name in the tab list (or yours), else null. */
    public static UUID uuidInTab(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return null;
        if (mc.thePlayer.getName().equalsIgnoreCase(name)) return mc.thePlayer.getUniqueID();
        if (mc.thePlayer.sendQueue == null) return null;
        for (NetworkPlayerInfo i : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            if (i.getGameProfile().getName().equalsIgnoreCase(name)) return i.getGameProfile().getId();
        }
        return null;
    }

    /** Name -> UUID from Mojang. */
    public static UUID lookup(String name) {
        return StatsApi.lookupName(name);
    }

    private static void say(String s) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(s));
    }
}
