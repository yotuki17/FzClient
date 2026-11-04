package dev.fzkc.fzhud.config;

import java.util.ArrayList;
import java.util.List;

/** A toggleable feature with its own settings page. HUD elements are modules too. */
public class Module {
    public enum Category {
        HUD("HUD"), PVP("PvP"), VISUAL("Visual"), SERVER("Server"), UTILITY("Utility");

        public final String label;

        Category(String label) { this.label = label; }
    }

    public final String id;
    public final String name;
    public final Category category;
    public final String description;
    public final boolean defaultEnabled;
    public boolean enabled;
    /** False for pages that only hold settings (language, chroma): no ON/OFF switch, always on. */
    public boolean toggleable = true;
    public final List<Setting> settings = new ArrayList<>();

    public Module(String id, String name, Category category, String description, boolean defaultEnabled) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.defaultEnabled = defaultEnabled;
        this.enabled = defaultEnabled;
    }

    /** Marks this as a settings-only page (no ON/OFF switch). */
    public Module alwaysOn() {
        toggleable = false;
        enabled = true;
        return this;
    }

    public <T extends Setting> T add(T s) {
        settings.add(s);
        return s;
    }

    public void resetSettings() {
        for (Setting s : settings) s.reset();
    }
}
