package dev.fzkc.fzhud.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Config;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Optimization "起動を速くする". While starting, the game loads all block / item models and all sounds, and once the
 * mods are set up Forge reloads every resource and throws all of that away (Forge itself already leaves the block
 * textures out of the first round: TextureMap's skipFirst). Here the models and sounds of the first round are left
 * out as well; the second round loads everything as always, so the result is the same.
 * <p>
 * The first round comes before the mods' settings are loaded, so the setting is read from config/fzclient.json.
 */
public final class FastStart {
    private FastStart() {}

    private static Boolean on;
    /** Between Forge's two steps of the start. Not set when a mod failed before (CrashPatch loads its own sounds then). */
    public static boolean firstRound;
    private static boolean modelsLeftOut, modelsCame;
    private static boolean soundsLeftOut, soundsCame;

    /** True exactly once, for the first load of the models. */
    public static boolean skipModels() {
        if (!firstRound || modelsLeftOut || modelsCame || !on()) return false;
        return modelsLeftOut = true;
    }

    /** True exactly once, for the first load of the sounds. */
    public static boolean skipSounds() {
        if (!firstRound || soundsLeftOut || soundsCame || !on()) return false;
        return soundsLeftOut = true;
    }

    public static void modelsLoaded() {
        modelsCame = true;
    }

    public static void soundsLoaded() {
        soundsCame = true;
    }

    /** After Forge's own reload: something was left out and never came (Forge stopped at a mod's error, another mod skipped the reload). */
    public static boolean missing() {
        return modelsLeftOut && !modelsCame || soundsLeftOut && !soundsCame;
    }

    private static boolean on() {
        if (on == null) on = read();
        return on;
    }

    private static boolean read() {
        if (Boolean.getBoolean("fzclient.nofaststart")) return false;
        try {
            File f = Config.readFile();
            if (!f.exists()) return true;
            try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
                JsonObject modules = new JsonParser().parse(r).getAsJsonObject().getAsJsonObject("modules");
                JsonObject m = modules == null ? null : modules.getAsJsonObject("optimize");
                if (m == null) return true;
                if (m.has("enabled") && !m.get("enabled").getAsBoolean()) return false;
                JsonObject s = m.getAsJsonObject("settings");
                JsonElement v = s == null ? null : s.get("faststart");
                return v == null || v.getAsBoolean();
            }
        } catch (Throwable e) {
            return true;   // no readable settings: the default
        }
    }
}
