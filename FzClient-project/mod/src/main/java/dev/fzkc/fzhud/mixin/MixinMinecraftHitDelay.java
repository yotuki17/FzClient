package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hit Delay Fix. In 1.8.9 a swing that hits nothing sets a 10-tick (0.5 s) timer during which the next left click is
 * ignored; 1.7 had no such timer. This puts the timer back to 0 right after the click, so a missed swing never
 * blocks the next one. Whether something is hit is still decided exactly as before (and by the server).
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftHitDelay {

    /** Ticks left until the next left click is accepted again. */
    @Shadow
    private int leftClickCounter;

    @Inject(require = 0, method = "clickMouse", at = @At("TAIL"))
    private void fzhud$noMissDelay(CallbackInfo ci) {
        if (Modules.hitDelayFix.enabled) this.leftClickCounter = 0;
    }
}
