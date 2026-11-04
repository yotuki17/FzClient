package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7-style first person animations: while blocking / eating / drawing a bow,
 * the item still follows the arm swing (the "block hit" look).
 */
@Mixin(ItemRenderer.class)
public abstract class MixinItemRenderer {
    private static float fzhud$partialTicks;

    @Shadow
    private void transformFirstPersonItem(float equipProgress, float swingProgress) {
    }

    @Inject(require = 0, method = "renderItemInFirstPerson", at = @At("HEAD"))
    private void fzhud$capturePartialTicks(float partialTicks, CallbackInfo ci) {
        fzhud$partialTicks = partialTicks;
    }

    @Redirect(require = 0, method = "renderItemInFirstPerson",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;transformFirstPersonItem(FF)V"))
    private void fzhud$oldTransform(ItemRenderer self, float equipProgress, float swingProgress) {
        if (Modules.animations.enabled && swingProgress == 0.0F) {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            ItemStack using = p == null ? null : p.getItemInUse();
            if (using != null && p.getItemInUseCount() > 0) {
                EnumAction action = using.getItemUseAction();
                boolean allowed = action == EnumAction.BLOCK ? Modules.animBlockHit.get()
                        : (action == EnumAction.EAT || action == EnumAction.DRINK) ? Modules.animEat.get()
                        : action == EnumAction.BOW ? Modules.animBow.get()
                        : false;
                if (allowed) swingProgress = p.getSwingProgress(fzhud$partialTicks);
            }
        }
        this.transformFirstPersonItem(equipProgress, swingProgress);
    }

    // ---- Low fire / no fire ----
    private boolean fzhud$firePushed;

    @Inject(require = 0, method = "renderFireInFirstPerson", at = @At("HEAD"), cancellable = true)
    private void fzhud$fireStart(float partialTicks, CallbackInfo ci) {
        fzhud$firePushed = false;
        if (!Modules.overlays.enabled) return;
        int mode = Modules.fireMode.get();
        if (mode == 2) {
            ci.cancel();
        } else if (mode == 1) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, -Modules.fireHeight.get(), 0.0F);
            fzhud$firePushed = true;
        }
    }

    @Inject(require = 0, method = "renderFireInFirstPerson", at = @At("RETURN"))
    private void fzhud$fireEnd(float partialTicks, CallbackInfo ci) {
        if (fzhud$firePushed) {
            GlStateManager.popMatrix();
            fzhud$firePushed = false;
        }
    }
}
