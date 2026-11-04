package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Active potion effects: icon, name, time. Rows slide in, and blink when about to expire. */
public class Potions extends HudElement {
    private static final ResourceLocation INV = new ResourceLocation("textures/gui/container/inventory.png");
    private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};
    private static final float ROW = 24f;
    private final Gui gui = new Gui();
    private final Map<Integer, Anim> rows = new HashMap<>();
    private float lastW = 90f, lastH = ROW;

    private final BoolSetting icons, blink, nameOn, colorBad;

    public Potions(float defX, float defY) {
        super("potions", "Potion Effects", "ポーション効果の一覧", defX, defY, LEFT);
        icons = add(new BoolSetting("icons", "アイコンを表示", true));
        nameOn = add(new BoolSetting("names", "効果の名前を表示", true));
        blink = add(new BoolSetting("blink", "切れそうになったら点滅", true));
        colorBad = add(new BoolSetting("bad", "悪い効果を赤くする", true));
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    /**
     * Name and time text of one effect. Building the name runs Minecraft's translation, which formats with
     * String.format; it is only redone when the level or the language / resources change, the time only when the
     * shown seconds change.
     */
    private static final class Texts {
        int amp = Integer.MIN_VALUE, resources = Integer.MIN_VALUE;
        String name;
        int secs = Integer.MIN_VALUE;
        boolean max;
        String time;
    }

    private final Map<Integer, Texts> texts = new HashMap<>();
    private final List<PotionEffect> effects = new ArrayList<>();
    private final List<Integer> ids = new ArrayList<>();

    private Texts texts(PotionEffect e, Potion potion) {
        Texts t = texts.get(e.getPotionID());
        if (t == null) {
            t = new Texts();
            texts.put(e.getPotionID(), t);
        }
        int amp = e.getAmplifier();
        if (amp != t.amp || t.resources != dev.fzkc.fzhud.util.Stamp.resources) {
            t.amp = amp;
            t.resources = dev.fzkc.fzhud.util.Stamp.resources;
            t.name = I18n.format(potion.getName()) + ROMAN[Math.max(0, Math.min(ROMAN.length - 1, amp))];
        }
        // "**:**" for endless effects, else the minutes:seconds Minecraft shows (it only depends on whole seconds)
        boolean max = e.getIsPotionDurationMax();
        int secs = e.getDuration() / 20;
        if (secs != t.secs || max != t.max || t.time == null) {
            t.secs = secs;
            t.max = max;
            t.time = max ? "**:**" : Potion.getDurationString(e);
        }
        return t;
    }

    @Override
    public void draw(float alpha, boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        List<PotionEffect> effects = this.effects;
        effects.clear();
        if (mc.thePlayer != null) {
            Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
            effects.addAll(active);
        }
        if (effects.isEmpty() && preview) {
            effects.add(new PotionEffect(Potion.moveSpeed.id, 20 * 90, 1));
            effects.add(new PotionEffect(Potion.regeneration.id, 20 * 8, 0));
        }

        float w = 60f;
        float y = 0f;
        for (PotionEffect e : effects) {
            Potion potion = Potion.potionTypes[e.getPotionID()];
            if (potion == null) continue;
            Anim a = rows.get(e.getPotionID());
            if (a == null) {
                a = new Anim(0f, 10f);
                rows.put(e.getPotionID(), a);
            }
            float t = a.update(1f);
            float rowAlpha = alpha * t;
            if (blink.get() && !e.getIsPotionDurationMax() && e.getDuration() < 200) {
                // blink faster as it runs out
                double phase = System.currentTimeMillis() / (e.getDuration() < 60 ? 90.0 : 180.0);
                rowAlpha *= 0.55f + 0.45f * (float) Math.abs(Math.sin(phase));
            }
            float slide = (1f - t) * -20f;

            Texts txt = texts(e, potion);
            String name = txt.name;
            String time = txt.time;

            GlStateManager.pushMatrix();
            GlStateManager.translate(slide, y, 0f);
            if (icons.get() && potion.hasStatusIcon()) {
                int idx = potion.getStatusIconIndex();
                GlStateManager.enableBlend();
                GlStateManager.color(1f, 1f, 1f, rowAlpha);
                mc.getTextureManager().bindTexture(INV);
                gui.drawTexturedModalRect(0, 2, idx % 8 * 18, 198 + idx / 8 * 18, 18, 18);
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
            int nameColor = colorBad.get() && potion.isBadEffect() ? 0xFFFF8A8A : R.TEXT;
            float tx = icons.get() ? 22 : 0;
            if (nameOn.get()) {
                R.text(name, tx, 3, R.alpha(nameColor, rowAlpha));
                R.text(time, tx, 13, R.alpha(R.MUTED, rowAlpha));
            } else {
                R.text(time, tx, 8, R.alpha(R.TEXT, rowAlpha));
            }
            GlStateManager.popMatrix();

            w = Math.max(w, (icons.get() ? 24 : 2) + (nameOn.get() ? Math.max(R.width(name), R.width(time)) : R.width(time)));
            y += ROW;
        }
        // forget rows for effects that ended so they animate in again next time
        if (!preview) {
            List<Integer> ids = this.ids;
            ids.clear();
            for (PotionEffect e : effects) ids.add(e.getPotionID());
            rows.keySet().retainAll(ids);
            texts.keySet().retainAll(ids);
        }
        lastW = w;
        lastH = Math.max(ROW, y);
    }
}
