package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/**
 * Sounds of the FzClient menus.
 *  - click():  the touch sound, played when a left click lands on something that reacts (a button, card, row...).
 *              Ui.inside() reports a hit while a click is being handled; clicks on empty space stay silent.
 *  - cancel(): called by a screen while it handles a click that means "back / close / turn off". When the click is
 *              done the cancel sound is played instead of the touch sound (never both).
 * "fzclient:ui.confirm" and "fzclient:ui.cancel" are the two own sound files (assets/fzclient/sounds/ui/*.ogg,
 * registered in assets/fzclient/sounds.json); the others are sounds that ship with the game.
 */
public final class UiSound {
    private UiSound() {}

    /** Same order as the choices in Modules.menuSoundType. */
    private static final String[] SOUNDS = {"gui.button.press", "random.pop", "note.pling", "random.click", "fzclient:ui.confirm"};
    private static final String CANCEL = "fzclient:ui.cancel";

    private static boolean cancelRequested;
    /** True while a screen handles a left click. */
    static boolean clicking;
    private static boolean hit;

    /** Start of a click: forget an old request. */
    public static void beginClick() {
        cancelRequested = false;
        hit = false;
        clicking = true;
    }

    /** "This click landed on something that reacts" (Ui.inside calls this by itself during a click). */
    public static void hit() {
        if (clicking) hit = true;
    }

    /** "This click means back / close / off": the cancel sound will be played when the click has been handled. */
    public static void cancel() {
        cancelRequested = true;
    }

    /** End of a click: the cancel sound if it was requested, the touch sound otherwise. */
    public static void endClick() {
        clicking = false;
        if (cancelRequested) {
            cancelRequested = false;
            playCancel();
        } else if (hit) {
            click();
        }
        hit = false;
    }

    /** The touch sound. */
    public static void click() {
        if (!Modules.menuSound.enabled) return;
        int i = Math.max(0, Math.min(SOUNDS.length - 1, Modules.menuSoundType.get()));
        play(SOUNDS[i], Modules.menuSoundPitch.get());
    }

    /** The cancel sound (or the touch sound when the cancel sound is switched off). */
    public static void playCancel() {
        if (!Modules.menuSound.enabled) return;
        if (!Modules.menuSoundCancel.get()) {
            click();
            return;
        }
        play(CANCEL, Modules.menuSoundPitch.get());
    }

    private static void play(String name, float pitch) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() == null) return;
        pitch = Math.max(0.5f, Math.min(2.0f, pitch));
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation(name), pitch));
    }
}
