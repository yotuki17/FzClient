package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.DropLock;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drop Protection: Q on a slot and clicking outside an inventory with an item held. */
@Mixin(PlayerControllerMP.class)
public abstract class MixinPlayerControllerMP {

    @Inject(require = 0, method = "windowClick", at = @At("HEAD"), cancellable = true)
    private void fzhud$dropLock(int windowId, int slotId, int mouseButton, int mode, EntityPlayer player,
                                CallbackInfoReturnable<ItemStack> cir) {
        if (DropLock.blocksClick(slotId, mode, player)) cir.setReturnValue(null);
    }
}
