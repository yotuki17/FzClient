package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
