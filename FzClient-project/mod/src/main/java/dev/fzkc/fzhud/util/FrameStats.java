package dev.fzkc.fzhud.util;

/**
 * Numbers for the overlay (Alt+X). Frame times are kept all the time (one array write per frame), so the graph
 * and the 1% low already have history the moment the overlay opens.
 */
public final class FrameStats {
    private FrameStats() {}

    public static final int SIZE = 1024;
    private static final float[] MS = new float[SIZE];
    private static int head, count;
    private static long last;

    /** End of every frame. */
    public static void frame() {
        long now = System.nanoTime();
        if (last != 0) {
            MS[head] = (now - last) / 1e6f;
            head = (head + 1) % SIZE;
            if (count < SIZE) count++;
        }
        last = now;
    }

    public static int count() { return count; }

    /** Frame time in ms, i = 0 is the newest frame. */
    public static float ago(int i) { return MS[((head - 1 - i) % SIZE + SIZE) % SIZE]; }

    /** Average FPS over the last second. */
    public static float fps() {
        float sum = 0;
        int n = 0;
        while (n < count && sum < 1000f) sum += ago(n++);
        return sum > 0 ? n * 1000f / sum : 0;
    }

    /** "1% low": the FPS of the slowest 1% of the last (up to) 1000 frames. Sorts, so call it a few times a second. */
    public static float low1() {
        int n = Math.min(count, 1000);
        if (n < 50) return 0;
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = ago(i);
        java.util.Arrays.sort(a);
        int k = Math.max(1, n / 100);
        float sum = 0;
        for (int i = n - k; i < n; i++) sum += a[i];
        return sum > 0 ? 1000f * k / sum : 0;
    }

    // ---------------------------------------------------------------- CPU

    private static long cpuAt, cpuTimeAt;
    private static float gameCores = -1, systemLoad = -1;

    /**
     * Logical processors of the whole PC. NUMBER_OF_PROCESSORS is Windows' own count; Java's availableProcessors()
     * can be lower when the launcher limits the game to some cores (Ryzen X3D).
     */
    public static final int LOGICAL = logical();

    private static int logical() {
        try {
            int n = Integer.parseInt(System.getenv("NUMBER_OF_PROCESSORS"));
            if (n > 0) return n;
        } catch (Exception ignored) { }
        return Runtime.getRuntime().availableProcessors();
    }

    /** Samples the CPU use twice a second. */
    public static void sampleCpu() {
        long now = System.nanoTime();
        if (now - cpuAt < 500_000_000L) return;
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean s = (com.sun.management.OperatingSystemMXBean) os;
                long cpuTime = s.getProcessCpuTime();
                // CPU time used per real time = how many cores the game kept busy (1.0 = one whole core)
                if (cpuAt != 0 && cpuTime >= 0) gameCores = (cpuTime - cpuTimeAt) / (float) (now - cpuAt);
                cpuTimeAt = cpuTime;
                systemLoad = (float) s.getSystemCpuLoad();
            }
        } catch (Throwable ignored) { }
        cpuAt = now;
    }

    /** Cores the game kept busy (1.0 = one whole core), -1 = unknown yet. */
    public static float gameCores() { return gameCores; }

    /** Use of the whole PC, 0..1, -1 = unknown. */
    public static float systemLoad() { return systemLoad; }
}
