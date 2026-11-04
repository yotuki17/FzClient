package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Locale;

/**
 * Target HUD: the health of the last player (or mob) you damaged, for a few seconds after the hit - melee
 * (confirmed by the target's hurt animation, so NPCs and hits that did nothing don't count) and bow hits
 * (the arrow "ding" or Hypixel's "Name is on X HP!").
 * Name, head, a smooth health bar (with a lighter trail that shows what was just lost), the numbers and
 * optionally the distance. Nothing here asks the server for anything: it only reads what your game already knows.
 */
public class TargetHud extends HudElement {
    private static final float PAD = 5f, HEAD = 22f, BAR_H = 6f, HEIGHT = 34f;

    private final SliderSetting showTime;
    private final BoolSetting head, number, distance, dynamic;
    private final ColorSetting barColor;

    private EntityLivingBase target;
    /** System.nanoTime() of your last hit on the target. */
    private long hitAt;
    /** True right after the target changed, so the bar starts at the real health instead of sliding there. */
    private boolean fresh;
    /** Smoothed health fractions: the bar itself, and the slower trail behind it. */
    private float front, trail;
    private long lastFrame;
    private float lastW = 120, lastH = HEIGHT;

    // texts are rebuilt only when the numbers change (this draws every frame)
    private int hpKey = Integer.MIN_VALUE;
    private String hpText = "";
    private int distKey = Integer.MIN_VALUE;
    private String distText = "";

    public TargetHud(float defX, float defY) {
        super("targethud", "Target HUD", "殴った相手の体力", defX, defY, LEFT, false);
        withBackground();
        showTime = add(new SliderSetting("time", "表示する秒数", 1, 15, 1, 4, "秒"));
        head = add(new BoolSetting("head", "頭のアイコンを表示", true));
        number = add(new BoolSetting("number", "体力の数字を表示", true));
        distance = add(new BoolSetting("distance", "距離を表示", false));
        dynamic = add(new BoolSetting("dynamic", "体力に応じて色を変える", true));
        barColor = add(new ColorSetting("color", "バーの色", 0xFF5CE1E6));
        MinecraftForge.EVENT_BUS.register(this);
    }

    // ---- only real damage counts: NPCs (lobby characters) never take damage, so they never show up.
    /** Something you hit in melee, waiting for its hurt animation to confirm that the hit did damage. */
    private EntityLivingBase pending;
    private long pendingAt;
    /** hurtTime of every living entity last tick, and when each one was last hurt (System.nanoTime). */
    private final java.util.WeakHashMap<EntityLivingBase, Integer> lastHurtTime = new java.util.WeakHashMap<EntityLivingBase, Integer>();
    private final java.util.WeakHashMap<EntityLivingBase, Long> hurtAt = new java.util.WeakHashMap<EntityLivingBase, Long>();
    /** "Name is on 14.5 HP!" - Hypixel's message after your arrow hits someone. */
    private static final java.util.regex.Pattern BOW_HP = java.util.regex.Pattern.compile("^([A-Za-z0-9_]{1,16}) is on [0-9.]+ HP!$");

    /** Your own melee attack (fires on the client for the player you control): wait for proof of damage. */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.entityPlayer != mc.thePlayer) return;
        if (!(event.target instanceof EntityLivingBase)) return;
        pending = (EntityLivingBase) event.target;
        pendingAt = System.nanoTime();
    }

    /** Watches who gets hurt; confirms a pending melee hit when its target flinches. */
    @SubscribeEvent
    public void onTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        long now = System.nanoTime();
        for (net.minecraft.entity.Entity e : mc.theWorld.loadedEntityList) {
            if (!(e instanceof EntityLivingBase) || e == mc.thePlayer) continue;
            EntityLivingBase l = (EntityLivingBase) e;
            Integer before = lastHurtTime.get(l);
            if (l.hurtTime > 0 && (before == null || l.hurtTime > before)) hurtAt.put(l, now);   // just got hurt
            lastHurtTime.put(l, l.hurtTime);
        }
        if (pending != null) {
            Long h = hurtAt.get(pending);
            if (h != null && h >= pendingAt - 100_000_000L) {
                show(pending, now);
                pending = null;
            } else if (now - pendingAt > 700_000_000L) {
                pending = null;   // no damage (NPC, invulnerable, missed): don't show
            }
        }
    }

    /** The arrow "ding": the player who was just hurt is the one your arrow hit. */
    @SubscribeEvent
    public void onSound(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
        if (!"random.successful_hit".equals(event.name)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        long now = System.nanoTime();
        EntityLivingBase best = null;
        double bestScore = Double.MAX_VALUE;
        net.minecraft.util.Vec3 look = mc.thePlayer.getLook(1f);
        for (net.minecraft.entity.Entity e : mc.theWorld.loadedEntityList) {
            if (!(e instanceof net.minecraft.entity.player.EntityPlayer) || e == mc.thePlayer) continue;
            EntityLivingBase l = (EntityLivingBase) e;
            Long h = hurtAt.get(l);
            boolean hurtNow = l.hurtTime > 0 && l.hurtTime >= l.maxHurtTime - 1;
            if (!hurtNow && (h == null || now - h > 400_000_000L)) continue;
            // most likely the one closest to where you are looking
            net.minecraft.util.Vec3 to = new net.minecraft.util.Vec3(l.posX - mc.thePlayer.posX, l.posY + l.height / 2 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight(), l.posZ - mc.thePlayer.posZ).normalize();
            double score = 1.0 - look.dotProduct(to);
            if (score < bestScore) {
                bestScore = score;
                best = l;
            }
        }
        if (best != null) show(best, now);
    }

    /** Hypixel's "Name is on X HP!" after a bow hit names the target exactly. */
    @SubscribeEvent
    public void onChat(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
        if (event.type == 2) return;
        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText()).trim();
        java.util.regex.Matcher m = BOW_HP.matcher(plain);
        if (!m.matches()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        net.minecraft.entity.player.EntityPlayer p = mc.theWorld.getPlayerEntityByName(m.group(1));
        if (p != null && p != mc.thePlayer) show(p, System.nanoTime());
    }

    private void show(EntityLivingBase hit, long now) {
        if (hit != target) fresh = true;
        target = hit;
        hitAt = now;
    }

    /** Shown for a few seconds after a hit that did damage, as long as the target is still around. */
    @Override
    public boolean active() {
        EntityLivingBase t = target;
        if (t == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || t.isDead || t.worldObj != mc.theWorld) return false;
        return (System.nanoTime() - hitAt) / 1.0e9f < showTime.get();
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    private static String num(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void draw(float alpha, boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityLivingBase t = preview ? null : target;
        if (t == null && !preview) return;

        // ---- what to show (a sample while you are placing it in the HUD editor)
        String name;
        float hp, max, dist;
        if (t == null) {
            name = "Player";
            hp = 15f;
            max = 20f;
            dist = 3.2f;
        } else {
            name = t.getDisplayName().getFormattedText();
            hp = Math.max(0f, t.getHealth());
            max = Math.max(1f, t.getMaxHealth());
            dist = mc.thePlayer == null ? 0f : t.getDistanceToEntity(mc.thePlayer);
        }
        float frac = Math.min(1f, hp / max);

        // ---- smoothing
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1.0e9f);
        lastFrame = now;
        if (fresh || t == null) {
            front = trail = frac;
            fresh = false;
        } else {
            front += (frac - front) * (1f - (float) Math.exp(-16f * dt));
            if (frac >= trail) trail = frac;
            else trail += (frac - trail) * (1f - (float) Math.exp(-3f * dt));
        }

        // ---- texts
        int key = Math.round(hp * 10f) * 1000 + Math.round(max * 10f);
        if (key != hpKey) {
            hpKey = key;
            hpText = num(hp) + " / " + num(max);
        }
        int dk = Math.round(dist * 10f);
        if (dk != distKey) {
            distKey = dk;
            distText = String.format(Locale.ROOT, "%.1f", dist) + "m";
        }

        // ---- head (players only)
        ResourceLocation skin = null;
        if (head.get()) {
            if (t == null) skin = mc.thePlayer == null ? null : mc.thePlayer.getLocationSkin();
            else if (t instanceof AbstractClientPlayer) skin = ((AbstractClientPlayer) t).getLocationSkin();
        }
        float headW = skin != null ? HEAD + 5f : 0f;

        // ---- size
        float w = Math.max(116f, PAD * 2 + headW + Math.max(R.width(name), 76f));
        lastW = w;
        lastH = HEIGHT;
        bg(0, 0, w, HEIGHT, alpha);

        if (skin != null) {
            float hy = (HEIGHT - HEAD) / 2f;
            mc.getTextureManager().bindTexture(skin);
            GlStateManager.enableBlend();
            GlStateManager.color(1f, 1f, 1f, alpha);
            Gui.drawScaledCustomSizeModalRect((int) PAD, (int) hy, 8, 8, 8, 8, (int) HEAD, (int) HEAD, 64f, 64f);
            Gui.drawScaledCustomSizeModalRect((int) PAD, (int) hy, 40, 8, 8, 8, (int) HEAD, (int) HEAD, 64f, 64f);
            GlStateManager.color(1f, 1f, 1f, 1f);
        }

        float x0 = PAD + headW;
        R.text(name, x0, PAD, R.alpha(0xFFFFFFFF, alpha));

        // ---- health bar
        float by = 16f, bw = w - x0 - PAD;
        int col;
        if (dynamic.get()) {
            col = frac < 0.5f ? R.lerpColor(0xFFFF5555, 0xFFFFAA00, frac * 2f)
                    : R.lerpColor(0xFFFFAA00, 0xFF55FF55, (frac - 0.5f) * 2f);
        } else {
            col = barColor.get();
        }
        R.rounded(x0, by, bw, BAR_H, 2f, R.alpha(0xFF0A1114, alpha));
        if (trail > 0.005f) R.rounded(x0, by, Math.max(2f, bw * trail), BAR_H, 2f, R.alpha(0xFFE6EEF0, alpha * 0.55f));
        if (front > 0.005f) R.rounded(x0, by, Math.max(2f, bw * front), BAR_H, 2f, R.alpha(col, alpha));

        // ---- numbers
        float ty = 24.5f;
        if (number.get()) R.text(hpText, x0, ty, R.alpha(0xFFB9C6C9, alpha));
        if (distance.get()) R.text(distText, w - PAD - R.width(distText), ty, R.alpha(0xFF8FA5AA, alpha));
    }
}
