package dev.fzkc.fzhud.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockNewLeaf;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crash fix: acacia / dark oak leaves (leaves2) only have two kinds, but some Hypixel maps send the block
 * with a third or fourth kind. Vanilla turns that into "oak", which this block does not allow, and the game crashes
 * ("Cannot set property ... to oak on block minecraft:leaves2"). Such a block becomes acacia / dark oak instead.
 */
@Mixin(BlockNewLeaf.class)
public abstract class MixinBlockNewLeaf {

    @Inject(require = 0, method = "getStateFromMeta", at = @At("HEAD"), cancellable = true)
    private void fzhud$validKind(int meta, CallbackInfoReturnable<IBlockState> cir) {
        if ((meta & 3) > 1) cir.setReturnValue(((Block) (Object) this).getStateFromMeta(meta & ~2));
    }
}
