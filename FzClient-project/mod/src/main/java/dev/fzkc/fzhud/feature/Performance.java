package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.client.IRenderHandler;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Performance: draw less of what you can't use, so the FPS goes up. Everything here only changes what YOUR screen
 * draws; nothing is sent to the server and players are never hidden.*
 *  1. Far-away dropped items, XP orbs, mobs, armor stands (holograms) and item frames / paintings are not drawn.
 *     The game decides this with a per-entity "render distance" (box size x 64 x renderDistanceWeight), so
 *     changing that weight is enough - no hooks needed.
 *  2. Mobs / items / frames behind solid blocks are not drawn (occlusion culling). Checked a few times a second
 *     with rays from your eyes to three points of the entity; glass and other see-through blocks don't count,
 *     and an entity only disappears after it stayed hidden for a moment (no flicker at corners).
 *  3. Rain / snow and clouds can be switched off through the renderer hooks Forge provides.
 *  4. Menus (inventory, pause screen...) can be capped to a lower FPS while the game is paused anyway.
 * Turning the module off puts everything back.
 */
public final class Performance {
    private Performance() {}

    private static final class Info {
        /** The render-distance weight the entity had before we touched it. */
        double orig;
        /** In how many checks in a row it was completely behind blocks. */
        int streak;
    }

    private static final Map<Entity, Info> info = new IdentityHashMap<>();
    private static World lastWorld;
    private static int ticks;
    private static long lastFrame;

    /** Does nothing: used to switch off rain / snow / clouds. */
    private static final IRenderHandler NOTHING = new IRenderHandler() {
        @Override
        public void render(float partialTicks, WorldClient world, Minecraft mc) {}
    };

    // ---------------------------------------------------------------- every client tick

    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != lastWorld) {
            info.clear();
            lastWorld = mc.theWorld;
        }
        if (mc.theWorld == null) return;
        WorldProvider provider = mc.theWorld.provider;
        if (!Modules.performance.enabled) {
            restoreEntities();
            restoreRenderers(provider);
            return;
        }
        ticks++;
        applyRenderers(provider);

        double itemDist = Modules.perfItems.get(), mobDist = Modules.perfMobs.get(), frameDist = Modules.perfFrames.get();
        boolean cull = Modules.perfCull.get();
        Entity view = mc.getRenderViewEntity();
        Vec3 eye = cull && view != null ? new Vec3(view.posX, view.posY + view.getEyeHeight(), view.posZ) : null;

        for (Entity e : mc.theWorld.loadedEntityList) {
            double limit;
            if (e instanceof EntityItem || e instanceof EntityXPOrb) {
                limit = itemDist;
            } else if (e instanceof EntityHanging) {
                limit = frameDist; // item frames, paintings
            } else if (e instanceof EntityLivingBase && !(e instanceof EntityPlayer) && !(e instanceof IBossDisplayData)) {
                limit = mobDist; // animals, mobs, armor stands; bosses like the dragon stay visible
            } else {
                continue;
            }
            if (e == view) continue;

            Info in = info.get(e);
            if (in == null) {
                in = new Info();
                in.orig = e.renderDistanceWeight;
                info.put(e, in);
            }

            boolean hidden = false;
            if (eye != null) {
                // each entity is checked every 3rd tick, spread over the ticks so the work stays even
                if ((e.getEntityId() + ticks) % 3 == 0) in.streak = occluded(mc.theWorld, eye, e) ? in.streak + 1 : 0;
                hidden = in.streak >= 2;
            } else {
                in.streak = 0;
            }

            double avg = e.getEntityBoundingBox().getAverageEdgeLength();
            if (Double.isNaN(avg) || avg < 0.1) avg = 0.1;
            // distance the game will use = avg * 64 * weight = limit; never longer than the entity had before.
            // A weight of 0 means "not drawn at all".
            e.renderDistanceWeight = hidden ? 0.0 : Math.min(in.orig, limit / (64.0 * avg));
        }

        // forget entities that are gone
        if (ticks % 200 == 0) {
            info.keySet().removeIf(entity -> entity.isDead);
        }
    }

    // ---------------------------------------------------------------- occlusion culling

    /** True when all three sample points of the entity are behind solid blocks. */
    private static boolean occluded(World world, Vec3 eye, Entity e) {
        AxisAlignedBB bb = e.getEntityBoundingBox();
        double cx = (bb.minX + bb.maxX) / 2.0, cz = (bb.minZ + bb.maxZ) / 2.0, cy = (bb.minY + bb.maxY) / 2.0;
        double dx = cx - eye.xCoord, dy = cy - eye.yCoord, dz = cz - eye.zCoord;
        if (dx * dx + dy * dy + dz * dz < 25.0) return false; // closer than 5 blocks: never hidden
        // the first point that can be seen ends the check
        return blocked(world, eye, new Vec3(cx, cy, cz))
                && blocked(world, eye, new Vec3(cx, bb.maxY - 0.05, cz))
                && blocked(world, eye, new Vec3(cx, bb.minY + 0.05, cz));
    }

    /** Is there a solid block between the two points? Glass, leaves, fences... are looked through. */
    private static boolean blocked(World world, Vec3 from, Vec3 to) {
        Vec3 cur = from;
        for (int i = 0; i < 4; i++) {
            MovingObjectPosition hit = world.rayTraceBlocks(cur, to, false, true, false);
            if (hit == null || hit.hitVec == null) return false;
            Block b = world.getBlockState(hit.getBlockPos()).getBlock();
            if (b.isOpaqueCube()) return true;
            // a see-through block: carry on from just behind it
            Vec3 dir = to.subtract(cur).normalize();
            cur = new Vec3(hit.hitVec.xCoord + dir.xCoord * 0.05, hit.hitVec.yCoord + dir.yCoord * 0.05,
                    hit.hitVec.zCoord + dir.zCoord * 0.05);
        }
        return false; // too many see-through blocks in a row: show it
    }

    // ---------------------------------------------------------------- rain / snow / clouds

    private static void applyRenderers(WorldProvider p) {
        // only ever replace "nothing": another mod's own renderer is left alone
        if (Modules.perfNoWeather.get()) {
            if (p.getWeatherRenderer() == null) p.setWeatherRenderer(NOTHING);
        } else if (p.getWeatherRenderer() == NOTHING) {
            p.setWeatherRenderer(null);
        }
        if (Modules.perfNoClouds.get()) {
            if (p.getCloudRenderer() == null) p.setCloudRenderer(NOTHING);
        } else if (p.getCloudRenderer() == NOTHING) {
            p.setCloudRenderer(null);
        }
    }

    private static void restoreRenderers(WorldProvider p) {
        if (p.getWeatherRenderer() == NOTHING) p.setWeatherRenderer(null);
        if (p.getCloudRenderer() == NOTHING) p.setCloudRenderer(null);
    }

    private static void restoreEntities() {
        if (info.isEmpty()) return;
        for (Map.Entry<Entity, Info> en : info.entrySet()) en.getKey().renderDistanceWeight = en.getValue().orig;
        info.clear();
    }

    // ---------------------------------------------------------------- menu FPS

    /** End of every frame (RenderTickEvent, phase END): slows the frame down while a menu is open. */
    public static void afterFrame() {
        long now = System.nanoTime();
        if (Modules.performance.enabled) {
            Minecraft mc = Minecraft.getMinecraft();
            int limit = (int) Modules.perfMenuFps.get();
            // in a world, with a menu open (the chat is left alone, typing should feel fluid)
            if (limit < 240 && mc.theWorld != null && mc.currentScreen != null && !(mc.currentScreen instanceof GuiChat)) {
                long wait = 1000000000L / limit - (now - lastFrame);
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                    now = System.nanoTime();
                }
            }
        }
        lastFrame = now;
    }
}
