package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/** Right Shift: two big buttons in the middle of the screen. */
public class MenuScreen extends FzScreen {
    private final Anim open = new Anim(0f, 12f);
    private final Anim hMods = new Anim(0f, 14f), hEdit = new Anim(0f, 14f);
    private static final float BW = 150, BH = 44;

    /** Middle of the two cards, moved left / right by the UI page's "メニューの位置". */
    private float mid() { return Ui.menuX(vw, BW * 2 + 16) + BW + 8; }
    private float modsX() { return mid() - BW - 8; }
    private float editX() { return mid() + 8; }
    private float by() { return vh / 2f - BH / 2f + 10; }

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        float cx = mid(), cy = vh / 2f;
        GlStateManager.pushMatrix();
        float s = 0.94f + 0.06f * o;
        GlStateManager.translate(cx, cy, 0);
        GlStateManager.scale(s, s, 1f);
        GlStateManager.translate(-cx, -cy, 0);

        Ui.title(cx - 80, by() - 92, 160, R.alpha(Ui.accent, o));
        R.textCentered("Created by Fuzuki_flan17", cx, by() - 22, R.alpha(Ui.MUTED, o));
        card(modsX(), "MODS", "Tweak the config",
                hMods.update(Ui.inside(mx, my, modsX(), by(), BW, BH) ? 1 : 0), o);
        card(editX(), "Edit HUD", "Tweak the HUD",
                hEdit.update(Ui.inside(mx, my, editX(), by(), BW, BH) ? 1 : 0), o);
        GlStateManager.popMatrix();
        R.textCentered("Esc: 閉じる", cx, vh - 18, R.alpha(Ui.MUTED, o));

        Decoration.corner(vw, vh, o);
    }

    private void card(float x, String title, String sub, float h, float o) {
        float y = by() - h * 2;
        Ui.bigCard(x, y, BW, BH, h, o);
        R.textScaledCentered(title, x + BW / 2f, y + 9, 1.5f, R.alpha(Ui.text, o));
        R.textCentered(sub, x + BW / 2f, y + 28, R.alpha(Ui.MUTED, o));
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        if (Ui.inside(mx, my, modsX(), by() - 4, BW, BH + 4)) mc.displayGuiScreen(new ModsScreen(this));
        else if (Ui.inside(mx, my, editX(), by() - 4, BW, BH + 4)) mc.displayGuiScreen(new EditScreen(this));
    }

    @Override
    protected void key(char c, int keyCode) {
        if (keyCode == 1) mc.displayGuiScreen(null);
    }

}
