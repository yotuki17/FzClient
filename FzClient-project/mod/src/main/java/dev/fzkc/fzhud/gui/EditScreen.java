package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Config;
import dev.fzkc.fzhud.FzHud;
import dev.fzkc.fzhud.hud.HudElement;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

/**
 * HUD layout editor.
 * Left-drag: move   Right-click: on/off   Wheel: resize   Middle-click: open its settings
 * R: reset positions   Esc: back
 */
public class EditScreen extends GuiScreen {
    private static final float SNAP = 4f;

    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private HudElement dragging;
    private float offX, offY;
    private boolean snapX, snapY;

    public EditScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private List<HudElement> elements() {
        return FzHud.elements;
    }

    @Override
    public void initGui() {
        Glass.leave();   // the HUD behind the editor stays sharp
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Ui.refresh();
        float o = open.update(1f);
        R.rect(0, 0, width, height, R.alpha(0x88000000, o));

        if (dragging != null) {
            if (snapX) R.rect(width / 2f - 0.5f, 0, 1, height, R.alpha(Ui.accent, o * 0.7f));
            if (snapY) R.rect(0, height / 2f - 0.5f, width, 1, R.alpha(Ui.accent, o * 0.7f));
        }

        HudElement hovered = topAt(mouseX, mouseY);
        for (HudElement e : elements()) {
            float x = e.screenX(width), y = e.screenY(height);
            float h = e.hover.update(e == hovered || e == dragging ? 1f : 0f);
            float a = o * (e.enabled ? 1f : 0.35f);

            R.rounded(x - 2, y - 2, e.scaledW() + 4, e.scaledH() + 4, 4f, R.alpha(Ui.accentA(0x33), o * (0.4f + 0.6f * h)));
            R.roundedOutline(x - 2, y - 2, e.scaledW() + 4, e.scaledH() + 4, 4f, 1f,
                    R.alpha(R.lerpColor(0x667FD8DC, 0xFFFFFFFF, h), o));

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0f);
            GlStateManager.scale(e.scale(), e.scale(), 1f);
            e.draw(a, true);
            GlStateManager.popMatrix();

            if (h > 0.05f) {
                String label = e.name + (e.enabled ? "" : " (OFF)") + String.format("  x%.2f", e.scale());
                float ly = y - 12 < 0 ? y + e.scaledH() + 4 : y - 12;
                R.text(label, x, ly, R.alpha(R.TEXT, o * h));
            }
        }

        if (dragging == null) {
            String help = "ドラッグ: 移動  右クリック: ON/OFF  ホイール: 大きさ  ホイールクリック: 設定";
            String help2 = "R: 位置をリセット   Esc: 戻る";
            float w = Math.max(R.width(help), R.width(help2)) + 12;
            float by = height - 30;
            if (Glass.on()) Glass.surface(width / 2f - w / 2f, by, w, 24, 10f, o, 0f, 0f, 0.8f);
            else R.rounded(width / 2f - w / 2f, by, w, 24, 4f, R.alpha(0xCC0B1214, o));
            R.textCentered(help, width / 2f, by + 3, R.alpha(R.TEXT, o));
            R.textCentered(help2, width / 2f, by + 13, R.alpha(R.MUTED, o));
        }
        super.drawScreen(mouseX, mouseY, partialTicks);

        Decoration.over(this, o);
    }

    private HudElement topAt(int mx, int my) {
        List<HudElement> list = elements();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).contains(mx, my, width, height)) return list.get(i);
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        HudElement e = topAt(mouseX, mouseY);
        if (e != null) {
            if (button == 0 && e.movable()) {
                dragging = e;
                offX = mouseX - e.screenX(width);
                offY = mouseY - e.screenY(height);
            } else if (button == 1) {
                e.enabled = !e.enabled;
            } else if (button == 2) {
                mc.displayGuiScreen(new SettingsScreen(e, this));
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (dragging == null) return;
        float w = dragging.scaledW(), h = dragging.scaledH();
        float x = mouseX - offX, y = mouseY - offY;
        if (Math.abs(x) < SNAP) x = 0;
        if (Math.abs(x + w - width) < SNAP) x = width - w;
        if (Math.abs(y) < SNAP) y = 0;
        if (Math.abs(y + h - height) < SNAP) y = height - h;
        snapX = Math.abs(x + w / 2f - width / 2f) < SNAP;
        if (snapX) x = width / 2f - w / 2f;
        snapY = Math.abs(y + h / 2f - height / 2f) < SNAP;
        if (snapY) y = height / 2f - h / 2f;
        dragging.setScreenPos(x, y, width, height);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = null;
        snapX = snapY = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        HudElement e = topAt(mx, my);
        if (e != null) {
            float cxBefore = e.screenX(width) + e.scaledW() / 2f;
            float y = e.screenY(height);
            e.scaleSetting.set(e.scale() + (wheel > 0 ? 0.05 : -0.05));
            if (e.movable()) e.setScreenPos(cxBefore - e.scaledW() / 2f, y, width, height);
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_R) {
            for (HudElement e : elements()) {
                e.resetPosition();
                e.scaleSetting.reset();
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    public void onGuiClosed() {
        Config.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
