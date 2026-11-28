package dev.fzkc.fzhud.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev check (-Dfzclient.uitest=chunks): how smooth the game stays while new chunks keep arriving. In a single player
 * world (the client receives the same chunk packets as on a server) the player flies fast in a straight line
 * ("move": a new row of chunks every few frames), then jumps 2000 blocks at a time ("jump": everything reloads, like
 * joining a game). Logs the frame times of each phase (average, 1 % / 0.1 % worst, hitches); with
 * -Dfzclient.chunkprof also which parts of the frame the hitches were spent in (Minecraft's profiler, which costs a
 * little itself: compare the frame times without it).
 */
public final class ChunkBench {
    private ChunkBench() {}

    private static final Logger LOG = LogManager.getLogger("FzClient-ChunkBench");
    private static final float HITCH = 6f;
    private static final boolean PROF = System.getProperty("fzclient.chunkprof") != null;
    private static final int MOVE_FROM = 200, MOVE_TO = 900, JUMP_FROM = 960, JUMP_TO = 1560;

    private static String phase = "";
    private static final List<Float> frames = new ArrayList<Float>();
    private static long last;
    private static double x, z;
    private static boolean shotLater;

    private static Field mapField;
    private static Map<String, Long> prevProf = new HashMap<String, Long>();
    private static final Map<String, long[]> spike = new HashMap<String, long[]>();   // section -> {ns, frames}
    private static final Map<String, long[]> all = new HashMap<String, long[]>();

    /** Client tick (from DevTest), w = ticks since the world was joined. */
    public static void tick(Minecraft mc, int w) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        final String name = mc.thePlayer.getName();
        if (w == 20) {
            mc.gameSettings.renderDistanceChunks = 12;
            dev.fzkc.fzhud.Modules.bgFps.enabled = false;   // the test window is usually behind other windows
            mc.gameSettings.limitFramerate = 260;   // unlimited
            mc.gameSettings.enableVsync = false;
            mc.gameSettings.pauseOnLostFocus = false;
            org.lwjgl.opengl.Display.setVSyncEnabled(false);
            mc.renderGlobal.loadRenderers();
            x = mc.thePlayer.posX;
            z = mc.thePlayer.posZ;
            server.addScheduledTask(() -> {
                EntityPlayerMP p = server.getConfigurationManager().getPlayerByUsername(name);
                if (p == null) return;
                p.setGameType(net.minecraft.world.WorldSettings.GameType.CREATIVE);
                p.capabilities.isFlying = true;
                p.sendPlayerAbilities();
            });
        }
        if (mc.currentScreen != null && w > 20) mc.displayGuiScreen(null);   // a pause menu would change the frame rate
        if (PROF) {
            mc.gameSettings.showDebugInfo = true;
            mc.gameSettings.showDebugProfilerChart = true;
        }
        if (w >= MOVE_FROM - 40 && w < MOVE_TO && w % 4 == 0) teleport(server, name, x += 8, z);   // 40 blocks a second
        if (w >= JUMP_FROM && w < JUMP_TO && (w - JUMP_FROM) % 120 == 0) teleport(server, name, x, z += 2000);
        if (w % 100 == 0) {
            LOG.info(String.format("chunkbench w=%d pos %.0f %.0f %.0f pitch %.0f | %s | %s", w, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.rotationPitch, mc.renderGlobal.getDebugInfoRenders(), mc.theWorld.getProviderName()));
            if (w == 500 || w == 1100) shotLater = true;
        }
        String now = w >= MOVE_FROM && w < MOVE_TO ? "move" : w >= JUMP_FROM && w < JUMP_TO ? "jump" : "";
        if (!now.equals(phase)) {
            if (!phase.isEmpty()) report(phase);
            phase = now;
            frames.clear();
            spike.clear();
            all.clear();
            last = 0;
        }
        if (w == JUMP_TO + 20) {
            LOG.info("chunkbench done");
            mc.shutdown();
        }
    }

    private static void teleport(final MinecraftServer server, final String name, final double tx, final double tz) {
        server.addScheduledTask(() -> {
            EntityPlayerMP p = server.getConfigurationManager().getPlayerByUsername(name);
            if (p != null) {
                p.capabilities.isFlying = true;
                p.setPositionAndUpdate(tx, 110, tz);
            }
        });
    }

    /** Start of every frame (render tick start). */
    public static void frame() {
        long t = System.nanoTime();
        if (shotLater) {
            shotLater = false;
            Minecraft mc = Minecraft.getMinecraft();
            net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, "chunkbench_" + phase + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        }
        if (phase.isEmpty()) {
            last = 0;
            return;
        }
        float ms = last == 0 ? -1 : (t - last) / 1e6f;
        last = t;
        if (ms < 0) return;
        frames.add(ms);
        if (PROF) profile(ms);
    }

    @SuppressWarnings("unchecked")
    private static void profile(float ms) {
        try {
            if (mapField == null) {
                for (String n : new String[]{"profilingMap", "field_76324_e"}) {
                    try {
                        mapField = net.minecraft.profiler.Profiler.class.getDeclaredField(n);
                        mapField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
            Map<String, Long> map = (Map<String, Long>) mapField.get(Minecraft.getMinecraft().mcProfiler);
            Map<String, Long> now = new HashMap<String, Long>(map);
            boolean hitch = ms > HITCH;
            List<Map.Entry<String, Long>> frame = new ArrayList<Map.Entry<String, Long>>();
            for (Map.Entry<String, Long> e : now.entrySet()) {
                Long before = prevProf.get(e.getKey());
                long d = e.getValue() - (before == null ? 0L : before);
                if (d <= 0) continue;
                add(all, e.getKey(), d);
                if (hitch) add(spike, e.getKey(), d);
                frame.add(new java.util.AbstractMap.SimpleEntry<String, Long>(e.getKey(), d));
            }
            prevProf = now;
            if (ms > 25f) {
                // one big hitch: where its time went (deepest parts that took at least a fifth of it)
                Collections.sort(frame, (a, b) -> Long.compare(b.getValue(), a.getValue()));
                StringBuilder sb = new StringBuilder(String.format("chunkbench hitch %s %.1f ms:", phase, ms));
                int shown = 0;
                for (Map.Entry<String, Long> e : frame) {
                    if (e.getValue() < ms * 1e6 / 5 || e.getKey().split("\\.").length < 2) continue;
                    sb.append(String.format("  %s %.1f", e.getKey(), e.getValue() / 1e6));
                    if (++shown == 6) break;
                }
                LOG.info(sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void add(Map<String, long[]> m, String k, long ns) {
        long[] v = m.get(k);
        if (v == null) m.put(k, v = new long[2]);
        v[0] += ns;
        v[1]++;
    }

    private static void report(String name) {
        if (frames.size() < 20) return;
        List<Float> s = new ArrayList<Float>(frames);
        Collections.sort(s);
        double sum = 0;
        int over25 = 0, over50 = 0, hitches = 0;
        for (float f : s) {
            sum += f;
            if (f > 25) over25++;
            if (f > HITCH) hitches++;
            if (f > 50) over50++;
        }
        int n = s.size();
        LOG.info(String.format("chunkbench %s: frames %d, avg %.2f ms (%.0f fps), median %.2f, 1%% worst %.1f ms, 0.1%% worst %.1f ms, max %.1f ms, >25ms %d, >50ms %d",
                name, n, sum / n, 1000 * n / sum, s.get(n / 2), s.get(Math.min(n - 1, (int) (n * 0.99))),
                s.get(Math.min(n - 1, (int) (n * 0.999))), s.get(n - 1), over25, over50));
        if (PROF) {
            top(name + " frames over " + (int) HITCH + " ms", spike, hitches);
            top(name + " all frames", all, n);
        }
    }

    private static void top(String title, Map<String, long[]> m, int frameCount) {
        List<Map.Entry<String, long[]>> list = new ArrayList<Map.Entry<String, long[]>>(m.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder("chunkbench " + title + " (" + frameCount + " frames), ms per frame:");
        int shown = 0;
        for (Map.Entry<String, long[]> e : list) {
            if (e.getKey().split("\\.").length < 3) continue;   // root / root.x: totals, not parts
            sb.append(String.format("%n   %-60s %7.2f", e.getKey(), e.getValue()[0] / 1e6 / Math.max(1, frameCount)));
            if (++shown == 22) break;
        }
        LOG.info(sb.toString());
    }
}
