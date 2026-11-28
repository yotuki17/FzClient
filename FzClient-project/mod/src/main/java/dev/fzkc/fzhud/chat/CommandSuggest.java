package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.command.ICommand;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
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
 * The commands listed are (no history: the sent messages are kept across restarts, so old commands - even ones
 * of mods that are gone - kept coming back):
 *  - a short list of the main Hypixel commands, only while you are on Hypixel,
 *  - Minecraft's own commands: in single player the ones you may use there (all of them with cheats on), on other
 *    servers the few anyone may use,
 *  - the commands of FzClient and other client-side mods (their main names, not every alias).
 * <p>
 * Up / Down pick an entry, Tab fills it in (Enter still sends what is typed). When the list is not showing, Tab
 * and the arrow keys work as usual, and while you go through your sent messages with Up / Down the list stays
 * out of the way. It asks the server for nothing: no extra packets are sent.
 */
public class CommandSuggest {
    /** Main Hypixel commands (only offered on Hypixel). */
    private static final String[] HYPIXEL = {
            "party", "p", "msg", "w", "tell", "r", "reply", "friend", "f", "guild", "g", "lobby", "l", "hub",
            "play", "stats", "ignore", "report", "achievements", "chat", "limbo", "tip", "tipall"};

    private static final String SRC_MOD = "MOD", SRC_HYPIXEL = "Hypixel", SRC_MC = "Minecraft";
    /** Minecraft commands anyone may use on a server (the rest need operator rights there). */
    private static final String[] VANILLA_SERVER = {"help", "list", "me", "msg", "tell", "w", "trigger"};

    private static Field inputField;
    /** The registered instance (dev checks read its rows). */
    public static CommandSuggest instance;

    public CommandSuggest() {
        instance = this;
    }

    /** Dev check: the rows shown for this text, as "shown [tag]". */
    public List<String> testRows(String text) {
        List<String> out = new ArrayList<>();
        lastText = "";
        for (String[] r : suggestions(text)) out.add(r[2] + " [" + r[1] + "] -> " + r[0]);
        return out;
    }
    private int sel;
    private String lastText = "";
    /** The text Up / Down put in from the sent messages: no list for it, so Up / Down keep going through them. */
    private String historyText;
    private boolean historyKey;

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

    /** True in single player (and when you host a LAN world): Minecraft's commands run here. */
    private static MinecraftServer localServer() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.isSingleplayer() ? MinecraftServer.getServer() : null;
    }

    /** You as the local server sees you (what the commands check rights on and complete for). */
    private static EntityPlayerMP localPlayer(MinecraftServer server) {
        Minecraft mc = Minecraft.getMinecraft();
        if (server == null || mc.thePlayer == null || server.getConfigurationManager() == null) return null;
        return server.getConfigurationManager().getPlayerByUsername(mc.thePlayer.getName());
    }

    /** Last list of the local server's commands you may use (worked out on the server's thread). */
    private volatile List<String> localCmds = Collections.emptyList();
    private long localAt;

    /**
     * Names of Minecraft's commands you may use here, sorted. In single player they are asked from the server's
     * own thread (its commands read its state, which that thread is changing) at most once a second; the list
     * shown is the last answer.
     */
    private List<String> vanillaCommands() {
        final MinecraftServer server = localServer();
        if (server == null) return onHypixel() ? Collections.<String>emptyList() : java.util.Arrays.asList(VANILLA_SERVER);
        long now = System.currentTimeMillis();
        if (now - localAt >= 1000) {
            localAt = now;
            try {
                server.addScheduledTask(() -> {
                    try {
                        EntityPlayerMP me = localPlayer(server);
                        java.util.Set<String> names = new java.util.TreeSet<>();
                        for (Object o : server.getCommandManager().getCommands().values()) {
                            ICommand c = (ICommand) o;
                            if (me == null || c.canCommandSenderUseCommand(me)) names.add(c.getCommandName().toLowerCase(Locale.ROOT));
                        }
                        localCmds = new ArrayList<>(names);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        return localCmds;
    }

    /** What the local server answered for one text: {text, rows}. Swapped as one, so the rows match the text. */
    private static final class Answer {
        final String text;
        final List<String> rows;

        Answer(String text, List<String> rows) {
            this.text = text;
            this.rows = rows;
        }
    }

    private volatile Answer localAnswer = new Answer("", Collections.<String>emptyList());
    private String localAsked = "";

    /**
     * Single player: what Minecraft's own command offers next for this text (game modes, names, items...). Asked
     * once per text on the server's thread, like the game's own Tab (which sends it to the server); until the
     * answer is there, nothing.
     */
    private List<String> localCompletion(final String text, final String raw, final String[] args) {
        Answer a = localAnswer;
        if (a.text.equals(text)) return a.rows;
        if (!text.equals(localAsked)) {
            localAsked = text;
            final MinecraftServer server = localServer();
            Minecraft mc = Minecraft.getMinecraft();
            // the block you look at, as the game's own Tab sends it (coordinates of /setblock, /fill...)
            final net.minecraft.util.BlockPos pos = mc.objectMouseOver != null
                    && mc.objectMouseOver.typeOfHit == net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK
                    ? mc.objectMouseOver.getBlockPos() : null;
            try {
                server.addScheduledTask(() -> {
                    List<String> rows = Collections.emptyList();
                    try {
                        ICommand c = (ICommand) server.getCommandManager().getCommands().get(raw);
                        EntityPlayerMP me = localPlayer(server);
                        if (c != null && me != null && c.canCommandSenderUseCommand(me)) {
                            List<String> opts = c.addTabCompletionOptions(me, args, pos);
                            if (opts != null) rows = new ArrayList<>(opts);
                        }
                    } catch (Throwable ignored) {
                    }
                    localAnswer = new Answer(text, rows);
                });
            } catch (Throwable ignored) {
            }
        }
        return Collections.emptyList();
    }

    /** Main names of the client-side commands (FzClient and other mods), sorted; aliases are left out. */
    private static java.util.Set<String> clientCommands() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Map.Entry<String, ICommand> e : ClientCommandHandler.instance.getCommands().entrySet()) {
            String main = e.getValue().getCommandName();
            if (main != null && main.equalsIgnoreCase(e.getKey())) out.add(main.toLowerCase(Locale.ROOT));
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
        if (historyText != null) {
            if (text.equals(historyText)) return out;   // going through the sent messages with Up / Down
            historyText = null;   // typed or changed since: the list works again
        }

        if (!text.equals(lastText)) {
            lastText = text;
            sel = 0;
        }
        if (text.indexOf(' ') >= 0) return arguments(text);
        String prefix = text.substring(1).toLowerCase(Locale.ROOT);

        // all candidates in priority order: Hypixel commands, Minecraft's commands, client commands.
        // (Client commands used to come first: with a few mods installed they filled every row and /p, /party...
        // never showed.)
        boolean hypixel = Modules.cmdHypixel.get() && onHypixel();
        java.util.Set<String> client = clientCommands();
        java.util.Set<String> hy = new java.util.HashSet<>(java.util.Arrays.asList(HYPIXEL));
        List<String> mcCmds = vanillaCommands();
        Map<String, String> all = new LinkedHashMap<>();
        // what is typed exactly, when it is a known command, comes first (a client command answers before the server)
        if (client.contains(prefix)) all.put(prefix, SRC_MOD);
        else if (hypixel && hy.contains(prefix)) all.put(prefix, SRC_HYPIXEL);
        else if (mcCmds.contains(prefix)) all.put(prefix, SRC_MC);
        if (hypixel) for (String n : HYPIXEL) if (!all.containsKey(n)) all.put(n, SRC_HYPIXEL);
        for (String n : mcCmds) if (!all.containsKey(n)) all.put(n, client.contains(n) ? SRC_MOD : SRC_MC);
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
        } else if (localServer() != null) {
            // single player: Minecraft's own command completes for itself (game modes, player names, items...)
            String[] args = new String[words.length - 1];
            System.arraycopy(words, 1, args, 0, args.length);
            for (String o : localCompletion(text, raw, args)) all.add(new String[]{o, SRC_MC, o});
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
        // above the chat tabs when they show (they would cover the last rows)
        float y = (ChatExtras.tabsOn() ? event.gui.height - 14f - ChatExtras.TAB_H - 3f : event.gui.height - 16f) - h;

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
        int key = Keyboard.getEventKey();
        List<String[]> list = suggestions(f.getText());
        if (list.isEmpty()) {
            // nothing showing: the keys keep their normal job; Up / Down bring back a sent message, which gets no list
            if (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN) historyKey = true;
            return;
        }

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

    @SubscribeEvent
    public void onKeyDone(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (!historyKey) return;
        historyKey = false;
        if (!(event.gui instanceof GuiChat)) return;
        GuiTextField f = field((GuiChat) event.gui);
        if (f != null) historyText = f.getText();
    }

    @SubscribeEvent
    public void onOpen(net.minecraftforge.client.event.GuiOpenEvent event) {
        historyText = null;   // a new chat starts with the list working
        historyKey = false;
    }
}
