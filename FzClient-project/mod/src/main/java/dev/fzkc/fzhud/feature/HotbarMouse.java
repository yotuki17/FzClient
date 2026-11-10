package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.mixin.AccessorGuiContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.inventory.Slot;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

/**
 * In an inventory, the vanilla game swaps the hovered item into hotbar slot N only when the hotbar key for N is a
 * keyboard key. With "Hotbar Slot 3" on a mouse side button the press did nothing there; now a mouse button bound
 * to a hotbar slot works exactly like that key would (the same swap the game does for keys).
 */
public final class HotbarMouse {
    @SubscribeEvent
    public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!Modules.fixes.enabled || !Modules.fixHotbarMouse.get()) return;
        if (!(event.gui instanceof GuiContainer) || event.gui instanceof GuiContainerCreative) return;
        int button = Mouse.getEventButton();
        if (button < 0 || !Mouse.getEventButtonState()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.playerController == null || mc.thePlayer.inventory.getItemStack() != null) return;
        int code = button - 100;   // how the game stores mouse buttons in a key binding
        for (int i = 0; i < 9; i++) {
            if (mc.gameSettings.keyBindsHotbar[i].getKeyCode() != code) continue;
            Slot slot = ((AccessorGuiContainer) event.gui).fzhud$getHovered();
            if (slot == null) return;
            GuiContainer gui = (GuiContainer) event.gui;
            mc.playerController.windowClick(gui.inventorySlots.windowId, slot.slotNumber, i, 2, mc.thePlayer);
            event.setCanceled(true);   // not also a normal click
            return;
        }
    }
}
