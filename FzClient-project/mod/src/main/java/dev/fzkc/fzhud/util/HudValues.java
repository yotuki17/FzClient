package dev.fzkc.fzhud.util;

import dev.fzkc.fzhud.feature.Extras;

/** Text for the Reach / Combo / Memory panels, rebuilt only when the number changes. */
public final class HudValues {
    private HudValues() {}

    /** "blocks" after the number (setting on the Reach Display element). */
    public static dev.fzkc.fzhud.config.BoolSetting reachUnit;

    /** Hide the panel while there is no recent hit. */
    public static dev.fzkc.fzhud.config.BoolSetting reachHideIdle;

    public static boolean reachFresh() {
        return Extras.lastReach >= 0 && System.currentTimeMillis() - Extras.lastReachTime < 3000L;
    }

    private static long reachKey = Long.MIN_VALUE;
    private static String reachText = "";

    public static String reach() {
        boolean fresh = Extras.lastReach >= 0 && System.currentTimeMillis() - Extras.lastReachTime < 3000L;
        long key = fresh ? Math.round(Extras.lastReach * 100.0) : -1;
        boolean unit = reachUnit == null || reachUnit.get();
        long fullKey = key * 2 + (unit ? 1 : 0);
        if (fullKey != reachKey) {
            reachKey = fullKey;
            String suffix = unit ? " blocks" : "";
            reachText = key < 0 ? "--" + suffix : Fmt.append(new StringBuilder(14), key / 100.0, 2).append(suffix).toString();
        }
        return reachText;
    }

    private static int comboKey = -1;
    private static String comboText = "";

    public static String combo() {
        int c = Extras.combo;
        if (c != comboKey) {
            comboKey = c;
            comboText = c == 0 ? "No Combo" : c + " Combo";
        }
        return comboText;
    }

    private static long memAt;
    private static String memText = "";

    public static String memory() {
        long now = System.currentTimeMillis();
        if (now - memAt > 1000L) {
            memAt = now;
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory() / 1048576L, used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
            memText = "Mem " + (max > 0 ? used * 100 / max : 0) + "% " + used + "/" + max + "MB";
        }
        return memText;
    }
}
