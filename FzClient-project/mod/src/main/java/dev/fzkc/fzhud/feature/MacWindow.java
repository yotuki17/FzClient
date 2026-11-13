package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.util.Os;

/**
 * A Mac always starts the game in its window.
 * <p>
 * The game's fullscreen can show nothing but a white screen on a Mac, and the game remembers fullscreen in
 * options.txt ("fullscreen:true"), so after one F11 every start was white. Both ways into fullscreen at the start
 * are refused there (see MixinMinecraft): "--fullscreen" from a launcher, and the remembered setting, which the game
 * applies at the very end of its start. F11 while playing is left as the game does it.
 */
public final class MacWindow {
    private MacWindow() {}

    private static volatile boolean started;

    /** True on a Mac while the game is still starting: it must not go fullscreen yet. */
    public static boolean refuseFullscreen() {
        return Os.MAC && !started;
    }

    /** The game has started (the end of Minecraft.startGame, or the first tick, whichever comes first). */
    public static void started() {
        started = true;
    }
}
