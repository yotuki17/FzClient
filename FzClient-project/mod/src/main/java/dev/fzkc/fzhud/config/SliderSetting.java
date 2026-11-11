package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class SliderSetting extends Setting {
    public final double min, max, step, def;
    public final String suffix;
    public double value;

    public SliderSetting(String key, String label, double min, double max, double step, double def, String suffix) {
        super(key, label);
        this.min = min;
        this.max = max;
        this.step = step;
        this.def = def;
        this.suffix = suffix;
        this.value = def;
    }

    public float get() { return (float) value; }

    public void set(double v) {
        v = Math.max(min, Math.min(max, v));
        if (step > 0) v = min + Math.round((v - min) / step) * step;
        v = Math.max(min, Math.min(max, v));
        value = Math.abs(v) < 1e-9 ? 0 : v;
    }

    /** 0..1 position of the knob. */
    public double fraction() { return max == min ? 0 : (value - min) / (max - min); }

    // the settings screen asks every frame; String.format is only run again when the value or language changes
    private double displayValue = Double.NaN;
    private boolean displayEnglish;
    private String displayText;

    public String display() {
        boolean english = dev.fzkc.fzhud.util.Lang.english();
        if (displayText != null && Double.doubleToLongBits(value) == Double.doubleToLongBits(displayValue)
                && english == displayEnglish) {
            return displayText;
        }
        int decimals = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
        displayText = String.format("%." + decimals + "f", value) + dev.fzkc.fzhud.util.Lang.t(suffix);
        displayValue = value;
        displayEnglish = english;
        return displayText;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override
    public void load(JsonElement e) {
        try { set(e.getAsDouble()); } catch (Exception ignored) {}
    }

    @Override public void reset() { value = def; }
}
