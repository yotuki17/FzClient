package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.cosmetic.Cosmetics;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FzClient capes: the vanilla cape layer draws whatever this returns, so a picked cosmetic cape shows up there. */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer {

    @Inject(require = 0, method = "getLocationCape", at = @At("HEAD"), cancellable = true)
    private void fzhud$cosmeticCape(CallbackInfoReturnable<ResourceLocation> cir) {
        try {
            ResourceLocation rl = Cosmetics.cape((AbstractClientPlayer) (Object) this);
            if (rl != null) cir.setReturnValue(rl);
        } catch (Throwable ignored) {
            // never break player rendering
        }
    }
}
