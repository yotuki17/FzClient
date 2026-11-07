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
    private Denick() {}

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
        return Modules.stats.enabled && (Modules.denickSkin.get() || Modules.denickNumbers.get()) && Stats.onHypixel();
    }

    /** New world: number lookups start over (the nicks may stay the same, so resolved names are kept). */
    static void worldChanged() {
        NUMBERS.clear();
    }

    /** Once a second: look at nicked players in the tab list. */
    static void tick() {
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

    // ------------------------------------------------------------------ result

    private static void resolve(String nick, String realName, String how) {
        String k = nick.toLowerCase(Locale.ROOT);
        if (RESOLVED.containsKey(k)) return;
        Real r = new Real(realName, how);
        RESOLVED.put(k, r);
        ANNOUNCED.add(k);
        say("§d[Denick] §f" + nick + " §7→ §a§l" + realName + " §8(" + how + ")");
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
