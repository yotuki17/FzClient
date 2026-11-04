package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.particle.EffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Optimization "パーティクルの上限": the game keeps up to 4000 particles per layer and drops the oldest one when a
 * new one comes in. A lower limit keeps big explosions / potion clouds from dragging the FPS down.
 */
@Mixin(EffectRenderer.class)
public abstract class MixinEffectRenderer {

    @ModifyConstant(require = 0, method = "addEffect", constant = @Constant(intValue = 4000))
    private int fzhud$particleLimit(int vanilla) {
        return Modules.optimize.enabled ? Math.min(vanilla, (int) Modules.optParticles.get()) : vanilla;
    }
}
