package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.feature.Extras;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Block Outline styling inside vanilla's own outline drawing. Drawing there (instead of on our own) keeps
 * OptiFine's shader handling around it (it switches to its outline program), so the outline stays visible
 * with shader packs.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {

    @Redirect(require = 0, method = "drawSelectionBox",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V"))
    private void fzhud$outlineColor(float r, float g, float b, float a) {
        if (Modules.blockOutline.enabled) Extras.outlineColor(Modules.outlineColor.get(), Modules.outlineAlpha.get());
        else GlStateManager.color(r, g, b, a);
    }

    @Redirect(require = 0, method = "drawSelectionBox",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glLineWidth(F)V", remap = false))
    private void fzhud$outlineWidth(float width) {
        GL11.glLineWidth(Modules.blockOutline.enabled ? Modules.outlineWidth.get() : width);
    }

    @Redirect(require = 0, method = "drawSelectionBox",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;drawSelectionBoundingBox(Lnet/minecraft/util/AxisAlignedBB;)V"))
    private void fzhud$outlineBox(AxisAlignedBB bb) {
        if (!Modules.blockOutline.enabled) {
            RenderGlobal.drawSelectionBoundingBox(bb);
            return;
        }
        boolean through = Modules.outlineThrough.get();
        if (through) GlStateManager.disableDepth();
        if (Modules.outlineFill.get()) {
            Extras.outlineColor(Modules.outlineFillColor.get(), Modules.outlineFillAlpha.get());
            GlStateManager.disableCull();
            Extras.fillBox(bb);
            GlStateManager.enableCull();
            Extras.outlineColor(Modules.outlineColor.get(), Modules.outlineAlpha.get());
        }
        RenderGlobal.drawSelectionBoundingBox(bb);
        if (through) GlStateManager.enableDepth();
        GL11.glLineWidth(2f);
    }
}
