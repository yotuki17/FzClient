package dev.fzkc.fzhud.util;

import dev.fzkc.fzhud.Modules;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Client language. The code is written in Japanese; in English mode every text drawn by FzClient is looked up in
 * assets/fzclient/lang/en.txt (one "japanese<TAB>english" pair per line). Unknown text is shown as it is.
 */
public final class Lang {
    private Lang() {}

    private static Map<String, String> en;

    public static boolean english() {
        return Modules.langChoice != null && Modules.langChoice.get() == 1;
    }

    public static String t(String s) {
        if (s == null || !english()) return s;
        if (en == null) load();
        String v = en.get(s);
        return v == null ? s : v;
    }

    /**
     * Translated text with values put in: the template is written with {0}, {1}... where the values go
     * (en.txt holds the template too), e.g. f("「{0}」を削除しました", name).
     */
    public static String f(String template, Object... args) {
        String s = t(template);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** Switches between Japanese and English. */
    public static void toggle() {
        Modules.langChoice.next();
        dev.fzkc.fzhud.Config.save();
    }

    private static synchronized void load() {
        if (en != null) return;
        Map<String, String> m = new HashMap<>(512);
        try {
            InputStream in = Lang.class.getResourceAsStream("/assets/fzclient/lang/en.txt");
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) m.put(line.substring(0, tab), line.substring(tab + 1));
                }
                r.close();
            }
        } catch (Exception ignored) {
        }
        en = m;
    }
}
