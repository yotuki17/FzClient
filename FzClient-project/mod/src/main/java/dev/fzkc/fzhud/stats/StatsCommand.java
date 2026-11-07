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
 * /fzstats [name] [game] [period] - a player's stats in the chat. Game: bw, duels, tnttag, bowspleef, mm
 * (default: the game you are in). Period: total, daily, weekly, monthly.
 */
public class StatsCommand extends CommandBase {
    private static final String HEAD = "§b[FzClient] ";

    @Override public String getCommandName() { return "fzstats"; }

    @Override public List<String> getCommandAliases() { return Arrays.asList("fzs"); }

    @Override public String getCommandUsage(ICommandSender sender) { return "/fzstats [name] [bw|duels|tnttag|bowspleef|mm] [total|daily|weekly|monthly]"; }

    @Override public int getRequiredPermissionLevel() { return 0; }

    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        List<String> words = new ArrayList<>();
        for (Game g : Game.values()) words.add(g.key);
        for (History.Period p : History.Period.values()) words.add(p.key);
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
        String name = null;
        Game game = null;
        History.Period period = null;
        for (String a : args) {
            Game g = Game.byName(a);
            History.Period p = History.Period.byName(a);
            if (g != null && game == null) game = g;
            else if (p != null && period == null) period = p;
            else if (name == null) name = a;
        }
        if (game == null) game = Stats.current != null ? Stats.current : Game.BEDWARS;
        if (period == null) period = History.Period.TOTAL;
        if (name == null) name = mc.thePlayer.getName();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            say("§c" + Lang.t("名前が正しくありません: ") + name);
            return;
        }
        final Game g = game;
        final History.Period per = period;
        final String nm = name;
        UUID known = uuidInTab(name);
        say("§7" + Lang.f("{0} の統計を取得中…", name));
        if (known != null) show(known, nm, g, per);
        else {
            new Thread(() -> {
                UUID id = lookup(nm);
                mc.addScheduledTask(() -> {
                    if (id == null) say("§c" + Lang.f("{0} というプレイヤーが見つかりません", nm));
                    else show(id, nm, g, per);
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
                else show(id, name, game, period);
            });
        }, "FzClient-StatsCheck").start();
    }

    private static void show(UUID id, String name, Game game, History.Period period) {
        Tags.get(id);   // start the tag lookups at the same time
        StatsApi.fetch(id, name, d -> {
            // give the tags a moment (they are fetched in parallel), then print everything together
            new Thread(() -> {
                for (int i = 0; i < 30 && Tags.loading(id); i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Minecraft.getMinecraft().addScheduledTask(() -> print(d, game, period));
            }, "FzClient-StatsWait").start();
        }, err -> say("§c" + Lang.t("統計を取得できませんでした: ") + err));
    }

    private static void print(PlayerData d, Game game, History.Period period) {
        String title = HEAD + "§f" + d.display() + " §8· §b" + game.label + " §7(" + Lang.t(period.label) + ")";
        say(title);
        if (d.nicked) {
            say("  §c" + Lang.t("Hypixel にこの名前のデータがありません（ニックネームの可能性が高い）"));
            tags(d.id);
            return;
        }
        Map<String, Long> now = d.of(game);
        if (now.isEmpty()) {
            if ("Bordic".equals(d.source) && game != Game.BEDWARS && game != Game.DUELS) {
                say("  §e" + Lang.t("このゲームの統計には Hypixel API キーが必要です（FzClient 設定 → Player Stats）"));
            } else {
                say("  §7" + Lang.t("このゲームの記録がありません"));
            }
            tags(d.id);
            buttons(d, game, period);
            return;
        }
        Map<String, Long> shown = now;
        String note = null;
        if (period != History.Period.TOTAL) {
            History.Base base = History.base(d.id, period);
            if (base == null || System.currentTimeMillis() - base.since < 120_000L) {
                say("  §7" + Lang.t("まだ記録がありません。今から記録するので、次からこの期間の数字が出ます。"));
                tags(d.id);
                buttons(d, game, period);
                return;
            }
            shown = game.minus(now, game.slice(base.counters));
            if (base.partial) {
                note = Lang.f("（{0} からの記録）", new SimpleDateFormat("M/d HH:mm").format(new Date(base.since)));
            }
        }
        for (String[] row : game.chat(shown, now, period != History.Period.TOTAL)) {
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
            line.appendSibling(button(Lang.t(p.label), p == period, "/fzstats " + d.name + " " + game.key + " " + p.key));
            line.appendSibling(new ChatComponentText(" "));
        }
        line.appendSibling(new ChatComponentText("§8|  "));
        for (Game g : Game.values()) {
            line.appendSibling(button(g.label, g == game, "/fzstats " + d.name + " " + g.key + " " + period.key));
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

    private static UUID uuidInTab(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer.getName().equalsIgnoreCase(name)) return mc.thePlayer.getUniqueID();
        if (mc.thePlayer.sendQueue == null) return null;
        for (NetworkPlayerInfo i : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            if (i.getGameProfile().getName().equalsIgnoreCase(name)) return i.getGameProfile().getId();
        }
        return null;
    }

    /** Name -> UUID from Mojang. */
    private static UUID lookup(String name) {
        try {
            String body = Http.get("Mojang", "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name, null, null);
            JsonObject o = new JsonParser().parse(body).getAsJsonObject();
            String id = PlayerData.str(o, "id");
            if (id == null || id.length() != 32) return null;
            return UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void say(String s) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(s));
    }
}
