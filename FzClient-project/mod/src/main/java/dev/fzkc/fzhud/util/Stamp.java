package dev.fzkc.fzhud.util;

/** Counters that tell caches when to rebuild. */
public final class Stamp {
    private Stamp() {}

    /** Client ticks since the game started (20 per second; also counts on menus and while paused). */
    public static int ticks;

    /** Bumped whenever Minecraft reloads its resources (resource pack, language, font). */
    public static int resources;
}
