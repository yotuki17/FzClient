package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Warp: when you queue with a party, sometimes a member is not moved into the game with you. A few seconds
 * after you arrive in a waiting lobby ("You has joined (3/8)!"), /pl is asked (its answer is not shown); if you are
 * the party leader and an online member is not in the lobby, /p warp brings them in. Once per lobby.
 */
public final class PartyWarp {
    private static final Pattern JOIN = Pattern.compile("^([A-Za-z0-9_]{1,16}) has joined \\(\\d+/\\d+\\)!$");
    private static final Pattern NAME = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");

    private Object world;
    /** When to ask /pl (0 = nothing planned) and when it was asked (its answer is hidden for a moment). */
    private long askAt, askedAt;
    private String leader;
    private final List<String> online = new ArrayList<String>();

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        checkWorld(mc);
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;
        if (askedAt != 0) {
            if (System.currentTimeMillis() - askedAt > 4000L) finish(mc);
            else if (reply(plain, event.message.getFormattedText())) {
                event.setCanceled(true);
                return;
            }
        }
        if (!Modules.partyWarp.enabled || askAt != 0 || event.isCanceled()) return;
        Matcher m = JOIN.matcher(plain.trim());
        if (m.matches() && m.group(1).equalsIgnoreCase(mc.thePlayer.getName()) && world != null && world != asked) {
            asked = world;
            askAt = System.currentTimeMillis() + (long) (Modules.partyWarpDelay.get() * 1000);
        }
    }

    /** The lobby /pl was already planned for (once per lobby). */
    private Object asked;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        checkWorld(mc);
        long now = System.currentTimeMillis();
        if (askAt != 0 && now >= askAt) {
            askAt = 0;
            askedAt = now;
            leader = null;
            online.clear();
            mc.thePlayer.sendChatMessage("/pl");
        }
        // the answer has stopped coming: decide
        if (askedAt != 0 && leader != null && now - askedAt > 1500L) finish(mc);
        else if (askedAt != 0 && now - askedAt > 4000L) finish(mc);
    }

    private void checkWorld(Minecraft mc) {
        if (mc.theWorld == world) return;
        world = mc.theWorld;
        askAt = 0;
    }

    /** True for a line of the /pl answer (maybe several lines in one message); the names are collected. */
    private boolean reply(String plain, String formatted) {
        String[] lines = plain.split("\n"), flines = formatted.split("\n");
        for (String l : lines) {
            String s = l.trim();
            boolean ours = s.isEmpty() || s.matches("-{10,}") || s.startsWith("Party Members (") || s.startsWith("Party Leader:")
                    || s.startsWith("Party Moderators:") || s.startsWith("Party Members:") || s.startsWith("You are not currently in a party");
            if (!ours) return false;
        }
        for (int i = 0; i < flines.length; i++) {
            String s = EnumChatFormatting.getTextWithoutFormattingCodes(flines[i]).trim();
            if (s.startsWith("You are not currently in a party")) {
                askedAt = 0;   // no party: nothing to do
                return true;
            }
            boolean isLeader = s.startsWith("Party Leader:");
            if (!isLeader && !s.startsWith("Party Moderators:") && !s.startsWith("Party Members:")) continue;
            // "Party Members: [VIP] A ● [MVP+] B ●": a green dot = online, red = offline
            String[] parts = flines[i].substring(flines[i].indexOf(':') + 1).split("●", -1);
            for (int p = 0; p < parts.length - 1; p++) {
                String seg = parts[p];
                Matcher n = NAME.matcher(EnumChatFormatting.getTextWithoutFormattingCodes(seg));
                if (!n.find()) continue;
                char color = 'f';
                for (int c = seg.length() - 2; c >= 0; c--) {
                    char k = Character.toLowerCase(seg.charAt(c + 1));
                    if (seg.charAt(c) == '§' && "0123456789abcdef".indexOf(k) >= 0) { color = k; break; }
                }
                if (isLeader) leader = n.group(1);
                else if (color == 'a') online.add(n.group(1));
            }
        }
        return true;
    }

    /** The whole answer is here: warp the members who are missing, if you lead the party. */
    private void finish(Minecraft mc) {
        askedAt = 0;
        if (leader == null || mc.thePlayer == null || mc.theWorld == null) return;
        if (!leader.equalsIgnoreCase(mc.thePlayer.getName())) return;   // only the leader can warp
        List<String> missing = new ArrayList<String>();
        for (String n : online) {
            if (n.equalsIgnoreCase(mc.thePlayer.getName())) continue;
            boolean here = mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfo(n) != null
                    || mc.theWorld.getPlayerEntityByName(n) != null;
            if (!here) missing.add(n);
        }
        if (missing.isEmpty()) return;
        mc.thePlayer.sendChatMessage("/p warp");
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.YELLOW
                + Lang.f("{0} が入れなかったので /p warp しました", String.join(", ", missing))));
    }

}
