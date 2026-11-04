package dev.fzkc.fzhud.account;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Keeps the saved Microsoft sign-ins unreadable for other Windows users and other PCs: Windows' own per-user
 * encryption (DPAPI, the same thing browsers use for saved passwords). On other systems the text is only encoded.
 * Stored form: "dp:" + base64 (DPAPI) or "b64:" + base64 (plain).
 */
final class Protect {
    private Protect() {}

    public static class Blob extends Structure {
        public int cbData;
        public Pointer pbData;

        @Override
        protected List<String> getFieldOrder() { return Arrays.asList("cbData", "pbData"); }
    }

    interface Crypt32 extends StdCallLibrary {
        boolean CryptProtectData(Blob in, String descr, Pointer entropy, Pointer reserved, Pointer prompt, int flags, Blob out);
        boolean CryptUnprotectData(Blob in, Pointer descr, Pointer entropy, Pointer reserved, Pointer prompt, int flags, Blob out);
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer LocalFree(Pointer p);
    }

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    private static Crypt32 crypt;
    private static Kernel32 kernel;
    private static boolean tried;

    private static boolean dpapi() {
        if (!tried) {
            tried = true;
            if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
                try {
                    crypt = (Crypt32) Native.loadLibrary("crypt32", Crypt32.class);
                    kernel = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
                } catch (Throwable t) {
                    crypt = null;
                }
            }
        }
        return crypt != null;
    }

    private static Blob blob(byte[] data) {
        Blob b = new Blob();
        Memory m = new Memory(Math.max(1, data.length));
        m.write(0, data, 0, data.length);
        b.cbData = data.length;
        b.pbData = m;
        return b;
    }

    static String seal(String text) {
        if (text == null) return null;
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (dpapi()) {
            try {
                Blob out = new Blob();
                if (crypt.CryptProtectData(blob(raw), "FzClient", null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out)) {
                    byte[] enc = out.pbData.getByteArray(0, out.cbData);
                    kernel.LocalFree(out.pbData);
                    return "dp:" + Base64.getEncoder().encodeToString(enc);
                }
            } catch (Throwable ignored) {
            }
        }
        return "b64:" + Base64.getEncoder().encodeToString(raw);
    }

    /** The original text, or null when it cannot be read here (another PC / Windows user). */
    static String open(String stored) {
        if (stored == null) return null;
        try {
            if (stored.startsWith("b64:")) return new String(Base64.getDecoder().decode(stored.substring(4)), StandardCharsets.UTF_8);
            if (stored.startsWith("dp:") && dpapi()) {
                Blob out = new Blob();
                if (crypt.CryptUnprotectData(blob(Base64.getDecoder().decode(stored.substring(3))), null, null, null, null,
                        CRYPTPROTECT_UI_FORBIDDEN, out)) {
                    byte[] dec = out.pbData.getByteArray(0, out.cbData);
                    kernel.LocalFree(out.pbData);
                    return new String(dec, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
