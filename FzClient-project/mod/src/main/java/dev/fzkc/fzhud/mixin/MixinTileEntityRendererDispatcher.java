package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimization "チェスト・看板・頭などの描画距離": chests, signs, skulls, banners... further away than the slider are
 * not drawn (the game's own limit is 64 blocks). Beacon beams keep their long range.
 */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinTileEntityRendererDispatcher {

    /** x / y / z are relative to the camera. */
    @Inject(require = 0, method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntity;DDDFI)V", at = @At("HEAD"), cancellable = true)
    private void fzhud$tileDistance(TileEntity te, double x, double y, double z, float partialTicks, int destroyStage, CallbackInfo ci) {
        if (!Modules.optimize.enabled) return;
        double max = Modules.optTileDist.get();
        if (max >= 64 || te instanceof TileEntityBeacon) return;
        if (x * x + y * y + z * z > max * max) ci.cancel();
    }
}
