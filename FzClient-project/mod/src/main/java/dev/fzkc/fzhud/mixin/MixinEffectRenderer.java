package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimization "パーティクルの上限": the game keeps up to 4000 particles per layer and drops the oldest one when a
 * new one comes in. A lower limit keeps big explosions / potion clouds from dragging the FPS down.
 */
@Mixin(EffectRenderer.class)
public abstract class MixinEffectRenderer {

    /** "パーティクルを完全に消す": every particle (hits, potions, block breaking, explosions...) goes through here. */
    @Inject(require = 0, method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void fzhud$noParticles(EntityFX effect, CallbackInfo ci) {
        if (Modules.optimize.enabled && Modules.optNoParticles.get()) ci.cancel();
    }

    @ModifyConstant(require = 0, method = "addEffect", constant = @Constant(intValue = 4000))
    private int fzhud$particleLimit(int vanilla) {
        return Modules.optimize.enabled ? Math.min(vanilla, (int) Modules.optParticles.get()) : vanilla;
    }
}
