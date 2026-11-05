// FzRecorder <game pid> <log file> <ffmpeg.exe> <ffmpeg arguments...>
//
// The FzClient recorder's helper. It starts ffmpeg with the given arguments (the sound comes in on "pipe:0" as
// raw 48 kHz, 16-bit, stereo PCM) and feeds it the sound of the game only, using Windows' process loopback capture
// (Windows 10 2004 or newer): Discord, music and system sounds are not recorded. While the game plays nothing,
// silence is written, so the sound always keeps real time and stays in sync with the video.
//
// ffmpeg is stopped (and this helper ends) when any of these happens, so a recording can never keep running alone:
//  - the game asks for it by closing this helper's stdin,
//  - the game process ends (also when it crashes or is killed: its end of the stdin pipe closes too),
//  - ffmpeg itself ends.
// If the sound cannot be captured on this PC, silence is recorded and a line is written to the log.
//
// Built on the user's PC by the launcher with the C# compiler that comes with Windows (.NET Framework 4), so only
// C# 5 is used here.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

[ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint frames);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService([MarshalAs(UnmanagedType.LPStruct)] Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}

[ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IActivateAudioInterfaceAsyncOperation {
    [PreserveSig] int GetActivateResult(out int result, [MarshalAs(UnmanagedType.IUnknown)] out object activated);
}

[ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IActivateAudioInterfaceCompletionHandler {
    void ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
}

// marks the handler as callable from any thread (required by ActivateAudioInterfaceAsync)
[ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAgileObject { }

[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
class ActivationDone : IActivateAudioInterfaceCompletionHandler, IAgileObject {
    public readonly ManualResetEvent Done = new ManualResetEvent(false);
    public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation) { Done.Set(); }
}

static class FzRecorder {
    const int Rate = 48000, Channels = 2, BytesPerFrame = 4;
    const int SHARED = 0;
    const int LOOPBACK = 0x00020000, EVENTCALLBACK = 0x00040000, AUTOCONVERTPCM = unchecked((int) 0x80000000), SRC_DEFAULT_QUALITY = 0x08000000;
    const uint SILENT = 0x2;

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
    static extern void ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        [MarshalAs(UnmanagedType.LPStruct)] Guid riid, IntPtr activationParams,
        IActivateAudioInterfaceCompletionHandler handler, out IActivateAudioInterfaceAsyncOperation operation);

    [DllImport("kernel32.dll")]
    static extern IntPtr GetStdHandle(int which);

    static volatile bool stop;
    static StreamWriter log;

    [MTAThread]
    static int Main(string[] args) {
        int pid;
        if (args.Length < 4 || !int.TryParse(args[0], out pid)) {
            Console.Error.WriteLine("usage: FzRecorder <game pid> <log file> <ffmpeg.exe> <ffmpeg arguments...>");
            return 2;
        }
        log = new StreamWriter(args[1], false, new UTF8Encoding(false));
        log.AutoFlush = true;
        Process ffmpeg = null;
        try {
            Process game = Process.GetProcessById(pid);

            // "{HWND}" in the arguments becomes the game's window (for ffmpeg's gfxcapture, which records just that
            // window: nothing on top of it shows, any monitor, also fullscreen)
            string hwnd = null;
            for (int i = 3; i < args.Length; i++) {
                if (args[i].IndexOf("{HWND}", StringComparison.Ordinal) < 0) continue;
                if (hwnd == null) hwnd = GameWindow(game);
                args[i] = args[i].Replace("{HWND}", hwnd);
            }

            ProcessStartInfo psi = new ProcessStartInfo(args[2]);
            StringBuilder line = new StringBuilder();
            for (int i = 3; i < args.Length; i++) line.Append(i > 3 ? " " : "").Append(Quote(args[i]));
            psi.Arguments = line.ToString();
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardInput = true;
            psi.RedirectStandardError = true;
            ffmpeg = Process.Start(psi);
            ffmpeg.ErrorDataReceived += (sender, e) => { if (e.Data != null) Log(e.Data); };
            ffmpeg.BeginErrorReadLine();

            // the game closes our stdin to stop us (and the pipe closes by itself if the game goes away)
            Thread watchStdin = new Thread(() => {
                // only the end of the pipe means stop: anything written into it (a text writer's BOM...) is skipped
                try {
                    FileStream stdin = new FileStream(new Microsoft.Win32.SafeHandles.SafeFileHandle(GetStdHandle(-10), false), FileAccess.Read);
                    byte[] skip = new byte[256];
                    while (stdin.Read(skip, 0, skip.Length) > 0) { }
                } catch { }
                stop = true;
            });
            watchStdin.IsBackground = true;
            watchStdin.Start();

            Feed(pid, game, ffmpeg);
        } catch (Exception e) {
            Log("FzRecorder: " + e.Message);
        } finally {
            if (ffmpeg != null) {
                try { ffmpeg.StandardInput.Close(); } catch { }
                // the video input never ends by itself: give ffmpeg a moment to write what it has, then stop it
                if (!ffmpeg.WaitForExit(1500)) { try { ffmpeg.Kill(); ffmpeg.WaitForExit(3000); } catch { } }
            }
            log.Close();
        }
        return 0;
    }

    /** The game's main window, as a number for ffmpeg (waits a little: the window may still be opening). */
    static string GameWindow(Process game) {
        for (int i = 0; i < 50; i++) {
            game.Refresh();
            IntPtr h = game.MainWindowHandle;
            if (h != IntPtr.Zero) return ((long) h).ToString();
            Thread.Sleep(100);
        }
        throw new Exception("the game window was not found");
    }

    /** Writes the game's sound (or silence) into ffmpeg until it is time to stop. */
    static void Feed(int pid, Process game, Process ffmpeg) {
        Stream output = ffmpeg.StandardInput.BaseStream;
        IAudioClient client = null;
        IAudioCaptureClient capture = null;
        AutoResetEvent ready = new AutoResetEvent(false);
        try {
            client = Activate(pid);
            Check(client.Initialize(SHARED, LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY,
                2000000 /* 200 ms */, 0, PcmFormat(), IntPtr.Zero), "Initialize");
            Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()), "SetEventHandle");
            object service;
            Check(client.GetService(typeof(IAudioCaptureClient).GUID, out service), "GetService");
            capture = (IAudioCaptureClient) service;
            Check(client.Start(), "Start");
        } catch (Exception e) {
            Log("FzRecorder: the game's sound can't be recorded on this PC, recording silence (" + e.Message + ")");
            capture = null;
        }

        byte[] buffer = new byte[Rate * BytesPerFrame];   // one second
        Stopwatch clock = Stopwatch.StartNew();
        long written = 0;                                 // frames sent so far
        try {
            while (!stop && !game.HasExited && !ffmpeg.HasExited) {
                ready.WaitOne(20);
                uint packet;
                while (capture != null && capture.GetNextPacketSize(out packet) == 0 && packet > 0) {
                    IntPtr data; uint frames, flags; ulong devicePosition, qpcPosition;
                    if (capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpcPosition) != 0) break;
                    int bytes = (int) frames * BytesPerFrame;
                    if (bytes > buffer.Length) buffer = new byte[bytes];
                    if ((flags & SILENT) != 0) Array.Clear(buffer, 0, bytes);
                    else Marshal.Copy(data, buffer, 0, bytes);
                    capture.ReleaseBuffer(frames);
                    output.Write(buffer, 0, bytes);
                    written += frames;
                }
                // nothing is played: fill the gap with silence once it is more than 50 ms behind real time
                long due = (long) (clock.Elapsed.TotalSeconds * Rate);
                if (due - written > Rate / 20) {
                    int frames = (int) Math.Min(due - written - Rate / 100, Rate);
                    Array.Clear(buffer, 0, frames * BytesPerFrame);
                    output.Write(buffer, 0, frames * BytesPerFrame);
                    written += frames;
                }
                output.Flush();
            }
        } catch (IOException) {
            // ffmpeg closed its input: it is ending
        } finally {
            if (client != null) client.Stop();
        }
    }

    /** 48 kHz 16-bit stereo PCM (Windows converts whatever the game plays). */
    static IntPtr PcmFormat() {
        IntPtr format = Marshal.AllocHGlobal(18);
        Marshal.WriteInt16(format, 0, 1);                       // WAVE_FORMAT_PCM
        Marshal.WriteInt16(format, 2, Channels);
        Marshal.WriteInt32(format, 4, Rate);
        Marshal.WriteInt32(format, 8, Rate * BytesPerFrame);
        Marshal.WriteInt16(format, 12, BytesPerFrame);
        Marshal.WriteInt16(format, 14, 16);
        Marshal.WriteInt16(format, 16, 0);
        return format;
    }

    /** Opens the "virtual" audio device that hears exactly one process tree. */
    static IAudioClient Activate(int pid) {
        // AUDIOCLIENT_ACTIVATION_PARAMS { PROCESS_LOOPBACK, { TargetProcessId, INCLUDE_TARGET_PROCESS_TREE } }
        IntPtr activation = Marshal.AllocHGlobal(12);
        Marshal.WriteInt32(activation, 0, 1);
        Marshal.WriteInt32(activation, 4, pid);
        Marshal.WriteInt32(activation, 8, 0);
        // PROPVARIANT holding it as a VT_BLOB
        IntPtr variant = Marshal.AllocHGlobal(24);
        for (int i = 0; i < 24; i++) Marshal.WriteByte(variant, i, 0);
        Marshal.WriteInt16(variant, 0, 65);                     // VT_BLOB
        Marshal.WriteInt32(variant, 8, 12);
        Marshal.WriteIntPtr(variant, IntPtr.Size == 8 ? 16 : 12, activation);

        ActivationDone done = new ActivationDone();
        IActivateAudioInterfaceAsyncOperation operation;
        ActivateAudioInterfaceAsync("VAD\\Process_Loopback", typeof(IAudioClient).GUID, variant, done, out operation);
        if (!done.Done.WaitOne(5000)) throw new Exception("activation timed out");
        int result; object activated;
        Check(operation.GetActivateResult(out result, out activated), "GetActivateResult");
        Check(result, "activation");
        return (IAudioClient) activated;
    }

    static void Check(int hr, string what) {
        if (hr < 0) throw new Exception(what + " failed (0x" + hr.ToString("X8") + ")");
    }

    static void Log(string text) {
        lock (log) { try { log.WriteLine(text); } catch { } }
    }

    /** One argument for the Windows command line (CommandLineToArgvW rules). */
    static string Quote(string a) {
        if (a.Length > 0 && a.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return a;
        StringBuilder sb = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in a) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') sb.Append('\\', slashes * 2 + 1).Append('"');
            else sb.Append('\\', slashes).Append(c);
            slashes = 0;
        }
        sb.Append('\\', slashes * 2).Append('"');
        return sb.ToString();
    }
}
