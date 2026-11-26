'use strict';
// Recording, done by the launcher while the game runs (used to be inside the game: feature/Recorder.java).
//
// FzRecorder.exe (src/tools/FzRecorder.cs) runs ffmpeg and feeds it only the game's sound (Windows' process
// loopback). ffmpeg takes the picture of the game window (Windows.Graphics.Capture), or of the monitor the game is
// on, and encodes it on the GPU (NVENC, else AMF, else Media Foundation).
//
// ffmpeg writes 2-second pieces into <game folder>/recordings/.buffer. The pieces of one ffmpeg run keep one clock
// and are MPEG-TS, so a save puts them back together byte by byte (what they were cut from) and only copies that
// into an mp4. (Joining them as separate files, each starting at 0, left a small jump at every piece.)
// Both kinds of recording only join pieces:
// "save the last 30 s" takes the newest ones, a recording everything from its start to its stop. A save waits until
// ffmpeg has moved on to the next piece, so the moment the button was pressed is in it. Pieces nothing needs any
// more are deleted.
//
// The helper stops ffmpeg when its stdin closes, so it also stops when the launcher goes away. It stops by itself
// when the game exits.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// GPU encoders first; each: encoder, video filter (null = take the GPU frames as they are), options
const ENCODERS = [
  // (forced-idr: every 2 s a picture that needs nothing from before it, so a clip can start at any piece)
  // (p1 and no B-frames / lookahead / AQ: the lightest NVENC work, so it takes as little GPU time from the game as
  // it can; the higher bitrate cap keeps the picture good)
  { name: 'NVENC', codec: 'h264_nvenc', vf: null, opts: ['-preset', 'p1', '-tune', 'll', '-bf', '0', '-rc-lookahead', '0', '-spatial-aq', '0', '-temporal-aq', '0', '-multipass', 'disabled', '-rc', 'vbr', '-cq', '23', '-b:v', '0', '-maxrate', '80M', '-bufsize', '160M', '-forced-idr', '1'] },
  { name: 'AMF', codec: 'h264_amf', vf: null, opts: ['-quality', 'speed', '-rc', 'cqp', '-qp_i', '22', '-qp_p', '24'] },
  { name: 'Media Foundation', codec: 'h264_mf', vf: 'hwdownload,format=bgra,format=nv12', opts: ['-b:v', '20M'] },
];
const SEG = /^seg(\d{6})\.ts$/;
// No new piece for this long: the window being captured is gone (see tick). Doubles while it does not help.
const STALL_MS = 8000;

const stamp = () => {
  const d = new Date(), p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}.${p(d.getMinutes())}.${p(d.getSeconds())}`;
};

class Capture {
  /**
   * game: { pid, gameDir }, tools: { ffmpeg, helper }, options(): { replay, replaySeconds, area, fps },
   * notify(text, error): a finished save (or a failure), changed(): the state changed.
   */
  constructor({ game, tools, options, notify, changed, log, sound }) {
    Object.assign(this, { game, tools, options, notify, changed, log, sound: sound || (() => {}) });
    this.helper = null;
    this.startedAt = 0;
    this.encoder = 0;
    this.base = 0;
    this.runs = [];          // the first piece of each ffmpeg run (pieces of different runs have different clocks)
    this.recordFrom = -1;
    this.recordStartedAt = 0;
    this.status = '';
    this.gaveUp = false;
    this.restarts = [];      // times the capture stopped by itself and was started again
    this.waiting = [];       // saves waiting for their last piece: { from, to, name, stopAfter }
    this.joining = new Set();
    this.fullscreen = false; // what the game says (F11): the window is made again then
    this.lastNewest = -1;    // the newest piece at the last look, and since when
    this.newestAt = 0;
    this.stallWait = STALL_MS;
    this.stalled = new Set(); // pieces the watchdog gave up on: a frozen picture with up to a minute of sound
    this.ticks = 0;
    this.timer = setInterval(() => this.tick(), 250);
  }

  get dir() { return path.join(this.game.gameDir, 'recordings'); }
  get buffer() { return path.join(this.dir, '.buffer'); }
  get running() { return !!this.helper; }
  get recording() { return this.recordFrom >= 0; }

  state() {
    const o = this.options();
    return {
      running: this.running, recording: this.recording, status: this.status,
      encoder: this.running ? ENCODERS[this.encoder].name : '',
      seconds: this.recording ? Math.floor((Date.now() - this.recordStartedAt) / 1000) : 0,
      replay: !!o.replay, replaySeconds: o.replaySeconds,
    };
  }

  setStatus(s) {
    this.status = s;
    this.changed();
  }

  // ---------------------------------------------------------------- actions

  /** Starts a recording, or stops it and saves it. */
  toggleRecording() {
    if (this.recording) {
      this.waiting.push({ from: this.recordFrom, to: this.newest(), name: `FzClient_${stamp()}.mp4`, stopAfter: true });
      this.recordFrom = -1;
      this.setStatus('保存しています…');
      this.sound('stop');
      return;
    }
    this.gaveUp = false;   // asked for by hand: every encoder gets another chance
    if (!this.running) this.encoder = 0;
    if (!this.start()) return;
    // the piece being written now: up to 2 s from before the button
    this.recordFrom = Math.max(this.base, this.newest());
    this.recordStartedAt = Date.now();
    this.setStatus('録画中');
    this.sound('start');
  }

  /**
   * Saves the last N seconds (only while they are being kept). They may span several runs of ffmpeg: it is started
   * again when the game connects, goes fullscreen... (a save right after the start used to keep only the seconds
   * since then). While ffmpeg is being started again the pieces written so far are saved.
   */
  saveReplay() {
    const o = this.options();
    if (!o.replay) {
      this.setStatus('「直前の数十秒をいつも残しておく」を ON にしてください');
      return;
    }
    const to = this.newest(), count = this.replayPieces();
    // the oldest piece that may be needed (the newest one may still be empty now): kept until the save is done
    const mine = this.pieces().map(p => p[0]).filter(i => i >= this.firstPiece() && i <= to && !this.stalled.has(i)).sort((a, b) => a - b);
    const from = mine.length ? mine[Math.max(0, mine.length - count - 1)] : to;
    this.waiting.push({ from, to, count, name: `FzClient_replay_${stamp()}.mp4`, stopAfter: false });
    this.setStatus('保存しています…');
    this.sound('save');
  }

  /** The options changed (the "keep the last seconds" switch, area, FPS): start again where it matters. */
  optionsChanged() {
    this.gaveUp = false;
    this.encoder = 0;
    if (this.running && !this.recording) { this.stop(); }
    this.tick();
  }

  /** The game says it went (or left) fullscreen: its window is made again, so capture the new one. */
  setFullscreen(fs) {
    if (fs === this.fullscreen) return;
    this.fullscreen = fs;
    if (this.running && this.options().area !== 'monitor') {
      this.stop();
      setTimeout(() => this.start(), 600);   // the new window needs a moment (the new helper waits for the old one to let go of ffmpeg.log)
    }
  }

  // ---------------------------------------------------------------- 4 times a second

  tick() {
    const o = this.options();
    const wanted = !!o.replay;

    if (wanted && !this.gaveUp && !this.running && !this.recording) this.start();

    // saves whose last piece is complete now (ffmpeg moved on to the next one, or stopped)
    const newest = this.newest();
    for (const s of [...this.waiting]) {
      if (newest > s.to || !this.running) {
        this.waiting.splice(this.waiting.indexOf(s), 1);
        this.join(s);
      }
    }
    if (!wanted && !this.recording && !this.waiting.length && this.running) this.stop();
    if (this.running) this.watchStall(newest);

    if (++this.ticks % 40 === 0) this.cleanUp(newest, wanted);
    if (this.recording && this.ticks % 4 === 0) this.changed();   // the clock on the button
  }

  /**
   * The capture holds on to the window it found when it started. At the start of the game that can be a window
   * that is gone a moment later: no more pictures come, only the sound, so the piece never ends, a replay waits for
   * it forever and the clip is black (turning the replay off and on again helped, because that starts it again).
   * When no new piece came for a while ffmpeg is started again; it then finds the window there is now.
   */
  watchStall(newest) {
    const now = Date.now();
    if (newest !== this.lastNewest) {
      this.lastNewest = newest;
      this.newestAt = now;
      if (newest > this.base) this.stallWait = STALL_MS;   // pieces come again (the first one of a run always appears)
      return;
    }
    if (now - Math.max(this.newestAt, this.startedAt) < this.stallWait) return;
    this.log(`[capture] no new picture for ${Math.round(this.stallWait / 1000)} s: started again`);
    this.stallWait = Math.min(this.stallWait * 2, 60000);   // e.g. the game is minimized: do not start again every 8 s
    this.newestAt = now;
    if (newest >= 0) this.stalled.add(newest);   // left out of saves: it would be a long black or frozen stretch
    this.stop();
    setTimeout(() => {
      if (!this.helper && !this.closed && !this.gaveUp && (this.options().replay || this.recording || this.waiting.length)) this.start();
    }, 600);
  }

  /** Pieces in one replay (2 seconds each). */
  replayPieces() {
    return Math.ceil(this.options().replaySeconds / 2);
  }

  /** The first piece of this game: pieces before it are left over from an earlier start. */
  firstPiece() {
    return this.runs.length ? this.runs[0] : this.base;
  }

  /** Deletes the pieces nothing needs any more. */
  cleanUp(newest, replay) {
    // for a replay the newest pieces by count: the numbers have gaps where ffmpeg was started again
    const nums = this.pieces().map(p => p[0]).filter(i => !this.stalled.has(i)).sort((a, b) => a - b);
    const keep = this.replayPieces() + 2;
    let keepFrom = !replay ? newest - 1 : nums.length > keep ? nums[nums.length - keep] : -Infinity;
    if (this.recording) keepFrom = Math.min(keepFrom, this.recordFrom);
    for (const s of this.waiting) keepFrom = Math.min(keepFrom, s.from);
    for (const f of this.joining) keepFrom = Math.min(keepFrom, f);
    for (const [i, f] of this.pieces()) if (i < keepFrom) try { fs.unlinkSync(f); } catch { /* still open */ }
  }

  // ---------------------------------------------------------------- ffmpeg

  start() {
    if (this.running) return true;
    fs.mkdirSync(this.buffer, { recursive: true });
    // numbers go on from what is there (+2: an ffmpeg that is still stopping may write one more piece)
    this.base = Math.max(this.base, this.newest() + 2);
    this.runs.push(this.base);
    const o = this.options(), fps = o.fps === 30 ? 30 : 60, e = ENCODERS[this.encoder];
    // the game window (FzRecorder puts in its handle), or the monitor it is on. (A window exactly as big as the
    // screen in front gets no new pictures in a window capture: FzClient's Borderless is one pixel taller for
    // that. Capturing the monitor instead made the mouse cursor disappear.)
    const monitor = o.area === 'monitor';
    const source = monitor ? `gfxcapture=hmonitor={HMONITOR}:max_framerate=${fps}` : `gfxcapture=hwnd={HWND}:max_framerate=${fps}`;
    const args = [String(this.game.pid), path.join(this.buffer, 'ffmpeg.log'), this.tools.ffmpeg,
      '-hide_banner', '-loglevel', 'warning',
      '-f', 'lavfi', '-i', source,
      '-f', 's16le', '-ar', '48000', '-ac', '2', '-thread_queue_size', '1024', '-i', 'pipe:0',
      '-map', '0:v', '-map', '1:a',
      ...(e.vf ? ['-vf', e.vf] : []), '-c:v', e.codec, ...e.opts,
      '-g', String(fps * 2), '-force_key_frames', 'expr:gte(t,n_forced*2)',
      '-c:a', 'aac', '-b:a', '160k',
      '-f', 'segment', '-segment_time', '2', '-segment_format', 'mpegts',
      '-segment_start_number', String(this.base), path.join(this.buffer, 'seg%06d.ts')];
    let child;
    try {
      const out = fs.openSync(path.join(this.buffer, 'helper.log'), 'w');
      child = spawn(this.tools.helper, args, { stdio: ['pipe', out, out], windowsHide: true });
      fs.closeSync(out);
    } catch (err) {
      this.setStatus('録画を始められませんでした（' + err.message + '）');
      return false;
    }
    this.helper = child;
    this.startedAt = Date.now();
    child.on('error', err => this.log('[capture] ' + err.message));
    child.on('exit', code => this.exited(child, code));
    if (this.status.startsWith('録画が止まりました')) this.status = '';
    this.log(`[capture] started (${e.name}, ${monitor ? 'monitor' : 'window'}, ${fps} fps) from piece ${this.base}`);
    this.changed();
    return true;
  }

  exited(child, code) {
    if (this.helper !== child) return;   // stopped on purpose
    this.helper = null;
    const o = this.options();
    // the game went away (the helper stops by itself then): nothing to start again
    const gameGone = (() => { try { process.kill(this.game.pid, 0); return false; } catch (e) { return e.code !== 'EPERM'; } })();
    const want = (this.recording || o.replay) && !gameGone && !this.closed;
    this.log(`[capture] stopped by itself (${code}) after ${Date.now() - this.startedAt} ms: ${this.lastLogLine()}`);
    if (!want) { this.changed(); return; }
    if (Date.now() - this.startedAt < 5000 && this.encoder < ENCODERS.length - 1) {
      this.encoder++;   // this encoder doesn't work on this PC: the next one
      this.start();
      return;
    }
    // ran for a while (the window was made again, the game went away...): start again, but not forever
    const now = Date.now();
    this.restarts = this.restarts.filter(t => now - t < 30000);
    this.restarts.push(now);
    if (Date.now() - this.startedAt >= 5000 && this.restarts.length <= 3) {
      setTimeout(() => { if (!this.helper && !this.closed) this.start(); }, 500);
      return;
    }
    this.gaveUp = true;
    this.recordFrom = -1;
    this.setStatus('録画が止まりました（' + this.lastLogLine() + '）');
  }

  /** Closing the helper's stdin is its signal to stop ffmpeg (it does that within about 2 seconds). */
  stop() {
    const p = this.helper;
    this.helper = null;
    if (!p) return;
    try { p.stdin.end(); } catch { /* gone */ }
    setTimeout(() => { if (p.exitCode === null) try { p.kill(); } catch { /* gone */ } }, 6000);
    this.changed();
  }

  /** Joins pieces [from, to] into one mp4 in the recordings folder (no re-encoding). */
  join(s) {
    const size = f => { try { return fs.statSync(f).size; } catch { return 0; } };
    let parts = this.pieces().filter(([i, f]) => i >= s.from && i <= s.to && !this.stalled.has(i) && size(f) > 0).sort((a, b) => a[0] - b[0]);
    if (s.count) parts = parts.slice(-s.count);   // a replay: the last N seconds
    if (s.stopAfter && !this.options().replay && !this.recording && !this.waiting.length) this.stop();
    if (!parts.length) {
      this.setStatus('保存できませんでした（録画がまだありません）');
      this.notify(this.status, true);
      return;
    }
    // the pieces of each ffmpeg run, in order
    const runOf = i => { let r = 0; for (const b of this.runs) if (b <= i) r = b; return r; };
    const groups = [];
    for (const [i, f] of parts) {
      const last = groups[groups.length - 1];
      if (last && last.run === runOf(i)) last.files.push(f); else groups.push({ run: runOf(i), files: [f] });
    }
    this.joining.add(s.from);
    const out = path.join(this.dir, s.name);
    const temps = [];
    let err = '';
    const onErr = e => { err += e; };
    const run = async () => {
      if (groups.length === 1) return this.remux(groups[0].files, out, onErr);
      // ffmpeg was started again in between: each run on its own, then those joined (mp4 lengths are exact)
      const mp4s = [];
      for (const [n, g] of groups.entries()) {
        const t = path.join(this.buffer, `join-${s.to}-${n}.mp4`);
        temps.push(t);
        if (!await this.remux(g.files, t, onErr)) return false;
        mp4s.push(t);
      }
      const list = path.join(this.buffer, `join-${s.to}.txt`);
      temps.push(list);
      fs.writeFileSync(list, mp4s.map(f => `file '${f.replace(/\\/g, '/').replace(/'/g, "'\\''")}'\n`).join(''));
      return this.ffmpeg(['-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', '-movflags', '+faststart', out], null, onErr);
    };
    run().catch(() => false).then(ok => {
      for (const t of temps) try { fs.unlinkSync(t); } catch { /* fine */ }
      try { ok = ok && fs.statSync(out).size > 0; } catch { ok = false; }
      this.joining.delete(s.from);
      if (!ok && err) this.log('[capture] join: ' + err.trim().slice(0, 1000));
      this.setStatus(ok ? '保存しました: ' + s.name : '保存できませんでした');
      this.notify(ok ? '録画を保存しました: ' + s.name : '録画を保存できませんでした', !ok, ok ? out : null);
    });
  }

  /** The MPEG-TS pieces of one run, given to ffmpeg one after another as one stream and copied into an mp4. */
  remux(files, out, onErr) {
    return this.ffmpeg(['-f', 'mpegts', '-i', 'pipe:0', '-c', 'copy', '-movflags', '+faststart', out], async stdin => {
      for (const f of files) {
        await new Promise(resolve => {
          const r = fs.createReadStream(f);
          r.on('error', resolve);
          r.on('end', resolve);
          r.pipe(stdin, { end: false });
        });
      }
      stdin.end();
    }, onErr);
  }

  /** Runs ffmpeg; feed(stdin) writes its input (or null for none). True when it finished without an error. */
  ffmpeg(args, feed, onErr) {
    return new Promise(resolve => {
      const p = spawn(this.tools.ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', ...args],
        { windowsHide: true, stdio: [feed ? 'pipe' : 'ignore', 'ignore', 'pipe'] });
      p.stderr.on('data', d => onErr(String(d)));
      const kill = setTimeout(() => p.kill(), 300000);
      if (feed) {
        p.stdin.on('error', () => { /* ffmpeg stopped early: its exit code says so */ });
        feed(p.stdin).catch(() => { try { p.stdin.end(); } catch { /* gone */ } });
      }
      p.on('error', () => { clearTimeout(kill); resolve(false); });
      p.on('close', code => { clearTimeout(kill); resolve(code === 0); });
    });
  }

  // ---------------------------------------------------------------- pieces on disk

  /** [[number, file]] */
  pieces() {
    let names = [];
    try { names = fs.readdirSync(this.buffer); } catch { /* none yet */ }
    return names.map(n => SEG.exec(n)).filter(Boolean).map(m => [Number(m[1]), path.join(this.buffer, m[0])]);
  }

  /** Number of the piece being written now (the highest there is), base - 1 when there is none yet. */
  newest() {
    let n = this.base - 1;
    for (const [i] of this.pieces()) n = Math.max(n, i);
    return n;
  }

  lastLogLine() {
    try {
      const lines = fs.readFileSync(path.join(this.buffer, 'ffmpeg.log'), 'utf8').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
      return lines.length ? lines[lines.length - 1].slice(0, 200) : '?';
    } catch { return '?'; }
  }

  /** The game is gone: a recording that was running is saved, the rest stops. */
  close() {
    this.closed = true;
    clearInterval(this.timer);
    if (this.recording) {
      this.waiting.push({ from: this.recordFrom, to: this.newest(), name: `FzClient_${stamp()}.mp4`, stopAfter: true });
      this.recordFrom = -1;
    }
    this.stop();
    // what is waiting is joined once ffmpeg has written its last piece
    const pending = this.waiting.splice(0);
    if (pending.length) setTimeout(() => pending.forEach(s => this.join(s)), 2500);
    // then the pieces are not needed any more (only ours: the game may have been started again meanwhile)
    const last = this.newest() + 1;   // + the one a stopping ffmpeg may still write
    const clean = () => {
      if (this.joining.size) { setTimeout(clean, 5000); return; }
      for (const [i, f] of this.pieces()) if (i <= last) try { fs.unlinkSync(f); } catch { /* still open */ }
    };
    setTimeout(clean, 10000);
  }
}

module.exports = { Capture };
