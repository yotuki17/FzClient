package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.FastStart;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimization "起動を速くする" ({@link FastStart}): Forge does not reload when a mod failed to start; what the first
 * round left out is loaded then, so its error screen has sounds and models like before.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftFastStart {

    @Inject(require = 0, method = "startGame", at = @At(value = "INVOKE", remap = false, shift = At.Shift.AFTER,
            target = "Lnet/minecraftforge/fml/client/FMLClientHandler;beginMinecraftLoading(Lnet/minecraft/client/Minecraft;Ljava/util/List;Lnet/minecraft/client/resources/IReloadableResourceManager;)V"))
    private void fzhud$firstRound(CallbackInfo ci) {
        FastStart.firstRound = true;
    }

    @Inject(require = 0, method = "startGame", at = @At(value = "INVOKE", remap = false, shift = At.Shift.AFTER,
            target = "Lnet/minecraftforge/fml/client/FMLClientHandler;finishMinecraftLoading()V"))
    private void fzhud$catchUp(CallbackInfo ci) {
        FastStart.firstRound = false;
        if (FastStart.missing()) ((Minecraft) (Object) this).refreshResources();
    }
}
