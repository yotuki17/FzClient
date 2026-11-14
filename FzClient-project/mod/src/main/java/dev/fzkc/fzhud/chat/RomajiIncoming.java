package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Other people's romaji, shown as Japanese with what they typed after it:
 * "[MVP+] Name: konnitiha" -> "[MVP+] Name: こんにちは(konnitiha)".
 * <p>
 * Only player chat ("name: message") is touched, with the stricter rules of {@link Romaji#parts(String, boolean)}.
 * Kanji come from Google like for your own messages. Until the answer is here (a fraction of a second, at most
 * {@link #WAIT_MS}) the line is held back, and so is every chat line after it, so the chat keeps its order.
 * <p>
 * It runs last (LOWEST, registered after the other chat features), so every other feature has already seen the
 * line as the server sent it: alerts, party detection, the chat log, timestamps and "(x2)" stacking.
 */
public class RomajiIncoming {
    private static final long WAIT_MS = 2000;
    /** More lines than this waiting for Google: the next ones get hiragana right away instead. */
    private static final int MAX_WAITING = 8;

    private static final class Held {
        IChatComponent msg;
        String body, key, result;
        List<String[]> parts;
        long at;
        boolean done;
    }

    private final ArrayDeque<Held> queue = new ArrayDeque<>();
    private int waiting;

    private static boolean on() {
        return Modules.chat.enabled && Modules.romajiOn.get() && Modules.romajiIncoming.get();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2 || event.message == null) return;
        Held h = new Held();
        h.msg = event.message;
        h.at = System.currentTimeMillis();
        if (on()) prepare(h);
        if (h.parts == null && queue.isEmpty()) return;   // nothing to change, nothing held: as it is

        if (h.parts != null) {
            String ready = Modules.romajiKanji.get() ? Romaji.cached(h.key) : join(h.parts);
            if (ready == null && waiting >= MAX_WAITING) ready = join(h.parts);
            if (ready != null) {
                if (queue.isEmpty()) {
                    event.message = convert(h, ready);
                    dev.fzkc.fzhud.feature.Extras.shownAs(h.msg, event.message);
                    return;
                }
                h.result = ready;
                h.done = true;
            }
        } else {
            h.done = true;   // only waits for the lines before it
        }
        event.setCanceled(true);
        queue.add(h);
        if (!h.done) {
            waiting++;
            final Held held = h;
            Romaji.ask(h.key, h.parts, () -> {
                held.done = true;
                waiting--;
                release();
            });
        }
    }

    /** Lines that only wait for a line before them, or for Google, still go out when it takes too long. */
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !queue.isEmpty()) release();
    }

    /** Prints every line at the front of the queue that is ready (in order). Game thread. */
    private void release() {
        long now = System.currentTimeMillis();
        while (!queue.isEmpty()) {
            Held h = queue.peek();
            if (!h.done && now - h.at < WAIT_MS) break;
            queue.poll();
            IChatComponent out = h.msg;
            if (h.parts != null) {
                String r = h.result != null ? h.result : Romaji.cached(h.key);
                out = convert(h, r != null ? r : join(h.parts));
                dev.fzkc.fzhud.feature.Extras.shownAs(h.msg, out);
            }
            try {
                Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(out);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ reading and rewriting the line

    private static void prepare(Held h) {
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(h.msg.getUnformattedText());
        if (plain == null) return;
        // "[MVP+] Name: hello", "Party > Name: hello", "From [VIP] Name: hello"
        int colon = plain.indexOf(": ");
        if (colon <= 0 || colon > 70) return;
        String body = plain.substring(colon + 2).trim();
        // " (x2)" from the chat stacking is not part of what they said
        body = body.replaceFirst(" \\(x\\d+\\)$", "");
        if (body.length() < 4 || body.length() > 256) return;
        List<String[]> parts = Romaji.parts(body, true);
        if (parts == null) return;
        h.body = body;
        h.parts = parts;
        h.key = "\u0001" + body.toLowerCase(Locale.ROOT);   // apart from your own messages (other rules)
    }

    private static String join(List<String[]> parts) {
        StringBuilder sb = new StringBuilder();
        for (String[] p : parts) sb.append(p[0]);
        return sb.toString();
    }

    /** The line with the message part as "Japanese(romaji)", the rest (name, rank, hover, click) unchanged. */
    static IChatComponent convert(Held h, String japanese) {
        String shown = japanese + EnumChatFormatting.GRAY + "(" + h.body + ")";
        List<ChatComponentText> leaves = new ArrayList<>();
        leaves(h.msg, leaves);
        // the message is at the end of the line: the last piece of text holding it
        for (int i = leaves.size() - 1; i >= 0; i--) {
            ChatComponentText leaf = leaves.get(i);
            String raw = leaf.getChatComponentText_TextValue();
            int at = raw.lastIndexOf(h.body);
            if (at < 0) continue;
            String rest = raw.substring(at + h.body.length());
            String text = raw.substring(0, at) + shown
                    + (EnumChatFormatting.getTextWithoutFormattingCodes(rest).isEmpty() ? "" : EnumChatFormatting.RESET + rest);
            IChatComponent rebuilt = rebuild(h.msg, leaf, text);
            if (rebuilt != null) return rebuilt;
            break;
        }
        // split over several pieces (or inside a translated line): add it after the line instead
        return h.msg.createCopy().appendSibling(new ChatComponentText(EnumChatFormatting.GRAY + " → " + EnumChatFormatting.RESET + japanese));
    }

    private static void leaves(IChatComponent c, List<ChatComponentText> out) {
        if (c instanceof ChatComponentText) out.add((ChatComponentText) c);
        for (IChatComponent s : c.getSiblings()) leaves(s, out);
    }

    private static boolean contains(IChatComponent c, IChatComponent target) {
        if (c == target) return true;
        for (IChatComponent s : c.getSiblings()) if (contains(s, target)) return true;
        return false;
    }

    /** A copy of the line with one piece's text replaced; null when that piece sits somewhere it can't be. */
    private static IChatComponent rebuild(IChatComponent c, IChatComponent target, String text) {
        if (!contains(c, target)) return c.createCopy();
        IChatComponent n;
        if (c == target) n = new ChatComponentText(text);
        else if (c instanceof ChatComponentText) n = new ChatComponentText(((ChatComponentText) c).getChatComponentText_TextValue());
        else return null;
        n.setChatStyle(c.getChatStyle().createShallowCopy());
        for (IChatComponent s : c.getSiblings()) {
            IChatComponent r = rebuild(s, target, text);
            if (r == null) return null;
            n.appendSibling(r);
        }
        return n;
    }
}
