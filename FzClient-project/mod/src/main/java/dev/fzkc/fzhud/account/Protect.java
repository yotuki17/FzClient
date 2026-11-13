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
 * encryption (DPAPI, the same thing browsers use for saved passwords). On a Mac a random AES key is kept in the
 * login Keychain and the text is encrypted with it (AES-GCM). Elsewhere the text is only encoded.
 * Stored form: "dp:" + base64 (DPAPI), "kc:" + base64(iv + data) (Mac Keychain key) or "b64:" + base64 (plain).
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

    // ---------------------------------------------------------------- Mac: AES key in the login Keychain

    private static final String KC_SERVICE = "FzClient sign-in key";
    /** security's exit code when the item does not exist (errSecItemNotFound). */
    private static final int NOT_FOUND = 44;
    private static byte[] macKey;

    /** Exit code and output of /usr/bin/security (exit -1 when it could not run or took too long). */
    private static final class Run {
        final int exit;
        final String out;

        Run(int exit, String out) {
            this.exit = exit;
            this.out = out;
        }
    }

    /** Output goes to a file, so a stuck Keychain prompt never blocks reading it: the timeout always works. */
    private static Run security(String... args) {
        java.io.File tmp = null;
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "/usr/bin/security";
            System.arraycopy(args, 0, cmd, 1, args.length);
            tmp = java.io.File.createTempFile("fzkc", ".txt");
            Process p = new ProcessBuilder(cmd).redirectOutput(tmp).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            p.getOutputStream().close();
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Run(-1, "");
            }
            String out = new String(java.nio.file.Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8).trim();
            return new Run(p.exitValue(), out);
        } catch (Throwable t) {
            return new Run(-1, "");
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    private static boolean hexKey(String s) {
        return s != null && (s.matches("[0-9a-f]{32}") || s.matches("[0-9a-f]{64}"));
    }

    /**
     * The AES key from the login Keychain, made once. 128-bit: Mojang's Mac Java 8 (8u74) allows no longer AES keys.
     * A failed lookup is not remembered (tried again next time), and an existing key is never replaced.
     */
    private static synchronized byte[] macKey() {
        if (macKey != null || !dev.fzkc.fzhud.util.Os.MAC) return macKey;
        Run found = security("find-generic-password", "-s", KC_SERVICE, "-a", "fzclient", "-w");
        String hex = found.exit == 0 ? found.out : null;
        if (!hexKey(hex)) {
            if (found.exit != NOT_FOUND) {
                System.out.println("[FzClient] Keychain key not readable (" + found.exit + "), sign-ins saved without it");
                return null;
            }
            byte[] k = new byte[16];
            new java.security.SecureRandom().nextBytes(k);
            StringBuilder sb = new StringBuilder();
            for (byte b : k) sb.append(String.format("%02x", b & 0xFF));
            if (security("add-generic-password", "-s", KC_SERVICE, "-a", "fzclient", "-w", sb.toString()).exit != 0) return null;
            Run again = security("find-generic-password", "-s", KC_SERVICE, "-a", "fzclient", "-w");
            if (again.exit != 0 || !hexKey(again.out)) return null;
            hex = again.out;
        }
        byte[] k = new byte[hex.length() / 2];
        for (int i = 0; i < k.length; i++) k[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        macKey = k;
        return macKey;
    }

    private static byte[] aes(int mode, byte[] key, byte[] iv, byte[] data) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        return c.doFinal(data);
    }

    static String seal(String text) {
        if (text == null) return null;
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        byte[] key = macKey();
        if (key != null) {
            try {
                byte[] iv = new byte[12];
                new java.security.SecureRandom().nextBytes(iv);
                byte[] enc = aes(javax.crypto.Cipher.ENCRYPT_MODE, key, iv, raw);
                byte[] all = new byte[iv.length + enc.length];
                System.arraycopy(iv, 0, all, 0, iv.length);
                System.arraycopy(enc, 0, all, iv.length, enc.length);
                return "kc:" + Base64.getEncoder().encodeToString(all);
            } catch (Throwable ignored) {
            }
        }
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
            if (stored.startsWith("kc:")) {
                byte[] key = macKey();
                if (key == null) return null;
                byte[] all = Base64.getDecoder().decode(stored.substring(3));
                if (all.length < 13) return null;
                byte[] iv = Arrays.copyOfRange(all, 0, 12);
                return new String(aes(javax.crypto.Cipher.DECRYPT_MODE, key, iv, Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
            }
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
