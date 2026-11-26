package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.KeyCombos;
import dev.fzkc.fzhud.util.KeyPress;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Key presses go through KeyCombos once a second key or a Ctrl / Shift / Alt combination is set (see there);
 * until then the game's own handling is untouched.
 */
@Mixin(KeyBinding.class)
public abstract class MixinKeyBinding implements KeyPress {
    @Shadow @Final private static List<KeyBinding> keybindArray;
    @Shadow private boolean pressed;
    @Shadow private int pressTime;

    @Override
    public void fzhud$addPress() {
        ++pressTime;
    }

    @Override
    public void fzhud$setPressed(boolean p) {
        pressed = p;
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private static void fzhud$onTick(int keyCode, CallbackInfo ci) {
        if (KeyCombos.onTick(keyCode, keybindArray)) ci.cancel();
    }

    @Inject(method = "setKeyBindState", at = @At("HEAD"), cancellable = true)
    private static void fzhud$setState(int keyCode, boolean down, CallbackInfo ci) {
        if (KeyCombos.setState(keyCode, down, keybindArray)) ci.cancel();
    }
}
