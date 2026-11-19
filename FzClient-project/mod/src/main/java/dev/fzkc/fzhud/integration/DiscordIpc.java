package dev.fzkc.fzhud.integration;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import dev.fzkc.fzhud.util.Os;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The connection to the Discord desktop app: a named pipe on Windows (\\.\pipe\discord-ipc-N), a Unix socket on a
 * Mac ($TMPDIR/discord-ipc-N). Java 8 has no Unix sockets, so on a Mac it calls the system's socket functions
 * through JNA, which Minecraft already ships.
 */
public final class DiscordIpc {
    private DiscordIpc() {}

    /** One open connection: whole frames are written and read through it. */
    interface Conn {
        void write(byte[] b) throws IOException;
        void readFully(byte[] b) throws IOException;
        void close();
    }

    /** discord-ipc-N; a test can point it elsewhere (-Dfzclient.discordipc=fztest-ipc-) to talk to a fake Discord. */
    private static final String NAME = System.getProperty("fzclient.discordipc", "discord-ipc-");

    /** True where FzClient can reach the Discord app. */
    public static boolean supported() {
        return Os.WIN || Os.MAC;
    }

    /** The first discord-ipc-0..9 that answers, or null (Discord not running). */
    static Conn open() {
        if (Os.WIN) {
            for (int i = 0; i < 10; i++) {
                try {
                    return new PipeConn(new RandomAccessFile("\\\\.\\pipe\\" + NAME + i, "rw"));
                } catch (Exception ignored) {
                }
            }
            return null;
        }
        if (Os.MAC) {
            for (String dir : socketDirs()) {
                for (int i = 0; i < 10; i++) {
                    File f = new File(dir, NAME + i);
                    if (!f.exists()) continue;
                    Conn c = UnixConn.connect(f.getPath());
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    /** Where Discord puts its socket: the user's temp folder (TMPDIR on a Mac), then the usual fallbacks. */
    private static List<String> socketDirs() {
        Set<String> dirs = new LinkedHashSet<String>();
        for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isEmpty()) dirs.add(v);
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) dirs.add(tmp);
        dirs.add("/tmp");
        return new ArrayList<String>(dirs);
    }

    // ---- Windows: the pipe is a file -----------------------------------------------------------------

    private static final class PipeConn implements Conn {
        private final RandomAccessFile f;

        PipeConn(RandomAccessFile f) {
            this.f = f;
        }

        public void write(byte[] b) throws IOException {
            f.write(b);
        }

        public void readFully(byte[] b) throws IOException {
            f.readFully(b);
        }

        public void close() {
            try {
                f.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- Mac: a Unix socket through the C library ------------------------------------------------------

    /** The few BSD socket calls needed (libSystem on a Mac). */
    private interface LibC extends Library {
        int socket(int domain, int type, int protocol);
        int connect(int fd, byte[] addr, int len);
        int setsockopt(int fd, int level, int name, int[] value, int len);
        NativeLong write(int fd, byte[] buf, NativeLong n);
        NativeLong read(int fd, byte[] buf, NativeLong n);
        int close(int fd);
    }

    private static final int AF_UNIX = 1, SOCK_STREAM = 1;
    /** macOS: SOL_SOCKET and SO_NOSIGPIPE (a write to a socket Discord closed then fails instead of a SIGPIPE). */
    private static final int SOL_SOCKET = 0xFFFF, SO_NOSIGPIPE = 0x1022;
    /** macOS sockaddr_un: 1 byte length, 1 byte family, 104 bytes path. */
    private static final int SUN_PATH = 104;
    private static final int EINTR = 4;

    private static LibC libc;
    private static boolean libcTried;

    private static synchronized LibC libc() {
        if (!libcTried) {
            libcTried = true;
            for (String name : new String[]{"c", "System"}) {
                try {
                    libc = (LibC) Native.loadLibrary(name, LibC.class);
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        return libc;
    }

    private static final class UnixConn implements Conn {
        private final LibC c;
        private int fd;

        private UnixConn(LibC c, int fd) {
            this.c = c;
            this.fd = fd;
        }

        static Conn connect(String path) {
            LibC c = libc();
            if (c == null) return null;
            byte[] p = path.getBytes(StandardCharsets.UTF_8);
            if (p.length >= SUN_PATH) return null;   // too long for a socket address
            int fd = c.socket(AF_UNIX, SOCK_STREAM, 0);
            if (fd < 0) return null;
            byte[] addr = new byte[2 + SUN_PATH];
            addr[0] = (byte) addr.length;
            addr[1] = AF_UNIX;
            System.arraycopy(p, 0, addr, 2, p.length);
            c.setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, new int[]{1}, 4);
            if (c.connect(fd, addr, addr.length) != 0) {
                c.close(fd);
                return null;
            }
            return new UnixConn(c, fd);
        }

        public void write(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                byte[] part = off == 0 ? b : java.util.Arrays.copyOfRange(b, off, b.length);
                long n = c.write(fd(), part, new NativeLong(part.length)).longValue();
                if (n < 0) {
                    if (Native.getLastError() == EINTR) continue;
                    throw new IOException("write failed (errno " + Native.getLastError() + ")");
                }
                off += (int) n;
            }
        }

        public void readFully(byte[] b) throws IOException {
            int off = 0;
            byte[] buf = new byte[b.length];
            while (off < b.length) {
                long n = c.read(fd(), buf, new NativeLong(b.length - off)).longValue();
                if (n == 0) throw new EOFException("Discord closed the connection");
                if (n < 0) {
                    if (Native.getLastError() == EINTR) continue;
                    throw new IOException("read failed (errno " + Native.getLastError() + ")");
                }
                System.arraycopy(buf, 0, b, off, (int) n);
                off += (int) n;
            }
        }

        private synchronized int fd() throws IOException {
            if (fd < 0) throw new IOException("closed");
            return fd;
        }

        public synchronized void close() {
            if (fd >= 0) {
                c.close(fd);
                fd = -1;
            }
        }
    }
}
