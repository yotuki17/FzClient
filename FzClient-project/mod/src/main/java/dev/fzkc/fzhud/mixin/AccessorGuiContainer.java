package dev.fzkc.fzhud.mixin;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The slot under the mouse (not public): hotbar keys on mouse buttons swap with it (HotbarMouse). */
@Mixin(GuiContainer.class)
public interface AccessorGuiContainer {
    @Accessor("theSlot")
    Slot fzhud$getHovered();
}
