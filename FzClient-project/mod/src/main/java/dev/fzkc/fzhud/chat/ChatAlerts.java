package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Locale;

/**
 * Chat sounds: a ping for party chat ("Party > name: ...") and when someone writes your name (or one of your
 * own keywords). Your own messages never ping. Mentions can also be marked in the chat.
 */
public final class ChatAlerts {
    private static final String[] SOUNDS = {"note.pling", "random.orb", "note.harp"};
    private static long lastSound;

    /** A UI-style sound: no position / distance, with our own volume. */
    private static final class Ping extends PositionedSound {
        Ping(ResourceLocation loc, float volume, float pitch) {
            super(loc);
            this.volume = volume;
            this.pitch = pitch;
            this.attenuationType = ISound.AttenuationType.NONE;
        }
    }

    // runs before the timestamp / compact handlers (LOWEST) so the text is still the server's
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2 || event.message == null || !Modules.chat.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;
        String me = mc.thePlayer.getName();

        boolean party = plain.startsWith("Party > ") || plain.startsWith("P > ");
        int colon = plain.indexOf(": ");
        if (colon < 0) return; // only player messages (system lines like "You joined" don't count)
        String sender = plain.substring(0, colon);
        String body = plain.substring(colon + 2);
        if (containsWord(sender, me)) return; // our own message

        if (party && Modules.chatPartySound.get()) {
            play(1.4f);
            return;
        }
        if (Modules.chatMentionSound.get() && mentioned(body, me)) {
            play(1.8f);
            if (Modules.chatMentionMark.get()) {
                IChatComponent marked = new ChatComponentText(EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "@ "
                        + EnumChatFormatting.RESET);
                marked.appendSibling(event.message);
                event.message = marked;
            }
        }
    }

    private static boolean mentioned(String body, String me) {
        if (containsWord(body, me)) return true;
        String words = Modules.chatMentionWords.get();
        if (words == null || words.trim().isEmpty()) return false;
        for (String w : words.split("[,、]")) {
            String t = w.trim();
            if (!t.isEmpty() && containsWord(body, t)) return true;
        }
        return false;
    }

    /** Case-insensitive, and not as part of a longer name ("Alex" doesn't match "Alex123"). */
    static boolean containsWord(String text, String word) {
        if (word == null || word.isEmpty()) return false;
        String t = text.toLowerCase(Locale.ROOT), w = word.toLowerCase(Locale.ROOT);
        int i = t.indexOf(w);
        while (i >= 0) {
            boolean before = i == 0 || !isNameChar(t.charAt(i - 1));
            int end = i + w.length();
            boolean after = end >= t.length() || !isNameChar(t.charAt(end));
            if (before && after) return true;
            i = t.indexOf(w, i + 1);
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return c == '_' || (c < 128 && Character.isLetterOrDigit(c));
    }

    private static void play(float pitch) {
        long now = System.currentTimeMillis();
        if (now - lastSound < 150L) return; // a burst of lines makes one ping
        lastSound = now;
        int i = Math.max(0, Math.min(SOUNDS.length - 1, Modules.chatSoundType.get()));
        Minecraft.getMinecraft().getSoundHandler().playSound(
                new Ping(new ResourceLocation(SOUNDS[i]), Modules.chatSoundVolume.get(), pitch));
    }
}
