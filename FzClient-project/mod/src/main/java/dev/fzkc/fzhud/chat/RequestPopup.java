package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.gui.Ui;
import dev.fzkc.fzhud.util.Lang;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small box for party invites and friend requests (like Mellow): Y accepts, N declines (friend requests) or
 * just closes it (party invites, which simply run out). One at a time; more wait behind it. The keys only count
 * while no screen (chat, inventory, menu) is open.
 */
public final class RequestPopup {
    private static final Pattern FRIEND = Pattern.compile("Friend request from (?:\\[[^\\]]{1,24}\\] )?([A-Za-z0-9_]{1,16})");
    private static final int MAX_WAITING = 5;

    enum Kind { PARTY, FRIEND }

    private static final class Req {
        final Kind kind;
        final String name, shown;   // shown keeps the rank colors
        long until;

        Req(Kind kind, String name, String shown) {
            this.kind = kind;
            this.name = name;
            this.shown = shown;
        }
    }

    private static final ArrayDeque<Req> waiting = new ArrayDeque<>();
    private static Req active;
    private static long shownAt;

    /** A party invite from this player (BedwarsAlerts reads the invite message). */
    static void party(String name, String shown) {
        if (Modules.chatPopupParty.get()) add(new Req(Kind.PARTY, name, shown));
    }

    private static synchronized void add(Req r) {
        if (active != null && active.kind == r.kind && active.name.equalsIgnoreCase(r.name)) {
            active.until = System.currentTimeMillis() + durationMs();   // the same invite again: keep it up longer
            return;
        }
        for (Req w : waiting) if (w.kind == r.kind && w.name.equalsIgnoreCase(r.name)) return;
        if (waiting.size() >= MAX_WAITING) waiting.pollFirst();
        waiting.addLast(r);
    }

    private static long durationMs() {
        return (long) (Modules.chatPopupTime.get() * 1000);
    }

    /** The box being shown now, after dropping one that ran out and showing the next. */
    private static synchronized Req current() {
        long now = System.currentTimeMillis();
        if (active != null && now >= active.until) active = null;
        if (active == null && !waiting.isEmpty()) {
            active = waiting.pollFirst();
            active.until = now + durationMs();
            shownAt = now;
        }
        return active;
    }

    private static synchronized void decide(boolean yes) {
        Req r = current();
        if (r == null) return;
        active = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        String cmd = null;
        if (yes) cmd = (r.kind == Kind.PARTY ? "/party accept " : "/friend accept ") + r.name;
        else if (r.kind == Kind.FRIEND) cmd = "/friend deny " + r.name;
        if (cmd != null) mc.thePlayer.sendChatMessage(cmd);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2 || !Modules.chat.enabled || !Modules.chatPopupFriend.get()) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null || !plain.contains("Friend request from")) return;
        Matcher m = FRIEND.matcher(plain);
        if (!m.find()) return;
        add(new Req(Kind.FRIEND, m.group(1), rankedName(event.message.getFormattedText(), m.group(1))));
        if (Modules.chatInviteSound.get()) ChatAlerts.play(1.2f);
    }

    /** "[MVP+] Name" with its colors, cut out of the formatted message (or just the name). */
    static String rankedName(String formatted, String name) {
        int i = formatted.indexOf(name);
        if (i < 0) return name;
        int start = i;
        int open = formatted.lastIndexOf('[', i);
        if (open >= 0 && i - open < 40) start = open;
        while (start >= 2 && formatted.charAt(start - 2) == '§') start -= 2;
        return formatted.substring(start, i + name.length());
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || !Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) return;
        int k = Keyboard.getEventKey();
        if (k != Keyboard.KEY_Y && k != Keyboard.KEY_N) return;
        if (current() == null) return;
        decide(k == Keyboard.KEY_Y);
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Req r = current();
        if (r == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.hideGUI) return;
        long now = System.currentTimeMillis();
        float in = Math.min(1f, (now - shownAt) / 180f);
        float o = in * Math.min(1f, (r.until - now) / 300f);
        if (o <= 0.02f) return;

        String title = Lang.t(r.kind == Kind.PARTY ? "パーティ招待" : "フレンドの申請が来ました");
        String keys = "§a[Y] §f" + Lang.t(r.kind == Kind.PARTY ? "参加" : "受ける") + "   §c[N] §f" + Lang.t(r.kind == Kind.PARTY ? "閉じる" : "断る");
        float w = Math.max(150, Math.max(R.width(r.shown), R.width(keys)) + 20), h = 46;
        ScaledResolution sr = event.resolution;
        int pos = Modules.chatPopupPos.get();
        float x = pos == 1 ? sr.getScaledWidth() - w - 6 : pos == 2 ? 6 : (sr.getScaledWidth() - w) / 2f;
        float y = (pos == 0 ? 34 : 6) - (1f - in) * 8f;

        R.rounded(x, y, w, h, Ui.rad(6f), R.alpha(0xD0101418, o));
        R.roundedOutline(x, y, w, h, Ui.rad(6f), 1f, R.alpha(Ui.accentA(0xA0), o));
        R.text("§l" + title, x + 8, y + 6, R.alpha(Ui.accent, o));
        R.text(r.shown, x + 8, y + 18, R.alpha(0xFFFFFFFF, o));
        R.text(keys, x + 8, y + 31, R.alpha(0xFFFFFFFF, o));
        // time left
        float left = Math.max(0f, Math.min(1f, (r.until - now) / (float) durationMs()));
        R.rect(x + 6, y + h - 3, (w - 12) * left, 1.5f, R.alpha(Ui.accent, o));
        int more = waiting.size();
        if (more > 0) {
            String s = "+" + more;
            R.text(s, x + w - 8 - R.width(s), y + 6, R.alpha(0xFFAAAAAA, o));
        }
    }
}
