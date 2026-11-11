package dev.fzkc.fzhud.feature;

import com.mojang.authlib.GameProfile;
import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Name Hider: your own name is drawn as another name - in the chat, the tab list, name tags, the scoreboard and
 * the menus: everything the game's font draws (see MixinFontRenderer). Only what YOUR screen shows changes, for
 * recordings and screenshots. The texts themselves stay as they are, so nothing that reads the chat or the tab
 * list is affected and nothing different is sent to the server.
 * <p>
 * A name only counts as a whole word ("Fzkc" is not replaced inside "Fzkc_2"), in any case ("fzkc"), and also
 * right after a colour code ("§aFzkc").
 */
public final class NameHider {
    private NameHider() {}

    /** The names to hide and what to show instead, as the account and the settings were when this was built. */
    private static final class Rules {
        Session session;
        String shownSetting, extraSetting;
        String[] names = new String[0];
        /** The characters a hidden name can start with (both cases). */
        final boolean[] first = new boolean[128];
        /** A hidden name starts with a character outside that table (typed by hand). */
        boolean wideFirst;
        String shown = "You";
        String ownName;
        UUID ownId;
        /** Texts that were already replaced (used on the game thread only). */
        final HashMap<String, String> done = new HashMap<String, String>();
    }

    private static volatile Rules rules;
    private static volatile int gen = 1;
    private static int raw;

    /**
     * While on, texts are drawn as they are (the settings box where you type the names to hide).
     * Game thread only; every raw(true) needs its raw(false).
     */
    public static void raw(boolean on) {
        raw += on ? 1 : -1;
    }

    /** True while texts are drawn as they are although the module is on (their widths must not be remembered). */
    public static boolean rawNow() {
        return raw > 0;
    }

    /** Changes whenever the same text may be drawn differently (remembered text widths follow it). */
    public static int stamp() {
        return Modules.nameHider.enabled ? gen : 0;
    }

    private static Rules rules() {
        Session s = Minecraft.getMinecraft().getSession();
        String shown = Modules.nameHiderName.value, extra = Modules.nameHiderExtra.value;
        Rules r = rules;
        if (r != null && r.session == s && r.shownSetting == shown && r.extraSetting == extra) return r;
        Rules n = new Rules();
        n.session = s;
        n.shownSetting = shown;
        n.extraSetting = extra;
        List<String> names = new ArrayList<String>();
        String own = s == null ? null : s.getUsername();
        n.ownName = own;
        if (own != null && own.length() >= 3) names.add(own);
        try {
            n.ownId = s == null ? null : s.getProfile().getId();
        } catch (Throwable noId) {
            n.ownId = null;
        }
        if (extra != null) {
            for (String e : extra.split("[,\u3001\\s]+")) {
                if (e.length() < 3 || e.length() > 32 || names.size() >= 10) continue;
                boolean have = false;
                for (String x : names) have |= x.equalsIgnoreCase(e);
                if (!have) names.add(e);
            }
        }
        String show = shown == null ? "" : shown.replace("\u00a7", "").trim();
        n.shown = show.isEmpty() ? "You" : show;
        n.names = names.toArray(new String[0]);
        for (String name : n.names) {
            char lo = Character.toLowerCase(name.charAt(0)), up = Character.toUpperCase(name.charAt(0));
            if (lo < 128 && up < 128) {
                n.first[lo] = true;
                n.first[up] = true;
            } else {
                n.wideFirst = true;
            }
        }
        rules = n;
        gen++;
        return n;
    }

    private static boolean nameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    /** The length of the hidden name that stands at i as a whole word, or 0. */
    private static int matchAt(String text, int i, int n, String[] names) {
        // the letter of a colour code ("§a") in front is not part of a word
        if (i > 0 && nameChar(text.charAt(i - 1)) && !(i > 1 && text.charAt(i - 2) == '\u00a7')) return 0;
        for (String name : names) {
            int len = name.length();
            if (i + len > n || !text.regionMatches(true, i, name, 0, len)) continue;
            if (i + len < n && nameChar(text.charAt(i + len))) continue;
            return len;
        }
        return 0;
    }

    /**
     * The text as it is drawn: the same object when nothing in it is hidden (almost always; no memory is used
     * then), else a copy with the other name.
     */
    public static String apply(String text) {
        if (!Modules.nameHider.enabled || raw > 0 || text == null) return text;
        int n = text.length();
        if (n < 3) return text;
        Rules r = rules();
        String[] names = r.names;
        if (names.length == 0) return text;
        boolean[] first = r.first;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c < 128 ? !first[c] : !r.wideFirst) continue;
            if (matchAt(text, i, n, names) > 0) return replace(text, i, r);
        }
        return text;
    }

    private static String replace(String text, int from, Rules r) {
        boolean game = Minecraft.getMinecraft().isCallingFromMinecraftThread();
        if (game) {
            String d = r.done.get(text);
            if (d != null) return d;
        }
        int n = text.length(), copied = 0;
        StringBuilder sb = new StringBuilder(n + 8);
        for (int i = from; i < n; i++) {
            char c = text.charAt(i);
            if (c < 128 ? !r.first[c] : !r.wideFirst) continue;
            int len = matchAt(text, i, n, r.names);
            if (len == 0) continue;
            sb.append(text, copied, i).append(r.shown);
            i += len - 1;
            copied = i + 1;
        }
        String out = sb.append(text, copied, n).toString();
        if (game) {
            if (r.done.size() > 2000) r.done.clear();
            r.done.put(text, out);
        }
        return out;
    }

    /** True when this tab list entry is you and your skin is to be shown as the default one. */
    public static boolean hidesSkin(GameProfile profile) {
        if (!Modules.nameHider.enabled || !Modules.nameHiderSkin.get() || profile == null) return false;
        Rules r = rules();
        UUID id = profile.getId();
        if (id != null && id.equals(r.ownId)) return true;
        return r.ownName != null && r.ownName.equalsIgnoreCase(profile.getName());
    }
}
