package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Arm swing speed for your own player (visual only - swings are client-side animation timing). */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase {

    @Inject(require = 0, method = "getArmSwingAnimationEnd", at = @At("RETURN"), cancellable = true)
    private void fzhud$swingSpeed(CallbackInfoReturnable<Integer> cir) {
        if (!Modules.animations.enabled) return;
        float speed = Modules.animSwingSpeed.get();
        if (speed == 1.0F) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || (Object) this != mc.thePlayer) return;
        cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValue() / speed)));
    }

    // ---- Fullbright "暗視の効果": your own player looks as if Night Vision were active. The game's light map and
    //      OptiFine's shader uniform "nightVision" both read it through these methods, so shader packs brighten too.
    //      Only these lookups are answered; the real effect list (inventory, potion HUD, server) is untouched.

    private static final PotionEffect FZHUD$NIGHT_VISION = new PotionEffect(Potion.nightVision.id, 1000000, 0, false, false);

    private boolean fzhud$fakeNightVision() {
        if (!Modules.fullbright.enabled || Modules.fullbrightMode.get() != 1) return false;
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && (Object) this == mc.thePlayer;
    }

    @Inject(require = 0, method = "isPotionActive(Lnet/minecraft/potion/Potion;)Z", at = @At("HEAD"), cancellable = true)
    private void fzhud$nightVision(Potion potion, CallbackInfoReturnable<Boolean> cir) {
        if (potion == Potion.nightVision && fzhud$fakeNightVision()) cir.setReturnValue(true);
    }

    @Inject(require = 0, method = "isPotionActive(I)Z", at = @At("HEAD"), cancellable = true)
    private void fzhud$nightVisionId(int id, CallbackInfoReturnable<Boolean> cir) {
        if (id == Potion.nightVision.id && fzhud$fakeNightVision()) cir.setReturnValue(true);
    }

    @Inject(require = 0, method = "getActivePotionEffect", at = @At("HEAD"), cancellable = true)
    private void fzhud$nightVisionEffect(Potion potion, CallbackInfoReturnable<PotionEffect> cir) {
        if (potion == Potion.nightVision && fzhud$fakeNightVision()) cir.setReturnValue(FZHUD$NIGHT_VISION);
    }
}
