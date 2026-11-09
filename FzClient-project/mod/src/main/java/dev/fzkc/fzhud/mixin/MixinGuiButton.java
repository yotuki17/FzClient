package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.gui.VanillaUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game's buttons and sliders in FzClient's style (see VanillaUi); buttons with their own drawing are untouched. */
@Mixin(GuiButton.class)
public abstract class MixinGuiButton {
    @Shadow public int xPosition;
    @Shadow public int yPosition;
    @Shadow protected int width;
    @Shadow protected int height;
    @Shadow public boolean enabled;
    @Shadow public boolean visible;
    @Shadow protected boolean hovered;
    @Shadow public String displayString;

    @Shadow protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

    @Inject(require = 0, method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void fzhud$draw(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        if (!visible) return;
        boolean h = mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width && mouseY < yPosition + height;
        GuiButton self = (GuiButton) (Object) this;
        if (!VanillaUi.buttonBody(self, xPosition, yPosition, width, height, enabled, h)) return;
        hovered = h;
        VanillaUi.knobStart(self, xPosition);
        try {
            mouseDragged(mc, mouseX, mouseY);
        } finally {
            VanillaUi.knobEnd();
        }
        VanillaUi.label(self, displayString, xPosition, yPosition, width, height, enabled);
        ci.cancel();
    }
}
