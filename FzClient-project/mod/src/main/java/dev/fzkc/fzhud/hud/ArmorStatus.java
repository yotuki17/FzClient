package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Fmt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Armour pieces and held tool with remaining durability. */
public class ArmorStatus extends HudElement {
    private static final float ROW = 17f;
    private float lastW = 50f, lastH = ROW * 4;

    private final BoolSetting held, percent, colored;

    public ArmorStatus(float defX, float defY) {
        super("armor", "Armor Status", "防具と道具の残り耐久", defX, defY, RIGHT);
        held = add(new BoolSetting("held", "手に持っている道具も表示", true));
        percent = add(new BoolSetting("percent", "耐久を%で表示", false));
        colored = add(new BoolSetting("colored", "残りに応じて色を変える", true));
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    private int color(ItemStack s) {
        if (!colored.get() || !s.isItemStackDamageable()) return R.TEXT;
        float left = 1f - (float) s.getItemDamage() / s.getMaxDamage();
        if (left > 0.6f) return 0xFF55FF55;
        if (left > 0.3f) return 0xFFFFFF55;
        if (left > 0.15f) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    private String label(ItemStack s) {
        if (s.isItemStackDamageable()) {
            int left = s.getMaxDamage() - s.getItemDamage();
            return percent.get() ? Fmt.percent(Math.round(100f * left / s.getMaxDamage())) : Fmt.num(left);
        }
        return s.stackSize > 1 ? Fmt.num(s.stackSize) : "";
    }

    private final List<ItemStack> items = new ArrayList<>();
    private String[] labels = new String[8];

    @Override
    public void draw(float alpha, boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        List<ItemStack> items = this.items;
        items.clear();
        if (mc.thePlayer != null) {
            for (int i = 3; i >= 0; i--) {
                ItemStack s = mc.thePlayer.inventory.armorItemInSlot(i);
                if (s != null) items.add(s);
            }
            ItemStack held = mc.thePlayer.getHeldItem();
            if (this.held.get() && held != null && held.isItemStackDamageable()) items.add(held);
        }
        if (items.isEmpty() && preview) {
            items.add(new ItemStack(Items.diamond_helmet));
            items.add(new ItemStack(Items.diamond_chestplate));
            items.add(new ItemStack(Items.iron_leggings));
            items.add(new ItemStack(Items.iron_boots));
        }
        int n = items.size();
        if (labels.length < n) labels = new String[n];
        float w = 20f;
        for (int i = 0; i < n; i++) {
            labels[i] = label(items.get(i));
            w = Math.max(w, 20 + R.width(labels[i]));
        }
        lastW = w;
        lastH = Math.max(ROW, items.size() * ROW);
        if (alpha < 0.05f) return;

        // right-aligned: text on the left of the icon (texts and icons never overlap, so all texts go first
        // and the icons follow with the item lighting switched on once, like vanilla's inventory does)
        float y = 0;
        for (int i = 0; i < n; i++) {
            String t = labels[i];
            R.text(t, w - 18 - R.width(t), y + 4.5f, R.alpha(color(items.get(i)), alpha));
            y += ROW;
        }
        if (n == 0) return;
        GlStateManager.pushMatrix();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        y = 0;
        for (int i = 0; i < n; i++) {
            mc.getRenderItem().renderItemAndEffectIntoGUI(items.get(i), (int) (w - 16), (int) y);
            y += ROW;
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.popMatrix();
    }
}
