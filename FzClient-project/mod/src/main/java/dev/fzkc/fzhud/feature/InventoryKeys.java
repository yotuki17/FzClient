package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.mixin.AccessorGuiContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Inventories compare the pressed key with each action's first key themselves (hotbar swap, close, drop), so the
 * second keys and Ctrl / Shift / Alt from KeyCombos are handled here: a second key does what the first one would,
 * and a first key that asks for a modifier does nothing while the modifier is not held.
 * Not in the creative inventory or the anvil, where keys type text.
 */
public final class InventoryKeys {
    @SubscribeEvent
    public void onKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Keyboard.getEventKeyState()) return;
        int key = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter() + 256 : Keyboard.getEventKey();
        if (handle(event.gui, key)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        int button = Mouse.getEventButton();
        if (button < 0 || !Mouse.getEventButtonState()) return;
        if (handle(event.gui, button - 100)) event.setCanceled(true);
    }

    /** True = done here (the inventory's own handling is skipped). */
    public static boolean handle(GuiScreen gui, int key) {
        if (key == 0 || !KeyCombos.active()) return false;
        if (!(gui instanceof GuiContainer) || gui instanceof GuiContainerCreative || gui instanceof GuiRepair) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.playerController == null) return false;
        GameSettings gs = mc.gameSettings;
        GuiContainer c = (GuiContainer) gui;
        int held = KeyCombos.held(key);

        // the inventory's actions: hotbar 1-9 (0-8), close (9), drop (10)
        KeyBinding[] actions = new KeyBinding[11];
        System.arraycopy(gs.keyBindsHotbar, 0, actions, 0, 9);
        actions[9] = gs.keyBindInventory;
        actions[10] = gs.keyBindDrop;

        // only keys a second key or a modifier is about; the rest the inventory handles itself
        boolean ours = false;
        for (KeyBinding kb : actions) {
            if (KeyCombos.key(kb, 1) == key || kb.getKeyCode() == key && KeyCombos.mods(kb, 0) != 0) ours = true;
        }
        if (!ours) return false;

        // as in the game: of the keys whose modifiers are all held, the one asking for the most wins
        int best = -1, bestBits = -1;
        for (int a = 0; a < actions.length; a++) {
            for (int s = 0; s < 2; s++) {
                if (KeyCombos.key(actions[a], s) != key) continue;
                int need = KeyCombos.mods(actions[a], s);
                if ((need & ~held) != 0) continue;
                int bits = Integer.bitCount(need);
                if (bits > bestBits) {
                    best = a;
                    bestBits = bits;
                }
            }
        }
        if (best < 0) return key > 0;   // only keys whose modifier is not held: nothing (mouse buttons: the inventory's)

        Slot hovered = ((AccessorGuiContainer) c).fzhud$getHovered();
        boolean empty = mc.thePlayer.inventory.getItemStack() == null;
        if (best < 9) {
            if (hovered != null && empty) {
                mc.playerController.windowClick(c.inventorySlots.windowId, hovered.slotNumber, best, 2, mc.thePlayer);
            }
        } else if (best == 9) {
            mc.thePlayer.closeScreen();
        } else if (hovered != null && hovered.getHasStack() && empty) {
            mc.playerController.windowClick(c.inventorySlots.windowId, hovered.slotNumber,
                    GuiScreen.isCtrlKeyDown() ? 1 : 0, 4, mc.thePlayer);
        }
        return true;
    }
}
