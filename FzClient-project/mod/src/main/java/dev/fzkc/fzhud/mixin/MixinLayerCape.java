package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.cosmetic.CapeCloth;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smooth capes (CapeCloth) instead of the game's stiff one, under the same conditions the game draws a cape. */
@Mixin(LayerCape.class)
public abstract class MixinLayerCape {
    @Inject(require = 0, method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void fzhud$cloth(AbstractClientPlayer p, float limbSwing, float limbAmount, float pt, float age,
                             float headYaw, float headPitch, float scale, CallbackInfo ci) {
        try {
            if (!CapeCloth.on() || !p.hasPlayerInfo() || p.isInvisible() || !p.isWearing(EnumPlayerModelParts.CAPE)) return;
            ResourceLocation tex = p.getLocationCape();
            if (tex == null) return;
            ci.cancel();
            CapeCloth.renderLayer(p, tex, pt);
        } catch (Throwable ignored) {
            // never break player rendering
        }
    }
}
