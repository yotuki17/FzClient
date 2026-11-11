package dev.fzkc.fzhud.mixin;

import com.mojang.authlib.GameProfile;
import dev.fzkc.fzhud.feature.NameHider;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Name Hider "自分のスキンもデフォルトにする": your own entry answers with the default skin (Steve). The player
 * model, the heads in the tab list and in the chat all ask here, so they all change together.
 */
@Mixin(NetworkPlayerInfo.class)
public abstract class MixinNetworkPlayerInfo {
    @Shadow @Final private GameProfile gameProfile;

    @Inject(require = 0, method = "getLocationSkin", at = @At("HEAD"), cancellable = true)
    private void fzhud$hiddenSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        if (NameHider.hidesSkin(gameProfile)) cir.setReturnValue(DefaultPlayerSkin.getDefaultSkinLegacy());
    }

    @Inject(require = 0, method = "getSkinType", at = @At("HEAD"), cancellable = true)
    private void fzhud$hiddenSkinType(CallbackInfoReturnable<String> cir) {
        if (NameHider.hidesSkin(gameProfile)) cir.setReturnValue("default");
    }
}
