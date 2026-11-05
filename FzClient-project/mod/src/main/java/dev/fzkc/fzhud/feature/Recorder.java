package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Recording for the overlay (Alt+X): a normal recording (start / stop) and "save the last N seconds".
 *
 * The launcher puts ffmpeg and its own helper (FzRecorder.exe, see launcher/src/tools/FzRecorder.cs) into a folder
 * and passes it as -Dfzclient.recorder. The helper runs ffmpeg and feeds it the game's sound only (Windows'
 * process loopback); ffmpeg takes the picture of just the game window (Windows.Graphics.Capture; or a whole
 * monitor with Desktop Duplication, see 録画する範囲) and encodes it on the GPU (NVENC, else AMF,
 * else Media Foundation), so recording costs almost no FPS.
 *
 * ffmpeg writes 2-second pieces into recordings/.buffer. Both kinds of recording only join pieces: "save the last
 * 30 s" takes the newest ones, a recording takes everything from its start to its stop. A save waits until ffmpeg
 * has moved on to the next piece, so the moment the button was pressed is complete. Old pieces are deleted as
 * soon as nothing needs them.
 *
 * The helper stops ffmpeg when its stdin is closed, so if the game goes away for any reason (also a crash) the
 * recording stops with it.
 */
public final class Recorder {
    private Recorder() {}

    private static final File TOOLS = tools();

    /** Keys for both kinds of recording, without opening the overlay (not bound at first). */
    public static final net.minecraft.client.settings.KeyBinding KEY_RECORD =
            new net.minecraft.client.settings.KeyBinding("録画 開始 / 停止", 0, "FzClient");
    public static final net.minecraft.client.settings.KeyBinding KEY_SAVE =
            new net.minecraft.client.settings.KeyBinding("録画 直前を保存", 0, "FzClient");

    private static File tools() {
        String p = System.getProperty("fzclient.recorder");
        if (p == null || p.isEmpty()) return null;
        File d = new File(p);
        return new File(d, "ffmpeg.exe").isFile() && new File(d, "FzRecorder.exe").isFile() ? d : null;
    }

    /** Prepared by the launcher (its settings page: 録画). */
    public static boolean available() {
        return TOOLS != null;
    }

    // GPU encoders first; each entry: encoder, video filter (null = take the GPU frames as they are), options
    private static final String[][] ENCODERS = {
            {"h264_nvenc", null, "-preset", "p4", "-rc", "vbr", "-cq", "23", "-b:v", "0", "-maxrate", "60M", "-bufsize", "120M"},
            {"h264_amf", null, "-quality", "speed", "-rc", "cqp", "-qp_i", "22", "-qp_p", "24"},
            {"h264_mf", "hwdownload,format=bgra,format=nv12", "-b:v", "20M"},
    };
    private static final String[] ENCODER_NAMES = {"NVENC", "AMF", "Media Foundation"};
    private static int encoder;

    private static Process helper;
    private static long startedAt;
    /** First piece number of the running capture (numbers keep growing across captures). */
    private static int base;
    /** First piece of the recording being made, -1 = not recording. */
    private static int recordFrom = -1;
    private static long recordStartedAt;
    private static volatile String status = "";
    /** Every encoder failed: no automatic restarts until the user asks again. */
    private static boolean gaveUp;

    /** A file to make from pieces [from, to], once piece `to` is complete. */
    private static final class Save {
        final int from, to;
        final String name;
        final boolean stopAfter;
        Save(int from, int to, String name, boolean stopAfter) { this.from = from; this.to = to; this.name = name; this.stopAfter = stopAfter; }
    }
    private static final List<Save> waiting = new ArrayList<>();
    /** Pieces from this number on are in use by a save being joined right now. */
    private static final List<Integer> joining = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static boolean hookAdded;
    private static int ticks;

    // ---------------------------------------------------------------- state for the overlay

    public static boolean running() { return helper != null && helper.isAlive(); }

    public static boolean recording() { return recordFrom >= 0; }

    public static long recordingMillis() { return recording() ? System.currentTimeMillis() - recordStartedAt : 0; }

    public static String status() { return status; }

    public static String encoderName() { return running() ? ENCODER_NAMES[encoder] : ""; }

    // ---------------------------------------------------------------- actions

    /** Starts a recording, or stops it and saves it. */
    public static void toggleRecording() {
        // stopping always works, also when the module was switched off meanwhile
        if (recording()) {
            waiting.add(new Save(recordFrom, newest(), "FzClient_" + stamp() + ".mp4", true));
            recordFrom = -1;
            status = "保存しています…";
            return;
        }
        if (!ready()) return;
        // asked for by hand: try every encoder again even if they all failed before
        gaveUp = false;
        encoder = running() ? encoder : 0;
        if (!startCapture()) return;
        // the piece being written now: up to 2 s from before the button, never less than what was asked for
        recordFrom = Math.max(base, newest());
        recordStartedAt = System.currentTimeMillis();
        status = "録画中";
    }

    /** Saves the last N seconds (only while they are being kept). */
    public static void saveReplay() {
        if (!ready()) return;
        if (!running() || !Modules.recReplay.get()) {
            status = "「直前の数十秒をいつも残しておく」を ON にしてください";
            return;
        }
        int to = newest();
        int pieces = (int) Math.ceil(Modules.recReplaySeconds.get() / 2.0);
        waiting.add(new Save(Math.max(base, to - pieces + 1), to, "FzClient_replay_" + stamp() + ".mp4", false));
        status = "保存しています…";
    }

    private static boolean ready() {
        if (!available()) {
            status = "ランチャーの「設定」→「録画」で準備してください";
            return false;
        }
        if (!Modules.recorder.enabled) {
            status = "Recorder が OFF になっています";
            return false;
        }
        return true;
    }

    /** The "keep the last seconds" switch was turned on again: worth another try after a failure. */
    public static void replaySwitched() {
        gaveUp = false;
        encoder = 0;
    }

    // ---------------------------------------------------------------- every client tick

    public static void tick() {
        if (!available()) return;
        if (++ticks % 5 != 0) return;   // 4 times a second is plenty
        boolean wanted = Modules.recorder.enabled && Modules.recReplay.get();

        if (helper != null && !helper.isAlive()) {
            helper = null;
            if (System.currentTimeMillis() - startedAt < 5000 && encoder < ENCODERS.length - 1) {
                encoder++;   // this encoder doesn't work on this PC: try the next one
                if (recording() || wanted) startCapture();
            } else {
                status = "録画が止まりました（" + lastLogLine() + "）";
                gaveUp = true;
                recordFrom = -1;
            }
        }

        if (wanted && !gaveUp && !running() && helper == null && !recording()) startCapture();

        // saves whose last piece is complete now (ffmpeg moved on to the next one, or stopped)
        int newest = newest();
        for (Iterator<Save> it = waiting.iterator(); it.hasNext(); ) {
            Save s = it.next();
            if (newest > s.to || !running()) {
                it.remove();
                join(s);
                if (s.stopAfter && !wanted && !recording() && waiting.isEmpty()) stopCapture();
            }
        }
        if (!wanted && !recording() && waiting.isEmpty() && running()) stopCapture();

        if (ticks % 40 == 0) cleanUp(newest, wanted);
    }

    /** Deletes the pieces nothing needs any more. */
    private static void cleanUp(int newest, boolean replay) {
        int keepFrom = replay ? newest - (int) Math.ceil(Modules.recReplaySeconds.get() / 2.0) - 2 : newest - 1;
        if (recording()) keepFrom = Math.min(keepFrom, recordFrom);
        for (Save s : waiting) keepFrom = Math.min(keepFrom, s.from);
        for (int from : joining) keepFrom = Math.min(keepFrom, from);
        for (File f : pieces()) {
            if (index(f) < keepFrom) f.delete();
        }
    }

    // ---------------------------------------------------------------- ffmpeg

    private static File bufferDir() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "recordings"), ".buffer");
    }

    private static boolean startCapture() {
        if (running()) return true;
        File buf = bufferDir();
        buf.mkdirs();
        // numbers go on from what is there (pieces of a capture that just ended may still be waiting to be saved)
        base = Math.max(base, newest() + 1);
        int fps = Modules.recFps.get() == 1 ? 30 : 60;
        int monitor = Math.max(0, Math.round(Modules.recMonitor.get()) - 1);
        String[] e = ENCODERS[encoder];

        List<String> cmd = new ArrayList<>();
        cmd.add(new File(TOOLS, "FzRecorder.exe").getPath());
        cmd.add(pid());
        cmd.add(new File(buf, "ffmpeg.log").getPath());
        cmd.add(new File(TOOLS, "ffmpeg.exe").getPath());
        Collections.addAll(cmd, "-hide_banner", "-loglevel", "warning",
                "-f", "lavfi", "-i", Modules.recArea.get() == 0
                        // just the game window; FzRecorder puts the window handle in for {HWND}
                        ? "gfxcapture=hwnd={HWND}:max_framerate=" + fps
                        // a whole monitor (Desktop Duplication: fails while a game runs in exclusive fullscreen)
                        : "ddagrab=output_idx=" + monitor + ":framerate=" + fps,
                "-f", "s16le", "-ar", "48000", "-ac", "2", "-thread_queue_size", "1024", "-i", "pipe:0",
                "-map", "0:v", "-map", "1:a");
        if (e[1] != null) Collections.addAll(cmd, "-vf", e[1]);
        Collections.addAll(cmd, "-c:v", e[0]);
        cmd.addAll(Arrays.asList(e).subList(2, e.length));
        Collections.addAll(cmd, "-g", String.valueOf(fps * 2), "-force_key_frames", "expr:gte(t,n_forced*2)",
                "-c:a", "aac", "-b:a", "160k",
                "-f", "segment", "-segment_time", "2", "-segment_format", "mpegts", "-reset_timestamps", "1",
                "-segment_start_number", String.valueOf(base), new File(buf, "seg%06d.ts").getPath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(buf, "helper.log"));
            helper = pb.start();
            startedAt = System.currentTimeMillis();
            addShutdownHook();
            if (status.isEmpty() || status.startsWith("録画が止まりました")) status = "";
            return true;
        } catch (Exception ex) {
            helper = null;
            status = "録画を始められませんでした（" + ex.getMessage() + "）";
            return false;
        }
    }

    /** Closing the helper's stdin is its signal to stop ffmpeg (it does that within about 2 seconds). */
    private static void stopCapture() {
        Process p = helper;
        helper = null;
        if (p == null) return;
        try { p.getOutputStream().close(); } catch (Exception ignored) { }
        Thread t = new Thread(() -> {
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            } catch (InterruptedException ignored) { }
        }, "FzClient-RecorderStop");
        t.setDaemon(true);
        t.start();
    }

    private static void addShutdownHook() {
        if (hookAdded) return;
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = helper;
            if (p != null) {
                try { p.getOutputStream().close(); } catch (Exception ignored) { }
                try { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        }, "FzClient-RecorderShutdown"));
    }

    /** Joins pieces [from, to] into one mp4 in the recordings folder (in the background, no re-encoding). */
    private static void join(Save s) {
        List<File> parts = new ArrayList<>();
        for (File f : pieces()) {
            int i = index(f);
            if (i >= s.from && i <= s.to && f.length() > 0) parts.add(f);
        }
        if (parts.isEmpty()) {
            status = "保存できませんでした（録画がまだありません）";
            return;
        }
        Integer lock = s.from;
        joining.add(lock);
        File out = new File(new File(Minecraft.getMinecraft().mcDataDir, "recordings"), s.name);
        Thread t = new Thread(() -> {
            boolean ok = false;
            File list = new File(bufferDir(), "join-" + s.to + ".txt");
            try {
                try (Writer w = new OutputStreamWriter(new FileOutputStream(list), StandardCharsets.UTF_8)) {
                    for (File f : parts) w.write("file '" + f.getAbsolutePath().replace("\\", "/").replace("'", "'\\''") + "'\n");
                }
                Process p = new ProcessBuilder(new File(TOOLS, "ffmpeg.exe").getPath(), "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "concat", "-safe", "0", "-i", list.getPath(), "-c", "copy", "-movflags", "+faststart", out.getPath())
                        .redirectErrorStream(true).redirectOutput(new File(bufferDir(), "join.log")).start();
                ok = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 && out.length() > 0;
                if (!ok) p.destroy();
            } catch (Exception ignored) {
            } finally {
                list.delete();
                joining.remove(lock);
            }
            final boolean done = ok;
            status = done ? "保存しました: " + s.name : "保存できませんでした";
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                if (mc.thePlayer == null) return;
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + (done
                        ? EnumChatFormatting.YELLOW + dev.fzkc.fzhud.util.Lang.t("録画を保存しました: ") + s.name
                        : EnumChatFormatting.RED + dev.fzkc.fzhud.util.Lang.t("録画を保存できませんでした"))));
            });
        }, "FzClient-RecorderSave");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- pieces on disk

    private static File[] pieces() {
        File[] f = bufferDir().listFiles((dir, name) -> name.startsWith("seg") && name.endsWith(".ts"));
        return f == null ? new File[0] : f;
    }

    private static int index(File f) {
        try {
            return Integer.parseInt(f.getName().substring(3, f.getName().length() - 3));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Number of the piece being written now (the highest there is), base - 1 when there is none yet. */
    private static int newest() {
        int n = base - 1;
        for (File f : pieces()) n = Math.max(n, index(f));
        return n;
    }

    private static String lastLogLine() {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(new File(bufferDir(), "ffmpeg.log").toPath(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).trim().isEmpty()) return lines.get(i).trim();
        } catch (Exception ignored) { }
        return "?";
    }

    private static String pid() {
        String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = n.indexOf('@');
        return at > 0 ? n.substring(0, at) : n;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
    }
}
