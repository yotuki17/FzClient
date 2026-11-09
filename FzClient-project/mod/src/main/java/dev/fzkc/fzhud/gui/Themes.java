package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.ColorSetting;

/**
 * The "テーマ" choice on the UI page: each theme sets the menu style, colours, glass and roundness in one go.
 * 0 = custom (nothing is changed). The single settings stay free to change afterwards.
 */
public final class Themes {
    private Themes() {}

    /** style (0 glass, 1 classic, 2 dashboard), accent, panel, panel %, text, backdrop %, glass tint %, radius %, blur. */
    private static final Object[][] THEMES = {
            null,
            /* ダーク */      {1, 0x5CE1E6, 0x0C1215, 100, 0xFFFFFF, 60, 50, 100, false},
            /* ホワイト */    {2, 0x2F80ED, 0xF3F5F8, 100, 0x2C3A41, 25, 50, 100, false},
            /* カジュアル */  {0, 0xFF9F43, 0x2B2340, 70, 0xFFFFFF, 35, 65, 200, true},
            /* ネオン */      {1, 0x39FF14, 0x05080A, 100, 0xE8FFE8, 75, 50, 40, false},
            /* サクラ */      {0, 0xFFB7C5, 0x3A1F2B, 75, 0xFFF4F7, 40, 60, 160, true},
            /* オーシャン */  {0, 0x4FC3F7, 0x0B2233, 80, 0xFFFFFF, 45, 45, 120, true},
            /* ミッドナイト */{1, 0x9B8CFF, 0x0E0B1E, 95, 0xEDEBFF, 70, 50, 100, false},
    };

    public static void apply(int index) {
        if (index <= 0 || index >= THEMES.length || THEMES[index] == null) return;
        Object[] t = THEMES[index];
        Modules.uiStyle.index = (Integer) t[0];
        color(Modules.uiAccent, (Integer) t[1]);
        color(Modules.uiPanelColor, (Integer) t[2]);
        Modules.uiPanelOpacity.set((Integer) t[3]);
        color(Modules.uiTextColor, (Integer) t[4]);
        Modules.uiBackdrop.set((Integer) t[5]);
        Modules.glassTint.set((Integer) t[6]);
        Modules.uiRadius.set((Integer) t[7]);
        Modules.glassBlur.value = (Boolean) t[8];
    }

    private static void color(ColorSetting c, int rgb) {
        c.rgb = rgb & 0xFFFFFF;
        c.chroma = false;
    }
}
