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
 */
public final class Denick {
    /** (an instance only listens for the player name event) */
    public Denick() {}

    private static boolean lastShow;

    /** Who a nick turned out to be. */
    public static final class Real {
        public final String name;
        public final String how;
        public volatile UUID id;

        Real(String name, String how) {
            this.name = name;
            this.how = how;
        }
    }

    private static final Map<String, Real> RESOLVED = new ConcurrentHashMap<>();
    /** Nicks already looked at (by skin) in this session. */
    private static final Set<String> CHECKED = new HashSet<>();
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
        return Modules.stats.enabled && (Modules.denickSkin.get() || Modules.denickNumbers.get() || Modules.denickTeam.get()) && Stats.onHypixel();
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
        if (!on()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) return;
        loadSkins();
        for (NetworkPlayerInfo info : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            if (!isNick(info)) continue;
            String nick = info.getGameProfile().getName();
            String k = nick.toLowerCase(Locale.ROOT);
            if (!CHECKED.add(k)) continue;
            if (!ANNOUNCED.contains(k) && RESOLVED.get(k) == null) {
                say("§d[Denick] §f" + nick + " §7" + Lang.t("はニックです"));
                ANNOUNCED.add(k);
            }
            if (Modules.denickSkin.get() && RESOLVED.get(k) == null) bySkin(info, nick);
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
                if (now.equals(hash)) resolve(nick, name, Lang.t("スキン"));
                else {
                    // not theirs: remember the skin as a nick skin
                    SKIN_NICKS.get(hash).add("*");
                    skinsDirty = true;
                }
            });
        }, "FzClient-DenickSkin").start();
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
        if (!isNick(info) || real(nick) != null) return;
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
        if (real(nick) != null) return;
        if (nb.finals != null && nb.beds != null) {
            List<String> both = new ArrayList<>(nb.finals);
            both.retainAll(nb.beds);
            if (both.size() == 1) {
                resolve(nick, both.get(0), Lang.t("数字"));
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
            if (r != null) {
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
            resolve(nicks.get(0), missing.get(0), Lang.t("パーティ・消去法"));
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

    private static void resolve(String nick, String realName, String how) {
        String k = nick.toLowerCase(Locale.ROOT);
        if (RESOLVED.containsKey(k)) return;
        Real r = new Real(realName, how);
        RESOLVED.put(k, r);
        ANNOUNCED.add(k);
        say("§d[Denick] §f" + nick + " §7→ §a§l" + realName + " §8(" + how + ")");
        refreshNames(nick);
        // the real UUID, so the tab list and chat show the real player's stats
        new Thread(() -> r.id = StatsApi.lookupName(realName), "FzClient-DenickName").start();
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
