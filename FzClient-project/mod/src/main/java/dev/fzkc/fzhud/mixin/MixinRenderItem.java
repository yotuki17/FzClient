package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optimization "エンチャントの光沢を描かない": the glint is the item drawn twice more with a moving texture. */
@Mixin(RenderItem.class)
public abstract class MixinRenderItem {

    @Inject(require = 0, method = "renderEffect", at = @At("HEAD"), cancellable = true)
    private void fzhud$noGlint(IBakedModel model, CallbackInfo ci) {
        if (Modules.optimize.enabled && Modules.optNoGlint.get()) ci.cancel();
    }
}
