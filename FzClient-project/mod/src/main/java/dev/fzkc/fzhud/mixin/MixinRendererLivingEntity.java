package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;

/**
 * Optimization "ホログラムを軽く描く". Server holograms (the floating text in lobbies) are one invisible armor stand
 * per line. For each of them the game still sets up the whole body - model, rotations, lighting, armor and
 * held-item layers - before finding out there is nothing to draw, and only then draws the name. With hundreds
 * of lines in a lobby that is most of the entity rendering time. An invisible armor stand that wears and holds
 * nothing only gets its name drawn; armor stands with a head or items (decorations, displays) are untouched.
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntity {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(require = 0, method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void fzhud$hologramNameOnly(EntityLivingBase entity, double x, double y, double z, float yaw, float partialTicks, CallbackInfo ci) {
        if (!(entity instanceof EntityArmorStand) || !entity.isInvisible()) return;
        if (!Modules.optimize.enabled || !Modules.optHolograms.get()) return;
        for (ItemStack s : ((EntityArmorStand) entity).getInventory()) if (s != null) return;
        ci.cancel();
        ((RendererLivingEntity) (Object) this).renderName(entity, x, y, z);
    }

    // ------------------------------------------------------------------ the red flash when hit (Animations)

    /** Our hit tint: null = vanilla (red, 0.3), else r, g, b, a (a = 0 when switched off). */
    private static final float[] FZHUD$TINT = new float[4];

    private static float[] fzhud$hitTint() {
        if (!Modules.animations.enabled) return null;
        float[] t = FZHUD$TINT;
        if (!Modules.animHitColor.get()) {
            t[0] = t[1] = t[2] = t[3] = 0F;
            return t;
        }
        int c = Modules.animHitColorRgb.get();
        t[0] = (c >> 16 & 255) / 255F;
        t[1] = (c >> 8 & 255) / 255F;
        t[2] = (c & 255) / 255F;
        t[3] = Modules.animHitColorAlpha.get();
        return t;
    }

    /** Without shaders: the colour is handed to OpenGL here (the buffer holds the vanilla 1, 0, 0, 0.3 while hurt). */
    @Redirect(require = 0, method = "setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexEnv(IILjava/nio/FloatBuffer;)V", remap = false))
    private void fzhud$hitTintGl(int target, int pname, FloatBuffer buf, EntityLivingBase entity, float partialTicks, boolean combine) {
        if (entity.hurtTime > 0 || entity.deathTime > 0) {
            float[] t = fzhud$hitTint();
            if (t != null) {
                buf.clear();
                buf.put(t[0]).put(t[1]).put(t[2]).put(t[3]);
                buf.flip();
            }
        }
        org.lwjgl.opengl.GL11.glTexEnv(target, pname, buf);
    }

    private static Method fzhud$setEntityColor;
    private static boolean fzhud$looked;

    /** With OptiFine shaders the colour goes to the shader instead (OptiFine adds this call next to the one above). */
    @Redirect(require = 0, method = "setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z",
            at = @At(value = "INVOKE", target = "Lnet/optifine/shaders/Shaders;setEntityColor(FFFF)V", remap = false))
    private void fzhud$hitTintShaders(float r, float g, float b, float a) {
        fzhud$shaderColor("net.optifine.shaders.Shaders", r, g, b, a);
    }

    /** The same for older OptiFine builds, where the class has its old name. */
    @Redirect(require = 0, method = "setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z",
            at = @At(value = "INVOKE", target = "Lshadersmod/client/Shaders;setEntityColor(FFFF)V", remap = false))
    private void fzhud$hitTintShadersOld(float r, float g, float b, float a) {
        fzhud$shaderColor("shadersmod.client.Shaders", r, g, b, a);
    }

    private static void fzhud$shaderColor(String cls, float r, float g, float b, float a) {
        float[] t = r == 1F && g == 0F && b == 0F ? fzhud$hitTint() : null;
        if (t != null) { r = t[0]; g = t[1]; b = t[2]; a = t[3]; }
        try {
            if (!fzhud$looked) {
                fzhud$looked = true;
                fzhud$setEntityColor = Class.forName(cls).getMethod("setEntityColor", float.class, float.class, float.class, float.class);
            }
            if (fzhud$setEntityColor != null) fzhud$setEntityColor.invoke(null, r, g, b, a);
        } catch (Throwable ignored) {
        }
    }
}
