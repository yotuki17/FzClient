package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.config.TextSetting;
import dev.fzkc.fzhud.integration.DiscordVoice;
import dev.fzkc.fzhud.render.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Discord voice channel on screen: who is in it, a green dot for whoever is talking right now, and a red tag for
 * people who are muted (M) or deafened (D). The connection itself lives in {@link DiscordVoice}; this only draws.
 *
 * It needs your own Discord application: put its Client ID and Client Secret in this HUD's settings.
 * The secret is a password for that application - keep it out of screenshots and shared preset codes.
 */
public class DiscordVcHud extends HudElement {
    private static final float PAD = 5f, ROW = 11f;

    /** DiscordVoice reads the credentials from here. */
    public static DiscordVcHud instance;

    private final TextSetting clientId, clientSecret;
    private final SliderSetting maxRows;
    private final BoolSetting onlySpeaking, showSelf, showHeader;

    private float lastW = 90, lastH = 30;

    public DiscordVcHud(float defX, float defY) {
        super("discordvc", "Discord VC", "VCの参加者を表示", defX, defY, LEFT, false);
        withBackground();
        clientId = add(new TextSetting("clientid", "Client ID", "", 32));
        clientSecret = add(new TextSetting("secret", "Client Secret（人に見せない）", "", 64));
        maxRows = add(new SliderSetting("rows", "表示する人数", 3, 15, 1, 8, "人"));
        onlySpeaking = add(new BoolSetting("onlyspeaking", "話している人だけ表示", false));
        showSelf = add(new BoolSetting("self", "自分も表示", true));
        showHeader = add(new BoolSetting("header", "チャンネル名を表示", true));
        instance = this;
    }

    public String clientId() { return clientId.get().trim(); }

    public String clientSecret() { return clientSecret.get().trim(); }

    /** The people to list right now (filters applied). */
    private List<DiscordVoice.Member> rows(boolean preview) {
        List<DiscordVoice.Member> all = DiscordVoice.snapshot();
        if (all.isEmpty() && preview) {
            // a sample for the HUD editor
            DiscordVoice.Member a = new DiscordVoice.Member("1", "Alice");
            a.speaking = true;
            DiscordVoice.Member b = new DiscordVoice.Member("2", "Bob");
            b.mute = true;
            all.add(a);
            all.add(b);
            all.add(new DiscordVoice.Member("3", "Charlie"));
        }
        String me = DiscordVoice.selfId();
        boolean self = showSelf.get(), talk = onlySpeaking.get();
        List<DiscordVoice.Member> out = new ArrayList<DiscordVoice.Member>();
        for (DiscordVoice.Member m : all) {
            if (!self && m.id.equals(me)) continue;
            if (talk && !m.speaking) continue;
            out.add(m);
        }
        return out;
    }

    /** Hidden while you are not in a voice channel (or nobody is left to list). */
    @Override
    public boolean active() {
        return DiscordVoice.connected() && DiscordVoice.channel() != null && !rows(false).isEmpty();
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    @Override
    public void draw(float alpha, boolean preview) {
        List<DiscordVoice.Member> list = rows(preview);
        if (list.isEmpty()) return;
        String channel = DiscordVoice.channel();
        if (channel == null) channel = "voice";
        boolean header = showHeader.get();
        int max = (int) maxRows.get();
        int shown = Math.min(list.size(), max);
        int hidden = list.size() - shown;

        // ---- size
        float nameW = header ? R.width("# " + channel) : 0f;
        for (int i = 0; i < shown; i++) {
            DiscordVoice.Member m = list.get(i);
            float w = R.width(m.name) + ((m.mute || m.deaf) ? 14f : 0f);
            if (w > nameW) nameW = w;
        }
        if (hidden > 0) nameW = Math.max(nameW, R.width("+" + hidden));
        float w = Math.max(80f, PAD * 2 + 10f + nameW);
        float h = PAD * 2 + (header ? ROW : 0f) + (shown + (hidden > 0 ? 1 : 0)) * ROW - 2f;
        lastW = w;
        lastH = h;
        bg(0, 0, w, h, alpha);

        // ---- rows
        float y = PAD;
        if (header) {
            R.text("# " + channel, PAD, y, R.alpha(0xFF8FA5AA, alpha));
            y += ROW;
        }
        for (int i = 0; i < shown; i++) {
            DiscordVoice.Member m = list.get(i);
            boolean talking = m.speaking;
            // green dot while talking, a dim one otherwise
            R.rounded(PAD, y + 1.5f, 6f, 6f, 3f, R.alpha(talking ? 0xFF55FF55 : 0xFF3A4A4F, alpha));
            R.text(m.name, PAD + 10f, y, R.alpha(talking ? 0xFFFFFFFF : 0xFFB9C6C9, alpha));
            if (m.deaf) R.text("D", w - PAD - R.width("D"), y, R.alpha(0xFFFF5555, alpha));
            else if (m.mute) R.text("M", w - PAD - R.width("M"), y, R.alpha(0xFFFF5555, alpha));
            y += ROW;
        }
        if (hidden > 0) R.text("+" + hidden, PAD + 10f, y, R.alpha(0xFF8FA5AA, alpha));
    }
}
