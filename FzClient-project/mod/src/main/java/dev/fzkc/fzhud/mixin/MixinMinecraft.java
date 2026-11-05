package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.Branding;
import dev.fzkc.fzhud.chat.ChatHistory;
import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.resources.DefaultResourcePack;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.LWJGLException;

import java.io.IOException;
import java.io.InputStream;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import dev.fzkc.fzhud.feature.Gameplay;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.LoadingScreenRenderer;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Borderless fullscreen + fast world swap. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Shadow private boolean fullscreen;
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;
    @Shadow public GameSettings gameSettings;

    @Shadow private void resize(int width, int height) {}
    @Shadow public abstract void updateDisplay();

    private int fzhud$windowW = 854, fzhud$windowH = 480;
    private boolean fzhud$quickSwap;

    // ---- Skip dirt screen when switching worlds ----
    @Inject(require = 0, method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V", at = @At("HEAD"))
    private void fzhud$beforeLoadWorld(WorldClient world, String message, CallbackInfo ci) {
        fzhud$quickSwap = world != null && Modules.noLoading.enabled && Modules.noLoadingFlash.get()
                && !((Minecraft)(Object)this).isIntegratedServerRunning();
    }

    @Redirect(require = 0, method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/LoadingScreenRenderer;resetProgressAndMessage(Ljava/lang/String;)V"))
    private void fzhud$resetProgress(LoadingScreenRenderer renderer, String message) {
        if (!fzhud$quickSwap) renderer.resetProgressAndMessage(message);
    }

    @Redirect(require = 0, method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/LoadingScreenRenderer;displayLoadingString(Ljava/lang/String;)V"))
    private void fzhud$displayLoading(LoadingScreenRenderer renderer, String message) {
        if (!fzhud$quickSwap) renderer.displayLoadingString(message);
    }

    // ============================================================
    // Borderless fullscreen
    // ============================================================

    @Inject(require = 0, method = "toggleFullscreen", at = @At("HEAD"), cancellable = true)
    private void fzhud$borderless(CallbackInfo ci) {
        if (!Modules.borderless.enabled) return;
        ci.cancel();

        try {
            this.fullscreen = !this.fullscreen;
            this.gameSettings.fullScreen = this.fullscreen;

            DisplayMode desktop = Display.getDesktopDisplayMode();

            if (this.fullscreen) {
                if (!Display.isFullscreen()) {
                    fzhud$windowW = this.displayWidth;
                    fzhud$windowH = this.displayHeight;
                }

                // the normal window, its frame taken off (Win32Window): a window LWJGL makes without a frame
                // ("undecorated") lost the mouse and froze recordings
                System.setProperty("org.lwjgl.opengl.Window.undecorated", "false");
                Display.setFullscreen(false);
                Display.setResizable(false);
                Display.setDisplayMode(new DisplayMode(desktop.getWidth(), desktop.getHeight()));
                Display.setLocation(0, 0);
                // one pixel taller than the screen: a window exactly as big as the screen is shown by Windows
                // directly ("fullscreen optimizations") and recordings of it freeze (tested: 15 of 120 frames,
                // with the extra pixel 120 of 120). The pixel is below the screen, so nothing shows.
                int h = desktop.getHeight() + 1;
                if (dev.fzkc.fzhud.util.Win32Window.enter(0, 0, desktop.getWidth(), h)) {
                    this.resize(desktop.getWidth(), h);
                } else {
                    // not on Windows: LWJGL's frameless window as before
                    System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
                    Display.setDisplayMode(new DisplayMode(desktop.getWidth(), desktop.getHeight()));
                    Display.setLocation(0, 0);
                    this.resize(desktop.getWidth(), desktop.getHeight());
                }
            } else {
                dev.fzkc.fzhud.util.Win32Window.leave();
                System.setProperty("org.lwjgl.opengl.Window.undecorated", "false");
                Display.setFullscreen(false);

                int w = Math.max(320, fzhud$windowW);
                int h = Math.max(240, fzhud$windowH);

                // no window size known yet (the game started in fullscreen) or one as big as the screen
                if (fzhud$windowW <= 0 || (w >= desktop.getWidth() && h >= desktop.getHeight())) {
                    w = 854;
                    h = 480;
                }

                Display.setDisplayMode(new DisplayMode(w, h));
                Display.setResizable(true);
                Display.setLocation((desktop.getWidth() - w) / 2, (desktop.getHeight() - h) / 2);

                this.resize(w, h);
            }

            Display.setVSyncEnabled(this.gameSettings.enableVsync);
            this.updateDisplay();

        } catch (LWJGLException e) {
            System.err.println("[FzClient] Borderless fullscreen failed: " + e);
        }
    }

    // ============================================================
    // FzClient branding: window title, window icon, vanilla loading logo
    // ============================================================

    @Redirect(require = 0, method = "createDisplay",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setTitle(Ljava/lang/String;)V", remap = false))
    private void fzhud$windowTitle(String title) {
        Display.setTitle(Branding.on("enabled") ? Branding.title() : title);
    }

    @Redirect(require = 0, method = "setWindowIcon",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/DefaultResourcePack;getInputStreamAssets(Lnet/minecraft/util/ResourceLocation;)Ljava/io/InputStream;"))
    private InputStream fzhud$icon(DefaultResourcePack pack, ResourceLocation loc) throws IOException {
        if (Branding.on("icon")) {
            InputStream in = Branding.icon(loc.getResourcePath());
            if (in != null) return in;
        }
        return pack.getInputStreamAssets(loc);
    }

    @Redirect(require = 0, method = "drawSplashScreen",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/DefaultResourcePack;getInputStream(Lnet/minecraft/util/ResourceLocation;)Ljava/io/InputStream;"))
    private InputStream fzhud$splash(DefaultResourcePack pack, ResourceLocation loc) throws IOException {
        if (Branding.on("splash")) {
            InputStream in = Branding.logo();
            if (in != null) return in;
        }
        return pack.getInputStream(loc);
    }

    // ---- Right click over left click: stop digging so the right click (block / place) isn't ignored
    @Redirect(require = 0, method = "rightClickMouse",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;getIsHittingBlock()Z"))
    private boolean fzhud$rightPriority(PlayerControllerMP controller) {
        if (controller.getIsHittingBlock() && Gameplay.rightPriority()) {
            controller.resetBlockRemoving();
            return false;
        }
        return controller.getIsHittingBlock();
    }

    // ... and don't start digging again while the right button is still held
    @ModifyArg(require = 0, method = "runTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;sendClickBlockToController(Z)V"))
    private boolean fzhud$noDigWhileRight(boolean leftClick) {
        return leftClick && !(Gameplay.rightPriority() && this.gameSettings.keyBindUseItem.isKeyDown());
    }

    // ---- F2: save the screenshot in the background (no freeze)
    @Redirect(require = 0, method = "dispatchKeypresses",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ScreenShotHelper;saveScreenshot(Ljava/io/File;IILnet/minecraft/client/shader/Framebuffer;)Lnet/minecraft/util/IChatComponent;"))
    private net.minecraft.util.IChatComponent fzhud$screenshot(java.io.File dir, int w, int h, net.minecraft.client.shader.Framebuffer fb) {
        return dev.fzkc.fzhud.feature.AsyncScreenshot.save(dir, w, h, fb);
    }

    // ---- Chat history: don't let the main menu wipe the chat and the sent-message history
    @Redirect(require = 0, method = "displayGuiScreen",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;clearChatMessages()V"))
    private void fzhud$keepChat(GuiNewChat chat) {
        if (!ChatHistory.keepOnMenu()) chat.clearChatMessages();
    }

    /**
     * Title screen FPS: with no world loaded and a menu open, Minecraft caps the frame rate at 30 (OptiFine 60).
     * The FzClient title screen is animated, so it uses the limit from Performance → "タイトル画面のFPS上限"
     * instead (260 = no limit; VSync still caps it at the monitor's refresh rate).
     */
    @Inject(require = 0, method = "getLimitFramerate", at = @At("HEAD"), cancellable = true)
    private void fzhud$titleFps(CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.theWorld == null && mc.currentScreen != null) cir.setReturnValue((int) Modules.perfTitleFps.get());
    }
}
