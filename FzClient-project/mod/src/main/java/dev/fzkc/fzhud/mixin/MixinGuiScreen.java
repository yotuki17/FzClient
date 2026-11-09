package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.gui.VanillaUi;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Backgrounds of the game's screens: panorama instead of dirt, FzClient's veil over the world (see VanillaUi). */
@Mixin(GuiScreen.class)
public abstract class MixinGuiScreen {
    @Inject(require = 0, method = "drawBackground", at = @At("HEAD"), cancellable = true)
    private void fzhud$dirt(int tint, CallbackInfo ci) {
        if (VanillaUi.dirtBackground((GuiScreen) (Object) this)) ci.cancel();
    }

    @Inject(require = 0, method = "drawWorldBackground", at = @At("HEAD"), cancellable = true)
    private void fzhud$world(int tint, CallbackInfo ci) {
        GuiScreen self = (GuiScreen) (Object) this;
        if (self.mc != null && self.mc.theWorld != null && VanillaUi.worldBackground(self)) ci.cancel();
    }
}
