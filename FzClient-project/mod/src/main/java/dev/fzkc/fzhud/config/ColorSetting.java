package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.fzkc.fzhud.render.R;

/** A colour picked with hex / HSV / presets, or "chroma" (flowing rainbow). */
public class ColorSetting extends Setting {
    /** Colours older versions offered as a numbered list; used to read old config files. */
    private static final int[] LEGACY = {0xFF5CE1E6, 0xFFFFFFFF, 0xFFFF5555, 0xFF22C55E, 0xFFFFD84A, 0xFFFF6AD5, 0xFFA78BFA};

    public final int def;
    public final boolean defChroma;
    public int rgb;          // 0xRRGGBB
    public boolean chroma;

    public ColorSetting(String key, String label, int defArgb) {
        this(key, label, defArgb, false);
    }

    public ColorSetting(String key, String label, int defArgb, boolean defChroma) {
        super(key, label);
        this.def = defArgb & 0xFFFFFF;
        this.defChroma = defChroma;
        reset();
    }

    /** ARGB for drawing; chroma returns the rainbow marker understood by {@link R}. */
    public int get() {
        return chroma ? R.CHROMA : (0xFF000000 | rgb);
    }

    private int hexOf = -1;
    private String hexText;

    public String hex() {
        if (hexText == null || hexOf != rgb) {
            hexText = String.format("#%06X", rgb);
            hexOf = rgb;
        }
        return hexText;
    }

    /** Accepts "#RGB", "#RRGGBB", "RRGGBB", or "chroma". Returns false if it couldn't parse. */
    public boolean setHex(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.equalsIgnoreCase("chroma") || s.equalsIgnoreCase("rainbow")) {
            chroma = true;
            return true;
        }
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() == 8) s = s.substring(2); // ignore alpha
        if (s.length() != 6) return false;
        try {
            rgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            chroma = false;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public float[] hsb() {
        return java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
    }

    public void setHsb(float h, float s, float b) {
        rgb = java.awt.Color.HSBtoRGB(h, s, b) & 0xFFFFFF;
        chroma = false;
    }

    @Override
    public JsonElement save() {
        return new JsonPrimitive(chroma ? "chroma" : hex());
    }

    @Override
    public void load(JsonElement e) {
        try {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                int i = p.getAsInt();
                if (i == 7) chroma = true;
                else if (i >= 0 && i < LEGACY.length) {
                    rgb = LEGACY[i] & 0xFFFFFF;
                    chroma = false;
                }
            } else {
                setHex(p.getAsString());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void reset() {
        rgb = def;
        chroma = defChroma;
    }
}
