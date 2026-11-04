package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;

/** A single user-adjustable option shown in the settings screen. */
public abstract class Setting {
    public final String key;
    public final String label;

    protected Setting(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public abstract JsonElement save();

    public abstract void load(JsonElement e);

    public abstract void reset();
}
