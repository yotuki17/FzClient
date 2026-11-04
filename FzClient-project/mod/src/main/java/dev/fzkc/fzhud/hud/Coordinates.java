package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/** X / Y / Z, facing and biome. Strings are only rebuilt when the block position or facing changes. */
public class Coordinates extends HudElement {
    private static final String[] DIRS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] AXIS = {"+Z", "+Z -X", "-X", "-X -Z", "-Z", "-Z +X", "+X", "+X +Z"};

    private final BoolSetting showDir, showBiome;
    private final ColorSetting labelColor, valueColor;

    private int cx = Integer.MIN_VALUE, cy, cz, cdir = -1;
    private String sx = "", sy = "", sz = "", sdir = "", sbiome = "";
    private int lines = 3;
    private float textW = 60;

    public Coordinates(float defX, float defY) {
        super("coords", "Coordinates", "今いる座標・向き・バイオーム", defX, defY, LEFT, false);
        withBackground();
        showDir = add(new BoolSetting("direction", "向きを表示", true));
        showBiome = add(new BoolSetting("biome", "バイオームを表示", false));
        labelColor = add(new ColorSetting("label", "X/Y/Z の色", 0xFF5CE1E6));
        valueColor = add(new ColorSetting("value", "数字の色", 0xFFFFFFFF));
    }

    @Override public float width() { return Math.max(70, textW + 12); }
    @Override public float height() { return 6 + lines * 10; }

    private void refresh(boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity e = mc.getRenderViewEntity();
        if (e == null || mc.theWorld == null) {
            if (preview) { sx = "123"; sy = "64"; sz = "-456"; sdir = "N (-Z)"; sbiome = "Plains"; }
            return;
        }
        int x = MathHelper.floor_double(e.posX), y = MathHelper.floor_double(e.getEntityBoundingBox().minY),
                z = MathHelper.floor_double(e.posZ);
        int dir = MathHelper.floor_double(e.rotationYaw * 8.0F / 360.0F + 0.5D) & 7;
        if (x == cx && y == cy && z == cz && dir == cdir) return;
        boolean moved = x != cx || z != cz;
        cx = x; cy = y; cz = z; cdir = dir;
        sx = Integer.toString(x);
        sy = Integer.toString(y);
        sz = Integer.toString(z);
        sdir = DIRS[dir] + " (" + AXIS[dir] + ")";
        if (moved || sbiome.isEmpty()) {
            BlockPos pos = new BlockPos(x, y, z);
            sbiome = mc.theWorld.isBlockLoaded(pos) ? mc.theWorld.getBiomeGenForCoords(pos).biomeName : "";
        }
    }

    @Override
    public void draw(float alpha, boolean preview) {
        refresh(preview);
        int lc = R.alpha(labelColor.get(), alpha), vc = R.alpha(valueColor.get(), alpha);
        lines = 3 + (showDir.get() ? 1 : 0) + (showBiome.get() && !sbiome.isEmpty() ? 1 : 0);
        bg(0, 0, width(), height(), alpha);
        float y = 4;
        float w = 0;
        w = Math.max(w, row("X ", sx, y, lc, vc)); y += 10;
        w = Math.max(w, row("Y ", sy, y, lc, vc)); y += 10;
        w = Math.max(w, row("Z ", sz, y, lc, vc)); y += 10;
        if (showDir.get()) { w = Math.max(w, row("", sdir, y, lc, vc)); y += 10; }
        if (showBiome.get() && !sbiome.isEmpty()) w = Math.max(w, row("", sbiome, y, lc, vc));
        textW = w;
    }

    private float row(String label, String value, float y, int lc, int vc) {
        float x = 6;
        if (!label.isEmpty()) {
            R.text(label, x, y, lc);
            x += R.width(label);
        }
        R.text(value, x, y, vc);
        return x - 6 + R.width(value);
    }
}
