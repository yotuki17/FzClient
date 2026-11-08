package dev.fzkc.fzhud.stats;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat on Hypixel (waiting lobbies and games: everyone; main lobbies, where many people talk: only people who say
 * your name, so the APIs are not flooded): puts the speaker's stats in front of their message ("[223✫ FKDR 2.10]") and warns
 * when someone with a Watcher / Xadia / Coral tag talks. A message whose speaker is not loaded yet waits up to
 * 1.2 s (later messages wait behind it, so the order stays the same) and is then sent on through Forge as usual.
 */
public class ChatStats {
    private static final long WAIT_MS = 1200, TAG_WAIT_MS = 8000;
    /** "[MVP+] Name: hi", "[223✫] [VIP] Name: hi", "Party > [MVP++] Name: hi", "Guild > Name [Officer]: hi". */
    private static final Pattern SPEAKER = Pattern.compile(
            "^(?:(?:Party|Guild|Officer|From) > |From )?(?:\\[[^\\]]{1,24}\\] ){0,3}(\\w{1,16})(?: \\[[^\\]]{1,24}\\])?: ");

    private static final class Held {
        final byte type;
        final IChatComponent message;
        final String name;
        final long until;

        Held(byte type, IChatComponent message, String name, long until) {
            this.type = type;
            this.message = message;
            this.name = name;
            this.until = until;
        }
    }

    private final Deque<Held> held = new ArrayDeque<>();
    private boolean reposting;

    /** Speakers whose tags are still being looked up (warn as soon as they arrive). */
    private static final class TagWatch {
        final UUID id;
        final String name;
        final long until;

        TagWatch(UUID id, String name, long until) {
            this.id = id;
            this.name = name;
            this.until = until;
        }
    }

    private final List<TagWatch> tagWatch = new ArrayList<>();

    /** Waiting lobby: a stats line under the message, like Mellow (once per player and world). */
    private static final class LineWatch {
        final String name;
        final long until;

        LineWatch(String name, long until) {
            this.name = name;
            this.until = until;
        }
    }

    private final List<LineWatch> lineWatch = new ArrayList<>();
    /** Names whose stats line was asked for from elsewhere (a party invite). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> REQUESTS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Puts the player's stats line in the chat as soon as it is loaded (like in a waiting lobby). */
    public static void requestLine(String name) {
        REQUESTS.add(name);
    }
    private final Set<String> lineShown = new HashSet<>();
    private Object lineWorld;
    private final Set<UUID> warned = new HashSet<>();

    private static boolean statsOn() {
        return Modules.stats.enabled && Modules.statsChat.get() && Stats.onHypixel();
    }

    private static boolean warnOn() {
        return Modules.stats.enabled && Modules.statsChatWarn.get() && Stats.onHypixel()
                && (Tags.watcherOn() || Tags.xadiaOn() || Tags.coralOn());
    }

    // ------------------------------------------------------------------ game start: everyone's stats

    /** "The game starts in 1 second!" (most games), "The match starts in 1 second!" */
    private static final Pattern COUNTDOWN = Pattern.compile("The (?:game|match) starts in 1 seconds?!?$");
    private Object startWorld;
    private long startAt, startUntil;
    private boolean startDone;

    private void gameStarting(String plain) {
        if (!Modules.stats.enabled || !Modules.statsChatStart.get() || !Stats.onHypixel() || Stats.lobby) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != startWorld) {
            startWorld = mc.theWorld;
            startDone = false;
            startAt = 0;
        }
        if (startDone) return;
        long now = System.currentTimeMillis();
        long at = 0;
        // teams are filled in the tab list right when the game starts: look a moment later
        if (COUNTDOWN.matcher(plain.trim()).find()) at = now + 2000;
        else if (plain.trim().equals("Protect your bed and destroy the enemy beds.")) at = now + 1000;
        if (at > 0 && (startAt == 0 || at < startAt)) {
            startAt = at;
            startUntil = at + 6000;
        }
    }

    /** One player in the start list. */
    private static final class Starter {
        final String name, team;
        final UUID id;

        Starter(String name, String team, UUID id) {
            this.name = name;
            this.team = team;
            this.id = id;
        }
    }

    private List<Starter> starters;

    private void startTick(long now) {
        Minecraft mc = Minecraft.getMinecraft();
        if (startAt == 0 || now < startAt || mc.thePlayer == null || mc.thePlayer.sendQueue == null) return;
        if (mc.theWorld != startWorld) {
            startAt = 0;
            starters = null;
            return;
        }
        if (starters == null) {
            starters = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            String myTeam = mc.thePlayer.getTeam() == null ? "" : mc.thePlayer.getTeam().getRegisteredName();
            for (net.minecraft.client.network.NetworkPlayerInfo info : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
                UUID pid = info.getGameProfile().getId();
                String nm = info.getGameProfile().getName();
                if (pid == null || nm == null || !nm.matches("[A-Za-z0-9_]{1,16}") || nm.equalsIgnoreCase(mc.thePlayer.getName())) continue;
                if (pid.version() != 4 && !Denick.isNick(info)) continue;   // NPCs and server-made entries
                String team = info.getPlayerTeam() == null ? "~" : info.getPlayerTeam().getRegisteredName();
                // your own team first, then the others by name
                starters.add(new Starter(nm, team.equals(myTeam) ? "" : team, pid));
                if (pid.version() == 4) {
                    ids.add(pid);
                    names.add(nm);
                }
                if (starters.size() >= 24) break;
            }
            starters.sort((a, b) -> a.team.equals(b.team) ? a.name.compareToIgnoreCase(b.name) : a.team.compareTo(b.team));
            StatsApi.prefetch(ids, names);
            if (Tags.watcherOn() || Tags.xadiaOn() || Tags.coralOn()) Tags.prefetch(ids);
            if (starters.isEmpty()) {
                startAt = 0;
                starters = null;
                return;
            }
        }
        // wait until everyone is loaded (or a few seconds), then print the whole list at once
        List<String> lines = new ArrayList<>();
        boolean waiting = false;
        for (Starter s : starters) {
            UUID id = idOf(s.name);
            String l = line(s.name, id, 2, "§8▪ ");
            if (l == null) waiting = true;
            lines.add(l);
        }
        if (waiting && now < startUntil) return;
        mc.thePlayer.addChatMessage(new ChatComponentText("§8[§bFzClient§8]§r §7" + Lang.t("試合開始 — 参加者の統計")));
        for (int i = 0; i < starters.size(); i++) {
            String l = lines.get(i);
            if (l == null || l.isEmpty()) l = "§8▪ §7" + starters.get(i).name + " §7|§r §8?";
            mc.thePlayer.addChatMessage(new ChatComponentText(l));
        }
        startDone = true;
        startAt = 0;
        starters = null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onChat(ClientChatReceivedEvent event) {
        if (reposting || event.type == 2 || event.isCanceled()) return;
        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;
        if (Denick.partyReply(plain)) {
            event.setCanceled(true);
            return;
        }
        Denick.onChat(plain);
        gameStarting(plain);
        if (!held.isEmpty()) {
            // keep the order: everything waits behind the message that is waiting
            held.add(new Held(event.type, event.message, null, 0));
            event.setCanceled(true);
            return;
        }
        boolean stats = statsOn(), warn = warnOn();
        if (!stats && !warn) return;
        if (plain.startsWith("[NPC]")) return;   // lobby NPCs talk in the same format
        Matcher m = SPEAKER.matcher(plain);
        if (!m.find()) return;
        String name = m.group(1);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName())) return;
        // a main lobby (Bed Wars lobby and so on): only people who mention you
        if (Stats.lobby && (mc.thePlayer == null || !mentions(plain.substring(m.end()), mc.thePlayer.getName()))) return;
        UUID id = idOf(name);
        if (warn && id != null) watchTags(id, name);
        if (!stats) return;
        if (!Stats.lobby) {
            // waiting lobby / game: the message stays as it is, the stats follow on their own line
            if (mc.theWorld != lineWorld) {
                lineWorld = mc.theWorld;
                lineShown.clear();
                lineWatch.clear();
            }
            if (lineShown.add(name.toLowerCase(Locale.ROOT))) lineWatch.add(new LineWatch(name, System.currentTimeMillis() + TAG_WAIT_MS));
            return;
        }
        String prefix = prefix(name, id);
        if (prefix != null) {
            if (!prefix.isEmpty()) event.message = withPrefix(prefix, event.message);
            return;
        }
        held.add(new Held(event.type, event.message, name, System.currentTimeMillis() + WAIT_MS));
        event.setCanceled(true);
    }

    /** True when the message text contains your name as a word (any case). */
    static boolean mentions(String text, String me) {
        if (!me.equals(mentionName)) {
            mentionName = me;
            mentionPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        }
        return mentionPattern.matcher(text).find();
    }

    private static String mentionName;
    private static Pattern mentionPattern;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        startTick(now);
        for (String r; (r = REQUESTS.poll()) != null; ) {
            idOf(r);   // starts the name lookup
            lineWatch.add(new LineWatch(r, now + TAG_WAIT_MS));
        }
        while (!held.isEmpty()) {
            Held h = held.peekFirst();
            String prefix = "";
            if (h.name != null) {
                UUID id = idOf(h.name);
                if (warnOn() && id != null) watchTags(id, h.name);
                String p = prefix(h.name, id);
                if (p == null && now < h.until) break;   // still loading
                if (p != null) prefix = p;
                else lineWatch.add(new LineWatch(h.name, now + TAG_WAIT_MS));   // too slow: the stats follow on their own line
            }
            held.pollFirst();
            repost(h.type, prefix.isEmpty() ? h.message : withPrefix(prefix, h.message));
        }
        if (!lineWatch.isEmpty()) {
            for (Iterator<LineWatch> it = lineWatch.iterator(); it.hasNext(); ) {
                LineWatch w = it.next();
                String line = line(w.name, idOf(w.name));
                if (line != null) {
                    it.remove();
                    if (!line.isEmpty() && Minecraft.getMinecraft().thePlayer != null) {
                        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(line));
                    }
                } else if (now > w.until) {
                    it.remove();
                }
            }
        }
        if (!tagWatch.isEmpty()) {
            for (Iterator<TagWatch> it = tagWatch.iterator(); it.hasNext(); ) {
                TagWatch w = it.next();
                List<Tags.Tag> tags = Tags.get(w.id);
                if (!tags.isEmpty()) {
                    it.remove();
                    warn(w.name, tags);
                } else if (now > w.until || !Tags.loading(w.id) && now > w.until - TAG_WAIT_MS + 3000) {
                    it.remove();
                }
            }
        }
    }

    /** Sends a held message on, the way the game does with a chat packet. */
    private void repost(byte type, IChatComponent msg) {
        ClientChatReceivedEvent ev = new ClientChatReceivedEvent(type, msg);
        reposting = true;
        try {
            if (MinecraftForge.EVENT_BUS.post(ev)) return;
        } finally {
            reposting = false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (ev.message != null && mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(ev.message);
    }

    /** The UUID behind a name in chat; for a nick that has been denicked, the real player's. */
    private static UUID idOf(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
            net.minecraft.client.network.NetworkPlayerInfo info = mc.thePlayer.sendQueue.getPlayerInfo(name);
            if (Denick.isNick(info)) {
                Denick.Real r = Denick.real(name);
                return r == null ? null : r.id;
            }
        }
        return StatsApi.uuidOf(name);
    }

    /**
     * The text to put in front of a message: "" when there is nothing to show, null while still loading.
     */
    private static String prefix(String name, UUID id) {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.network.NetworkPlayerInfo info = mc.thePlayer == null || mc.thePlayer.sendQueue == null
                ? null : mc.thePlayer.sendQueue.getPlayerInfo(name);
        if (Denick.isNick(info) && Denick.real(name) == null) return "§8[§c§lNICK§8] ";
        if (id == null) return StatsApi.nameUnknown(name) ? "§8[§c§lNICK§8] " : null;
        PlayerData d = StatsApi.get(id, name);
        if (d == null) return StatsApi.pending(id) ? null : "";   // loading, or the lookup failed
        if (d.nicked) return "§8[§c§lNICK§8] ";
        Game g = Stats.current != null ? Stats.current : Game.BEDWARS;
        java.util.Map<String, Long> c = d.of(g);
        if (c.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("§8[");
        String star = g.tabPrefix(c);
        if (!star.isEmpty()) sb.append(star.replace("[", "").replace("]", ""));
        String[] heads = g.tabHeadsPublic(), vals = g.tabPublic(c);
        for (int i = 0; i < Math.min(2, heads.length); i++) {
            if (sb.length() > 3) sb.append(' ');
            sb.append("§7").append(heads[i]).append(' ').append(vals[i]);
        }
        Denick.Real r = info != null && Denick.isNick(info) ? Denick.real(name) : null;
        if (r != null) sb.append(" §7= §a").append(r.name);
        return sb.append("§8] ").toString();
    }

    /**
     * "[FzClient] [422✫] [MVP+] Name | FKDR: 1.98" (Mellow's waiting lobby format, with the first tab column),
     * "" when there is nothing to show, null while loading.
     */
    private static String line(String name, UUID id) {
        return line(name, id, 1, "§8[§bFzClient§8]§r ");
    }

    /** The same with more tab columns and another start ("▪ " in the game start list). */
    private static String line(String name, UUID id, int cols, String head) {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.network.NetworkPlayerInfo info = mc.thePlayer == null || mc.thePlayer.sendQueue == null
                ? null : mc.thePlayer.sendQueue.getPlayerInfo(name);
        if (Denick.isNick(info) && Denick.real(name) == null) return head + "§7" + name + " §7|§r §c§lNICK";
        if (id == null) return StatsApi.nameUnknown(name) ? head + "§7" + name + " §7|§r §c§lNICK" : null;
        PlayerData d = StatsApi.get(id, name);
        if (d == null) return StatsApi.pending(id) ? null : "";
        if (d.nicked) return head + "§7" + name + " §7|§r §c§lNICK";
        Game g = Stats.current != null ? Stats.current : Game.BEDWARS;
        java.util.Map<String, Long> c = d.of(g);
        if (c.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(head);
        String star = g.tabPrefix(c);
        if (!star.isEmpty()) sb.append(star).append(" §r");
        // the name in its team colour (as the tab list and the game show it), the rank tag stays as it is
        String team = teamColor(info);
        sb.append(d.rankPrefix).append(team != null ? team : d.rankColor).append(d.name);
        Denick.Real r = info != null && Denick.isNick(info) ? Denick.real(name) : null;
        if (r != null) sb.append(" §7(§a").append(name).append("§7)");
        String[] heads = g.tabHeadsPublic(), vals = g.tabPublic(c);
        for (int i = 0; i < Math.min(cols, Math.min(heads.length, vals.length)); i++) {
            sb.append(" §7|§r ").append(heads[i]).append(": ").append(vals[i]);
        }
        if (cols > 1) {
            List<Tags.Tag> tags = Tags.get(id);
            if (!tags.isEmpty()) sb.append(" §c⚠ ").append(tags.get(0).text);
        }
        return sb.toString();
    }

    /** The last colour code of the player's team prefix ("§c" for red), or null when there is no team colour. */
    private static String teamColor(net.minecraft.client.network.NetworkPlayerInfo info) {
        if (info == null || info.getPlayerTeam() == null) return null;
        String p = info.getPlayerTeam().getColorPrefix();
        for (int i = p.length() - 2; i >= 0; i--) {
            if (p.charAt(i) == '§' && "0123456789abcdef".indexOf(Character.toLowerCase(p.charAt(i + 1))) >= 0) {
                return p.substring(i, i + 2);
            }
        }
        return null;
    }

    private static IChatComponent withPrefix(String prefix, IChatComponent msg) {
        ChatComponentText out = new ChatComponentText(prefix);
        out.appendSibling(msg);
        return out;
    }

    private void watchTags(UUID id, String name) {
        if (warned.contains(id)) return;
        for (TagWatch w : tagWatch) if (w.id.equals(id)) return;
        List<Tags.Tag> tags = Tags.get(id);
        if (!tags.isEmpty()) warn(name, tags);
        else tagWatch.add(new TagWatch(id, name, System.currentTimeMillis() + TAG_WAIT_MS));
    }

    private void warn(String name, List<Tags.Tag> tags) {
        UUID id = idOf(name);
        if (id != null && !warned.add(id)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return;
        StringBuilder sb = new StringBuilder("§c§l⚠ §f" + name + " §7" + Lang.t("にタグがあります: "));
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append("§7, ");
            sb.append(tags.get(i).text);
        }
        mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(sb.toString()));
        if (Modules.statsChatWarnSound.get() && mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1f, 0.6f);
    }
}
