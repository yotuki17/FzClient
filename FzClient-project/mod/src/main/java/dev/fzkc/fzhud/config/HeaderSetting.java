package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** Not a setting: a section title shown between groups of settings. Nothing is saved. */
public class HeaderSetting extends Setting {
    public HeaderSetting(String key, String label) {
        super(key, label);
    }

    @Override public JsonElement save() { return JsonNull.INSTANCE; }
    @Override public void load(JsonElement e) {}
    @Override public void reset() {}
}
