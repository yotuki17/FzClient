package dev.fzkc.fzhud.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ChatComponentText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out who is behind a Hypixel nick. Hypixel gives nicked players a version-1 UUID, which is how they are
 * spotted. Two ways to find the real name:
 * <ul>
 *   <li>Skin: someone who nicks but keeps their own skin still carries it, and Mojang's signed skin data names
 *       the skin's owner. Hypixel's random nick skins also name an owner (whoever the skin came from), so a
 *       skin seen on two or more different nicks is remembered as a nick skin and ignored from then on.</li>
 *   <li>Numbers: Bed Wars kill messages can show the killer's lifetime count ("final #15234", "bed #3021").
 *       The Aurora lookup (Bordic) lists players with about that many finals / beds; one name on both lists is
 *       the player.</li>
 * </ul>
 * Answers found by skin or numbers are shared with other FzClient players (DenickShare), with the same overwrite rule
 * (see resolve). A shared answer never stops this game's own checks: an own find is newer and wins.
 */
public final class Denick {
    /** (an instance only listens for the player name event) */
    public Denick() {}

    private static boolean lastShow;

    /** Who a nick turned out to be. */
    public static final class Real {
        public final String name;
        /** How it was found, as shown ("skin", "numbers · shared", ...). */
        public final String how;
        /** skin, numbers, party or manual. */
        final String code;
        /** The nick as it was written. */
        public final String nick;
        /** Found by another FzClient player (the shared answers), not by this game. */
        public final boolean shared;
        /** Hash of the skin the nick wore when it was found ("" = none). */
        final String skin;
        /** When it was found, on this game's clock (now()). */
        volatile long at;
        public volatile UUID id;

        Real(String name, String code, String nick, boolean shared, String skin, long at) {
            this.name = name;
            this.code = code;
            this.how = label(code) + (shared ? " · " + Lang.t("共有") : "");
            this.nick = nick;
            this.shared = shared;
            this.skin = skin == null ? "" : skin;
            this.at = at;
        }
    }

    private static String label(String code) {
        switch (code) {
            case "skin": return Lang.t("スキン");
            case "numbers": return Lang.t("数字");
            case "party": return Lang.t("パーティ・消去法");
            case "manual": return Lang.t("手動");
            default: return code;
        }
    }

    /** This game's clock for comparing answers (ms; not moved by the computer's clock being set). */
    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private static final Map<String, Real> RESOLVED = new ConcurrentHashMap<>();
    /** "/pdenick <nick> reset" (now()): shared answers found before that are not taken. */
    private static final Map<String, Long> FORGOT = new ConcurrentHashMap<>();
    /** Nicks this game sent an answer for (only those can be taken back on the server). */
    static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** Nicks looked at once more because a shared answer replaced this game's own one (once per session). */
    private static final Set<String> RECHECKED = new HashSet<>();
    /** "nick=name" shared answers naming an account Mojang does not know (not taken again this session). */
    private static final Set<String> REJECTED = new HashSet<>();
    private static boolean lastShare;
    /**
     * Nicks already looked at (by skin) in this session, with the skin they had then: the same nick with another skin
     * is looked at again (someone else using that nick now).
     */
    private static final Map<String, String> CHECKED = new HashMap<>();
    private static final Set<String> ANNOUNCED = new HashSet<>();
    /** Skin hash -> nicks seen wearing it; two or more = one of Hypixel's nick skins. Saved between sessions. */
    private static final Map<String, Set<String>> SKIN_NICKS = new HashMap<>();
    private static boolean skinsLoaded, skinsDirty;

    /** Number lookups per nick: candidates from finals and from beds (null = not asked yet). */
    private static final class Numbers {
        List<String> finals, beds;
        boolean finalsBusy, bedsBusy;
    }

    private static final Map<String, Numbers> NUMBERS = new HashMap<>();

    private static final Pattern FINAL = Pattern.compile("^(\\w{1,16}) was (\\w{1,16})'s final #([\\d,]+)\\. FINAL KILL!$");
    private static final Pattern BED = Pattern.compile("^(?:BED DESTRUCTION > )?(\\w+) [Bb]ed was bed #([\\d,]+) destroyed by (\\w{1,16})!$");

    public static boolean isNick(NetworkPlayerInfo info) {
        return info != null && info.getGameProfile().getId() != null && info.getGameProfile().getId().version() == 1;
    }

    /** The real player behind a nick name, or null. */
    public static Real real(String nick) {
        return nick == null ? null : RESOLVED.get(nick.toLowerCase(Locale.ROOT));
    }

    static boolean on() {
        return Modules.stats.enabled && (Modules.denickSkin.get() || Modules.denickNumbers.get() || Modules.denickTeam.get()
                || Modules.denickShare.get()) && Stats.onHypixel();
    }

    /** New world: number lookups start over (the nicks may stay the same, so resolved names are kept). */
    static void worldChanged() {
        NUMBERS.clear();
    }

    /** Once a second: look at nicked players in the tab list. */
    static void tick() {
        boolean show = Modules.denickShowReal.get() && Modules.stats.enabled;
        if (show != lastShow) {   // the display setting changed: names again
            lastShow = show;
            refreshAllNames();
        }
        DenickShare.tick();
        boolean share = DenickShare.on();
        if (share != lastShare) {   // sharing turned off: shared answers go, nothing more is sent
            lastShare = share;
            if (!share) {
                DenickShare.clear();
                for (Map.Entry<String, Real> e : RESOLVED.entrySet()) {
                    if (!e.getValue().shared) continue;
                    RESOLVED.remove(e.getKey(), e.getValue());
                    refreshNames(e.getValue().nick);
                }
            }
        }
        if (!on()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) return;
        loadSkins();
        for (NetworkPlayerInfo info : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            if (!isNick(info)) continue;
            String nick = info.getGameProfile().getName();
            String k = nick.toLowerCase(Locale.ROOT);
            DenickShare.saw(nick);
            String[] owner = skinOwner(info);
            String skin = owner == null ? "" : owner[1];
            String before = CHECKED.put(k, skin);
            if (skin.equals(before)) continue;
            boolean otherSkin = before != null;
            Real known = RESOLVED.get(k);
            if (otherSkin && known != null && known.shared && !known.skin.equals(skin) && RESOLVED.remove(k, known)) {
                // a shared answer only holds while the nick wears the skin it was found with (someone else now?)
                refreshNames(nick);
                known = null;
            }
            boolean ownChecks = Modules.denickSkin.get() || Modules.denickNumbers.get() || Modules.denickTeam.get();
            if (ownChecks && !ANNOUNCED.contains(k) && known == null) {
                say("§d[Denick] §f" + nick + " §7" + Lang.t("はニックです"));
                ANNOUNCED.add(k);
            }
            // a known nick is looked at again only when it shows up with another skin (a new answer replaces the old);
            // a shared answer does not stop the own check
            if (Modules.denickSkin.get() && (otherSkin || known == null || known.shared)) bySkin(info, nick);
        }
        if (Modules.denickTeam.get()) byParty(mc);
        if (skinsDirty) {
            skinsDirty = false;
            saveSkins();
        }
    }

    // ------------------------------------------------------------------ skin

    private static void bySkin(NetworkPlayerInfo info, String nick) {
        String[] owner = skinOwner(info);
        if (owner == null) return;
        String hash = owner[1], name = owner[0];
        Set<String> nicks = SKIN_NICKS.get(hash);
        if (nicks == null) {
            nicks = new HashSet<>();
            SKIN_NICKS.put(hash, nicks);
        }
        if (nicks.add(nick.toLowerCase(Locale.ROOT))) skinsDirty = true;
        if (nicks.size() >= 2) return;   // the same skin on several nicks: a Hypixel nick skin, not theirs
        if (name.equalsIgnoreCase(nick)) return;
        // the owner only counts when they wear this exact skin right now (a random nick skin's original owner
        // has almost always changed theirs since)
        new Thread(() -> {
            UUID id = StatsApi.lookupName(name);
            String now = id == null ? null : currentSkin(id);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (now == null) return;
                if (now.equals(hash)) resolve(nick, name, "skin");
                else {
                    // not theirs: remember the skin as a nick skin
                    SKIN_NICKS.get(hash).add("*");
                    skinsDirty = true;
                }
            });
        }, "FzClient-DenickSkin").start();
    }

    /** A skin known as one of Hypixel's nick skins (seen on two or more nicks, counting this one, or not its owner's). */
    private static boolean isNickSkin(String hash, String nick) {
        Set<String> nicks = SKIN_NICKS.get(hash);
        if (nicks == null) return false;
        if (nicks.contains("*")) return true;
        return nicks.size() + (nicks.contains(nick.toLowerCase(Locale.ROOT)) ? 0 : 1) >= 2;
    }

    /** Hash of the skin a player wears right now (Mojang's session server), or null. */
    private static String currentSkin(UUID id) {
        try {
            String body = Http.get("Mojang", "https://sessionserver.mojang.com/session/minecraft/profile/"
                    + id.toString().replace("-", ""), null, null);
            JsonArray props = new JsonParser().parse(body).getAsJsonObject().getAsJsonArray("properties");
            for (JsonElement e : props) {
                JsonObject p = e.getAsJsonObject();
                if (!"textures".equals(PlayerData.str(p, "name"))) continue;
                String json = new String(Base64.getDecoder().decode(PlayerData.str(p, "value")), StandardCharsets.UTF_8);
                String url = PlayerData.str(Game.obj(Game.obj(new JsonParser().parse(json).getAsJsonObject(), "textures"), "SKIN"), "url");
                return url == null ? "" : url.substring(url.lastIndexOf('/') + 1);
            }
            return "";
        } catch (Exception e) {
            return null;
        }
    }

    /** {owner's name, skin hash} out of the signed skin data, or null. */
    private static String[] skinOwner(NetworkPlayerInfo info) {
        try {
            java.util.Collection<Property> props = info.getGameProfile().getProperties().get("textures");
            if (props == null || props.isEmpty()) return null;
            String json = new String(Base64.getDecoder().decode(props.iterator().next().getValue()), StandardCharsets.UTF_8);
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            String name = PlayerData.str(o, "profileName");
            JsonObject skin = Game.obj(Game.obj(o, "textures"), "SKIN");
            String url = PlayerData.str(skin, "url");
            if (name == null || url == null || !name.matches("\\w{1,16}")) return null;
            return new String[]{name, url.substring(url.lastIndexOf('/') + 1)};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ numbers

    /** Bed Wars kill / bed messages with a lifetime number next to a nicked player's name. */
    static void onChat(String plain) {
        if (!on() || !Modules.denickNumbers.get() || Modules.denickAuroraKey.get().trim().isEmpty()) return;
        if (Stats.current != Game.BEDWARS || Stats.lobby) return;
        Matcher m = FINAL.matcher(plain);
        if (m.matches()) {
            int n = parse(m.group(3));
            if (n >= (int) Modules.denickMinFinals.get()) lookup(m.group(2), "finals", n);
            return;
        }
        m = BED.matcher(plain);
        if (m.matches()) {
            int n = parse(m.group(2));
            if (n >= (int) Modules.denickMinFinals.get() / 5) lookup(m.group(3), "beds", n);
        }
    }

    private static void lookup(String nick, String type, int value) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) return;
        NetworkPlayerInfo info = mc.thePlayer.sendQueue.getPlayerInfo(nick);
        if (!isNick(info) || known(nick)) return;
        Numbers nb = NUMBERS.computeIfAbsent(nick.toLowerCase(Locale.ROOT), k -> new Numbers());
        boolean finals = type.equals("finals");
        if (finals ? (nb.finalsBusy || nb.finals != null) : (nb.bedsBusy || nb.beds != null)) return;
        if (finals) nb.finalsBusy = true;
        else nb.bedsBusy = true;
        int range = finals ? 50 : 20;
        String key = Modules.denickAuroraKey.get().trim();
        say("§d[Denick] §7" + Lang.f("{0} を数字で調べています（{1} #{2}）", nick, finals ? "final" : "bed", value));
        new Thread(() -> {
            List<String> found = null;
            String error = null;
            try {
                String body = Http.get("Aurora", "https://bordic.xyz/api/v2/resources/lookup/" + type + "?key="
                        + URLEncoder.encode(key, "UTF-8") + "&value=" + value + "&range=" + range + "&max=10", null, null);
                JsonObject r = new JsonParser().parse(body).getAsJsonObject();
                found = new ArrayList<>();
                JsonArray data = r.getAsJsonArray("data");
                if (data != null) {
                    for (JsonElement e : data) {
                        JsonObject p = e.getAsJsonObject();
                        String name = PlayerData.str(p, "name");
                        Long dist = Game.num(p, "distance");
                        if (name != null && (dist == null || dist <= range)) found.add(name);
                    }
                }
            } catch (Http.Status s) {
                error = s.code == 401 || s.code == 403 ? Lang.t("Aurora の API キーが違います") : s.getMessage();
            } catch (Exception ex) {
                error = ex.getMessage();
            }
            final List<String> result = found;
            final String err = error;
            mc.addScheduledTask(() -> numbersDone(nick, nb, finals, result, err));
        }, "FzClient-Denick").start();
    }

    private static void numbersDone(String nick, Numbers nb, boolean finals, List<String> found, String error) {
        if (finals) nb.finalsBusy = false;
        else nb.bedsBusy = false;
        if (found == null) {
            say("§d[Denick] §c" + (error == null ? "error" : error));
            return;
        }
        if (finals) nb.finals = found;
        else nb.beds = found;
        if (known(nick)) return;
        if (nb.finals != null && nb.beds != null) {
            List<String> both = new ArrayList<>(nb.finals);
            both.retainAll(nb.beds);
            if (both.size() == 1) {
                resolve(nick, both.get(0), "numbers");
                return;
            }
            if (both.isEmpty()) {
                say("§d[Denick] §7" + Lang.f("{0}：ファイナルとベッドの両方に合う人はいませんでした", nick));
                return;
            }
            found = both;
        }
        if (found.size() == 1 && nb.finals != null && nb.beds == null && finals) {
            say("§d[Denick] §f" + nick + " §7" + Lang.t("の候補: ") + "§a" + found.get(0) + " §8" + Lang.t("（ベッドの数字でも合えば確定）"));
        } else if (found.isEmpty()) {
            say("§d[Denick] §7" + Lang.f("{0}：その数字の人は見つかりませんでした", nick));
        } else {
            say("§d[Denick] §f" + nick + " §7" + Lang.t("の候補: ") + "§a" + String.join("§7, §a", found));
        }
    }

    // ------------------------------------------------------------------ teammates, by elimination

    /** When we sent /pl ourselves (its answer is hidden), and in which world. */
    private static long partyAskedAt;
    private static Object partyAskedWorld;
    private static final List<String> partyReply = new ArrayList<>();
    /** Real names seen in the tab list in this world: a party member seen here is not the nick (they only died or left a moment). */
    private static final Set<String> seenHere = new HashSet<>();
    private static Object seenWorld;
    /** The nick = party member pair found last time, and for how many checks in a row (once a second). */
    private static String pendingPair;
    private static int pendingCount;

    /**
     * Your party moves into a game with you, and party members show their real names in party chat and /pl. So a
     * party member who is not in the tab list under that name is playing nicked: when exactly one nick on your team
     * is still unknown and exactly one party member is missing, they are the same person.
     * Only teammates: without a known team of your own nothing is matched, a party member who was in the tab list under
     * their real name at any time in this world is not missing (players drop out of the tab list for a moment when
     * they die), a team bigger than the party (random players in it) is skipped, and the same pair has to hold for
     * 3 seconds.
     */
    private static void byParty(Minecraft mc) {
        if (Stats.lobby || mc.thePlayer == null || mc.theWorld == null) return;
        if (seenWorld != mc.theWorld) {
            seenWorld = mc.theWorld;
            seenHere.clear();
            pendingPair = null;
            pendingCount = 0;
        }
        NetworkPlayerInfo meInfo = mc.thePlayer.sendQueue.getPlayerInfo(mc.thePlayer.getUniqueID());
        String myTeam = teamKey(meInfo);
        java.util.Collection<NetworkPlayerInfo> all = mc.thePlayer.sendQueue.getPlayerInfoMap();
        for (NetworkPlayerInfo i : all) {
            String n = i.getGameProfile().getName();
            if (n != null && !isNick(i)) seenHere.add(n.toLowerCase(Locale.ROOT));
        }
        int teamSize = 0;
        if (myTeam != null) for (NetworkPlayerInfo i : all) if (sameTeam(i, myTeam)) teamSize++;
        // a team that holds everyone (waiting lobby) says nothing: then every nick counts
        boolean useTeam = myTeam != null && teamSize > 1 && teamSize < all.size();
        if (!useTeam) { pendingPair = null; why("no team (" + myTeam + ", " + teamSize + "/" + all.size() + ")"); return; }   // only your own team: an unknown team could match an enemy
        List<String> nicks = new ArrayList<>();
        Set<String> visible = new HashSet<>();
        for (NetworkPlayerInfo i : all) {
            String n = i.getGameProfile().getName();
            if (n == null) continue;
            if (!isNick(i)) {
                visible.add(n.toLowerCase(Locale.ROOT));
                continue;
            }
            Real r = real(n);
            if (r != null && !r.shared) {
                visible.add(r.name.toLowerCase(Locale.ROOT));   // already found: that party member is accounted for
                continue;
            }
            if (sameTeam(i, myTeam)) nicks.add(n);
        }
        if (nicks.isEmpty()) { pendingPair = null; why("no unknown nick on the team"); return; }
        // the party list from chat can be old or partial (members who joined while you were away): ask once per game,
        // without showing the answer, and wait for it
        if (Modules.denickAskParty.get() && partyAskedWorld != mc.theWorld) {
            partyAskedWorld = mc.theWorld;
            partyAskedAt = System.currentTimeMillis();
            partyReply.clear();
            mc.thePlayer.sendChatMessage("/pl");
            why("asked /pl");
            return;
        }
        if (partyAskedAt != 0 && System.currentTimeMillis() - partyAskedAt < 2500L) return;   // the answer is still coming
        List<String> party = dev.fzkc.fzhud.chat.KnownNames.partyNames();
        if (party.isEmpty()) { why("party unknown"); return; }
        List<String> missing = new ArrayList<>();
        String me = mc.thePlayer.getName();
        // someone on your team who is not in the party (a random player) could be the nick: then nothing is matched
        int partyCount = 1;
        for (String p : party) if (!p.equalsIgnoreCase(me)) partyCount++;
        if (teamSize > partyCount) { pendingPair = null; why("team " + teamSize + " > party " + partyCount); return; }
        for (String p : party) {
            String pk = p.toLowerCase(Locale.ROOT);
            if (p.equalsIgnoreCase(me) || visible.contains(pk) || seenHere.contains(pk)) continue;
            missing.add(p);
        }
        if (nicks.size() != 1 || missing.size() != 1) { pendingPair = null; why("nicks " + nicks + ", missing " + missing + ", party " + party); return; }
        String pair = nicks.get(0) + "=" + missing.get(0);
        if (!pair.equals(pendingPair)) { pendingPair = pair; pendingCount = 0; }
        if (++pendingCount >= 3) {
            pendingPair = null;
            resolve(nicks.get(0), missing.get(0), "party");
        }
    }

    private static boolean sameTeam(NetworkPlayerInfo i, String team) {
        return team != null && team.equals(teamKey(i));
    }

    /**
     * Which team a player is on, as the tab list shows it: Hypixel often gives every player a scoreboard team of
     * their own (for the name colour), so the team's name says nothing; the colour and letter in front ("§cR ") do.
     * Falls back to the scoreboard team's name when the prefix has neither.
     */
    static String teamKey(NetworkPlayerInfo i) {
        net.minecraft.scoreboard.ScorePlayerTeam t = i == null ? null : i.getPlayerTeam();
        if (t == null) return null;
        String p = t.getColorPrefix();
        String color = "";
        for (int c = p.length() - 2; c >= 0; c--) {
            if (p.charAt(c) == '§' && "0123456789abcdef".indexOf(Character.toLowerCase(p.charAt(c + 1))) >= 0) {
                color = p.substring(c + 1, c + 2).toLowerCase(Locale.ROOT);
                break;
            }
        }
        String text = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(p).trim();
        // a rank in front ("[MVP+]") is not a team
        text = text.replaceAll("\\[[^\\]]*\\]", "").trim();
        if (color.isEmpty() && text.isEmpty()) return "t:" + t.getRegisteredName();
        return "p:" + color + ":" + text;
    }

    private static String lastWhy;

    /** Why the party check found nothing, in the log once per change (to see why a nick was not matched). */
    private static void why(String s) {
        if (s.equals(lastWhy)) return;
        lastWhy = s;
        org.apache.logging.log4j.LogManager.getLogger("FzClient-Denick").info("party check: " + s);
    }

    private static final Pattern DOT = Pattern.compile("(?:\\[[^\\]]{1,24}\\] )?([A-Za-z0-9_]{1,16}) ?●");

    /**
     * True for a line of the /pl answer we asked for ourselves (it is then not shown). The names in it become the
     * party list.
     */
    public static boolean partyReply(String plain) {
        if (partyAskedAt == 0) return false;
        long now = System.currentTimeMillis();
        if (now - partyAskedAt > 4000L) {
            partyAskedAt = 0;
            return false;
        }
        String s = plain.trim();
        boolean ours = s.isEmpty() || s.matches("-{10,}") || s.startsWith("Party Members (") || s.startsWith("Party Leader:")
                || s.startsWith("Party Moderators:") || s.startsWith("Party Members:") || s.startsWith("You are not currently in a party");
        if (!ours) return false;
        if (s.startsWith("You are not currently in a party")) {
            partyAskedAt = 0;
            return true;
        }
        Matcher m = DOT.matcher(s);
        boolean any = false;
        while (m.find()) {
            partyReply.add(m.group(1));
            any = true;
        }
        if (any) dev.fzkc.fzhud.chat.KnownNames.setParty(partyReply);
        return true;
    }

    // ------------------------------------------------------------------ showing the real name

    /** "RealName §8(nick)" for a found nick (with the setting on), else null. */
    public static String shown(String nick) {
        if (nick == null || !Modules.denickShowReal.get() || !Modules.stats.enabled) return null;
        Real r = real(nick);
        return r == null ? null : r.name + " §8(" + nick + ")";
    }

    /** Puts the real name in place of the nick inside a coloured name ("§cR §cNick" -> "§cR §cReal §8(Nick)§c"). */
    public static String replaceIn(String formatted, String nick) {
        String s = shown(nick);
        if (s == null || formatted == null) return formatted;
        int i = formatted.indexOf(nick);
        if (i < 0) return formatted;
        String after = net.minecraft.client.gui.FontRenderer.getFormatFromString(formatted.substring(0, i));
        return formatted.substring(0, i) + s + after + formatted.substring(i + nick.length());
    }

    /** Nametags (and anything else that asks a player for its name): the real name. */
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void onNameFormat(net.minecraftforge.event.entity.player.PlayerEvent.NameFormat event) {
        if (event.entityPlayer == null || event.entityPlayer.getGameProfile() == null) return;
        UUID id = event.entityPlayer.getGameProfile().getId();
        if (id == null || id.version() != 1) return;
        String s = shown(event.username);
        if (s != null) event.displayname = s;
    }

    /** Players already in the world keep their cached name until asked again. */
    private static void refreshNames(String nick) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        for (Object o : mc.theWorld.playerEntities) {
            net.minecraft.entity.player.EntityPlayer p = (net.minecraft.entity.player.EntityPlayer) o;
            if (nick == null || nick.equalsIgnoreCase(p.getName())) p.refreshDisplayName();
        }
    }

    /** The display setting changed: every player's name again. */
    public static void refreshAllNames() { refreshNames(null); }

    // ------------------------------------------------------------------ result

    /** /pdenick <nick> <party member>: you say who it is (replaces a guess); "reset" forgets it. */
    public static class NameCommand extends net.minecraft.command.CommandBase {
        @Override public String getCommandName() { return "pdenick"; }

        @Override public String getCommandUsage(net.minecraft.command.ICommandSender sender) { return "/pdenick <nick> <party member | reset>"; }

        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override public boolean canCommandSenderUseCommand(net.minecraft.command.ICommandSender sender) { return true; }

        @Override
        public void processCommand(net.minecraft.command.ICommandSender sender, String[] args) {
            if (args.length != 2 || !args[0].matches("[A-Za-z0-9_]{1,16}") || !args[1].matches("[A-Za-z0-9_]{1,16}")) {
                say("§d[Denick] §7" + Lang.t("使い方") + ": §f/pdenick <nick> <" + Lang.t("パーティメンバー") + ">");
                return;
            }
            String k = args[0].toLowerCase(Locale.ROOT);
            RESOLVED.remove(k);
            if (args[1].equalsIgnoreCase("reset")) {
                forget(args[0]);
                say("§d[Denick] §f" + args[0] + " §7" + Lang.t("の名前を消しました"));
                refreshNames(args[0]);
                return;
            }
            resolve(args[0], args[1], "manual");
        }

        @Override
        public List<String> addTabCompletionOptions(net.minecraft.command.ICommandSender sender, String[] args, net.minecraft.util.BlockPos pos) {
            List<String> names = new ArrayList<>();
            Minecraft mc = Minecraft.getMinecraft();
            if (args.length == 1 && mc.thePlayer != null && mc.thePlayer.sendQueue != null) {
                for (NetworkPlayerInfo i : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
                    if (isNick(i)) names.add(i.getGameProfile().getName());
                }
            } else if (args.length == 2) {
                names.addAll(dev.fzkc.fzhud.chat.KnownNames.partyNames());
                names.add("reset");
            }
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
    }

    /** This game found who a nick is (now: always the newest answer). */
    private static void resolve(String nick, String realName, String code) {
        apply(nick, realName, code, now(), false, skinNow(nick), false);
    }

    /**
     * A nick turned out to be someone. One name per nick and one nick per name, the newest answer winning either way:
     * a player found under a new nick leaves the old one (which may be someone else by now), and a nick found to be
     * someone else gets the new name. The server keeps the shared answers by the same rule, so an answer from there
     * (shared) only counts against what is known here by when each was found.
     */
    private static void apply(String nick, String realName, String code, long at, boolean shared, String skin, boolean dev) {
        String k = nick.toLowerCase(Locale.ROOT);
        Real old = RESOLVED.get(k);
        if (shared) {
            Long forgot = FORGOT.get(k);
            if (forgot != null && at <= forgot) return;   // you said to forget it after that
            if (!dev && DenickShare.waiting(k, realName)) return;   // an own newer find is still on its way there
        }
        if (old != null && old.name.equalsIgnoreCase(realName)) {
            if (!shared && old.shared) {
                // the own check agrees with a shared answer: own from now on (and sent again, which keeps it alive)
                Real mine = new Real(old.name, code, old.nick, false, skin, at);
                mine.id = old.id;
                RESOLVED.put(k, mine);
                if (mine.id == null) findId(k, mine);
                DenickShare.report(mine);
            } else if (shared && old.shared && at > old.at) {
                old.at = at;
            }
            return;   // nothing new
        }
        if (old != null && old.at > at) return;   // something newer is known about this nick
        List<Map.Entry<String, Real>> holders = new ArrayList<>();
        boolean newerElsewhere = false;
        for (Map.Entry<String, Real> e : RESOLVED.entrySet()) {
            if (e.getKey().equals(k) || !e.getValue().name.equalsIgnoreCase(realName)) continue;
            holders.add(e);
            if (e.getValue().at > at) newerElsewhere = true;
        }
        if (newerElsewhere) {
            // this player is known under another nick since later: in time order this answer came and went again,
            // so the nick's earlier answer is gone too
            if (old != null) {
                drop(k, old, Lang.f("{0} の名前を外しました（{1} は今 {2}）", "§f" + old.nick + "§7", "§a" + realName + "§7",
                        "§f" + holders.get(0).getValue().nick + "§7"), shared);
            }
            return;
        }
        for (Map.Entry<String, Real> e : holders) {
            drop(e.getKey(), e.getValue(), Lang.f("{0} の名前を外しました（{1} は今 {2}）", "§f" + e.getValue().nick + "§7",
                    "§a" + realName + "§7", "§f" + nick + "§7"), shared);
        }
        Real r = new Real(realName, code, nick, shared, skin, at);
        RESOLVED.put(k, r);
        ANNOUNCED.add(k);
        say("§d[Denick] §f" + nick + " §7→ §a§l" + realName + " §8(" + r.how + ")"
                + (old != null ? " §8" + Lang.f("（前は {0}）", old.name) : ""));
        refreshNames(nick);
        if (shared && old != null && !old.shared) recheck(k);
        findId(k, r);
        if (!shared) DenickShare.report(r);
    }

    /** Takes an answer away (with a line in chat). A shared answer taking away an own one: looked at once more. */
    private static void drop(String k, Real r, String line, boolean byShared) {
        if (!RESOLVED.remove(k, r)) return;
        say("§d[Denick] §7" + line);
        refreshNames(r.nick);
        if (byShared && !r.shared) recheck(k);
    }

    /** The own checks run once more for this nick (once per session). */
    private static void recheck(String k) {
        if (!RECHECKED.add(k)) return;
        CHECKED.remove(k);
        NUMBERS.remove(k);
    }

    /** True when this game itself already knows who the nick is (a shared answer does not stop the own checks). */
    private static boolean known(String nick) {
        Real r = real(nick);
        return r != null && !r.shared;
    }

    /** Still this game's own current answer (for DenickShare, any thread). */
    static boolean stillMine(Real r) {
        return !r.shared && RESOLVED.get(r.nick.toLowerCase(Locale.ROOT)) == r;
    }

    /** "/pdenick <nick> reset": shared answers found before now are not taken, and one you sent is taken back. */
    private static void forget(String nick) {
        String k = nick.toLowerCase(Locale.ROOT);
        CHECKED.remove(k);
        FORGOT.put(k, now());
        DenickShare.reset(nick, REPORTED.contains(k));
    }

    /** Skin hash the nick wears right now ("" = none or not in the tab list). */
    private static String skinNow(String nick) {
        String[] owner = skinOwner(tabEntry(nick));
        return owner == null ? "" : owner[1];
    }

    /** The tab list entry of a name, any case (null when not there). */
    private static NetworkPlayerInfo tabEntry(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null || name == null) return null;
        for (NetworkPlayerInfo i : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            if (name.equalsIgnoreCase(i.getGameProfile().getName())) return i;
        }
        return null;
    }

    /**
     * Answers from the shared list (main thread). Only for nicks in your tab list right now, and only when the nick
     * still wears the skin it wore when it was found (otherwise someone else may use that nick now); a skin answer
     * also has to match the skin's owner.
     */
    static void sharedRows(List<DenickShare.Row> rows) {
        if (!DenickShare.on()) return;
        rows.sort((x, y) -> Long.compare(x.at, y.at));
        for (DenickShare.Row w : rows) {
            NetworkPlayerInfo info = tabEntry(w.nick);
            if (!isNick(info)) continue;
            String nick = info.getGameProfile().getName();
            if (w.name == null) {
                gone(nick, w.gone, w.how, w.at, false);
                continue;
            }
            if (!"skin".equals(w.how) && !"numbers".equals(w.how)) continue;
            String[] owner = skinOwner(info);
            if (!(owner == null ? "" : owner[1]).equals(w.skin)) continue;
            if ("skin".equals(w.how) && (owner == null || !owner[0].equalsIgnoreCase(w.name))) continue;
            if ("skin".equals(w.how) && isNickSkin(owner[1], nick)) continue;   // proves nothing (see bySkin)
            if (w.name.equalsIgnoreCase(nick) || REJECTED.contains(nick.toLowerCase(Locale.ROOT) + "=" + w.name.toLowerCase(Locale.ROOT))) continue;
            apply(nick, w.name, w.how, w.at, true, w.skin, false);
        }
    }

    /** The shared list says this name left the nick at that time (found under another nick, or taken back). */
    private static void gone(String nick, String gone, String how, long at, boolean dev) {
        String k = nick.toLowerCase(Locale.ROOT);
        Real r = RESOLVED.get(k);
        if (r == null || gone == null || !r.name.equalsIgnoreCase(gone) || r.at > at) return;
        if (!dev && DenickShare.waiting(k, r.name)) return;
        drop(k, r, "moved".equals(how)
                ? Lang.f("{0} の名前を外しました（その人はほかのニックで見つかりました）", "§f" + r.nick + "§7")
                : Lang.f("{0} の名前を外しました（共有データで取り消されました）", "§f" + r.nick + "§7"), true);
    }

    /** The real UUID, so the tab list and chat show the real player's stats. */
    private static void findId(String k, Real r) {
        String realName = r.name, nick = r.nick;
        new Thread(() -> {
            // one failed request used to leave the nick on "NICK" for the whole session: try again for a while
            // (stops when a newer answer replaced this one)
            for (int i = 0; i < 8 && r.id == null && RESOLVED.get(k) == r; i++) {
                try {
                    UUID id = StatsApi.lookupNameOrThrow(realName);
                    if (id == null) {
                        why("real name " + realName + " of " + nick + ": Mojang does not know it");
                        // a shared answer naming nobody is dropped
                        if (r.shared) Minecraft.getMinecraft().addScheduledTask(() -> {
                            REJECTED.add(k + "=" + realName.toLowerCase(Locale.ROOT));
                            if (RESOLVED.remove(k, r)) refreshNames(nick);
                        });
                        return;
                    }
                    r.id = id;
                    why("real name " + realName + " of " + nick + ": found");
                } catch (Exception e) {
                    why("real name " + realName + " of " + nick + ": " + e.getMessage() + " (try " + (i + 1) + ")");
                    try {
                        Thread.sleep(Math.min(30_000L, 2000L << i));
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            }
        }, "FzClient-DenickName").start();
    }

    /** DevTest: as if a check had found this (the same path as the real checks). */
    public static void devResolve(String nick, String realName, String how) { resolve(nick, realName, how); }

    /** DevTest: as if the shared list had answered this (name null = gone), found ageMs ago. */
    public static void devShared(String nick, String name, String gone, String how, long ageMs) {
        long at = now() - ageMs;
        if (name == null) gone(nick, gone, how, at, true);
        else apply(nick, name, how, at, true, "", true);
    }

    /** DevTest: "/pdenick <nick> reset". */
    public static void devForget(String nick) {
        RESOLVED.remove(nick.toLowerCase(Locale.ROOT));
        forget(nick);
    }

    /** DevTest: sharing against a local test server, outside Hypixel. */
    public static void devShare(String... ask) {
        DenickShare.devMode = true;
        DenickShare.tick();
        for (String n : ask) DenickShare.saw(n);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void say(String s) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(s));
    }

    // ------------------------------------------------------------------ remembered nick skins

    private static File skinsFile() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-nickskins.json");
    }

    private static void loadSkins() {
        if (skinsLoaded) return;
        skinsLoaded = true;
        File f = skinsFile();
        if (!f.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonObject o = new JsonParser().parse(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                Set<String> s = new HashSet<>();
                for (JsonElement n : e.getValue().getAsJsonArray()) s.add(n.getAsString());
                SKIN_NICKS.put(e.getKey(), s);
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveSkins() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Set<String>> e : SKIN_NICKS.entrySet()) {
            JsonArray a = new JsonArray();
            int n = 0;
            for (String s : e.getValue()) {
                if (n++ >= 5) break;   // two are enough to know; a few more do no harm
                a.add(new com.google.gson.JsonPrimitive(s));
            }
            o.add(e.getKey(), a);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(skinsFile()), StandardCharsets.UTF_8)) {
            w.write(o.toString());
        } catch (Exception ignored) {
        }
    }
}
