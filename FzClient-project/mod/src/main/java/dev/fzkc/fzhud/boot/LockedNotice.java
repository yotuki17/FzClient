package dev.fzkc.fzhud.boot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Shown on the (vanilla) title screen when FzClient stays off because another launcher started the game. */
public final class LockedNotice {

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.gui instanceof GuiMainMenu)) return;
        FontRenderer f = Minecraft.getMinecraft().fontRendererObj;
        boolean en = !String.valueOf(Minecraft.getMinecraft().gameSettings.language).startsWith("ja");
        String a = en ? "FzClient is off" : "FzClient は動いていません";
        String b = en ? "Start the game from the FzClient Launcher to use it."
                : "FzClient Launcher から起動したときだけ使えます";
        int w = event.gui.width;
        f.drawStringWithShadow(a, (w - f.getStringWidth(a)) / 2f, 4, 0xFF6B6B);
        f.drawStringWithShadow(b, (w - f.getStringWidth(b)) / 2f, 15, 0xDDDDDD);
    }
}
