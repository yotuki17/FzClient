package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Free text. "&" colour codes are converted to Minecraft's section sign when read. */
public class TextSetting extends Setting {
    public final String def;
    public final int maxLength;
    public String value;

    public TextSetting(String key, String label, String def, int maxLength) {
        super(key, label);
        this.def = def;
        this.maxLength = maxLength;
        this.value = def;
    }

    /** An API key or similar: the settings screen shows dots instead of the text while not editing it. */
    public boolean secret;

    public TextSetting secret() {
        secret = true;
        return this;
    }

    public String get() { return value; }

    /** Value with &-colour codes turned into real formatting codes. */
    private String fmtSrc, fmtOut;

    public String formatted() {
        String v = value;
        if (!v.equals(fmtSrc)) {
            fmtOut = v.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
            fmtSrc = v;
        }
        return fmtOut;
    }

    @Override public JsonElement save() { return new JsonPrimitive(value); }

    @Override
    public void load(JsonElement e) {
        try {
            String s = e.getAsString();
            value = s.length() > maxLength ? s.substring(0, maxLength) : s;
        } catch (Exception ignored) {}
    }

    @Override public void reset() { value = def; }
}
