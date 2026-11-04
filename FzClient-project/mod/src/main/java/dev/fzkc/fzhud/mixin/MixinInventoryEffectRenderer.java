package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.inventory.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bug fix "インベントリがずれる": with a potion effect active, 1.8.9 moves the inventory to the right to make room
 * for the effect list. This keeps it in the middle like every other screen (the list is still drawn on the left).
 */
@Mixin(InventoryEffectRenderer.class)
public abstract class MixinInventoryEffectRenderer extends GuiContainer {

    public MixinInventoryEffectRenderer(Container container) {
        super(container);
    }

    @Inject(require = 0, method = "updateActivePotionEffects", at = @At("TAIL"))
    private void fzhud$keepCentred(CallbackInfo ci) {
        if (Modules.fixes.enabled && Modules.fixInventory.get()) this.guiLeft = (this.width - this.xSize) / 2;
    }
}
