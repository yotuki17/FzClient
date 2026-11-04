package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimization "GL エラーの確認を省く". Minecraft asks the graphics driver for errors (glGetError) several times
 * every frame; each call makes the CPU wait until the GPU has caught up. The answer is only ever printed to the log.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftOptimize {

    @Inject(require = 0, method = "checkGLError", at = @At("HEAD"), cancellable = true)
    private void fzhud$skipGlErrors(String message, CallbackInfo ci) {
        if (Modules.optimize.enabled && Modules.optGlErrors.get()) ci.cancel();
    }
}
