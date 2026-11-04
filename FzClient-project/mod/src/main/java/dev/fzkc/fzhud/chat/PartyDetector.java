package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Detector: in a waiting lobby ("Name has joined (5/8)!"), a party is moved into the game together, so its
 * members' join lines arrive at the same moment, while solo players trickle in one by one. Joins that come within
 * a short gap of each other are grouped; a group of two or more is announced in chat ("3Qパーティの可能性").
 * Leaving works the same way: "Name has quit!" lines that come together are one group, and N of them at once
 * takes one NQ party away again, so the HUD always shows the parties that are still in the lobby.
 * It only reads the server's join / quit messages; it is a guess, not a certainty.
 */
public final class PartyDetector {
    private static final Pattern JOIN = Pattern.compile("^([A-Za-z0-9_]{1,16}) has joined \\((\\d+)/(\\d+)\\)!$");
    private static final Pattern QUIT = Pattern.compile("^([A-Za-z0-9_]{1,16}) has quit!$");
    /** Lines that mean the game itself has begun (the waiting lobby is over). */
    private static final String[] GAME_START = {
            "Protect your bed and destroy the enemy beds.", "Cages opened! FIGHT!", "The game has started!"
    };

    /** Parties still in the lobby, by size: 2 -> how many 2Q parties, 3 -> how many 3Q... */
    private static final TreeMap<Integer, Integer> parties = new TreeMap<Integer, Integer>();
    /** In a waiting lobby: set by the join lines when you arrive, cleared when the game starts. */
    private static boolean inLobby;
    /** "The game starts in 1 second!" was seen: hide the HUD at this time (0 = not counting down). */
    private static long hideAt;
    private static String hudText;
    private static boolean hudEnglish;

    // joins / quits collected until the burst is over
    private final List<String> joining = new ArrayList<String>(), quitting = new ArrayList<String>();
    private long lastJoin, lastQuit;
    private Object world;
    private long enteredAt;
    /** Players the lobby holds ("(5/8)" -> 8); 0 = not seen yet. */
    private static int lobbyMax;

    // ------------------------------------------------------------------ HUD

    /** "パーティ: 2Q ×1  3Q ×2" from the parties still in the lobby (counted by how many of them remain). */
    public static String hudText() {
        if (hudText == null || hudEnglish != Lang.english()) {
            hudEnglish = Lang.english();
            TreeMap<Integer, Integer> bySize = parties;
            if (bySize.isEmpty()) {
                hudText = "FREE";
                return hudText;
            }
            StringBuilder sb = new StringBuilder(Lang.t("パーティ")).append(": ");
            boolean first = true;
            for (Map.Entry<Integer, Integer> e : bySize.entrySet()) {
                if (!first) sb.append("  ");
                sb.append(e.getKey()).append("Q ×").append(e.getValue());
                first = false;
            }
            hudText = sb.toString();
        }
        return hudText;
    }

    public static boolean hudVisible() {
        if (hideAt != 0 && System.currentTimeMillis() >= hideAt) {
            inLobby = false;
            hideAt = 0;
        }
        return Modules.partyDetect.enabled && inLobby && teamSize() != 1;
    }

    // ------------------------------------------------------------------ team size of this mode

    /** Parties are at most this big (Hypixel parties in PvP modes never go beyond 4 in one team). */
    private static final int MAX_PARTY = 4;
    private static int teamSize = -1;
    private static long teamSizeAt;

    /**
     * Players per team in this lobby: 1 for solo modes and duels (no parties to look for), 2 for doubles, 3 / 4 for
     * 3v3v3v3 / 4v4v4v4 / 4v4, else MAX_PARTY when it cannot be told. Read from the sidebar ("Mode: Solo") and the
     * lobby size (a 2-player lobby is a duel). Looked up at most once a second.
     */
    static int teamSize() {
        long now = System.currentTimeMillis();
        if (now - teamSizeAt < 1000L && teamSize > 0) return teamSize;
        teamSizeAt = now;
        int size = MAX_PARTY;
        String mode = sidebarText().toLowerCase(java.util.Locale.ROOT);
        if (lobbyMax == 2 || mode.contains("solo") || mode.contains("1v1") || mode.contains("duel")) size = 1;
        else if (mode.contains("doubles") || mode.contains("2v2") || mode.contains("teams of 2")) size = 2;
        else if (mode.contains("3v3") || mode.contains("teams of 3")) size = 3;
        else if (mode.contains("4v4") || mode.contains("teams of 4") || mode.contains("squads")) size = 4;
        teamSize = size;
        return size;
    }

    /** All sidebar (scoreboard) lines in one string, without colour codes. */
    private static String sidebarText() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return "";
        net.minecraft.scoreboard.Scoreboard sb = mc.theWorld.getScoreboard();
        net.minecraft.scoreboard.ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
        if (obj == null) return "";
        StringBuilder out = new StringBuilder();
        for (net.minecraft.scoreboard.Score sc : sb.getSortedScores(obj)) {
            String name = sc.getPlayerName();
            if (name == null || name.startsWith("#")) continue;
            String line = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(name), name);
            out.append(EnumChatFormatting.getTextWithoutFormattingCodes(line)).append('\n');
        }
        return out.toString();
    }

    /** The biggest party this lobby can have (0 = none: solo / duel). */
    private static int partyCap() {
        int t = teamSize();
        return t <= 1 ? 0 : Math.min(MAX_PARTY, t);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || !Modules.partyDetect.enabled) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText()).trim();
        checkWorld();
        if (inLobby) {
            for (String g : GAME_START) {
                if (plain.equals(g)) {   // the game has begun: the lobby (and the HUD) is over
                    inLobby = false;
                    hideAt = 0;
                    return;
                }
            }
            if (plain.startsWith("The game starts in 1 second")) hideAt = System.currentTimeMillis() + 1500L;
            else if (plain.contains("Start cancelled") || plain.contains("countdown has been cancelled")) hideAt = 0;
        }
        Matcher j = JOIN.matcher(plain), q = QUIT.matcher(plain);
        boolean join = j.matches();
        if (!join && !q.matches()) return;
        long now = System.currentTimeMillis();
        long gap = (long) Modules.partyDetectGap.get();
        if (join) {
            // join lines only appear in a waiting lobby (your own arrival shows one too): the lobby has begun
            inLobby = true;
            try { lobbyMax = Integer.parseInt(j.group(3)); } catch (NumberFormatException ignored) { }
            // the burst when you yourself arrive (you and your own party) is not an enemy party
            if (now - enteredAt < 2000L) {
                joining.clear();
                return;
            }
            if (!joining.isEmpty() && now - lastJoin > gap) flushJoins();
            joining.add(j.group(1));
            lastJoin = now;
        } else {
            if (!quitting.isEmpty() && now - lastQuit > (long) Modules.partyDetectQuitGap.get()) flushQuits();
            quitting.add(q.group(1));
            lastQuit = now;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        checkWorld();
        long now = System.currentTimeMillis(), gap = (long) Modules.partyDetectGap.get();
        if (!joining.isEmpty() && now - lastJoin > gap) flushJoins();
        if (!quitting.isEmpty() && now - lastQuit > (long) Modules.partyDetectQuitGap.get()) flushQuits();
    }

    /** New lobby / game (the world object changed): start again. */
    private void checkWorld() {
        Object w = Minecraft.getMinecraft().theWorld;
        if (w == world) return;
        world = w;
        parties.clear();
        inLobby = false;
        hideAt = 0;
        hudText = null;
        lobbyMax = 0;
        teamSize = -1;
        teamSizeAt = 0;
        enteredAt = System.currentTimeMillis();
        joining.clear();
        quitting.clear();
    }

    // ------------------------------------------------------------------ joins / quits

    /** A burst of joins: N players at once = one NQ party more. */
    private void flushJoins() {
        int n = joining.size();
        List<String> g = new ArrayList<String>(joining);
        joining.clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || n < (int) Modules.partyDetectMin.get()) return;
        // solo / duel: there are no parties; more than a team at once: two groups that happened to arrive together
        if (n > partyCap()) return;
        String me = mc.getSession() != null ? mc.getSession().getUsername() : "";
        for (String name : g) if (name.equalsIgnoreCase(me) || name.equalsIgnoreCase(mc.thePlayer.getName())) return;   // your own party
        Integer had = parties.get(n);
        parties.put(n, had == null ? 1 : had + 1);
        hudText = null;
        say(Lang.f("{0}Qパーティの可能性", n), "note.pling", 1.4f);
    }

    /** A burst of quits, grouped the same way: N players at once = one NQ party less (if there was one). */
    private void flushQuits() {
        int n = quitting.size();
        quitting.clear();
        if (n < (int) Modules.partyDetectMin.get() || n > partyCap()) return;
        Integer had = parties.get(n);
        if (had == null) return;
        if (had <= 1) parties.remove(n);
        else parties.put(n, had - 1);
        hudText = null;
        say(Lang.f("{0}Qパーティが抜けました", n), "note.bass", 0.8f);
    }

    private static void say(String text, String sound, float pitch) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.YELLOW + text));
        if (Modules.partyDetectSound.get()) mc.thePlayer.playSound(sound, 0.7f, pitch);
    }
}
