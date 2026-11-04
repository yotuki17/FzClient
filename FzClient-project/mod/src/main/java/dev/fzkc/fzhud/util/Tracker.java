package dev.fzkc.fzhud.util;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;

import java.util.ArrayDeque;
import java.util.Deque;

/** Gathers the numbers the HUD shows: TPS, CPS, speed, ping. */
public final class Tracker {
    private Tracker() {}

    // ---- TPS: servers send a time update every 20 ticks, so the gap between them tells us the tick rate.
    private static final float[] tpsSamples = new float[6];
    private static int tpsCount, tpsIndex;
    private static long lastTimePacket;

    public static synchronized void onTimeUpdate() {
        long now = System.nanoTime();
        if (lastTimePacket != 0) {
            float seconds = (now - lastTimePacket) / 1.0e9f;
            if (seconds > 0.05f) {
                tpsSamples[tpsIndex] = Math.max(0f, Math.min(20f, 20f / seconds));
                tpsIndex = (tpsIndex + 1) % tpsSamples.length;
                tpsCount = Math.min(tpsCount + 1, tpsSamples.length);
                tpsText = null;
            }
        }
        lastTimePacket = now;
    }

    private static String tpsText;

    public static synchronized String tps() {
        if (tpsText == null) {
            if (tpsCount == 0) {
                tpsText = "-- TPS";
            } else {
                float sum = 0;
                for (int i = 0; i < tpsCount; i++) sum += tpsSamples[i];
                tpsText = Fmt.append(new StringBuilder(10), sum / tpsCount, 2).append(" TPS").toString();
            }
        }
        return tpsText;
    }

    public static synchronized void resetTps() {
        tpsCount = 0;
        tpsText = null;
        tpsIndex = 0;
        lastTimePacket = 0;
    }

    /** Netty handler inserted before the vanilla packet handler; only observes, never modifies packets. */
    public static class TpsHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S03PacketTimeUpdate) onTimeUpdate();
            else if (msg instanceof S12PacketEntityVelocity) onVelocity((S12PacketEntityVelocity) msg);
            else if (msg instanceof S27PacketExplosion) onExplosion((S27PacketExplosion) msg);
            super.channelRead(ctx, msg);
        }
    }

    // ---- Knockback: when the server last pushed us (a hit, an arrow, an explosion). Fall / fire / poison
    //      damage hurt without a push, which is how the KB FOV tells them apart. System.nanoTime(), 0 = never.
    public static volatile long knockbackAt;

    /**
     * The server sends our velocity after EVERY kind of damage (fall, fire, poison... all call setBeenAttacked),
     * so the packet alone means nothing. For those it just repeats our (near zero) speed; a hit adds a real push:
     * about 0.4 sideways (and up). Only the sideways part is used: the server may still hold an upward speed from
     * your last jump, which it would send again with fall or fire damage.
     */
    private static void onVelocity(S12PacketEntityVelocity p) {
        EntityPlayerSP me = Minecraft.getMinecraft().thePlayer;
        if (me == null || p.getEntityID() != me.getEntityId()) return;
        double x = p.getMotionX() / 8000.0, z = p.getMotionZ() / 8000.0;
        if (x * x + z * z > 0.15 * 0.15) knockbackAt = System.nanoTime();
    }

    private static void onExplosion(S27PacketExplosion p) {
        if (p.func_149149_c() != 0f || p.func_149144_d() != 0f || p.func_149147_e() != 0f) knockbackAt = System.nanoTime();
    }

    // ---- CPS
    private static final Deque<Long> left = new ArrayDeque<Long>(), right = new ArrayDeque<Long>();
    public static long lastClick;

    public static void click(int button) {
        long now = System.currentTimeMillis();
        // old clicks are dropped here too, so the lists can't keep growing while the CPS display is off
        if (button == 0) {
            left.addLast(now);
            prune(left, now);
        } else if (button == 1) {
            right.addLast(now);
            prune(right, now);
        }
        lastClick = now;
    }

    private static void prune(Deque<Long> q, long now) {
        long cutoff = now - 1000L;
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
    }

    private static int count(Deque<Long> q) {
        prune(q, System.currentTimeMillis());
        return q.size();
    }

    public static int leftCps() { return count(left); }
    public static int rightCps() { return count(right); }

    // ---- Speed (blocks per second, horizontal)
    public static float speed;

    public static void tick() {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        if (p == null) {
            speed = 0;
            return;
        }
        double dx = p.posX - p.prevPosX, dz = p.posZ - p.prevPosZ;
        float inst = (float) Math.sqrt(dx * dx + dz * dz) * 20f;
        speed += (inst - speed) * 0.35f;
        if (speed < 0.05f) speed = 0f;
    }

    // ---- Ping / FPS: the text is rebuilt only when the number changes (these run every frame)
    private static int pingKey = Integer.MIN_VALUE, fpsKey = Integer.MIN_VALUE;
    private static String pingText = "-- ms", fpsText = "0 FPS";

    public static String ping() {
        Minecraft mc = Minecraft.getMinecraft();
        int ms;
        if (mc.thePlayer == null || mc.getNetHandler() == null) ms = -1;
        else if (mc.isSingleplayer()) ms = 0;
        else {
            // measured ourselves every few seconds (the tab list's value is rarely updated, e.g. on Hypixel)
            ms = PingMeter.get();
            if (ms < 0) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                ms = info == null ? -1 : info.getResponseTime();
            }
        }
        if (ms != pingKey) {
            pingKey = ms;
            pingText = ms < 0 ? "-- ms" : ms + " ms";
        }
        return pingText;
    }

    public static String fps() {
        int f = Minecraft.getDebugFPS();
        if (f != fpsKey) {
            fpsKey = f;
            fpsText = f + " FPS";
        }
        return fpsText;
    }
}
