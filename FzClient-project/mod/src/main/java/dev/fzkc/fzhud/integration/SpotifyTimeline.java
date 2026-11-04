package dev.fzkc.fzhud.integration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * How far into the song Spotify is. Windows' media controls (the same thing the volume flyout shows) know the
 * position and length; Java can't reach them directly, so a small PowerShell helper reads them once a second
 * and prints one line. It starts only when the HUD needs it and quits by itself when the game closes.
 * <p>
 * If that isn't available, the length from the cover-art search is used and the position is counted from when
 * the song started (only for songs that started while the game was running, so it's never a wild guess).
 */
public final class SpotifyTimeline {
    private SpotifyTimeline() {}

    private static volatile Process helper;
    private static volatile boolean failed;
    private static volatile long wantedUntil;

    // last values from the helper
    private static volatile long posMs, endMs, receivedAt;
    private static volatile boolean playing, valid;

    // fallback: our own clock for songs we saw start
    private static int clockSong = -1;
    private static long clockMs, clockAt;
    private static boolean clockKnown;

    private static final String SCRIPT =
            "$ErrorActionPreference='SilentlyContinue';"
            + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
            + "$at=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1"
            + " -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
            + "$T=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime];"
            + "$task=$at.MakeGenericMethod($T).Invoke($null,@($T::RequestAsync()));$task.Wait(-1)|Out-Null;$m=$task.Result;"
            + "if(-not $m){[Console]::Out.WriteLine('X');exit};"
            + "while($true){"
            + "if(-not(Get-Process -Id %PID% -ErrorAction SilentlyContinue)){exit};"
            + "$s=$m.GetSessions()|?{$_.SourceAppUserModelId -like '*Spotify*'}|Select-Object -First 1;"
            + "if($s){$t=$s.GetTimelineProperties();$p=$s.GetPlaybackInfo();"
            + "$age=[long]([DateTimeOffset]::Now-$t.LastUpdatedTime).TotalMilliseconds;"
            + "[Console]::Out.WriteLine('T '+[long]$t.Position.TotalMilliseconds+' '+[long]$t.EndTime.TotalMilliseconds+' '+$age+' '+[int]$p.PlaybackStatus)}"
            + "else{[Console]::Out.WriteLine('N')};"
            + "[Console]::Out.Flush();Start-Sleep -Milliseconds 1000}";

    private static int pid() {
        try {
            String n = ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(n.substring(0, n.indexOf('@')));
        } catch (Exception e) {
            return -1;
        }
    }

    /** When the helper's state is looked at next (once a second is plenty; this is called every frame). */
    private static long nextCheck;
    private static long startedAt;
    /** Times the helper quit by itself soon after starting; after 3 it isn't started again (fallback clock only). */
    private static int quickExits;
    /** Set when we stopped the helper ourselves because nothing was showing the bar. */
    private static volatile boolean stoppedIdle;
    private static boolean hookAdded;

    /** Called while the bar is drawn: keeps the helper running. */
    public static void keepAlive() {
        long now = System.currentTimeMillis();
        wantedUntil = now + 5000L;
        if (failed || now < nextCheck) return;
        nextCheck = now + 1000L;
        if (helper != null) {
            if (helper.isAlive()) return;
            // it ended without us stopping it, right after starting: something on this PC stops it
            if (!stoppedIdle && now - startedAt < 15000L && ++quickExits >= 3) {
                failed = true;
                return;
            }
        }
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            failed = true;
            return;
        }
        int pid = pid();
        if (pid <= 0) {
            failed = true;
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-Command", SCRIPT.replace("%PID%", String.valueOf(pid)));
            pb.redirectErrorStream(true);
            stoppedIdle = false;
            startedAt = now;
            helper = pb.start();
            final Process p = helper;
            Thread reader = new Thread(() -> read(p), "FzClient-SpotifyTimeline");
            reader.setDaemon(true);
            reader.start();
            if (!hookAdded) {
                // one hook for whichever helper is running when the game closes
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    Process h = helper;
                    if (h != null) h.destroy();
                }));
            }
        } catch (Throwable t) {
            failed = true;
        }
    }

    private static void read(Process p) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.equals("X")) {
                    failed = true;
                    break;
                }
                if (line.equals("N")) {
                    valid = false;
                } else if (line.startsWith("T ")) {
                    String[] f = line.split(" ");
                    if (f.length < 5) continue;
                    long pos = Long.parseLong(f[1]), end = Long.parseLong(f[2]), age = Long.parseLong(f[3]);
                    int status = Integer.parseInt(f[4]);
                    playing = status == 4;
                    endMs = end;
                    posMs = pos + (playing ? Math.max(0, Math.min(age, end)) : 0);
                    receivedAt = System.currentTimeMillis();
                    valid = end > 0;
                }
                // nobody has looked at the bar for a while: stop
                if (System.currentTimeMillis() > wantedUntil) {
                    stoppedIdle = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            p.destroy();
        }
    }

    /**
     * 0..1 through the current song, or -1 when unknown.
     * songId / songPlaying / lengthMs come from the window watcher and the cover search.
     */
    public static float progress(int songId, boolean songPlaying, long lengthMs) {
        keepAlive();
        long now = System.currentTimeMillis();
        if (valid && now - receivedAt < 4000L) {
            long pos = posMs + (playing ? now - receivedAt : 0);
            return Math.max(0f, Math.min(1f, pos / (float) endMs));
        }
        // fallback clock
        if (songId != clockSong) {
            clockKnown = clockSong != -1; // the first song we see may already be half way: don't guess
            clockSong = songId;
            clockMs = 0;
            clockAt = now;
        }
        if (songPlaying) clockMs += now - clockAt;
        clockAt = now;
        if (!clockKnown || lengthMs <= 0) return -1f;
        return Math.max(0f, Math.min(1f, clockMs / (float) lengthMs));
    }
}
