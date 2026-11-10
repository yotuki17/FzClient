package dev.fzkc.fzhud.mixin;

import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player in the inventory (and the creative inventory) is drawn with depth: when another screen part or mod left
 * depth off, the cape, armor and shoulder pet were drawn over the body in the order they come.
 */
@Mixin(GuiInventory.class)
public abstract class MixinGuiInventory {
    @Inject(require = 0, method = "drawEntityOnScreen", at = @At("HEAD"))
    private static void fzhud$depth(int x, int y, int scale, float mouseX, float mouseY, EntityLivingBase ent, CallbackInfo ci) {
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
    }
}
