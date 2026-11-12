package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class ChoiceSetting extends Setting {
    public final String[] options;
    public final int def;
    public int index;
    /** Optional picture of each option, shown in the option picker window (e.g. the crosshair shapes). */
    public Preview preview;

    /** Draws option {@code index} inside the square (x, y, size). */
    public interface Preview {
        void draw(int index, float x, float y, float size, float alpha);
    }

    public ChoiceSetting(String key, String label, int def, String... options) {
        super(key, label);
        this.options = options;
        this.def = def;
        this.index = def;
    }

    public int get() { return index; }

    public String display() { return dev.fzkc.fzhud.util.Lang.t(options[index]); }

    public void next() { index = (index + 1) % options.length; }

    public void prev() { index = (index - 1 + options.length) % options.length; }

    /** Text of option i in the current language. */
    public String label(int i) { return dev.fzkc.fzhud.util.Lang.t(options[i]); }

    @Override public JsonElement save() { return new JsonPrimitive(index); }

    @Override
    public void load(JsonElement e) {
        try {
            int i = e.getAsInt();
            if (i >= 0 && i < options.length) index = i;
        } catch (Exception ignored) {}
    }

    @Override public void reset() { index = def; }
}
