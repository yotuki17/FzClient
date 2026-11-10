package dev.fzkc.fzhud.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.stats.Stats;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Height Limit: shows how far the build height limit is (text under the crosshair, and a see-through plane at the
 * limit when you get close). Hypixel does not say where the limit is, so it is learned: the first time
 * "Build height limit reached!" comes after you tried to place a block, the highest Y you may build at is saved
 * for that game and map (config/fzclient-heightlimits.json) and shown from then on, also in later games there.
 * Shared: what FzClient players find is sent to the FzClient server (proven like the cosmetics, so nobody can send
 * in someone else's name) and everyone gets the list, so a map is known even before you hit its limit yourself.
 * Your own value comes first; the shared one is used where you have none.
 */
public class HeightLimit {
    private static final String FILE = "fzclient-heightlimits.json", SHARED_FILE = "fzclient-heightlimits-shared.json";
    /** What you found yourself. */
    private static final Map<String, Integer> limits = new ConcurrentHashMap<>();
    /** What FzClient players found (from the server, kept in SHARED_FILE for offline use). */
    private static final Map<String, Integer> shared = new ConcurrentHashMap<>();
    /** Values already sent in this session. */
    private static final Map<String, Integer> reported = new ConcurrentHashMap<>();
    private static final LinkedBlockingQueue<Boolean> poke = new LinkedBlockingQueue<>();
    private static Thread sync;
    private static long pokedAt;
    private static boolean loaded;

    /** Y of the last block you tried to place, and when. */
    private int lastPlaceY;
    private long lastPlaceAt;

    private static String key() {
        if (Stats.lobby || Stats.map == null) return null;
        return (Stats.current != null ? Stats.current.name() : "?") + ":" + Stats.map;
    }

    /** The highest Y you may place a block at here, or null when not known yet. */
    public static Integer limit() {
        load();
        String k = key();
        if (k == null) return null;
        Integer own = limits.get(k);
        if (own != null) return own;
        Integer s = Modules.heightLimitShare.get() ? shared.get(k) : null;
        long now = System.currentTimeMillis();
        if (s == null && Modules.heightLimitShare.get() && now - pokedAt > 5 * 60_000L) {   // a map nobody had: maybe someone has found it since
            pokedAt = now;
            poke.offer(Boolean.TRUE);
        }
        return s;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Modules.heightLimit.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null || !mc.gameSettings.keyBindUseItem.isKeyDown()) return;
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.getBlockPos() == null) return;
        if (mc.thePlayer.getHeldItem() == null || !(mc.thePlayer.getHeldItem().getItem() instanceof ItemBlock)) return;
        lastPlaceY = hit.getBlockPos().offset(hit.sideHit).getY();
        lastPlaceAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || !Modules.heightLimit.enabled) return;
        String s = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (s == null || !s.trim().startsWith("Build height limit reached!")) return;
        if (System.currentTimeMillis() - lastPlaceAt > 2000) return;
        String k = key();
        if (k == null) return;
        load();
        int y = lastPlaceY - 1;
        Integer was = limits.get(k);
        if (was != null && was <= y) return;
        limits.put(k, y);
        save();
        poke.offer(Boolean.TRUE);   // share it
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("§8[§bFzClient§8]§r §7"
                    + Lang.t("高さ上限を覚えました") + ": " + "§fY " + y + " §7(" + Stats.map + ")"));
        }
    }

    /** Blocks you can still stack under you before the limit (0 = at the limit), or MIN_VALUE when not known. */
    private static int left(Minecraft mc, Integer lim) {
        if (lim == null || mc.thePlayer == null) return Integer.MIN_VALUE;
        int feet = MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY + 0.001);
        return lim - feet + 1;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !Modules.heightLimit.enabled || !Modules.heightLimitText.get()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        int n = left(mc, limit());
        if (n == Integer.MIN_VALUE || n > Modules.heightLimitWarn.get() && !Modules.heightLimitAlways.get()) return;
        String t = n <= 0 ? "§c§l" + Lang.t("高さ上限") : (n <= 2 ? "§c" : n <= 5 ? "§e" : "§7") + Lang.t("上限まで") + " " + "§f" + n;
        ScaledResolution sr = event.resolution;
        R.textCentered(t, sr.getScaledWidth() / 2f, sr.getScaledHeight() / 2f + 10, 0xFFFFFFFF);
    }

    @SubscribeEvent
    public void onWorld(RenderWorldLastEvent event) {
        if (!Modules.heightLimit.enabled || !Modules.heightLimitPlane.get()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Integer lim = limit();
        int n = left(mc, lim);
        if (n == Integer.MIN_VALUE || n > Modules.heightLimitWarn.get()) return;
        RenderManager rm = mc.getRenderManager();
        double y = lim + 1 - rm.viewerPosY + 0.002;
        int px = MathHelper.floor_double(mc.thePlayer.posX), pz = MathHelper.floor_double(mc.thePlayer.posZ);
        int r = 4;
        double x0 = px - r - rm.viewerPosX, x1 = px + r + 1 - rm.viewerPosX;
        double z0 = pz - r - rm.viewerPosZ, z1 = pz + r + 1 - rm.viewerPosZ;
        int c = Modules.heightLimitColor.get();
        float cr = (c >> 16 & 255) / 255f, cg = (c >> 8 & 255) / 255f, cb = (c & 255) / 255f;
        // stronger the closer you are
        float a = 0.12f + 0.25f * (1f - Math.max(0, n) / (float) Math.max(1, Modules.heightLimitWarn.get()));

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.depthMask(false);
        GL11.glColor4f(cr, cg, cb, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x0, y, z0);
        GL11.glVertex3d(x1, y, z0);
        GL11.glVertex3d(x1, y, z1);
        GL11.glVertex3d(x0, y, z1);
        GL11.glEnd();
        GL11.glColor4f(cr, cg, cb, Math.min(1f, a * 2.2f));
        GL11.glLineWidth(1.5f);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i <= 2 * r + 1; i++) {
            GL11.glVertex3d(x0 + i, y, z0);
            GL11.glVertex3d(x0 + i, y, z1);
            GL11.glVertex3d(x0, y, z0 + i);
            GL11.glVertex3d(x1, y, z0 + i);
        }
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ file

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), FILE);
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        read(new File(file().getParentFile(), SHARED_FILE), shared);
        sync = new Thread(HeightLimit::syncLoop, "FzClient-HeightLimits");
        sync.setDaemon(true);
        sync.start();
        File f = file();
        if (!f.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonObject o = new JsonParser().parse(r).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                try { limits.put(e.getKey(), e.getValue().getAsInt()); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        }
    }

    private static void read(File f, Map<String, Integer> into) {
        if (!f.isFile()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonObject o = new JsonParser().parse(r).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                try { into.put(e.getKey(), e.getValue().getAsInt()); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ sharing

    /** Gets the shared list and sends what you found: at the start, when you find one, and every 30 minutes. */
    private static void syncLoop() {
        while (true) {
            try {
                if (Modules.heightLimit.enabled && Modules.heightLimitShare.get()) {
                    fetch();
                    report();
                }
            } catch (Throwable ignored) {
            }
            try {
                poke.poll(30, TimeUnit.MINUTES);
                Thread.sleep(3000);   // several pokes close together: one round
                poke.clear();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void fetch() throws Exception {
        JsonObject r = dev.fzkc.fzhud.integration.FzUsers.post("/hl/all", new JsonObject());
        if (r == null || !r.has("limits") || !r.get("limits").isJsonObject()) return;
        JsonObject o = r.getAsJsonObject("limits");
        for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
            try { shared.put(e.getKey(), e.getValue().getAsInt()); } catch (Exception ignored) { }
        }
        File f = new File(file().getParentFile(), SHARED_FILE);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(o.toString());
        }
    }

    /** Sends your values the shared list does not have yet (proven with a Mojang session join, like the cosmetics). */
    private static void report() throws Exception {
        JsonObject send = new JsonObject();
        int n = 0;
        for (Map.Entry<String, Integer> e : limits.entrySet()) {
            Integer v = e.getValue();
            if (v.equals(shared.get(e.getKey())) || v.equals(reported.get(e.getKey()))) continue;
            send.addProperty(e.getKey(), v);
            if (++n >= 50) break;
        }
        if (n == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        com.mojang.authlib.GameProfile profile = mc.getSession().getProfile();
        String token = mc.getSession().getToken();
        if (profile == null || profile.getId() == null || token == null || token.length() < 20) return;   // offline account
        String serverId = new BigInteger(130, new SecureRandom()).toString(16);
        mc.getSessionService().joinServer(profile, token, serverId);
        JsonObject body = new JsonObject();
        body.addProperty("name", profile.getName());
        body.addProperty("serverId", serverId);
        body.add("limits", send);
        JsonObject r = dev.fzkc.fzhud.integration.FzUsers.post("/hl/report", body);
        if (r == null || !r.has("ok")) return;
        for (Map.Entry<String, com.google.gson.JsonElement> e : send.entrySet()) reported.put(e.getKey(), e.getValue().getAsInt());
        fetch();   // the list with yours in it
    }

    private static void save() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : limits.entrySet()) o.addProperty(e.getKey(), e.getValue());
        File f = file();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(o.toString());
        } catch (Exception ignored) {
        }
    }
}
