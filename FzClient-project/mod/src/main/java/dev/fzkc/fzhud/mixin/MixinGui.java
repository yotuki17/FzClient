package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.gui.VanillaUi;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Slider knobs (drawn as two texture halves while VanillaUi says so) in FzClient's style. */
@Mixin(Gui.class)
public abstract class MixinGui {
    @Inject(require = 0, method = "drawTexturedModalRect(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void fzhud$knob(int x, int y, int u, int v, int w, int h, CallbackInfo ci) {
        if (VanillaUi.knob(x, y, u, w, h)) ci.cancel();
    }
}
