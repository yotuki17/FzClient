package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Config;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Base for FzClient menus. Draws in its own "virtual" pixels so the menu has the same,
 * comfortable size no matter which Minecraft GUI scale (x1 ... x6) is selected.
 * Every left click in any of these menus plays the menu click sound (see UiSound).
 */
public abstract class FzScreen extends GuiScreen {
    /** GUI-pixels per virtual pixel. */
    protected float k = 1f;
    /** Virtual screen size. */
    protected int vw, vh;

    protected void computeScale() {
        int sf = Ui.scaleFactor();
        int phys = Math.max(1, (int) Math.floor(Math.min(mc.displayWidth / 640f, mc.displayHeight / 360f)));
        k = phys / (float) sf;
        vw = (int) (width / k);
        vh = (int) (height / k);
        Ui.k = k;
    }

    @Override
    public void initGui() {
        computeScale();
        Glass.enter();
    }

    protected abstract void render(int mx, int my, float partialTicks);

    protected void click(int mx, int my, int button) {}

    protected void release(int mx, int my) {}

    protected void drag(int mx, int my) {}

    protected void scroll(int mx, int my, int amount) {}

    protected void key(char c, int keyCode) {}

    /** Drawn before this screen, in normal GUI pixels (the overlay draws the screen it was opened over here). */
    protected void drawBehind(int mouseX, int mouseY, float partialTicks) {}

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawBehind(mouseX, mouseY, partialTicks);
        computeScale();
        Ui.refresh();
        RecentActions.track();
        GlStateManager.pushMatrix();
        GlStateManager.scale(k, k, 1f);
        R.noShadow = Ui.flat();
        try {
            render((int) (mouseX / k), (int) (mouseY / k), partialTicks);
        } finally {
            R.noShadow = false;
        }
        GlStateManager.popMatrix();
    }

    @Override
    protected final void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0) UiSound.beginClick();
        click((int) (mouseX / k), (int) (mouseY / k), button);
        if (button == 0) UiSound.endClick(); // the touch sound, or the cancel sound if the screen asked for it
    }

    @Override
    protected final void mouseReleased(int mouseX, int mouseY, int state) {
        release((int) (mouseX / k), (int) (mouseY / k));
    }

    @Override
    protected final void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        drag((int) (mouseX / k), (int) (mouseY / k));
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mx = Mouse.getEventX() * width / mc.displayWidth;
            int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            scroll((int) (mx / k), (int) (my / k), wheel > 0 ? -1 : 1);
        }
    }

    @Override
    protected final void keyTyped(char c, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) UiSound.playCancel();
        key(c, keyCode);
    }

    @Override
    public void onGuiClosed() {
        Glass.leave();
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        RecentActions.track(true);
        Config.save();
        RecentActions.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
