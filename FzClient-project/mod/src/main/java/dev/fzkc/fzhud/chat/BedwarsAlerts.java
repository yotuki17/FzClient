package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.stats.Game;
import dev.fzkc.fzhud.stats.Stats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bed Wars chat helpers: a sound when someone falls into the void, a party chat message when a teammate does, and
 * a sound when someone invites you to a party (with their stats, like Mellow).
 */
public final class BedwarsAlerts {
    /** Words in Hypixel's void death messages (the default ones and the kill message themes). */
    private static final String[] VOID_WORDS = {"void", "off the edge", " fell", "abyss", "off a cliff", "knocked off", "off the map"};
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern INVITE = Pattern.compile("(?:\\[[^\\]]{1,24}\\] )?([A-Za-z0-9_]{1,16}) has invited you to join (?:their|[A-Za-z0-9_]{1,16}'s) party!");

    /** Each player's height about 2 seconds ago (a player falling fast who then dies went into the void). */
    private final Map<String, double[]> heights = new HashMap<>();
    private int ticks;
    private long lastParty;

    private static boolean inBedwarsGame() {
        return Stats.onHypixel() && Stats.current == Game.BEDWARS && !Stats.lobby;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !Modules.chat.enabled || !inBedwarsGame()
                || Modules.bwVoidSound.get() == 0 && !Modules.bwVoidParty.get()) {
            if (!heights.isEmpty()) heights.clear();
            return;
        }
        if (++ticks % 5 != 0) return;
        // a ring of 8 heights, one every 5 ticks: [0..7] heights, [8] next slot
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            double[] h = heights.get(p.getName());
            if (h == null) {
                h = new double[9];
                java.util.Arrays.fill(h, 0, 8, p.posY);
                heights.put(p.getName(), h);
            }
            int i = (int) h[8];
            h[i] = p.posY;
            h[8] = (i + 1) % 8;
        }
        if (ticks % 600 == 0) heights.clear();   // players who left
    }

    /** True when the player went down more than 10 blocks in the last 2 seconds. */
    private boolean wasFalling(String name) {
        double[] h = heights.get(name);
        if (h == null) return false;
        int newest = ((int) h[8] + 7) % 8, oldest = (int) h[8];
        return h[oldest] - h[newest] > 10;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2 || !Modules.chat.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;
        invite(plain);
        if (inBedwarsGame()) death(plain.trim(), mc);
    }

    private void invite(String plain) {
        if (!plain.contains("has invited you to join")) return;
        Matcher m = INVITE.matcher(plain);
        if (!m.find()) return;
        if (Modules.chatInviteSound.get()) ChatAlerts.play(1.2f);
        if (Modules.stats.enabled && Modules.statsChatInvite.get() && Stats.onHypixel()) {
            dev.fzkc.fzhud.stats.ChatStats.requestLine(m.group(1));
        }
    }

    private void death(String plain, Minecraft mc) {
        int sp = plain.indexOf(' ');
        if (sp <= 0 || plain.indexOf(':') >= 0) return;   // player chat has "Name: ..."
        String victim = plain.substring(0, sp);
        if (!NAME.matcher(victim).matches() || mc.thePlayer.sendQueue == null) return;
        NetworkPlayerInfo info = mc.thePlayer.sendQueue.getPlayerInfo(victim);
        if (info == null) return;
        String low = plain.toLowerCase(Locale.ROOT);
        boolean isVoid = false;
        for (String w : VOID_WORDS) {
            if (low.contains(w)) {
                isVoid = true;
                break;
            }
        }
        if (!isVoid) {
            // a themed message without a void word: someone falling fast who dies now
            boolean looksLikeDeath = low.endsWith(".") || low.endsWith("final kill!");
            if (!looksLikeDeath || !wasFalling(victim)) return;
        }
        boolean me = victim.equalsIgnoreCase(mc.thePlayer.getName());
        boolean mate = me || sameTeam(mc, info);
        int who = Modules.bwVoidSound.get();   // 0 off, 1 everyone, 2 teammates, 3 enemies
        if (who == 1 || who == 2 && mate || who == 3 && !mate) ChatAlerts.play(mate ? 0.7f : 1.0f);
        if (Modules.bwVoidParty.get() && mate && (!me || Modules.bwVoidPartySelf.get())) {
            long now = System.currentTimeMillis();
            if (now - lastParty < 3000) return;
            lastParty = now;
            String text = Modules.bwVoidText.get().trim();
            if (text.isEmpty()) return;
            mc.thePlayer.sendChatMessage("/pc " + text.replace("{name}", victim));
        }
    }

    private static boolean sameTeam(Minecraft mc, NetworkPlayerInfo info) {
        ScorePlayerTeam mine = mc.theWorld.getScoreboard().getPlayersTeam(mc.thePlayer.getName());
        ScorePlayerTeam theirs = info.getPlayerTeam();
        return mine != null && theirs != null && mine.getRegisteredName().equals(theirs.getRegisteredName());
    }
}
