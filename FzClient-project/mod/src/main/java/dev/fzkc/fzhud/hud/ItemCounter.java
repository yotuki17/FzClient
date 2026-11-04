package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Fmt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * How many arrows / golden apples / pearls / blocks... you carry (handy in Bed Wars and SkyWars).
 * Only items you actually have are listed. Three modes:
 *  - the chosen kinds (arrows, gapples, pearls, blocks total, potions, snowballs)
 *  - the item in your hand:
 *      holding any block  -> the total of ALL blocks you carry (wool, clay, wood... together)
 *      holding a bow      -> the total of your arrows
 *      anything else      -> how many of that item you carry in total
 *  - match the held item: holding a block shows all your blocks, holding a bow shows all your arrows
 */
public class ItemCounter extends HudElement {
    private static final int ARROW = 0, GAPPLE = 1, PEARL = 2, BLOCKS = 3, POTION = 4, SNOWBALL = 5, KINDS = 6;
    private static final ItemStack[] ICONS = {
            new ItemStack(Items.arrow), new ItemStack(Items.golden_apple), new ItemStack(Items.ender_pearl),
            new ItemStack(Blocks.wool, 1, 0), new ItemStack(Items.potionitem, 1, 8229), new ItemStack(Items.snowball)
    };
    private static final String[] NAMES = {"矢", "金リンゴ", "パール", "ブロック", "ポーション", "雪玉"};

    private final ChoiceSetting mode;
    private final BoolSetting[] show = new BoolSetting[KINDS];
    private final BoolSetting icons, names, horizontal;
    private final ColorSetting textColor, nameColor;

    /** One line: icon, label, count. Reused between frames. */
    private static final class Entry {
        ItemStack icon;
        String name;
        int count;
        /** Where its icon goes this frame. */
        float iconX, iconY;
    }

    private final List<Entry> pool = new ArrayList<>();
    private final List<Entry> rows = new ArrayList<>();
    private float lastW = 46, lastH = 22;

    public ItemCounter(float defX, float defY) {
        super("itemcounter", "Item Counter", "持っている矢・金リンゴ・パール・ブロックなどの数", defX, defY, RIGHT, false);
        withBackground();
        mode = add(new ChoiceSetting("mode", "表示するもの", 0, "選んだアイテム", "手に持っているアイテム", "手持ちに合わせる"));
        show[ARROW] = add(new BoolSetting("arrows", "矢", true));
        show[GAPPLE] = add(new BoolSetting("gapples", "金リンゴ", true));
        show[PEARL] = add(new BoolSetting("pearls", "エンダーパール", true));
        show[BLOCKS] = add(new BoolSetting("blocks", "ブロック（合計）", true));
        show[POTION] = add(new BoolSetting("potions", "ポーション", false));
        show[SNOWBALL] = add(new BoolSetting("snowballs", "雪玉・卵", false));
        icons = add(new BoolSetting("icons", "アイコンを表示", true));
        names = add(new BoolSetting("names", "名前を表示", false));
        horizontal = add(new BoolSetting("horizontal", "横に並べる", false));
        textColor = add(new ColorSetting("color", "数字の色", 0xFFFFFFFF));
        nameColor = add(new ColorSetting("namecolor", "名前の色", 0xFF9DB4B8));
    }

    private Entry entry(int i) {
        while (pool.size() <= i) pool.add(new Entry());
        return pool.get(i);
    }

    /** Counts every kind in the main inventory (hotbar included). */
    private static int[] countKinds(ItemStack[] inv) {
        int[] counts = new int[KINDS];
        for (ItemStack s : inv) {
            if (s == null) continue;
            Item it = s.getItem();
            if (it == Items.arrow) counts[ARROW] += s.stackSize;
            else if (it == Items.golden_apple) counts[GAPPLE] += s.stackSize;
            else if (it == Items.ender_pearl) counts[PEARL] += s.stackSize;
            else if (it == Items.potionitem) counts[POTION] += s.stackSize;
            else if (it == Items.snowball || it == Items.egg) counts[SNOWBALL] += s.stackSize;
            else if (it instanceof ItemBlock) counts[BLOCKS] += s.stackSize;
        }
        return counts;
    }

    /** Fills {@link #rows} with only the items you have. */
    private int collectedTick = Integer.MIN_VALUE;

    private void collect(boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        // the inventory only changes once per tick: reuse the rows for every frame of the same tick
        int tick = mc.thePlayer == null ? Integer.MIN_VALUE : mc.thePlayer.ticksExisted;
        if (!preview && tick != Integer.MIN_VALUE && tick == collectedTick) return;
        collectedTick = preview ? Integer.MIN_VALUE : tick;
        rows.clear();
        ItemStack[] inv = mc.thePlayer == null ? null : mc.thePlayer.inventory.mainInventory;

        if (mode.get() == 1) {
            // the item in your hand
            ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
            if (held == null || inv == null) {
                if (preview) add(0, ICONS[BLOCKS], NAMES[BLOCKS], 64);
                return;
            }
            Item heldItem = held.getItem();

            if (heldItem instanceof ItemBlock) {
                // any block in hand: the total of ALL blocks you carry (different colours / kinds together)
                int blocks = 0;
                for (ItemStack s : inv) {
                    if (s != null && s.getItem() instanceof ItemBlock) blocks += s.stackSize;
                }
                add(0, held, NAMES[BLOCKS], blocks);
                return;
            }
            if (heldItem instanceof ItemBow) {
                // bow in hand: how many arrows you have left (shown with the arrow icon)
                int arrows = 0;
                for (ItemStack s : inv) {
                    if (s != null && s.getItem() == Items.arrow) arrows += s.stackSize;
                }
                add(0, ICONS[ARROW], NAMES[ARROW], arrows);
                return;
            }

            // anything else: how many of that item you carry in total
            boolean anyDamage = heldItem.isDamageable(); // tools: ignore wear when matching
            int total = 0;
            for (ItemStack s : inv) {
                if (s != null && s.getItem() == heldItem && (anyDamage || s.getMetadata() == held.getMetadata())) {
                    total += s.stackSize;
                }
            }
            add(0, held, held.getDisplayName(), total);
            return;
        }

        if (mode.get() == 2) {
            // match the held item: block -> all blocks, bow -> all arrows (shown even at 0, so you notice you ran out)
            if (inv == null) {
                if (preview) {
                    add(0, ICONS[ARROW], NAMES[ARROW], 32);
                    add(1, ICONS[BLOCKS], NAMES[BLOCKS], 64);
                }
                return;
            }
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null) return;
            int[] counts = countKinds(inv);
            if (held.getItem() instanceof ItemBlock) add(0, ICONS[BLOCKS], NAMES[BLOCKS], counts[BLOCKS]);
            else if (held.getItem() == Items.bow) add(0, ICONS[ARROW], NAMES[ARROW], counts[ARROW]);
            return;
        }

        int[] counts;
        if (inv == null) {
            if (!preview) return;
            counts = new int[KINDS];
            counts[ARROW] = 32; counts[GAPPLE] = 3; counts[PEARL] = 2; counts[BLOCKS] = 64;
        } else {
            counts = countKinds(inv);
        }
        for (int k = 0; k < KINDS; k++) {
            if (show[k].get() && counts[k] > 0) add(rows.size(), ICONS[k], NAMES[k], counts[k]);
        }
        if (rows.isEmpty() && preview) {
            add(0, ICONS[ARROW], NAMES[ARROW], 32);
            add(1, ICONS[BLOCKS], NAMES[BLOCKS], 64);
        }
    }

    private void add(int i, ItemStack icon, String name, int count) {
        Entry e = entry(i);
        e.icon = icon;
        e.name = name;
        e.count = count;
        rows.add(e);
    }

    /** Hidden while there is nothing to show (it fades back in when you pick something up). */
    @Override
    public boolean active() {
        collect(false);
        return !rows.isEmpty();
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    @Override
    public void draw(float alpha, boolean preview) {
        collect(preview);
        if (rows.isEmpty()) return;
        boolean ic = icons.get(), nm = names.get(), hz = horizontal.get();
        float rowH = ic ? 18 : 11;
        float iconW = ic ? 18 : 0;

        // measure
        float maxCell = 0, totalW = 0;
        for (Entry e : rows) {
            float w = iconW + R.width(Fmt.num(e.count)) + (nm ? R.width(e.name) + 4 : 0);
            maxCell = Math.max(maxCell, w);
            totalW += w + 8;
        }
        lastW = hz ? totalW + 2 : maxCell + 10;
        lastH = hz ? rowH + 4 : 4 + rows.size() * rowH;
        bg(0, 0, lastW, lastH, alpha);

        Minecraft mc = Minecraft.getMinecraft();
        int tc = R.alpha(textColor.get(), alpha), nc = R.alpha(nameColor.get(), alpha);
        float x = 5, y = 2;
        float textY = ic ? 4.5f : 1.5f;
        // texts first, then all icons with the item lighting switched on once (icons and texts never overlap)
        for (Entry e : rows) {
            float cx = x;
            if (ic) {
                e.iconX = cx - 1;
                e.iconY = y + 1;
                cx += iconW;
            }
            if (nm) {
                R.text(e.name, cx, y + textY, nc);
                cx += R.width(e.name) + 4;
            }
            String n = Fmt.num(e.count);
            R.text(n, cx, y + textY, tc);
            cx += R.width(n);
            if (hz) x = cx + 8;
            else y += rowH;
        }
        if (ic) {
            RenderHelper.enableGUIStandardItemLighting();
            for (Entry e : rows) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(e.iconX, e.iconY, 0);
                mc.getRenderItem().renderItemAndEffectIntoGUI(e.icon, 0, 0);
                GlStateManager.popMatrix();
            }
            RenderHelper.disableStandardItemLighting();
        }
        GlStateManager.enableBlend();
    }
}
