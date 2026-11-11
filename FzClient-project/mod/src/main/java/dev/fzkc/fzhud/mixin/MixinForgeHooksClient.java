package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimization "空の色". Forge blends the sky colour of every block column around you (37 x 37 columns at render
 * distance 8, 69 x 69 at 16) and means to remember the answer while you stand in the same column - but it stores
 * the Y coordinate where the Z belongs, so the answer is thrown away and everything is worked out again several
 * times every frame. Here the answer is kept properly: per column, for one second at most.
 */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class MixinForgeHooksClient {
    @Unique private static World fzhud$skyWorld;
    @Unique private static int fzhud$skyX, fzhud$skyZ, fzhud$skyDist, fzhud$skyValue;
    @Unique private static boolean fzhud$skyFancy;
    @Unique private static long fzhud$skyAt;

    @Inject(require = 0, method = "getSkyBlendColour", at = @At("HEAD"), cancellable = true)
    private static void fzhud$skyCached(World world, BlockPos center, CallbackInfoReturnable<Integer> cir) {
        if (!Modules.optimize.enabled || !Modules.optSky.get() || world != fzhud$skyWorld) return;
        GameSettings s = Minecraft.getMinecraft().gameSettings;
        if (center.getX() == fzhud$skyX && center.getZ() == fzhud$skyZ && s.fancyGraphics == fzhud$skyFancy
                && s.renderDistanceChunks == fzhud$skyDist && System.currentTimeMillis() - fzhud$skyAt < 1000L) {
            cir.setReturnValue(fzhud$skyValue);
        }
    }

    @Inject(require = 0, method = "getSkyBlendColour", at = @At("RETURN"))
    private static void fzhud$skyStore(World world, BlockPos center, CallbackInfoReturnable<Integer> cir) {
        GameSettings s = Minecraft.getMinecraft().gameSettings;
        fzhud$skyWorld = world;
        fzhud$skyX = center.getX();
        fzhud$skyZ = center.getZ();
        fzhud$skyFancy = s.fancyGraphics;
        fzhud$skyDist = s.renderDistanceChunks;
        fzhud$skyValue = cir.getReturnValue();
        fzhud$skyAt = System.currentTimeMillis();
    }
}
