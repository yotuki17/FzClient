package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The game menu (Esc) gets a row of two FzClient buttons, FzClient Settings and Cosmetics, where
 * "Options... / Mod Options..." were; that row moves down one, and "Disconnect" under it.
 */
public class PauseButtons {
    private static final int ID_FZ = 7101, ID_COS = 7102;

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiIngameMenu)) return;
        int disc = event.gui.height / 4 + 120 - 16;   // where vanilla puts "Disconnect"
        int opts = disc - 24;                          // the "Options... / Mod Options..." row
        for (GuiButton b : event.buttonList) {
            if (b.id == 1) disc = b.yPosition;
            if (b.id == 0) opts = b.yPosition;
        }
        // Options / Mod Options move down into Disconnect's place, Disconnect one row further
        for (GuiButton b : event.buttonList) {
            if (b.id == 1) b.yPosition = disc + 24;
            else if (b.yPosition == opts) b.yPosition = disc;
        }
        int x = event.gui.width / 2 - 100;
        event.buttonList.add(new GuiButton(ID_FZ, x, opts, 98, 20, Lang.t("FzClient Settings")));
        event.buttonList.add(new GuiButton(ID_COS, x + 102, opts, 98, 20, Lang.t("Cosmetics")));
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.gui instanceof GuiIngameMenu)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (event.button.id == ID_FZ) {
            mc.displayGuiScreen(new ModsScreen(event.gui));
            event.setCanceled(true);
        } else if (event.button.id == ID_COS) {
            mc.displayGuiScreen(new CosmeticsScreen(event.gui));
            event.setCanceled(true);
        }
    }
}
