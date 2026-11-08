package dev.fzkc.fzhud.feature;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Finds out what a sudden stutter was. A small background thread watches the frames; when one takes longer than
 * {@link #LIMIT_MS}, it looks at what the game thread is doing right then, and once the frame is done one line
 * (plus that stack) goes to the log: how long it was, how much of it the Java garbage collector took, and where
 * the game was. Costs nothing while frames are normal (one time stamp per frame).
 */
public final class HitchWatch {
    private static final Logger LOG = LogManager.getLogger("FzClient-Hitch");
    private static final long LIMIT_MS = 150;
    private static final int MAX_LOGS = 40;   // per game start, so a broken setup cannot fill the log

    private static volatile long frameAt;          // start of the current frame (ms)
    private static volatile Thread game;
    private static volatile StackTraceElement[] seen;   // the game thread's stack while the frame hung
    private static volatile long gcAtStart;
    private static int logged;
    private static Thread watcher;

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        long now = System.currentTimeMillis();
        long last = frameAt;
        if (game == null) {
            game = Thread.currentThread();
            start();
        }
        if (last > 0 && now - last >= LIMIT_MS && logged < MAX_LOGS) report(now - last);
        seen = null;
        frameAt = now;
        gcAtStart = -1;   // the watcher reads the GC time only when a frame is already slow
    }

    private static void report(long ms) {
        logged++;
        StackTraceElement[] st = seen;
        long gcStart = gcAtStart;
        long gc = gcStart < 0 ? -1 : gcMillis() - gcStart;
        StringBuilder sb = new StringBuilder();
        sb.append("Stutter: one frame took ").append(ms).append(" ms");
        if (gc >= 0) sb.append(" (garbage collection: ").append(gc).append(" ms)");
        if (st != null) {
            sb.append(", game thread was at:");
            int shown = 0;
            for (StackTraceElement e : st) {
                sb.append("\n    at ").append(e);
                if (++shown >= 28) break;
            }
        }
        LOG.info(sb.toString());
    }

    private static long gcMillis() {
        long t = 0;
        List<GarbageCollectorMXBean> all = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean b : all) {
            long c = b.getCollectionTime();
            if (c > 0) t += c;
        }
        return t;
    }

    private static synchronized void start() {
        if (watcher != null) return;
        watcher = new Thread(HitchWatch::loop, "FzClient-HitchWatch");
        watcher.setDaemon(true);
        watcher.setPriority(Thread.MIN_PRIORITY + 1);
        watcher.start();
    }

    private static void loop() {
        long gcBase = gcMillis();
        while (true) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
            long at = frameAt;
            if (at <= 0) continue;
            long late = System.currentTimeMillis() - at;
            if (late < 60) {
                gcBase = gcMillis();   // (a collection stops this thread too, so this is from before it)
                continue;
            }
            // the frame is getting long: keep the GC total from before it, then look at the game thread
            if (gcAtStart < 0) gcAtStart = gcBase;
            if (late >= LIMIT_MS * 2 / 3 && seen == null) {
                Thread g = game;
                if (g != null) {
                    try {
                        seen = g.getStackTrace();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }
}
