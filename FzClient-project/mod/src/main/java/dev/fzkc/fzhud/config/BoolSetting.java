package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting {
    public final boolean def;
    public boolean value;

    public BoolSetting(String key, String label, boolean def) {
        super(key, label);
        this.def = def;
        this.value = def;
    }

    public boolean get() { return value; }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override
    public void load(JsonElement e) {
        try { value = e.getAsBoolean(); } catch (Exception ignored) {}
    }

    @Override public void reset() { value = def; }
}


