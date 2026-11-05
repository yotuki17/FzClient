'use strict';
// Recording, done by the launcher while the game runs (used to be inside the game: feature/Recorder.java).
//
// FzRecorder.exe (src/tools/FzRecorder.cs) runs ffmpeg and feeds it only the game's sound (Windows' process
// loopback). ffmpeg takes the picture of the game window (Windows.Graphics.Capture), or of the monitor the game is
// on, and encodes it on the GPU (NVENC, else AMF, else Media Foundation).
//
// ffmpeg writes 2-second pieces into <game folder>/recordings/.buffer. Both kinds of recording only join pieces:
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
  { name: 'NVENC', codec: 'h264_nvenc', vf: null, opts: ['-preset', 'p4', '-rc', 'vbr', '-cq', '23', '-b:v', '0', '-maxrate', '60M', '-bufsize', '120M'] },
  { name: 'AMF', codec: 'h264_amf', vf: null, opts: ['-quality', 'speed', '-rc', 'cqp', '-qp_i', '22', '-qp_p', '24'] },
  { name: 'Media Foundation', codec: 'h264_mf', vf: 'hwdownload,format=bgra,format=nv12', opts: ['-b:v', '20M'] },
];
const SEG = /^seg(\d{6})\.ts$/;

const stamp = () => {
  const d = new Date(), p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}.${p(d.getMinutes())}.${p(d.getSeconds())}`;
};

class Capture {
  /**
   * game: { pid, gameDir }, tools: { ffmpeg, helper }, options(): { replay, replaySeconds, area, fps },
   * notify(text, error): a finished save (or a failure), changed(): the state changed.
   */
  constructor({ game, tools, options, notify, changed, log }) {
    Object.assign(this, { game, tools, options, notify, changed, log });
    this.helper = null;
    this.startedAt = 0;
    this.encoder = 0;
    this.base = 0;
    this.recordFrom = -1;
    this.recordStartedAt = 0;
    this.status = '';
    this.gaveUp = false;
    this.restarts = [];      // times the capture stopped by itself and was started again
    this.waiting = [];       // saves waiting for their last piece: { from, to, name, stopAfter }
    this.joining = new Set();
    this.fullscreen = false; // what the game says (F11): the window is made again then
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
      return;
    }
    this.gaveUp = false;   // asked for by hand: every encoder gets another chance
    if (!this.running) this.encoder = 0;
    if (!this.start()) return;
    // the piece being written now: up to 2 s from before the button
    this.recordFrom = Math.max(this.base, this.newest());
    this.recordStartedAt = Date.now();
    this.setStatus('録画中');
  }

  /** Saves the last N seconds (only while they are being kept). */
  saveReplay() {
    const o = this.options();
    if (!this.running || !o.replay) {
      this.setStatus('「直前の数十秒をいつも残しておく」を ON にしてください');
      return;
    }
    const to = this.newest();
    this.waiting.push({ from: Math.max(this.base, to - Math.ceil(o.replaySeconds / 2) + 1), to, name: `FzClient_replay_${stamp()}.mp4`, stopAfter: false });
    this.setStatus('保存しています…');
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
      setTimeout(() => this.start(), 600);   // the new window needs a moment
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

    if (++this.ticks % 40 === 0) this.cleanUp(newest, wanted);
    if (this.recording && this.ticks % 4 === 0) this.changed();   // the clock on the button
  }

  /** Deletes the pieces nothing needs any more. */
  cleanUp(newest, replay) {
    let keepFrom = replay ? newest - Math.ceil(this.options().replaySeconds / 2) - 2 : newest - 1;
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
      '-f', 'segment', '-segment_time', '2', '-segment_format', 'mpegts', '-reset_timestamps', '1',
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
    const want = this.recording || o.replay;
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
    const parts = this.pieces().filter(([i, f]) => i >= s.from && i <= s.to && size(f) > 0).map(([, f]) => f);
    if (s.stopAfter && !this.options().replay && !this.recording && !this.waiting.length) this.stop();
    if (!parts.length) {
      this.setStatus('保存できませんでした（録画がまだありません）');
      this.notify(this.status, true);
      return;
    }
    this.joining.add(s.from);
    const list = path.join(this.buffer, `join-${s.to}.txt`);
    const out = path.join(this.dir, s.name);
    fs.writeFileSync(list, parts.map(f => `file '${f.replace(/\\/g, '/').replace(/'/g, "'\\''")}'\n`).join(''));
    const p = spawn(this.tools.ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', list,
      '-c', 'copy', '-movflags', '+faststart', out], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
    let err = '';
    p.stderr.on('data', d => { err += d; });
    const kill = setTimeout(() => p.kill(), 120000);
    const done = ok => {
      clearTimeout(kill);
      try { fs.unlinkSync(list); } catch { /* fine */ }
      this.joining.delete(s.from);
      if (!ok && err) this.log('[capture] join: ' + err.trim().slice(0, 1000));
      this.setStatus(ok ? '保存しました: ' + s.name : '保存できませんでした');
      this.notify(ok ? '録画を保存しました: ' + s.name : '録画を保存できませんでした', !ok, ok ? out : null);
    };
    p.on('error', () => done(false));
    p.on('close', code => {
      let ok = code === 0;
      try { ok = ok && fs.statSync(out).size > 0; } catch { ok = false; }
      done(ok);
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
