package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.DropLock;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.item.EntityItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drop Protection: the Q key in game. */
@Mixin(EntityPlayerSP.class)
public abstract class MixinEntityPlayerSP {

    @Inject(require = 0, method = "dropOneItem", at = @At("HEAD"), cancellable = true)
    private void fzhud$dropLock(boolean dropAll, CallbackInfoReturnable<EntityItem> cir) {
        if (DropLock.blocks(((EntityPlayerSP) (Object) this).getCurrentEquippedItem())) cir.setReturnValue(null);
    }
}
