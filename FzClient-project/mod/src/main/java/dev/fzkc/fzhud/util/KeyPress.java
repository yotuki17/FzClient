package dev.fzkc.fzhud.util;

/** Added to every KeyBinding by MixinKeyBinding: lets KeyCombos press and release it. */
public interface KeyPress {
    /** One more press for isPressed() (what KeyBinding.onTick does). */
    void fzhud$addPress();

    /** Held or not (what KeyBinding.setKeyBindState does). */
    void fzhud$setPressed(boolean pressed);
}
