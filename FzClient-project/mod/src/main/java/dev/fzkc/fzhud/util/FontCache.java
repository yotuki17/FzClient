package dev.fzkc.fzhud.util;

import dev.fzkc.fzhud.Modules;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Optimization "文字の描画を使い回す". Minecraft builds every letter of every text again in every frame. Here a
 * text that is drawn a second time (same letters, colour, shadow) is kept as a display list on the graphics card
 * and only called from then on. Texts that change all the time are never kept, and a kept text that has not been
 * drawn for 30 seconds is thrown away. One cache per font (see MixinFontRenderer).
 */
public final class FontCache {
    public static final class Entry {
        String text;
        boolean shadow, unicode;
        float r, g, b, a;
        /** The display list (0 = seen once, not kept yet). */
        public int list;
        /** How far the text moved the pen. */
        public float width;
        /** The texture the list leaves bound (-1 = it binds none). */
        public int tex = -1;
        int used;
        Entry next;
    }

    private static final boolean OFF = System.getProperty("fzclient.nofontcache") != null;
    private static Boolean otherMod;

    /** Switched on, and no other mod that does the same thing is installed. */
    public static boolean on() {
        if (OFF || !Modules.optimize.enabled || !Modules.optFont.get()) return false;
        if (otherMod == null) {
            boolean other = false;
            try {
                other = net.minecraftforge.fml.common.Loader.isModLoaded("patcher");
            } catch (Throwable ignored) {
            }
            otherMod = other;
        }
        return !otherMod;
    }

    private static java.lang.reflect.Field shaderPack, shaderWorld;
    private static boolean shadersLooked;

    /**
     * True while OptiFine draws the world through a shader pack. Binding a texture then also binds its normal
     * and specular pictures on other texture units and sets shader values; a replayed list would do that behind
     * the game's back (wrong pictures on blocks afterwards), so texts in the world (name tags, signs) are drawn
     * the normal way then. The HUD and menus are not affected.
     */
    public static boolean shadersWorld() {
        if (!shadersLooked) {
            shadersLooked = true;
            try {
                Class<?> c = Class.forName("net.optifine.shaders.Shaders");
                shaderPack = c.getField("shaderPackLoaded");
                shaderWorld = c.getField("isRenderingWorld");
            } catch (Throwable noOptiFine) {
                shaderPack = null;
                shaderWorld = null;
            }
        }
        if (shaderPack == null || shaderWorld == null) return false;
        try {
            return shaderPack.getBoolean(null) && shaderWorld.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Buckets by the text's hash; the few entries of one text (colours, shadow) hang in a chain. */
    private final Map<String, Entry> map = new HashMap<String, Entry>();
    private int purgedAt, fullAt = -1, size;

    /** The entry of this text and look, or null when it was never drawn. */
    public Entry find(String text, boolean shadow, boolean unicode, float r, float g, float b, float a) {
        int now = Stamp.ticks;
        if (now - purgedAt >= 200 || now < purgedAt) purge(now, 600);
        for (Entry e = map.get(text); e != null; e = e.next) {
            if (e.shadow == shadow && e.unicode == unicode && e.r == r && e.g == g && e.b == b && e.a == a) {
                e.used = now;
                return e;
            }
        }
        return null;
    }

    /** Remembers that this text was drawn once. */
    public void note(String text, boolean shadow, boolean unicode, float r, float g, float b, float a) {
        if (size > 6000) {
            // full (rainbow or fading texts are new in every frame): clean up once a tick at most, not for every text
            int now = Stamp.ticks;
            if (now == fullAt) return;
            fullAt = now;
            purge(now, 20);
            if (size > 6000) return;
        }
        Entry e = new Entry();
        e.text = text;
        e.shadow = shadow;
        e.unicode = unicode;
        e.r = r;
        e.g = g;
        e.b = b;
        e.a = a;
        e.used = Stamp.ticks;
        e.next = map.get(text);
        map.put(text, e);
        size++;
    }

    /** Throws away what was not drawn for maxAge ticks. */
    private void purge(int now, int maxAge) {
        purgedAt = now;
        for (Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> en = it.next();
            Entry head = null, tail = null;
            for (Entry e = en.getValue(); e != null; ) {
                Entry nx = e.next;
                if (now - e.used > maxAge || now < e.used) {
                    if (e.list != 0) GL11.glDeleteLists(e.list, 1);
                    size--;
                } else {
                    e.next = null;
                    if (head == null) head = e; else tail.next = e;
                    tail = e;
                }
                e = nx;
            }
            if (head == null) it.remove(); else en.setValue(head);
        }
    }

    /** Throws everything away (the font's pictures changed). */
    public void clear() {
        for (Entry head : map.values()) {
            for (Entry e = head; e != null; e = e.next) if (e.list != 0) GL11.glDeleteLists(e.list, 1);
        }
        map.clear();
        size = 0;
    }
}
