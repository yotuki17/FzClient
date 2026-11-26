package dev.fzkc.fzhud.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.fzkc.fzhud.Config;
import dev.fzkc.fzhud.util.KeyPress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two keys for every action, each optionally with Ctrl / Shift / Alt ("Alt + X").
 *
 * The first key is the game's own (options.txt), so other launchers and mods still see it; its modifiers and the
 * second key live in FzClient's config, by the action's name. A key that is some action's second key, or the first
 * key of an action with modifiers or a second key, is handed out here instead of by the game:
 *  - a key held with modifiers goes to the actions bound to exactly that combination (the one with the most
 *    modifiers wins: Ctrl + Shift + X before Ctrl + X);
 *  - when no combination matches, to the actions on the bare key, so W still walks while Shift (sneak) or Ctrl
 *    (sprint) is held;
 *  - every action on the key gets it (the game gave a key to only one of the actions sharing it).
 * Letting go of a key lets go of its actions unless their other key is still held.
 */
public final class KeyCombos {
    private KeyCombos() {}

    public static final int CTRL = 1, SHIFT = 2, ALT = 4;

    /** Action name -> {modifiers of key 1, key 2, modifiers of key 2}. */
    private static final Map<String, int[]> extra = new HashMap<String, int[]>();

    private static int[] of(KeyBinding kb) {
        int[] e = extra.get(kb.getKeyDescription());
        return e != null ? e : new int[3];
    }

    public static int key(KeyBinding kb, int slot) {
        return slot == 0 ? kb.getKeyCode() : of(kb)[1];
    }

    public static int mods(KeyBinding kb, int slot) {
        return of(kb)[slot == 0 ? 0 : 2];
    }

    /** Sets key 1 or 2 of an action (key 0 = none) and saves. */
    public static void set(KeyBinding kb, int slot, int key, int mods) {
        if (key == 0) mods = 0;
        int[] e = of(kb).clone();
        if (slot == 0) {
            Minecraft.getMinecraft().gameSettings.setOptionKeyBinding(kb, key);
            KeyBinding.resetKeyBindingArrayAndHash();
            e[0] = mods;
        } else {
            e[1] = key;
            e[2] = mods;
        }
        store(kb, e);
        Config.save();
    }

    /** Back to the game's default key, no modifiers, no second key. */
    public static void reset(KeyBinding kb) {
        Minecraft.getMinecraft().gameSettings.setOptionKeyBinding(kb, kb.getKeyCodeDefault());
        KeyBinding.resetKeyBindingArrayAndHash();
        extra.remove(kb.getKeyDescription());
        Config.save();
    }

    public static boolean isDefault(KeyBinding kb) {
        return kb.getKeyCode() == kb.getKeyCodeDefault() && !extra.containsKey(kb.getKeyDescription());
    }

    private static void store(KeyBinding kb, int[] e) {
        if (e[0] == 0 && e[1] == 0 && e[2] == 0) extra.remove(kb.getKeyDescription());
        else extra.put(kb.getKeyDescription(), e);
    }

    // ------------------------------------------------------------------ names

    /** "Ctrl + Shift + X", "Alt + Button 4", "NONE". */
    public static String name(int key, int mods) {
        if (key == 0) return GameSettings.getKeyDisplayString(0);
        return modsName(mods) + GameSettings.getKeyDisplayString(key);
    }

    /** "Ctrl + Alt + " (empty without modifiers). */
    public static String modsName(int mods) {
        StringBuilder b = new StringBuilder();
        if ((mods & CTRL) != 0) b.append("Ctrl + ");
        if ((mods & SHIFT) != 0) b.append("Shift + ");
        if ((mods & ALT) != 0) b.append("Alt + ");
        return b.toString();
    }

    public static boolean isModifier(int key) {
        return bit(key) != 0;
    }

    private static int bit(int key) {
        switch (key) {
            case Keyboard.KEY_LCONTROL: case Keyboard.KEY_RCONTROL: return CTRL;
            case Keyboard.KEY_LSHIFT: case Keyboard.KEY_RSHIFT: return SHIFT;
            case Keyboard.KEY_LMENU: case Keyboard.KEY_RMENU: return ALT;
            default: return 0;
        }
    }

    /** Ctrl / Shift / Alt held now, not counting the key itself (Shift as the key is not "Shift + Shift"). */
    public static int held(int except) {
        int m = 0;
        int[] keys = {Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL, Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT,
                Keyboard.KEY_LMENU, Keyboard.KEY_RMENU};
        for (int k : keys) {
            if (k != except && Keyboard.isKeyDown(k)) m |= bit(k);
        }
        return m;
    }

    private static boolean down(int key) {
        if (key < 0) return SideButtons.isDown(key + 100);
        return key > 0 && key < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(key);
    }

    // ------------------------------------------------------------------ presses (from MixinKeyBinding)

    /** Any second key or modifier is set somewhere. */
    public static boolean active() {
        return !extra.isEmpty();
    }

    /**
     * The key is handed out here only when it matters: it is some action's second key, or the first key of an
     * action that has modifiers or a second key. Every other key goes the game's own way.
     */
    private static boolean relevant(int key, List<KeyBinding> all) {
        for (KeyBinding kb : all) {
            int[] e = extra.get(kb.getKeyDescription());
            if (e == null) continue;
            if (e[1] == key) return true;
            if (kb.getKeyCode() == key && (e[0] != 0 || e[1] != 0)) return true;
        }
        return false;
    }

    /** True = handled here (the game's own lookup is skipped). */
    public static boolean onTick(int key, List<KeyBinding> all) {
        if (extra.isEmpty() || key == 0) return false;
        if (!relevant(key, all)) return false;
        try {
            for (KeyBinding kb : matches(key, all)) ((KeyPress) kb).fzhud$addPress();
            return true;
        } catch (Throwable t) {
            return false;   // the game's way then
        }
    }

    public static boolean setState(int key, boolean pressed, List<KeyBinding> all) {
        if (extra.isEmpty() || key == 0) return false;
        if (!relevant(key, all)) return false;
        try {
            if (pressed) {
                for (KeyBinding kb : matches(key, all)) ((KeyPress) kb).fzhud$setPressed(true);
            } else {
                for (KeyBinding kb : all) {
                    int k1 = key(kb, 0), k2 = key(kb, 1);
                    if (k1 != key && k2 != key) continue;
                    int other = k1 == key ? k2 : k1;
                    ((KeyPress) kb).fzhud$setPressed(other != 0 && other != key && down(other));
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<KeyBinding> matches(int key, List<KeyBinding> all) {
        int held = held(key);
        List<KeyBinding> combo = new ArrayList<KeyBinding>(), bare = new ArrayList<KeyBinding>();
        int best = 0;
        for (KeyBinding kb : all) {
            for (int s = 0; s < 2; s++) {
                if (key(kb, s) != key) continue;
                int m = mods(kb, s);
                if (m == 0) {
                    if (!bare.contains(kb)) bare.add(kb);
                } else if ((m & ~held) == 0) {
                    int n = Integer.bitCount(m);
                    if (n > best) {
                        best = n;
                        combo.clear();
                    }
                    if (n == best && !combo.contains(kb)) combo.add(kb);
                }
            }
        }
        return combo.isEmpty() ? bare : combo;
    }

    // ------------------------------------------------------------------ config

    public static void load(JsonObject o) {
        extra.clear();
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            try {
                JsonArray a = en.getValue().getAsJsonArray();
                int[] e = {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
                if (e[0] != 0 || e[1] != 0 || e[2] != 0) extra.put(en.getKey(), e);
            } catch (Exception ignored) {
                // a broken entry is left out
            }
        }
    }

    public static JsonObject save() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, int[]> en : extra.entrySet()) {
            JsonArray a = new JsonArray();
            for (int v : en.getValue()) a.add(new com.google.gson.JsonPrimitive(v));
            o.add(en.getKey(), a);
        }
        return o;
    }
}
