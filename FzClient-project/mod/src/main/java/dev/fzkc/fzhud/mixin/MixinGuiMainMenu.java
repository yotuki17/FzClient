package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.Branding;
import net.minecraft.client.gui.GuiMainMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Main menu: draws "FzClient" in place of the MINECRAFT title image. */
@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu {

    @Redirect(require = 0, method = "drawScreen",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiMainMenu;drawTexturedModalRect(IIIIII)V"))
    private void fzhud$title(GuiMainMenu self, int x, int y, int u, int v, int w, int h) {
        if (!Branding.on("menutitle")) {
            self.drawTexturedModalRect(x, y, u, v, w, h);
            return;
        }
        // the title is drawn in 2 (or 5) pieces; draw ours once, on the first piece
        if (u == 0 && v == 0) Branding.drawMenuTitle(self.width);
    }
}
