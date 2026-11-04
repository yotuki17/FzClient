package dev.fzkc.fzhud.util;

/** Cheap fixed-point number formatting (String.format builds a Formatter and parses a pattern every call). */
public final class Fmt {
    private Fmt() {}

    private static final long[] POW = {1L, 10L, 100L, 1000L};

    private static final String[] NUM = new String[4096];
    private static final String[] PERCENT = new String[101];

    /** Same as Integer.toString, but 0 ... 4095 (counts, durability, ping, CPS) are made only once. */
    public static String num(int n) {
        if (n < 0 || n >= NUM.length) return Integer.toString(n);
        String s = NUM[n];
        if (s == null) NUM[n] = s = Integer.toString(n);
        return s;
    }

    /** n + "%", made only once for 0 ... 100. */
    public static String percent(int n) {
        if (n < 0 || n >= PERCENT.length) return n + "%";
        String s = PERCENT[n];
        if (s == null) PERCENT[n] = s = n + "%";
        return s;
    }

    public static String fixed(double v, int decimals) {
        StringBuilder sb = new StringBuilder(12);
        append(sb, v, decimals);
        return sb.toString();
    }

    public static StringBuilder append(StringBuilder sb, double v, int decimals) {
        if (decimals < 0) decimals = 0;
        if (decimals > 3) decimals = 3;
        if (Double.isNaN(v) || Double.isInfinite(v)) return sb.append('-');
        long p = POW[decimals];
        long n = Math.round(Math.abs(v) * p);
        if (v < 0 && n != 0) sb.append('-');
        sb.append(n / p);
        if (decimals > 0) {
            sb.append('.');
            String frac = Long.toString(n % p);
            for (int i = frac.length(); i < decimals; i++) sb.append('0');
            sb.append(frac);
        }
        return sb;
    }
}
