package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Per-tick logic for toggle sprint/sneak, fullbright and the block-hit swing. */
public final class Gameplay {
    private Gameplay() {}

    public static boolean sprintToggled;
    public static boolean sneakToggled;
    private static float savedGamma = -1f;
    private static int savedGuiScale = -1;

    public static void tick(KeyBinding toggleSneakKey) {
        Minecraft mc = Minecraft.getMinecraft();
        GameSettings gs = mc.gameSettings;

        // Fullbright
        if (Modules.fullbright.enabled && Modules.fullbrightMode.get() == 0) {
            if (savedGamma < 0f) savedGamma = Math.min(gs.gammaSetting, 1f);
            gs.gammaSetting = Modules.brightness.get();
        } else if (savedGamma >= 0f) {
            gs.gammaSetting = savedGamma;
            savedGamma = -1f;
        }

        // GUI scale override (vanilla's button only goes up to Large = 3)
        int wantScale = -1;
        if (Modules.guiScale.enabled) {
            if (savedGuiScale < 0) savedGuiScale = gs.guiScale;
            wantScale = (int) Modules.guiScaleValue.get();
        } else if (savedGuiScale >= 0) {
            wantScale = savedGuiScale;
            savedGuiScale = -1;
        }
        if (wantScale >= 0 && gs.guiScale != wantScale) {
            gs.guiScale = wantScale;
            if (mc.currentScreen != null) {
                net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
                mc.currentScreen.setWorldAndResolution(mc, sr.getScaledWidth(), sr.getScaledHeight());
            }
        }

        if (mc.thePlayer == null) {
            sprintToggled = false;
            sneakToggled = false;
            return;
        }

        boolean on = Modules.sprint.enabled;
        if (on && Modules.sprintToggle.get()) {
            while (gs.keyBindSprint.isPressed()) sprintToggled = !sprintToggled;
        } else {
            sprintToggled = false;
        }
        if (on && Modules.sneakToggle.get()) {
            while (toggleSneakKey.isPressed()) sneakToggled = !sneakToggled;
        } else {
            sneakToggled = false;
        }

        boolean inGame = mc.currentScreen == null;
        apply(gs.keyBindSprint, sprintToggled && inGame);
        apply(gs.keyBindSneak, sneakToggled && inGame);

        // 1.7-style block-hit visuals. Client-side only: no swing packet is sent, so other
        // players and the server never see a swing that didn't happen.
        // Holding attack keeps it swinging; the first press is handled right away in onAttackPressed.
        if (inGame && attackHeld(gs.keyBindAttack)) blockHitSwing(mc, false);
    }

    /**
     * Swing the arm (visual only) while right-click is held, and you look at a block in reach ("block-hit on
     * blocks", the 1.7 look). Client-side only: no swing packet is sent, so other players and the
     * server never see a swing that didn't happen, and nothing is attacked.
     */
    public static void blockHitSwing(Minecraft mc, boolean newPress) {
        if (!Modules.animations.enabled || mc.thePlayer == null || mc.currentScreen != null || !mc.thePlayer.isUsingItem()) return;
        boolean onBlock = mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        if (!(onBlock && mc.thePlayer.isBlocking() && Modules.animBlockHitBlock.get())) return;
        // restart the swing on a new click, or keep it going while the button is held
        if (newPress || !mc.thePlayer.isSwingInProgress || mc.thePlayer.swingProgressInt >= 3 || mc.thePlayer.swingProgressInt < 0) {
            mc.thePlayer.swingProgressInt = -1;
            mc.thePlayer.isSwingInProgress = true;
        }
    }

    /**
     * Right click wins over left click: vanilla ignores right click while you are digging a block (holding left on
     * it), so you can't block with the sword or place a block until you let go. With this on, the dig is stopped
     * (the normal "stop digging" packet, same as letting go) and the right click goes through.
     */
    public static boolean rightPriority() {
        Minecraft mc = Minecraft.getMinecraft();
        return Modules.animations.enabled && Modules.rightPriority.get() && mc.currentScreen == null && mc.thePlayer != null;
    }

    /** Called from the mouse event when the attack button goes down (works even if another mod eats the click). */
    public static void onAttackPressed() {
        blockHitSwing(Minecraft.getMinecraft(), true);
    }

    /** The attack key is down: either Minecraft's own state, or the physical button (some mods swallow the click). */
    private static boolean attackHeld(KeyBinding kb) {
        if (kb.isKeyDown()) return true;
        int code = kb.getKeyCode();
        try {
            return code < 0 ? Mouse.isButtonDown(code + 100) : code > 0 && Keyboard.isKeyDown(code);
        } catch (Exception e) {
            return false;
        }
    }

    private static void apply(KeyBinding kb, boolean forced) {
        int code = kb.getKeyCode();
        if (code == 0) return;
        boolean physical;
        try {
            physical = code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code);
        } catch (Exception e) {
            physical = false;
        }
        if (forced) {
            KeyBinding.setKeyBindState(code, true);
        } else if (!physical && kb.isKeyDown()) {
            KeyBinding.setKeyBindState(code, false);
        }
    }

    public static void onDisconnect() {
        sprintToggled = false;
        sneakToggled = false;
    }
}
