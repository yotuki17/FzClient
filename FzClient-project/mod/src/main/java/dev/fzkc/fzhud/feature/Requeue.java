package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.stats.Stats;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Locale;

/**
 * Requeue: /rq sends "/play <the game you played last>" on Hypixel, so you are in the next game without walking
 * back through the lobby. One /rq is one command.
 */
public class Requeue {
    /** The /play name of the game played last ("bedwars_eight_two"). */
    private static String last;
    private static long sentAt;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        String raw = Stats.rawMode;
        if (raw != null && !Stats.lobby) {
            String m = raw.toLowerCase(Locale.ROOT);
            // SkyBlock, Housing and the like have no queue to join again
            if (m.matches("[a-z0-9_]{3,40}") && !m.equals("dynamic") && !m.equals("lobby")) last = m;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (TEST && mc.thePlayer != null && ++testTicks % 100 == 0) {
            if (testTicks == 200) last = "bedwars_eight_two";
            net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/rq");
        }
    }

    private static void run(Minecraft mc) {
        if (mc.thePlayer == null) return;
        if (!TEST && (!Modules.requeue.enabled || !Stats.onHypixel())) {
            mc.thePlayer.sendChatMessage("/rq");   // not ours here: the server's own /rq, when it has one
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sentAt < 3000L) return;   // Hypixel answers "please wait" to commands sent too fast
        if (last == null) {
            say(mc, Lang.t("まだ入り直せるゲームがありません（一度ゲームに入ると使えます）"));
            return;
        }
        sentAt = now;
        if (TEST) say(mc, "/play " + last);
        else mc.thePlayer.sendChatMessage("/play " + last);
    }

    private static void say(Minecraft mc, String text) {
        mc.thePlayer.addChatMessage(new ChatComponentText("\u00a78[\u00a7bFzClient\u00a78]\u00a7r \u00a77" + text));
    }

    /** /rq */
    public static class Command extends CommandBase {
        @Override public String getCommandName() { return "rq"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/rq"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
        @Override public void processCommand(ICommandSender sender, String[] args) { run(Minecraft.getMinecraft()); }
    }

    /** Dev check (-Dfzclient.requeuetest): runs /rq every 5 seconds and prints the command instead of sending it. */
    private static final boolean TEST = System.getProperty("fzclient.requeuetest") != null;
    private static int testTicks;
}
