package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command suggestions while you type "/..." in the chat (the list newer Minecraft versions show).
 * After the command and a space, the list shows what can come next: the command's sub commands with a note on
 * what each does (/p invite, /f remove...), and player names that fit there - friends, guild and party members,
 * people you messaged, and the players in the tab list (see {@link KnownNames}).
 * <p>
 *  - commands you used before (from the chat's own sent-message history, newest first),
 *  - the commands of FzClient and other client-side mods,
 *  - a short list of the main Hypixel commands, only while you are on Hypixel.
 * <p>
 * Up / Down pick an entry, Tab fills it in (Enter still sends what is typed). When the list is not showing, Tab
 * and the arrow keys work as usual. It asks the server for nothing: no extra packets are sent.
 */
public class CommandSuggest {
    /** Main Hypixel commands (only offered on Hypixel). Not complete - your own history adds the rest over time. */
    private static final String[] HYPIXEL = {
            "party", "p", "msg", "w", "tell", "r", "reply", "friend", "f", "guild", "g", "lobby", "l", "hub",
            "play", "stats", "ignore", "report", "achievements", "chat", "limbo", "tip", "tipall"};

    private static final String SRC_MOD = "MOD", SRC_HYPIXEL = "Hypixel";

    private static Field inputField;
    private int sel;
    private String lastText = "";

    /** The chat's text box (it is protected in vanilla, so it is read with reflection). */
    private static GuiTextField field(GuiChat gui) {
        try {
            if (inputField == null) {
                for (String n : new String[]{"inputField", "field_146415_a"}) {
                    try {
                        Field f = GuiChat.class.getDeclaredField(n);
                        f.setAccessible(true);
                        inputField = f;
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            return inputField == null ? null : (GuiTextField) inputField.get(gui);
        } catch (Exception e) {
            return null;
        }
    }

    /** Hypixel by its address or, behind a proxy with another address, by the brand the server reports. */
    private static boolean onHypixel() {
        return dev.fzkc.fzhud.stats.Stats.onHypixel();
    }

    private static final String SRC_HISTORY = "履歴";

    /** First words of the commands you sent, newest first (the chat's own sent-message history). */
    private static List<String> usedCommands() {
        List<String> out = new ArrayList<>();
        try {
            List<String> sent = Minecraft.getMinecraft().ingameGUI.getChatGUI().getSentMessages();
            for (int i = sent.size() - 1; i >= 0 && out.size() < 40; i--) {
                String s = sent.get(i);
                if (s == null || !s.startsWith("/") || s.length() < 2) continue;
                int sp = s.indexOf(' ');
                String name = (sp < 0 ? s.substring(1) : s.substring(1, sp)).toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && !out.contains(name)) out.add(name);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * {text, tag, shown} rows matching what is typed, the best first. Empty when no list should show.
     * text replaces the word being typed, tag is the grey note on the right, shown is what the row says.
     */
    private List<String[]> suggestions(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null || !text.startsWith("/")) return out;

        if (!text.equals(lastText)) {
            lastText = text;
            sel = 0;
        }
        if (text.indexOf(' ') >= 0) return arguments(text);
        String prefix = text.substring(1).toLowerCase(Locale.ROOT);

        // all candidates in priority order: your history, Hypixel commands, client commands.
        // (Client commands used to come first: with a few mods installed they filled every row and /p, /party...
        // never showed.)
        boolean hypixel = Modules.cmdHypixel.get() && onHypixel();
        java.util.Set<String> client = new java.util.TreeSet<>();
        for (String n : ClientCommandHandler.instance.getCommands().keySet()) client.add(n.toLowerCase(Locale.ROOT));
        java.util.Set<String> hy = new java.util.HashSet<>(java.util.Arrays.asList(HYPIXEL));
        Map<String, String> all = new LinkedHashMap<>();
        // what is typed exactly, when it is a known command, comes first
        if (client.contains(prefix)) all.put(prefix, SRC_MOD);
        else if (hypixel && hy.contains(prefix)) all.put(prefix, SRC_HYPIXEL);
        for (String n : usedCommands()) {
            if (!all.containsKey(n)) all.put(n, client.contains(n) ? SRC_MOD : hy.contains(n) ? SRC_HYPIXEL : SRC_HISTORY);
        }
        if (hypixel) for (String n : HYPIXEL) if (!all.containsKey(n)) all.put(n, SRC_HYPIXEL);
        for (String n : client) if (!all.containsKey(n)) all.put(n, SRC_MOD);

        int max = (int) Modules.cmdRows.get();
        // names that start with what is typed first, then names that merely contain it
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (out.size() >= max) break;
            if (e.getKey().startsWith(prefix)) out.add(new String[]{"/" + e.getKey(), e.getValue(), "/" + e.getKey()});
        }
        if (!prefix.isEmpty()) {
            for (Map.Entry<String, String> e : all.entrySet()) {
                if (out.size() >= max) break;
                if (!e.getKey().startsWith(prefix) && e.getKey().contains(prefix)) {
                    out.add(new String[]{"/" + e.getKey(), e.getValue(), "/" + e.getKey()});
                }
            }
        }
        // typing a complete command with nothing else to offer: no point in showing it back
        if (out.size() == 1 && out.get(0)[0].equals("/" + prefix)) out.clear();
        if (sel >= out.size()) sel = 0;
        return out;
    }

    // ------------------------------------------------------------------ after the space

    private static final String SUB = "SUB";
    /** Short names Hypixel accepts for the same command. */
    private static final Map<String, String> ALIAS = new java.util.HashMap<>();
    static {
        String[][] a = {{"p", "party"}, {"f", "friend"}, {"g", "guild"}, {"w", "msg"}, {"tell", "msg"}, {"message", "msg"},
                {"whisper", "msg"}, {"l", "lobby"}, {"hub", "lobby"}};
        for (String[] x : a) ALIAS.put(x[0], x[1]);
    }

    /** {sub command, what it does} per command. */
    private static final Map<String, String[][]> SUBS = new java.util.HashMap<>();
    static {
        SUBS.put("party", new String[][]{
                {"invite", "招待する"}, {"list", "メンバー一覧"}, {"warp", "全員を自分のロビーへ"}, {"leave", "抜ける"},
                {"disband", "解散する"}, {"kick", "追い出す"}, {"kickoffline", "オフラインの人を追い出す"},
                {"transfer", "リーダーを渡す"}, {"promote", "昇格する"}, {"demote", "降格する"}, {"accept", "招待を受ける"},
                {"chat", "パーティチャットで送る"}, {"poll", "投票を作る"}, {"mute", "チャットをミュート"}, {"settings", "設定"}});
        SUBS.put("friend", new String[][]{
                {"add", "フレンド申請"}, {"accept", "申請を承認"}, {"deny", "申請を拒否"}, {"remove", "フレンドから外す"},
                {"list", "フレンド一覧"}, {"requests", "届いている申請"}, {"best", "ベストフレンドにする"},
                {"nickname", "ニックネームを付ける"}, {"notifications", "参加・退出の通知"}, {"removeall", "全員外す"}});
        SUBS.put("guild", new String[][]{
                {"online", "オンラインのメンバー"}, {"list", "メンバー一覧"}, {"invite", "招待する"}, {"accept", "招待を受ける"},
                {"chat", "ギルドチャットで送る"}, {"officer", "オフィサーチャットで送る"}, {"party", "オンラインの全員とパーティ"},
                {"kick", "追い出す"}, {"promote", "昇格する"}, {"demote", "降格する"}, {"mute", "ミュート"},
                {"unmute", "ミュート解除"}, {"member", "メンバーの情報"}, {"info", "ギルドの情報"}, {"motd", "今日のメッセージ"},
                {"top", "今日のトップ"}, {"quest", "ギルドクエスト"}, {"log", "ログ"}, {"notifications", "参加・退出の通知"},
                {"leave", "抜ける"}});
        SUBS.put("ignore", new String[][]{{"add", "無視する"}, {"remove", "無視をやめる"}, {"list", "無視している人"}});
        SUBS.put("chat", new String[][]{{"all", "全体チャット"}, {"party", "パーティチャット"}, {"guild", "ギルドチャット"},
                {"officer", "オフィサーチャット"}});
        SUBS.put("play", new String[][]{
                {"bedwars_eight_one", "Bed Wars ソロ"}, {"bedwars_eight_two", "Bed Wars ダブルス"},
                {"bedwars_four_three", "Bed Wars 3v3v3v3"}, {"bedwars_four_four", "Bed Wars 4v4v4v4"},
                {"bedwars_two_four", "Bed Wars 4v4"}, {"solo_normal", "SkyWars ソロ ノーマル"},
                {"solo_insane", "SkyWars ソロ インセイン"}, {"teams_normal", "SkyWars チーム ノーマル"},
                {"teams_insane", "SkyWars チーム インセイン"}, {"duels_classic_duel", "Duels Classic"},
                {"duels_sumo_duel", "Duels Sumo"}, {"duels_bridge_duel", "Duels Bridge"}, {"duels_uhc_duel", "Duels UHC"},
                {"duels_op_duel", "Duels OP"}, {"duels_bow_duel", "Duels Bow"}, {"duels_combo_duel", "Duels Combo"},
                {"duels_sw_duel", "Duels SkyWars"}, {"duels_boxing_duel", "Duels Boxing"},
                {"murder_classic", "Murder Mystery"}, {"build_battle_solo_normal", "Build Battle ソロ"},
                {"build_battle_guess_the_build", "Build Battle Guess the Build"}, {"tnt_tntrun", "TNT Run"},
                {"tnt_pvprun", "PVP Run"}, {"tnt_tntag", "TNT Tag"}, {"tnt_bowspleef", "Bow Spleef"}});
        SUBS.put("lobby", new String[][]{
                {"main", "メインロビー"}, {"bedwars", "Bed Wars"}, {"skywars", "SkyWars"}, {"duels", "Duels"},
                {"murder", "Murder Mystery"}, {"build", "Build Battle"}, {"arcade", "Arcade"}, {"tnt", "TNT Games"},
                {"pit", "The Pit"}, {"uhc", "UHC"}, {"blitz", "Blitz SG"}, {"megawalls", "Mega Walls"},
                {"classic", "Classic Games"}, {"housing", "Housing"}, {"prototype", "Prototype"}});
    }

    /** Where the names for the word after "command sub" come from (null = no names there). */
    private static String[] nameSources(String cmd, String sub, int index) {
        String[] anyone = {KnownNames.FRIEND, KnownNames.GUILD, KnownNames.PARTY, KnownNames.DM, KnownNames.ONLINE};
        String[] invitee = {KnownNames.FRIEND, KnownNames.GUILD, KnownNames.DM, KnownNames.ONLINE};
        if (cmd.equals("msg") || cmd.equals("stats") || cmd.equals("report") || cmd.equals("tip")) {
            return index == 1 ? new String[]{KnownNames.DM, KnownNames.FRIEND, KnownNames.GUILD, KnownNames.PARTY, KnownNames.ONLINE} : null;
        }
        if (cmd.equals("party")) {
            if (index == 1) return invitee;   // "/p Name" invites
            if (index >= 2 && (sub.equals("invite"))) return invitee;
            if (index == 2 && (sub.equals("kick") || sub.equals("transfer") || sub.equals("promote") || sub.equals("demote")))
                return new String[]{KnownNames.PARTY};
            if (index == 2 && sub.equals("accept")) return anyone;
            return null;
        }
        if (cmd.equals("friend")) {
            if (index == 1) return new String[]{KnownNames.ONLINE, KnownNames.GUILD, KnownNames.PARTY, KnownNames.DM};   // "/f Name" adds
            if (index != 2) return null;
            if (sub.equals("add")) return new String[]{KnownNames.ONLINE, KnownNames.GUILD, KnownNames.PARTY, KnownNames.DM};
            if (sub.equals("accept") || sub.equals("deny")) return new String[]{"request", KnownNames.ONLINE};
            if (sub.equals("remove") || sub.equals("best") || sub.equals("nickname")) return new String[]{KnownNames.FRIEND};
            return null;
        }
        if (cmd.equals("guild")) {
            if (index != 2) return null;
            if (sub.equals("invite")) return new String[]{KnownNames.FRIEND, KnownNames.PARTY, KnownNames.DM, KnownNames.ONLINE};
            if (sub.equals("kick") || sub.equals("promote") || sub.equals("demote") || sub.equals("mute")
                    || sub.equals("unmute") || sub.equals("member")) return new String[]{KnownNames.GUILD};
            if (sub.equals("accept")) return anyone;
            return null;
        }
        if (cmd.equals("ignore")) return index == 2 && (sub.equals("add") || sub.equals("remove")) ? anyone : null;
        return null;
    }

    /** Rows for the word being typed after the command: sub commands with what they do, then player names. */
    private List<String[]> arguments(String text) {
        List<String[]> out = new ArrayList<>();
        String[] words = text.substring(1).split(" ", -1);
        String typed = words[words.length - 1];
        String low = typed.toLowerCase(Locale.ROOT);
        int index = words.length - 1;   // 1 = first word after the command
        String raw = words[0].toLowerCase(Locale.ROOT);
        int max = (int) Modules.cmdRows.get();
        List<String[]> all = new ArrayList<>();

        // commands of FzClient and other client mods answer for themselves
        net.minecraft.command.ICommand mod = ClientCommandHandler.instance.getCommands().get(raw);
        if (mod != null) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                String[] args = new String[words.length - 1];
                System.arraycopy(words, 1, args, 0, args.length);
                List<String> opts = mc.thePlayer == null ? null : mod.addTabCompletionOptions(mc.thePlayer, args, null);
                if (opts != null) for (String o : opts) all.add(new String[]{o, SRC_MOD, o});
            } catch (Throwable ignored) {
            }
        } else if (Modules.cmdHypixel.get() && onHypixel()) {
            String cmd = ALIAS.containsKey(raw) ? ALIAS.get(raw) : raw;
            String sub = words.length > 2 ? words[1].toLowerCase(Locale.ROOT) : "";
            String[][] subs = SUBS.get(cmd);
            if (index == 1 && subs != null) for (String[] s : subs) all.add(new String[]{s[0], s[1], s[0]});
            if (index == 2 && cmd.equals("party") && sub.equals("chat")) return out;
            String[] from = nameSources(cmd, sub, index);
            if (from != null) for (String[] n : KnownNames.names(from)) all.add(new String[]{n[0], n[1], n[0]});
        } else if (index == 1 && (raw.equals("msg") || raw.equals("tell") || raw.equals("w"))) {
            for (String[] n : KnownNames.names(KnownNames.ONLINE)) all.add(new String[]{n[0], n[1], n[0]});
        }

        // words that start with what is typed first, then words that merely contain it
        for (String[] e : all) {
            if (out.size() >= max) break;
            if (e[0].toLowerCase(Locale.ROOT).startsWith(low)) out.add(e);
        }
        if (!low.isEmpty()) {
            for (String[] e : all) {
                if (out.size() >= max) break;
                String k = e[0].toLowerCase(Locale.ROOT);
                if (!k.startsWith(low) && k.contains(low)) out.add(e);
            }
        }
        if (out.size() == 1 && out.get(0)[0].equalsIgnoreCase(typed)) out.clear();
        if (sel >= out.size()) sel = 0;
        return out;
    }

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!Modules.commandSuggestEnabled() || !(event.gui instanceof GuiChat)) return;
        GuiTextField f = field((GuiChat) event.gui);
        if (f == null) return;
        List<String[]> list = suggestions(f.getText());
        if (list.isEmpty()) return;

        float rowH = 11f, pad = 4f;
        float nameW = 0, tagW = 0;
        for (String[] s : list) {
            nameW = Math.max(nameW, R.width(s[2]));
            tagW = Math.max(tagW, R.width(Lang.t(s[1])));
        }
        float w = pad * 2 + nameW + 10f + tagW;
        float h = pad * 2 + list.size() * rowH - 2f;
        // under the word being typed, like newer versions
        String text = f.getText();
        int cut = text.lastIndexOf(' ') + 1;
        float x = Math.min(2f + (cut > 0 ? R.width(text.substring(0, cut)) : 0f), event.gui.width - w - 2f);
        x = Math.max(2f, x);
        float y = event.gui.height - 16f - h;

        R.rounded(x, y, w, h, 3f, 0xE0101A1E);
        float ry = y + pad;
        for (int i = 0; i < list.size(); i++) {
            String[] s = list.get(i);
            boolean on = i == sel;
            if (on) R.rounded(x + 2f, ry - 1f, w - 4f, rowH, 2f, 0x405CE1E6);
            R.text(s[2], x + pad, ry, on ? R.ACCENT : 0xFFE6EEF0);
            String tag = Lang.t(s[1]);
            R.text(tag, x + w - pad - R.width(tag), ry, 0xFF8FA5AA);
            ry += rowH;
        }
    }

    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Modules.commandSuggestEnabled() || !(event.gui instanceof GuiChat)) return;
        if (!Keyboard.getEventKeyState()) return;
        GuiTextField f = field((GuiChat) event.gui);
        if (f == null) return;
        List<String[]> list = suggestions(f.getText());
        if (list.isEmpty()) return; // nothing showing: the keys keep their normal job

        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_DOWN) {
            sel = (sel + 1) % list.size();
            event.setCanceled(true);
        } else if (key == Keyboard.KEY_UP) {
            sel = (sel - 1 + list.size()) % list.size();
            event.setCanceled(true);
        } else if (key == Keyboard.KEY_TAB) {
            String text = f.getText();
            int cut = text.lastIndexOf(' ') + 1;
            f.setText(text.substring(0, cut) + list.get(sel)[0] + " ");
            sel = 0;
            event.setCanceled(true); // instead of the server's tab completion
        }
    }
}
