package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.chat.ChatHistory;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Chat scrollback: vanilla throws away everything past the newest 100 lines. Keep the whole game session instead
 * (Chat → "さかのぼれるチャットの行数"), so you can scroll back up through what was said earlier.
 */
@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {

    @ModifyConstant(require = 0, method = "setChatLine", constant = @Constant(intValue = 100))
    private int fzhud$scrollback(int vanilla) {
        try {
            return ChatHistory.maxLines();
        } catch (Throwable t) {
            return vanilla;
        }
    }
}
