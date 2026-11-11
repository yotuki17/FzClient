package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Performance "テクスチャのアニメーションを止める": water, lava, fire, portals... stay on the picture they show.
 * The game redraws all of them into the block texture twenty times a second.
 */
@Mixin(TextureMap.class)
public abstract class MixinTextureMap {

    @Inject(require = 0, method = "updateAnimations", at = @At("HEAD"), cancellable = true)
    private void fzhud$noAnimations(CallbackInfo ci) {
        if (Modules.performance.enabled && Modules.perfNoAnim.get()) ci.cancel();
    }
}
