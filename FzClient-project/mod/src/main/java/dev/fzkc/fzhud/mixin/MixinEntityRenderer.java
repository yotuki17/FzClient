package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.Extras;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Tracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    // ---- Smooth Sneak ----
    private static float fzhud$eye = -1F;
    private static long fzhud$last;

    @Redirect(require = 0, method = "orientCamera",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getEyeHeight()F"))
    private float fzhud$eyeHeight(Entity entity) {
        float vanilla = entity.getEyeHeight();
        Minecraft mc = Minecraft.getMinecraft();
        if (!Modules.animations.enabled || entity != mc.thePlayer || mc.thePlayer.isPlayerSleeping()) {
            fzhud$eye = -1F;
            return vanilla;
        }

        float standing = 1.62F;
        float target = entity.isSneaking()
                ? standing - Modules.sneakDepth.get()
                : standing;

        long now = System.nanoTime();
        float dt = Math.min(0.1F, (now - fzhud$last) / 1.0E9F);
        fzhud$last = now;

        if (fzhud$eye < 0F || !Modules.smoothSneak.get()) {
            fzhud$eye = target;
        } else {
            fzhud$eye += (target - fzhud$eye) * (1F - (float) Math.exp(-Modules.sneakSpeed.get() * dt));
        }

        return fzhud$eye;
    }

    // ---- Zoom: multiply the world FOV (not the hand's) by the zoom factor ----
    @Inject(require = 0, method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void fzhud$zoom(float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (!useFovSetting) return; // the hand keeps its own FOV
        float fov = cir.getReturnValue();
        float kb = fzhud$kbFov(partialTicks);
        if (kb != 0F) {
            // KB FOV can also narrow the view (negative amount); never narrower than 10 degrees
            fov = Math.max(10F, fov + kb);
        }
        if (Extras.zoomFov < 1F) fov *= Extras.zoomFov;
        cir.setReturnValue(fov);
    }

    // ---- KB FOV: when you get hit the view widens (or narrows, if negative) for a moment (visual only)
    private static long fzhud$hitAt;
    private static int fzhud$lastHurt;
    /** When we last got hurt, waiting for the push that tells it was a hit (0 = nothing waiting). */
    private static long fzhud$hurtAt;
    /** The push that already started a widen (so one push doesn't count twice). */
    private static long fzhud$usedPush;
    /** Hurt and push arrive within a tick or two of each other when it is a real hit. */
    private static final long FZHUD$PAIR_NS = 300_000_000L;

    private static float fzhud$kbFov(float partialTicks) {
        float amount = Modules.kbFov.get();
        Minecraft mc = Minecraft.getMinecraft();
        if (!Modules.overlays.enabled || amount == 0F || mc.thePlayer == null) {
            fzhud$hitAt = 0;
            return 0F;
        }
        long now = System.nanoTime();
        int hurt = mc.thePlayer.hurtTime;
        if (hurt > fzhud$lastHurt && mc.thePlayer.getHealth() > 0F) fzhud$hurtAt = now; // got hurt (by anything)
        fzhud$lastHurt = hurt;
        // only a hurt that comes with knockback widens the view: fall, fire, lava, poison... push nothing
        if (fzhud$hurtAt != 0) {
            long push = Tracker.knockbackAt;
            if (push != 0 && push != fzhud$usedPush && Math.abs(push - fzhud$hurtAt) < FZHUD$PAIR_NS) {
                fzhud$hitAt = now;
                fzhud$usedPush = push;
                fzhud$hurtAt = 0;
            } else if (now - fzhud$hurtAt > FZHUD$PAIR_NS) {
                fzhud$hurtAt = 0; // no push came: not a hit
            }
        }
        if (fzhud$hitAt == 0) return 0F;
        float t = (System.nanoTime() - fzhud$hitAt) / 1e9F / Math.max(0.05F, Modules.kbFovTime.get());
        if (t >= 1F) {
            fzhud$hitAt = 0;
            return 0F;
        }
        // quick change (first 15%), then ease back to normal
        float k = t < 0.15F ? t / 0.15F : 1F - (t - 0.15F) / 0.85F;
        k = k * k * (3F - 2F * k);
        return amount * k;
    }

    // ---- Hurt cam: scale (or remove) the screen tilt when you take damage ----
    @Inject(require = 0, method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void fzhud$hurtCam(float partialTicks, CallbackInfo ci) {
        if (!Modules.overlays.enabled) return;
        float strength = Modules.hurtCam.get();
        if (strength >= 0.999F) return;
        ci.cancel();
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (!(view instanceof EntityLivingBase)) return;
        EntityLivingBase e = (EntityLivingBase) view;
        if (e.getHealth() <= 0.0F) {
            float d = e.deathTime + partialTicks;
            GlStateManager.rotate(40.0F - 8000.0F / (d + 200.0F), 0.0F, 0.0F, 1.0F);
        }
        float f = e.hurtTime - partialTicks;
        if (f < 0.0F || strength <= 0.001F || e.maxHurtTime <= 0) return;
        f /= (float) e.maxHurtTime;
        f = MathHelper.sin(f * f * f * f * (float) Math.PI);
        float yaw = e.attackedAtYaw;
        GlStateManager.rotate(-yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-f * 14.0F * strength, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
    }
}
