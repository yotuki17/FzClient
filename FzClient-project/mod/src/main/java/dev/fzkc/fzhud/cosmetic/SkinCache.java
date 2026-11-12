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
 * Skins (and capes) of any player by UUID, for the /pv window. Someone in the tab list is ready at once;
 * anyone else is asked from Mojang in the background (the default skin shows until then).
 */
public final class SkinCache {
    private SkinCache() {}

    public static final class Skin {
        public volatile ResourceLocation skin, cape;
        public volatile boolean slim;
        /** Still on its way from Mojang; false once it came, or when the player has none (the default skin). */
        volatile boolean waiting;
        final long asked = System.currentTimeMillis();

        /** True while the real skin is still coming (for a few seconds at most; then the default one stays). */
        public boolean loading() {
            return waiting && System.currentTimeMillis() - asked < 6000L;
        }
    }

    private static final Map<UUID, Skin> CACHE = new ConcurrentHashMap<>();

    public static Skin get(UUID id, String name) {
        Skin cached = CACHE.get(id);
        if (cached != null) return cached;
        final Skin sk = new Skin();
        sk.slim = "slim".equals(DefaultPlayerSkin.getSkinType(id));
        sk.skin = DefaultPlayerSkin.getDefaultSkin(id);
        CACHE.put(id, sk);
        Minecraft mc = Minecraft.getMinecraft();
        NetworkPlayerInfo info = mc.thePlayer == null || mc.thePlayer.sendQueue == null ? null : mc.thePlayer.sendQueue.getPlayerInfo(id);
        if (info != null && info.hasLocationSkin()) {
            sk.skin = info.getLocationSkin();
            sk.slim = "slim".equals(info.getSkinType());
            sk.cape = info.getLocationCape();
            return sk;
        }
        final GameProfile profile = new GameProfile(id, name);
        sk.waiting = true;
        Thread t = new Thread(() -> {
            try {
                final GameProfile full = mc.getSessionService().fillProfileProperties(profile, false);
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
            } catch (Throwable ignored) {
                // offline or rate limited: keep the default skin
                sk.waiting = false;
            }
        }, "FzClient-Skin");
        t.setDaemon(true);
        t.start();
        return sk;
    }
}
