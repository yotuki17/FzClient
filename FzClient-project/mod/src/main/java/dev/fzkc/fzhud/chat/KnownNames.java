package dev.fzkc.fzhud.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player names for the command suggestions after a space (/msg Na..., /p invite ..., /f remove ...).
 * Learned only from what Hypixel already prints in your chat - nothing is asked from the server:
 * <ul>
 *   <li>friends: "Friend > Name joined.", the /f list pages, "You are now friends with ...";</li>
 *   <li>guild members: guild chat, "Guild > Name joined.", the /g list and /g online pages;</li>
 *   <li>party members: party chat, the /p list output, "Name joined the party.";</li>
 *   <li>people you messaged: "From Name:" / "To Name:".</li>
 * </ul>
 * Friends and guild members are kept in .minecraft/config/fzclient-names.json, so they are there next time too.
 * Party members and DMs are only kept while the game runs.
 */
public final class KnownNames {
    public static final String FRIEND = "フレンド", GUILD = "ギルド", PARTY = "パーティ", DM = "DM", ONLINE = "オンライン";

    private static final String RANK = "(?:\\[[^\\]]{1,24}\\] )?";
    private static final String NAME = "([A-Za-z0-9_]{1,16})";

    private static final Pattern FRIEND_JOIN = Pattern.compile("^Friend > " + NAME + " (?:joined|left)\\.$");
    private static final Pattern FRIEND_NEW = Pattern.compile("^You are now friends with " + RANK + NAME);
    private static final Pattern FRIEND_GONE = Pattern.compile("^You removed " + RANK + NAME + " from your friends list");
    private static final Pattern FRIEND_REQ = Pattern.compile("^Friend request from " + RANK + NAME);
    private static final Pattern FRIEND_LIST_HEAD = Pattern.compile("^ *(?:<< )?Friends \\(Page \\d+ of \\d+\\)");
    private static final Pattern FRIEND_LIST_ROW = Pattern.compile("^" + RANK + NAME + " (?:is |is currently)");

    private static final Pattern GUILD_JOIN = Pattern.compile("^Guild > " + NAME + " (?:joined|left)\\.$");
    private static final Pattern GUILD_CHAT = Pattern.compile("^(?:Guild|Officer) > " + RANK + NAME + "(?: \\[[^\\]]{1,10}\\])?: ");
    private static final Pattern GUILD_NEW = Pattern.compile("^" + RANK + NAME + " joined the guild!$");
    private static final Pattern GUILD_GONE = Pattern.compile("^" + RANK + NAME + " (?:left the guild!|was kicked from the guild)");
    private static final Pattern DOT_NAME = Pattern.compile(RANK + NAME + " ?●");   // "[MVP+] Name ●" in /g list, /p list

    private static final Pattern PARTY_CHAT = Pattern.compile("^Party > " + RANK + NAME + ": ");
    private static final Pattern PARTY_JOIN = Pattern.compile("^" + RANK + NAME + " joined the party\\.$");
    private static final Pattern PARTY_JOINED = Pattern.compile("^You have joined " + RANK + NAME + "'s party!$");
    private static final Pattern PARTY_GONE = Pattern.compile("^" + RANK + NAME + " has (?:left|been removed from) the party\\.$");
    private static final Pattern DM_LINE = Pattern.compile("^(?:From|To) " + RANK + NAME + ": ");

    private static final Map<String, String> friends = new LinkedHashMap<String, String>();   // lower -> name
    private static final Map<String, String> guild = new LinkedHashMap<String, String>();
    private static final Map<String, String> party = new LinkedHashMap<String, String>();
    private static final Map<String, String> dms = new LinkedHashMap<String, String>();
    private static final Map<String, String> requests = new LinkedHashMap<String, String>();
    private static boolean loaded, dirty;

    /** "/f list" or "/g list" pages are being printed (the lines after the header). */
    private long friendListUntil, dotListUntil;
    private String dotListKind;

    // ------------------------------------------------------------------ reading

    /**
     * Names for a command argument, in this order of sources, each {name, source}.
     * Sources are FRIEND, GUILD, PARTY, DM, ONLINE (players in the tab list); a name is listed once.
     */
    public static List<String[]> names(String... sources) {
        load();
        Map<String, String[]> out = new LinkedHashMap<String, String[]>();
        for (String src : sources) {
            Iterable<String> from;
            if (FRIEND.equals(src)) from = friends.values();
            else if (GUILD.equals(src)) from = guild.values();
            else if (PARTY.equals(src)) from = party.values();
            else if (DM.equals(src)) from = reversed(dms.values());
            else if ("request".equals(src)) from = reversed(requests.values());
            else from = online();
            String tag = "request".equals(src) ? FRIEND : src;
            for (String n : from) {
                String k = n.toLowerCase(Locale.ROOT);
                if (!out.containsKey(k)) out.put(k, new String[]{n, tag});
            }
        }
        return new ArrayList<String[]>(out.values());
    }

    private static List<String> reversed(Iterable<String> it) {
        List<String> l = new ArrayList<String>();
        for (String s : it) l.add(0, s);
        return l;
    }

    /** Real players in the tab list (Hypixel's fake NPC entries have names that are not valid player names). */
    private static List<String> online() {
        List<String> out = new ArrayList<String>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) return out;
        String me = mc.thePlayer == null ? "" : mc.thePlayer.getName();
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info.getGameProfile() == null) continue;
            String n = info.getGameProfile().getName();
            if (n == null || !n.matches("[A-Za-z0-9_]{3,16}") || n.equalsIgnoreCase(me)) continue;
            out.add(n);
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ------------------------------------------------------------------ learning from chat

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;
        // multi-line messages (the /f list and /g list pages come as one message with line breaks)
        for (String line : plain.split("\n")) read(line.trim());
    }

    private void read(String line) {
        if (line.isEmpty()) return;
        load();
        long now = System.currentTimeMillis();
        Matcher m;

        if ((m = FRIEND_JOIN.matcher(line)).find() || (m = FRIEND_NEW.matcher(line)).find()) {
            put(friends, m.group(1), true);
            requests.remove(m.group(1).toLowerCase(Locale.ROOT));
            return;
        }
        if ((m = FRIEND_GONE.matcher(line)).find()) { remove(friends, m.group(1), true); return; }
        if ((m = FRIEND_REQ.matcher(line)).find()) { put(requests, m.group(1), false); return; }
        if (FRIEND_LIST_HEAD.matcher(line).find()) { friendListUntil = now + 1500L; return; }
        if (now < friendListUntil && (m = FRIEND_LIST_ROW.matcher(line)).find()) { put(friends, m.group(1), true); return; }

        if ((m = GUILD_JOIN.matcher(line)).find() || (m = GUILD_CHAT.matcher(line)).find() || (m = GUILD_NEW.matcher(line)).find()) {
            put(guild, m.group(1), true);
            return;
        }
        if ((m = GUILD_GONE.matcher(line)).find()) { remove(guild, m.group(1), true); return; }

        if ((m = PARTY_CHAT.matcher(line)).find() || (m = PARTY_JOIN.matcher(line)).find() || (m = PARTY_JOINED.matcher(line)).find()) {
            put(party, m.group(1), false);
            return;
        }
        if ((m = PARTY_GONE.matcher(line)).find()) { remove(party, m.group(1), false); return; }
        if (line.startsWith("You left the party") || line.contains("party has been disbanded")
                || line.startsWith("You are not currently in a party")) {
            party.clear();
            return;
        }
        if ((m = DM_LINE.matcher(line)).find()) {
            dms.remove(m.group(1).toLowerCase(Locale.ROOT));   // move to the newest place
            put(dms, m.group(1), false);
            while (dms.size() > 30) dms.remove(dms.keySet().iterator().next());
            return;
        }

        // the "●" lists: "Guild Name: ..." / "Party Members (3)" start them
        if (line.startsWith("Guild Name:") || line.startsWith("-- ") && line.endsWith(" --")) {
            dotListKind = GUILD;
            dotListUntil = now + 1500L;
        } else if (line.startsWith("Party Members (")) {
            dotListKind = PARTY;
            dotListUntil = now + 1500L;
            party.clear();
        }
        if (now < dotListUntil && line.indexOf('●') >= 0) {
            Map<String, String> into = GUILD.equals(dotListKind) ? guild : party;
            m = DOT_NAME.matcher(line);
            while (m.find()) put(into, m.group(1), into == guild);
        }
    }

    /** Party members as far as the chat has shown (not you). */
    public static java.util.List<String> partyNames() {
        return new java.util.ArrayList<String>(party.values());
    }

    /** A fresh party list (read from a /pl answer that was not shown). */
    public static void setParty(java.util.Collection<String> names) {
        party.clear();
        for (String n : names) put(party, n, false);
    }

    private static void put(Map<String, String> map, String name, boolean save) {
        String me = Minecraft.getMinecraft().thePlayer == null ? "" : Minecraft.getMinecraft().thePlayer.getName();
        if (name.equalsIgnoreCase(me)) return;
        String k = name.toLowerCase(Locale.ROOT);
        if (name.equals(map.get(k))) return;
        map.put(k, name);
        if (save) { dirty = true; save(); }
    }

    private static void remove(Map<String, String> map, String name, boolean save) {
        if (map.remove(name.toLowerCase(Locale.ROOT)) != null && save) { dirty = true; save(); }
    }

    // ------------------------------------------------------------------ file

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "fzclient-names.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            JsonObject o = new JsonParser().parse(r).getAsJsonObject();
            if (o.has("friends")) for (JsonElement e : o.getAsJsonArray("friends")) friends.put(e.getAsString().toLowerCase(Locale.ROOT), e.getAsString());
            if (o.has("guild")) for (JsonElement e : o.getAsJsonArray("guild")) guild.put(e.getAsString().toLowerCase(Locale.ROOT), e.getAsString());
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }

    private static void save() {
        if (!dirty) return;
        dirty = false;
        JsonObject o = new JsonObject();
        JsonArray f = new JsonArray(), g = new JsonArray();
        for (String n : friends.values()) f.add(new com.google.gson.JsonPrimitive(n));
        for (String n : guild.values()) g.add(new com.google.gson.JsonPrimitive(n));
        o.add("friends", f);
        o.add("guild", g);
        try {
            File file = file();
            file.getParentFile().mkdirs();
            OutputStream out = new FileOutputStream(file);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }
}
