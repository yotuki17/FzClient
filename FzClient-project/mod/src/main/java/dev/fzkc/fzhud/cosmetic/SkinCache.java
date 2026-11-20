package dev.fzkc.fzhud.cosmetic;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skins (and capes) of any player by UUID: the /pv window, your own player on the title screen, the account list.
 * Someone in the tab list is ready at once; anyone else is asked from Mojang in the background (the default skin
 * shows until then).
 *
 * When Mojang cannot be reached the look-up is tried again a little later (30 s, then longer), so one hiccup at
 * start-up does not leave Steve for the whole session. It asks without authlib's profile cache on purpose: that cache
 * keeps a failed look-up for six hours as if the player had no skin.
 */
public final class SkinCache {
    private SkinCache() {}

    public static final class Skin {
        public volatile ResourceLocation skin, cape;
        public volatile boolean slim;
        /** Still on its way from Mojang; false once it came, or when the player has none (the default skin). */
        volatile boolean waiting;
        volatile long asked = System.currentTimeMillis();
        /** Mojang could not be reached: when to ask again (0 = not again). */
        volatile long retryAt;
        volatile int tries;

        /** True while the real skin is still coming (for a few seconds at most; then the default one stays). */
        public boolean loading() {
            return waiting && System.currentTimeMillis() - asked < 6000L;
        }
    }

    /** Tries after the first before giving up for the session (offline accounts never have a Mojang profile). */
    private static final int MAX_TRIES = 6;

    private static final Map<UUID, Skin> CACHE = new ConcurrentHashMap<>();
    /** Straight from Mojang, never from the tab list (there FzClient's own cape may stand in for the Mojang one). */
    private static final Map<UUID, Skin> MOJANG = new ConcurrentHashMap<>();

    /** The player's skin: from the tab list when they are in it, else from Mojang. */
    public static Skin get(UUID id, String name) {
        Skin cached = CACHE.get(id);
        if (cached != null) return again(cached, id, name);
        final Skin sk = fresh(id);
        CACHE.put(id, sk);
        Minecraft mc = Minecraft.getMinecraft();
        NetworkPlayerInfo info = mc.thePlayer == null || mc.thePlayer.sendQueue == null ? null : mc.thePlayer.sendQueue.getPlayerInfo(id);
        if (info != null && info.hasLocationSkin()) {
            sk.skin = info.getLocationSkin();
            sk.slim = "slim".equals(info.getSkinType());
            sk.cape = info.getLocationCape();
            return sk;
        }
        ask(sk, id, name);
        return sk;
    }

    /** The skin and cape on the player's Mojang profile (your own account on the title screen, the account list). */
    public static Skin mojang(UUID id, String name) {
        Skin cached = MOJANG.get(id);
        if (cached != null) return again(cached, id, name);
        final Skin sk = fresh(id);
        MOJANG.put(id, sk);
        ask(sk, id, name);
        return sk;
    }

    private static Skin fresh(UUID id) {
        Skin sk = new Skin();
        sk.slim = "slim".equals(DefaultPlayerSkin.getSkinType(id));
        sk.skin = DefaultPlayerSkin.getDefaultSkin(id);
        return sk;
    }

    /** A look-up that failed is started again once its time has come. */
    private static Skin again(Skin sk, UUID id, String name) {
        long at = sk.retryAt;
        if (at != 0 && System.currentTimeMillis() >= at) {
            sk.retryAt = 0;
            ask(sk, id, name);   // 'asked' stays: a retry does not bring the loading state back
        }
        return sk;
    }

    private static void ask(final Skin sk, UUID id, String name) {
        // an offline account (name-based UUID, version 3) has no Mojang profile: nothing to ask for
        if (id.version() != 4) {
            sk.waiting = false;
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        final GameProfile profile = new GameProfile(id, name);
        sk.waiting = true;
        Thread t = new Thread(() -> {
            try {
                // requireSecure = true goes past authlib's cache (see the class comment); Mojang signs the answer
                final GameProfile full = mc.getSessionService().fillProfileProperties(profile, true);
                if (full.getProperties().isEmpty()) {
                    // Mojang did not answer (or there is no such Mojang player, e.g. an offline account)
                    failed(sk);
                    return;
                }
                sk.tries = 0;
                // no skin of their own: nothing more will come
                if (!mc.getSessionService().getTextures(full, false).containsKey(MinecraftProfileTexture.Type.SKIN)) sk.waiting = false;
                mc.addScheduledTask(() -> mc.getSkinManager().loadProfileTextures(full, new SkinManager.SkinAvailableCallback() {
                    @Override
                    public void skinAvailable(MinecraftProfileTexture.Type type, ResourceLocation location, MinecraftProfileTexture texture) {
                        if (type == MinecraftProfileTexture.Type.SKIN) {
                            sk.slim = "slim".equals(texture.getMetadata("model"));
                            sk.skin = location;
                            sk.waiting = false;
                        } else if (type == MinecraftProfileTexture.Type.CAPE) {
                            sk.cape = location;
                        }
                    }
                }, false));
            } catch (Throwable e) {
                failed(sk);
            }
        }, "FzClient-Skin");
        t.setDaemon(true);
        t.start();
    }

    /** Keeps what is shown and asks again later: 30 s, 1 min, 2 min, ... up to 10 min apart. */
    private static void failed(Skin sk) {
        sk.waiting = false;
        int n = ++sk.tries;
        if (n > MAX_TRIES) return;
        long wait = Math.min(30000L << (n - 1), 600000L);
        sk.retryAt = System.currentTimeMillis() + wait;
    }
}
