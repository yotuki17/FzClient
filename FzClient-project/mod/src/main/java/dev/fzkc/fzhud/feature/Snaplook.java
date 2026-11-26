package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Snaplook: while the key is held the camera looks at you from the front (or from behind), and goes back to the
 * view you had when you let go. With "toggle" on, one press switches it on and the next one off.
 */
public class Snaplook {
    public static final KeyBinding KEY = new KeyBinding("Snaplook", Keyboard.KEY_V, "FzClient");

    private static boolean active, toggled, wasDown;
    private static int before;

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean on = false;
        if (Modules.snaplook.enabled && mc.thePlayer != null && mc.theWorld != null) {
            boolean down = mc.currentScreen == null && KEY.isKeyDown();
            if (TEST) down = test(mc);
            if (Modules.snaplookToggle.get()) {
                if (down && !wasDown) toggled = !toggled;
                on = toggled;
            } else {
                toggled = false;
                // a screen opened while holding (chat, inventory): keep looking until the key is let go
                on = down || active && mc.currentScreen != null && held();
            }
            wasDown = down;
        } else {
            toggled = false;
            wasDown = false;
        }
        int view = Modules.snaplookView.get() == 0 ? 2 : 1;
        if (on && !active) {
            before = mc.gameSettings.thirdPersonView;
            active = true;
        }
        if (on) {
            mc.gameSettings.thirdPersonView = view;
        } else if (active) {
            active = false;
            mc.gameSettings.thirdPersonView = before;
        }
    }

    /** Either of the two keys (with its Ctrl / Shift / Alt) is held right now (while a screen is open). */
    private static boolean held() {
        for (int s = 0; s < 2; s++) {
            int k = KeyCombos.key(KEY, s);
            if (k != 0 && isDown(k) && (KeyCombos.mods(KEY, s) & ~KeyCombos.held(k)) == 0) return true;
        }
        return false;
    }

    /** The key (or mouse button, stored as button - 100) is held right now. */
    private static boolean isDown(int code) {
        try {
            return code < 0 ? SideButtons.isDown(code + 100) : Keyboard.isKeyDown(code);
        } catch (Exception e) {
            return false;
        }
    }

    /** Dev check (-Dfzclient.snaptest): holds the key for a while in a world and takes pictures. */
    private static final boolean TEST = System.getProperty("fzclient.snaptest") != null;
    private static int testFrames;

    private static boolean test(Minecraft mc) {
        int f = ++testFrames;
        if (f == 2000 || f == 2600 || f == 3200) {
            net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, "snaptest" + f + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        }
        return f > 2200 && f < 3000;
    }

    /** True while the camera is turned by Snaplook. */
    public static boolean active() {
        return active;
    }
}
