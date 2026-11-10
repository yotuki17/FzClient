package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.stats.Stats;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * Requeue: one key press sends "/play <the game you played last>" on Hypixel, so you are in the next game without
 * walking back through the lobby. One press is one command. While a game is still running the key has to be
 * pressed twice, so a slip of the finger does not leave the game.
 */
public class Requeue {
    public static final KeyBinding KEY = new KeyBinding("Requeue", Keyboard.KEY_R, "FzClient");

    /** The /play name of the game played last ("bedwars_eight_two"). */
    private static String last;
    /** The world whose game is over (its end summary came in chat). */
    private static Object endedWorld;
    private static long armedAt, sentAt;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        String raw = Stats.rawMode;
        if (raw != null && !Stats.lobby) {
            String m = raw.toLowerCase(Locale.ROOT);
            // SkyBlock, Housing and the like have no queue to join again
            if (m.matches("[a-z0-9_]{3,40}") && !m.equals("dynamic") && !m.equals("lobby")) last = m;
        }
        boolean pressed = false;
        while (KEY.isPressed()) pressed = true;
        if (TEST && mc.thePlayer != null && ++testTicks % 100 == 0) {
            if (testTicks == 100) last = "bedwars_eight_two";
            pressed = true;
        }
        if (pressed && Modules.requeue.enabled && mc.thePlayer != null && mc.theWorld != null) press(mc);
    }

    private static void press(Minecraft mc) {
        if (!Stats.onHypixel() && !TEST) return;
        long now = System.currentTimeMillis();
        if (now - sentAt < 3000L) return;   // Hypixel answers "please wait" to commands sent too fast
        if (last == null) {
            say(mc, Lang.t("まだ入り直せるゲームがありません（一度ゲームに入ると使えます）"));
            return;
        }
        boolean running = !Stats.lobby && mc.theWorld != endedWorld;
        if (TEST) running = testTicks < 400;
        if (running && Modules.requeueConfirm.get() && now - armedAt > 2500L) {
            armedAt = now;
            say(mc, Lang.t("試合中です。もう一度押すと、抜けて次の試合に入ります"));
            return;
        }
        armedAt = 0;
        sentAt = now;
        if (TEST) say(mc, "/play " + last);
        else mc.thePlayer.sendChatMessage("/play " + last);
    }

    /** The end-of-game summary (the same lines Auto GG looks for). */
    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || event.message == null) return;
        String s = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (s == null || !s.startsWith(" ")) return;   // summary lines are centred; player chat never starts with a space
        for (String k : Extras.GAME_END) {
            if (s.contains(k)) {
                endedWorld = Minecraft.getMinecraft().theWorld;
                return;
            }
        }
    }

    private static void say(Minecraft mc, String text) {
        mc.thePlayer.addChatMessage(new ChatComponentText("\u00a78[\u00a7bFzClient\u00a78]\u00a7r \u00a77" + text));
    }

    /** Dev check (-Dfzclient.requeuetest): presses the key every 5 seconds and prints the command instead of sending it. */
    private static final boolean TEST = System.getProperty("fzclient.requeuetest") != null;
    private static int testTicks;
}
