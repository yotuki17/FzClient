package dev.fzkc.fzhud.util;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev check (-Dfzclient.perftest): how much time and memory FzClient's own drawing takes per frame. Every five
 * seconds one line goes to the log: frames per second, the memory the game thread used per frame, and for each
 * measured part its time and memory per frame. Does nothing (one boolean check) without the option.
 */
public final class Perf {
    private Perf() {}

    public static final boolean ON = System.getProperty("fzclient.perftest") != null;
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("FzClient-Perf");

    private static final class Part { long nanos, bytes; }
    private static final Map<String, Part> parts = new LinkedHashMap<>();
    private static com.sun.management.ThreadMXBean bean;
    private static long t0, b0, frames, since, bytesAtStart;

    private static long bytes() {
        try {
            if (bean == null) bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Start of a measured part. */
    public static void begin() {
        if (!ON) return;
        b0 = bytes();
        t0 = System.nanoTime();
    }

    /** End of the part started by begin(). */
    public static void end(String name) {
        if (!ON) return;
        long t = System.nanoTime() - t0, b = bytes() - b0;
        Part p = parts.get(name);
        if (p == null) parts.put(name, p = new Part());
        p.nanos += t;
        p.bytes += b;
    }

    /** End of every frame. */
    public static void frame() {
        if (!ON) return;
        long now = System.nanoTime();
        if (since == 0) {
            since = now;
            bytesAtStart = bytes();
            return;
        }
        frames++;
        if (now - since < 5000000000L) return;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("fps %.0f, %.1f KB/frame", frames * 1e9 / (now - since), (bytes() - bytesAtStart) / 1024.0 / frames));
        for (Map.Entry<String, Part> e : parts.entrySet()) {
            Part p = e.getValue();
            if (p.nanos == 0) continue;
            sb.append(String.format(" | %s %.1fus %.2fKB", e.getKey(), p.nanos / 1000.0 / frames, p.bytes / 1024.0 / frames));
            p.nanos = 0;
            p.bytes = 0;
        }
        LOG.info(sb.toString());
        frames = 0;
        since = now;
        bytesAtStart = bytes();
    }
}
