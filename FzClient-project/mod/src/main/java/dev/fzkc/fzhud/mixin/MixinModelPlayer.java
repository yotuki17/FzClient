package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.cosmetic.SkinLayers3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D skin layers in the world: the flat outer layer is hidden while the model draws, then the cube version is
 * drawn on the same parts. Which parts show (the skin customization settings) is kept as the game set it.
 */
@Mixin(ModelPlayer.class)
public abstract class MixinModelPlayer {
    @Unique private final boolean[] fzhud$shown = new boolean[6], fzhud$was = new boolean[6];
    @Unique private boolean fzhud$active;

    @Inject(require = 0, method = "render(Lnet/minecraft/entity/Entity;FFFFFF)V", at = @At("HEAD"))
    private void fzhud$hideFlat(Entity entity, float a, float b, float c, float d, float e, float scale, CallbackInfo ci) {
        fzhud$active = false;
        if (!(entity instanceof AbstractClientPlayer)) return;
        ModelPlayer self = (ModelPlayer) (Object) this;
        if (self.isChild) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        boolean me = entity == mc.thePlayer;
        double dist = view == null ? 0 : entity.getDistanceSqToEntity(view);
        if (!SkinLayers3D.active(me, dist)) return;
        ModelRenderer[] wear = SkinLayers3D.wear(self);
        for (int i = 0; i < 6; i++) {
            fzhud$was[i] = wear[i].showModel;
            fzhud$shown[i] = wear[i].showModel && !wear[i].isHidden;
            wear[i].showModel = false;
        }
        fzhud$active = true;
    }

    @Inject(require = 0, method = "render(Lnet/minecraft/entity/Entity;FFFFFF)V", at = @At("RETURN"))
    private void fzhud$draw3D(Entity entity, float a, float b, float c, float d, float e, float scale, CallbackInfo ci) {
        if (!fzhud$active) return;
        fzhud$active = false;
        ModelPlayer self = (ModelPlayer) (Object) this;
        ModelRenderer[] wear = SkinLayers3D.wear(self);
        AbstractClientPlayer p = (AbstractClientPlayer) entity;
        boolean slim = "slim".equals(p.getSkinType());
        GlStateManager.pushMatrix();
        if (entity.isSneaking()) GlStateManager.translate(0f, 0.2f, 0f);
        for (int i = 0; i < 6; i++) {
            if (!fzhud$shown[i]) continue;
            GlStateManager.pushMatrix();
            // postRender does nothing while showModel is false (no position, no rotation): turn it back on first
            wear[i].showModel = true;
            wear[i].postRender(scale);
            boolean ok = SkinLayers3D.draw(p.getLocationSkin(), slim, i, scale);
            GlStateManager.popMatrix();
            if (!ok) {
                // could not read the skin: the normal flat layer
                wear[i].showModel = true;
                wear[i].render(scale);
            }
        }
        GlStateManager.popMatrix();
        // put the flags back for the first-person arm and the next frame
        for (int i = 0; i < 6; i++) wear[i].showModel = fzhud$was[i];
    }
}
