package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.Branding;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.SplashProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

/** Forge's loading screen: FzClient logo and colours. */
@Mixin(value = SplashProgress.class, remap = false)
public abstract class MixinSplashProgress {

    /** Forge's logo + loading bars + anvil become just the FzClient logo, sized to the window. */
    @Inject(require = 0, method = "open", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fzhud$logo(ResourceLocation loc, CallbackInfoReturnable<InputStream> cir) {
        if (!Branding.on("splash")) return;
        InputStream in = null;
        if ("textures/gui/title/mojang.png".equals(loc.getResourcePath())) in = Branding.logo();      // our full logo
        else if ("textures/gui/forge.gif".equals(loc.getResourcePath())) in = Branding.emptyImage(); // no anvil
        if (in != null) cir.setReturnValue(in);
    }

    @Inject(require = 0, method = "getHex", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fzhud$colors(String name, int def, CallbackInfoReturnable<Integer> cir) {
        if (!Branding.on("splash")) return;
        Integer c = Branding.splashColor(name);
        if (c != null) cir.setReturnValue(c);
    }
}
