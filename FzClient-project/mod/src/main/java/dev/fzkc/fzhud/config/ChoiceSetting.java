package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class ChoiceSetting extends Setting {
    public final String[] options;
    public final int def;
    public int index;

    public ChoiceSetting(String key, String label, int def, String... options) {
        super(key, label);
        this.options = options;
        this.def = def;
        this.index = def;
    }

    public int get() { return index; }

    public String display() { return dev.fzkc.fzhud.util.Lang.t(options[index]); }

    public void next() { index = (index + 1) % options.length; }

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
