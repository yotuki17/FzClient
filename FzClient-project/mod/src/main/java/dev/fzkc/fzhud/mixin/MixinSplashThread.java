package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.Branding;
import dev.fzkc.fzhud.feature.SplashAnim;
import net.minecraftforge.fml.common.ProgressManager;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge's loading screen, FzClient style: only our logo, fitted to the window.
 *  - Forge tints the logo with the background colour (fine on white, almost black on our dark one): draw it untinted
 *  - the logo is a fixed 512px square; scale it to the window size
 *  - the loading bars are not drawn
 *  - the logo is animated (bird, glow, title, subtitle, progress line) by {@link SplashAnim}
 */
@Mixin(targets = "net.minecraftforge.fml.client.SplashProgress$3", remap = false)
public abstract class MixinSplashThread {

    @Inject(require = 0, method = "setColor", at = @At("HEAD"), cancellable = true, remap = false)
    private void fzhud$untintLogo(int color, CallbackInfo ci) {
        if ((color & 0xFFFFFF) == Branding.SPLASH_BG && Branding.on("splash")) {
            GL11.glColor3ub((byte) 255, (byte) 255, (byte) 255);
            ci.cancel();
        }
    }

    @Inject(require = 0, method = "drawBar", at = @At("HEAD"), cancellable = true, remap = false)
    private void fzhud$noBars(ProgressManager.ProgressBar bar, CallbackInfo ci) {
        if (Branding.on("splash")) ci.cancel();
    }

    /** Right before Forge starts the logo quad: draw our animated logo instead. */
    @Redirect(require = 0, method = "run", remap = false,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBegin(I)V", ordinal = 0, remap = false))
    private void fzhud$animatedLogo(int mode) {
        SplashAnim.active = Branding.on("splash") && SplashAnim.draw(Display.getWidth(), Display.getHeight());
        GL11.glBegin(mode);
    }

    /**
     * The logo quad is drawn at (64,-16)-(576,496) in a projection centred on (320,240) where one unit is one
     * window pixel. Grow it to fill the window (keeping it square; the image's own background matches the
     * screen colour, so it looks like one full-screen picture).
     */
    @Redirect(require = 0, method = "run", remap = false,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glVertex2f(FF)V", remap = false))
    private void fzhud$fitLogo(float x, float y) {
        if (SplashAnim.active && (x == 64f || x == 576f) && (y == -16f || y == 496f)) {
            GL11.glVertex2f(0f, 0f); // the animated logo was already drawn: collapse the static one
            return;
        }
        if (Branding.on("splash") && (x == 64f || x == 576f) && (y == -16f || y == 496f)) {
            float half = Math.min(Display.getWidth(), Display.getHeight()) / 2f;
            x = 320f + (x < 320f ? -half : half);
            y = 240f + (y < 240f ? -half : half);
        }
        GL11.glVertex2f(x, y);
    }
}
