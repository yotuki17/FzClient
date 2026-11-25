package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.FastStart;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optimization "起動を速くする" ({@link FastStart}): the sounds (and the sound engine) of the first round are left out. */
@Mixin(SoundHandler.class)
public abstract class MixinSoundHandler {

    @Inject(require = 0, method = "onResourceManagerReload", at = @At("HEAD"), cancellable = true)
    private void fzhud$leaveOutFirst(IResourceManager manager, CallbackInfo ci) {
        if (FastStart.skipSounds()) ci.cancel();
    }

    @Inject(require = 0, method = "onResourceManagerReload", at = @At("RETURN"))
    private void fzhud$loaded(IResourceManager manager, CallbackInfo ci) {
        FastStart.soundsLoaded();   // not reached when the first round was left out
    }
}
