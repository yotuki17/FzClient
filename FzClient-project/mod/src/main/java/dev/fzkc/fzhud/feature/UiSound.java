package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/** The click sound of the FzClient menus (uses the game's own sounds, so it follows the master volume). */
public final class UiSound {
    private UiSound() {}

    /** Same order as the choices in Modules.menuSoundType. */
    private static final String[] SOUNDS = {"gui.button.press", "random.pop", "note.pling", "random.click"};

    /** Plays the click sound (does nothing when the Menu Sound module is off). */
    public static void click() {
        if (!Modules.menuSound.enabled) return;
        int i = Math.max(0, Math.min(SOUNDS.length - 1, Modules.menuSoundType.get()));
        float pitch = Math.max(0.5f, Math.min(2.0f, Modules.menuSoundPitch.get()));
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() == null) return;
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation(SOUNDS[i]), pitch));
    }
}
