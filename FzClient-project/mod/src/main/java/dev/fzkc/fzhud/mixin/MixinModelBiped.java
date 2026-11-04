package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person arm tweaks for players:
 *  - other players' attack swing is made smaller (only the swing part, walking arms stay intact)
 *  - blocking pose correction
 * Runs at the end of ModelBiped.setRotationAngles, i.e. before ModelPlayer copies the angles to the
 * sleeve layers, so sleeves and armor follow automatically.
 */
@Mixin(ModelBiped.class)
public abstract class MixinModelBiped {

    private static final float PI = (float) Math.PI;

    @Inject(require = 0, method = "setRotationAngles(FFFFFFLnet/minecraft/entity/Entity;)V", at = @At("TAIL"))
    private void fzhud$playerArms(float limbSwing, float limbSwingAmount, float ageInTicks,
                                  float netHeadYaw, float headPitch, float scale, Entity entity, CallbackInfo ci) {
        if (!Modules.animations.enabled || !(entity instanceof EntityPlayer)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        ModelBiped self = (ModelBiped) (Object) this;
        EntityPlayer player = (EntityPlayer) entity;
        float swing = self.swingProgress;

        // Smaller swing for other players: undo part of vanilla's swing offset instead of scaling the
        // whole angle, so the arm doesn't snap when a swing starts or ends while walking.
        float k = Modules.animOtherSwing.get();
        if (k < 0.999F && swing > 0F && player != mc.thePlayer) {
            float f = 1F - swing;
            f *= f;
            f *= f;
            f = 1F - f;
            float lift = MathHelper.sin(f * PI) * 1.2F
                    + MathHelper.sin(swing * PI) * -(self.bipedHead.rotateAngleX - 0.7F) * 0.75F;
            float r = 1F - k;
            self.bipedRightArm.rotateAngleX += lift * r;
            self.bipedRightArm.rotateAngleZ += MathHelper.sin(swing * PI) * 0.4F * r;
        }

        // Blocking pose (you see this on other players and on yourself in F5)
        if (Modules.animBlockPose.get() && player.isBlocking() && swing <= 0F) {
            self.bipedRightArm.rotateAngleX -= 0.3F;
            self.bipedLeftArm.rotateAngleX -= 0.3F;
            self.bipedRightArm.rotateAngleY = -0.25F;
            self.bipedLeftArm.rotateAngleY = 0.25F;
        }
    }
}
