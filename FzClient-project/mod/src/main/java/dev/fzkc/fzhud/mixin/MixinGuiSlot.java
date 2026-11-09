package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.gui.VanillaUi;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lists (servers, worlds, controls, resource packs...): a see-through panel instead of dirt, and the entries are
 * clipped to the list so the dirt strips above and below it are not needed.
 */
@Mixin(GuiSlot.class)
public abstract class MixinGuiSlot {
    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow protected int left;
    @Shadow protected int right;

    @Inject(require = 0, remap = false, method = "drawContainerBackground", at = @At("HEAD"), cancellable = true)
    private void fzhud$back(Tessellator t, CallbackInfo ci) {
        if (VanillaUi.listBackground(left, top, right, bottom)) ci.cancel();
    }

    @Inject(require = 0, method = "overlayBackground", at = @At("HEAD"), cancellable = true)
    private void fzhud$cover(int startY, int endY, int startAlpha, int endAlpha, CallbackInfo ci) {
        if (VanillaUi.skipListCover()) ci.cancel();
    }

    @Inject(require = 0, method = "drawSelectionBox", at = @At("HEAD"))
    private void fzhud$clip(int x, int y, int mouseX, int mouseY, CallbackInfo ci) {
        VanillaUi.clipList(left, top, right, bottom);
    }

    @Inject(require = 0, method = "drawSelectionBox", at = @At("RETURN"))
    private void fzhud$unclip(int x, int y, int mouseX, int mouseY, CallbackInfo ci) {
        VanillaUi.unclipList();
    }
}
