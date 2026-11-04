package dev.fzkc.fzhud.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Real ping to the server you are on.
 * <p>
 * The tab list's latency (what vanilla shows) is only refreshed when the server feels like it; Hypixel sends it
 * once when you join and almost never again, so it looks frozen. Instead, every few seconds this does what the
 * multiplayer server list does: a tiny status "ping" to the same address over a separate connection, and times
 * the round trip (the time to open the connection is not counted).
 */
public final class PingMeter {
    private PingMeter() {}

    private static final long EVERY_MS = 5000L, FRESH_MS = 20000L;
    private static volatile int ms = -1;
    private static volatile long measuredAt;
    private static volatile String measuredFor = "";
    private static Thread thread;

    /** The last measured ping for the current server, or -1 when there is none (yet). */
    public static int get() {
        start();
        ServerData sd = Minecraft.getMinecraft().getCurrentServerData();
        if (sd == null || !sd.serverIP.equals(measuredFor)) return -1;
        return System.currentTimeMillis() - measuredAt < FRESH_MS ? ms : -1;
    }

    private static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(PingMeter::loop, "FzClient-Ping");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(EVERY_MS);
                Minecraft mc = Minecraft.getMinecraft();
                ServerData sd = mc.getCurrentServerData();
                if (sd == null || mc.isSingleplayer() || mc.getNetHandler() == null) continue;
                String ip = sd.serverIP;
                ServerAddress addr = ServerAddress.fromString(ip);
                int r = measure(addr.getIP(), addr.getPort());
                if (r >= 0) {
                    ms = r;
                    measuredAt = System.currentTimeMillis();
                    measuredFor = ip;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // server refused, offline...: keep the last value until it gets old
            }
        }
    }

    /** Status handshake + ping/pong; returns the round trip in ms. */
    private static int measure(String host, int port) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(4000);
            s.connect(new InetSocketAddress(host, port), 4000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            // handshake (protocol 47 = 1.8), next state 1 = status; then the status request
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(hs);
            h.writeByte(0x00);
            varInt(h, 47);
            byte[] hb = host.getBytes(StandardCharsets.UTF_8);
            varInt(h, hb.length);
            h.write(hb);
            h.writeShort(port);
            varInt(h, 1);
            packet(out, hs.toByteArray());
            packet(out, new byte[]{0x00});
            out.flush();

            // the status answer (server name, players...): read and skip
            int len = readVarInt(in);
            if (len <= 0 || len > 1 << 21) return -1;
            in.readFully(new byte[len]);

            // ping: the server echoes the number back
            ByteArrayOutputStream ps = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(ps);
            p.writeByte(0x01);
            long token = System.nanoTime();
            p.writeLong(token);
            long t0 = System.nanoTime();
            packet(out, ps.toByteArray());
            out.flush();
            int plen = readVarInt(in);
            byte[] pong = new byte[plen];
            in.readFully(pong);
            long t1 = System.nanoTime();
            if (plen < 9 || pong[0] != 0x01) return -1;
            return (int) Math.max(0, Math.round((t1 - t0) / 1e6));
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static void packet(DataOutputStream out, byte[] body) throws IOException {
        varInt(out, body.length);
        out.write(body);
    }

    private static void varInt(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int v = 0, n = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("closed");
            v |= (b & 0x7F) << (7 * n++);
            if (n > 5) throw new IOException("varint too long");
        } while ((b & 0x80) != 0);
        return v;
    }
}
