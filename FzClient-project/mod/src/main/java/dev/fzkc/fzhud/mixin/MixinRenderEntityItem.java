package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Item physics: dropped items lie flat on the ground and tumble while falling, instead of bobbing and spinning. */
@Mixin(RenderEntityItem.class)
public abstract class MixinRenderEntityItem {

    @Shadow
    private int func_177078_a(ItemStack stack) {
        return 1;
    }

    @Inject(require = 0, method = "func_177077_a", at = @At("HEAD"), cancellable = true)
    private void fzhud$physics(EntityItem item, double x, double y, double z, float partialTicks, IBakedModel model,
                               CallbackInfoReturnable<Integer> cir) {
        if (!Modules.itemPhysics.enabled) return;
        float spinMul = Modules.physicsSpin.get();
        ItemStack stack = item.getEntityItem();
        if (stack == null || stack.getItem() == null) {
            cir.setReturnValue(0);
            return;
        }
        boolean block = model.isGui3d();
        int count = this.func_177078_a(stack);
        // stable random yaw per item so they don't all face the same way
        float yaw = (item.getEntityId() * 47.0F + item.hoverStart * 57.3F) % 360.0F;

        if (block) {
            float groundScale = model.getItemCameraTransforms()
                    .getTransform(ItemCameraTransforms.TransformType.GROUND).scale.y;
            GlStateManager.translate((float) x, (float) y + 0.25F * groundScale, (float) z);
            GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
            if (!item.onGround) {
                float spin = ((float) item.getAge() + partialTicks) * 12.0F * spinMul;
                GlStateManager.rotate(spin, 1.0F, 0.0F, 0.0F);
            }
        } else {
            GlStateManager.translate((float) x, (float) y + 0.03F, (float) z);
            GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
            // -90 around X lays the sprite flat; extra copies of a stack (offset along +Z by vanilla) pile upwards
            if (item.onGround) {
                GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
            } else {
                float spin = ((float) item.getAge() + partialTicks) * 15.0F * spinMul;
                GlStateManager.rotate(-90.0F + spin, 1.0F, 0.0F, 0.0F);
            }
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        cir.setReturnValue(count);
    }
}
