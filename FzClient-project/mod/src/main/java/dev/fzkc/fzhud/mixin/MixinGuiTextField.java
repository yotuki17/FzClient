package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.gui.VanillaUi;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Text boxes of the game's screens: one rounded box (accent edge while typing) instead of grey + black. */
@Mixin(GuiTextField.class)
public abstract class MixinGuiTextField {
    @Shadow private boolean isFocused;

    @Redirect(require = 0, method = "drawTextBox",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiTextField;drawRect(IIIII)V"))
    private void fzhud$frame(int l, int t, int r, int b, int color) {
        if (!VanillaUi.textBox(l, t, r, b, color, isFocused)) Gui.drawRect(l, t, r, b, color);
    }

    @Redirect(require = 0, method = "drawTextBox",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;drawRect(IIIII)V"))
    private void fzhud$frame2(int l, int t, int r, int b, int color) {
        if (!VanillaUi.textBox(l, t, r, b, color, isFocused)) Gui.drawRect(l, t, r, b, color);
    }
}
