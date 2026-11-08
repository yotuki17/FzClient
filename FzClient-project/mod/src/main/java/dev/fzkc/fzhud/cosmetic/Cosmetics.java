package dev.fzkc.fzhud.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.integration.FzUsers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FzClient cosmetics: capes, auras and halos.
 * <ul>
 *   <li>What you wear comes from the Cosmetics page and is published to the FzClient server, so other FzClient
 *       users draw it on you. Publishing proves who you are the same way joining a server does (Mojang's session
 *       "join" check), so nobody can change what someone else wears.</li>
 *   <li>What the other FzClient players in your lobby wear is fetched from the same server.</li>
 *   <li>Nothing is drawn on invisible players.</li>
 * </ul>
 * Rendering: capes through the vanilla cape layer ({@link #cape}), auras in {@link AuraFx}.
 */
public final class Cosmetics {
    private static final Map<String, ResourceLocation> BUILTIN = new HashMap<String, ResourceLocation>();
    static {
        for (String id : new String[]{"fzclient", "aurora", "midnight", "sakura", "ocean", "ember", "galaxy", "neon", "royal"}) {
            BUILTIN.put(id, new ResourceLocation("fzclient", "textures/cosmetics/cape_" + id + ".png"));
        }
    }

    /** What the other FzClient players wear, by UUID. */
    private static final Map<UUID, Equip> others = new ConcurrentHashMap<UUID, Equip>();

    private static Thread thread;

    public Cosmetics() {}

    // ------------------------------------------------------------------ who wears what

    /** What you have picked on the Cosmetics page. */
    public static Equip mine() {
        Equip e = new Equip();
        if (!Modules.cosmetics.enabled) return e;
        e.cape = Equip.CAPES[clamp(Modules.cosCape.get(), Equip.CAPES.length)];
        e.aura = Equip.AURAS[clamp(Modules.cosAura.get(), Equip.AURAS.length)];
        e.halo = Equip.HALOS[clamp(Modules.cosHalo.get(), Equip.HALOS.length)];
        return e;
    }

    private static int clamp(int i, int n) {
        return i < 0 || i >= n ? 0 : i;
    }

    private static boolean isMe(EntityPlayer p) {
        Minecraft mc = Minecraft.getMinecraft();
        if (p == mc.thePlayer) return true;
        UUID me = mc.getSession() != null && mc.getSession().getProfile() != null ? mc.getSession().getProfile().getId() : null;
        return me != null && me.equals(p.getUniqueID());
    }

    /**
     * Invisible: the invisible flag the server sends, or an Invisibility effect the client knows about (your own
     * potions always, other players' when the server sends their effects). Either way no cosmetics are drawn.
     */
    public static boolean invisible(EntityPlayer p) {
        try {
            return p.isInvisible() || p.isPotionActive(net.minecraft.potion.Potion.invisibility);
        } catch (Throwable t) {
            return p.isInvisible();
        }
    }

    /** What this player wears, or null for nothing. Never anything on invisible players. */
    public static Equip of(EntityPlayer p) {
        if (p == null || invisible(p)) return null;
        if (isMe(p)) {
            Equip e = mine();
            return e.empty() ? null : e;
        }
        if (!Modules.cosmetics.enabled || !Modules.cosOthers.get() || !FzUsers.uses(p.getUniqueID())) return null;
        return others.get(p.getUniqueID());
    }

    /** The cape to draw for this player, or null to keep the normal one (called from the cape mixin). */
    public static ResourceLocation cape(AbstractClientPlayer p) {
        Equip e = of(p);
        if (e == null || e.cape.isEmpty()) return null;
        return BUILTIN.get(e.cape);
    }

    /** The texture of a built-in cape id (null for none). */
    public static ResourceLocation capeTexture(String id) {
        return BUILTIN.get(id);
    }


    // ------------------------------------------------------------------ tick: layers, auras, sync

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (thread == null) {
            thread = new Thread(Cosmetics::loop, "FzClient-Cosmetics");
            thread.setDaemon(true);
            thread.start();
        }
    }

    // ------------------------------------------------------------------ server

    private static void loop() {
        String sent = null;
        String changedTo = null;
        long changedAt = 0, fetchedAt = 0, failUntil = 0;
        Set<UUID> fetchedFor = new HashSet<UUID>();
        while (true) {
            try {
                Thread.sleep(1000L);
                long now = System.currentTimeMillis();
                Minecraft mc = Minecraft.getMinecraft();

                // ---- publish what you wear (2 s after the last change, so slider drags send once)
                Equip e = mine();
                String want = e.toJson().toString();
                if (!want.equals(changedTo)) {
                    changedTo = want;
                    changedAt = now;
                }
                if (!want.equals(sent) && now - changedAt > 2000L && now > failUntil) {
                    try {
                        JsonObject r = publish(mc, e);
                        if (r != null && r.has("ok")) {
                            sent = want;
                        } else {
                            failUntil = now + 60000L;
                        }
                    } catch (Throwable ex) {
                        failUntil = now + 60000L;   // offline account, no internet, server down: try again later
                    }
                }

                // ---- fetch what the other FzClient players wear (new players at once, everyone each minute)
                if (!Modules.cosmetics.enabled || !Modules.cosOthers.get()) continue;
                List<UUID> ids = FzUsers.known();
                boolean fresh = false;
                for (UUID id : ids) if (!fetchedFor.contains(id)) fresh = true;
                if (ids.isEmpty() || (!fresh && now - fetchedAt < 60000L)) continue;
                JsonArray arr = new JsonArray();
                for (UUID id : ids) arr.add(new JsonPrimitive(id.toString().replace("-", "")));
                JsonObject body = new JsonObject();
                body.add("uuids", arr);
                JsonObject r = FzUsers.post("/cos/get", body);
                fetchedAt = now;
                fetchedFor = new HashSet<UUID>(ids);
                if (r == null || !r.has("cosmetics")) continue;
                JsonObject all = r.getAsJsonObject("cosmetics");
                Map<UUID, Equip> got = new HashMap<UUID, Equip>();
                for (Map.Entry<String, JsonElement> en : all.entrySet()) {
                    UUID id = uuid(en.getKey());
                    if (id != null && en.getValue().isJsonObject()) got.put(id, Equip.fromJson(en.getValue().getAsJsonObject()));
                }
                for (UUID id : ids) {
                    if (got.containsKey(id)) others.put(id, got.get(id));
                    else others.remove(id);
                }
            } catch (InterruptedException ex) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** Proves who you are (Mojang session join, like joining a server) and stores what you wear. */
    private static JsonObject publish(Minecraft mc, Equip e) throws Exception {
        com.mojang.authlib.GameProfile profile = mc.getSession().getProfile();
        String token = mc.getSession().getToken();
        if (profile == null || profile.getId() == null || token == null || token.length() < 20) return null;   // offline account
        String serverId = new BigInteger(130, new SecureRandom()).toString(16);
        mc.getSessionService().joinServer(profile, token, serverId);
        JsonObject body = new JsonObject();
        body.addProperty("name", profile.getName());
        body.addProperty("serverId", serverId);
        body.add("data", e.toJson());
        return FzUsers.post("/cos/set", body);
    }


    private static UUID uuid(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{32}")) return null;
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20));
    }

}
