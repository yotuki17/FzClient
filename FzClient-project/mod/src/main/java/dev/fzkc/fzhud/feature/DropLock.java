package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

/**
 * Drop Protection: only resources (iron, gold, emerald, diamond) and blocks can be thrown away, so a sword,
 * bow, tools or potions are never dropped by a slip of the Q key or a click outside the inventory.
 * Purely a client-side safety catch: the drop is simply not sent. Hold Alt to drop anything anyway.
 * Hooked from MixinEntityPlayerSP (Q in game) and MixinPlayerControllerMP (Q / click outside in inventories).
 */
public final class DropLock {
    private DropLock() {}

    private static long noticeAt;

    private static boolean active() {
        if (!Modules.dropLock.enabled) return false;
        if (Modules.dropLockAlt.get() && (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))) return false;
        return !Modules.dropLockBedwars.get() || inBedWars();
    }

    /** The sidebar title says BED WARS (Hypixel and most servers). */
    private static boolean inBedWars() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return false;
        try {
            ScoreObjective o = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
            if (o == null) return false;
            String t = EnumChatFormatting.getTextWithoutFormattingCodes(o.getDisplayName()).toUpperCase().replace(" ", "");
            return t.contains("BEDWARS");
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean allowed(ItemStack s) {
        if (s == null) return true;
        Item i = s.getItem();
        if (i == Items.iron_ingot) return Modules.dropLockIron.get();
        if (i == Items.gold_ingot) return Modules.dropLockGold.get();
        if (i == Items.emerald) return Modules.dropLockEmerald.get();
        if (i == Items.diamond) return Modules.dropLockDiamond.get();
        if (i instanceof ItemBlock) return Modules.dropLockBlocks.get();
        return false;
    }

    /** True when dropping this stack must be stopped (also tells the player once in a while). */
    public static boolean blocks(ItemStack s) {
        if (s == null || !active() || allowed(s)) return false;
        long now = System.currentTimeMillis();
        if (Modules.dropLockNotice.get() && now - noticeAt > 1500L) {
            noticeAt = now;
            Minecraft.getMinecraft().ingameGUI.setRecordPlaying(EnumChatFormatting.YELLOW
                    + Lang.t("ドロップ保護: このアイテムは捨てられません（Alt を押しながらなら捨てられます）"), false);
        }
        return true;
    }

    /** Inventory clicks: Q on a slot (mode 4) or a click outside the window with an item on the cursor. */
    public static boolean blocksClick(int slotId, int mode, EntityPlayer player) {
        if (player == null || !Modules.dropLock.enabled) return false;
        if (slotId == -999 && (mode == 0 || mode == 4)) return blocks(player.inventory.getItemStack());
        if (mode == 4 && slotId >= 0) {
            Container c = player.openContainer;
            if (c == null || slotId >= c.inventorySlots.size()) return false;
            return blocks(c.getSlot(slotId).getStack());
        }
        return false;
    }
}
