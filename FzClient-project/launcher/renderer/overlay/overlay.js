'use strict';
// The Alt+X overlay: a see-through window of the launcher over the game (main.js: showOverlay).
const $ = id => document.getElementById(id);
let state = { rec: null, link: false, prepared: false };
let stats = null;
let translated = false;

// ---------------------------------------------------------------- open / close
function close() {
  document.body.classList.remove('on');
  fzo.hide();
}
fzo.onShow(() => {
  stats = null;
  render();
  drawStats();
  loadGallery();
  requestAnimationFrame(() => document.body.classList.add('on'));
});
document.addEventListener('keydown', e => {
  if (waiting) return;   // choosing a key: see keyCapture
  if (e.key === 'Escape' && viewIdx >= 0) { e.preventDefault(); closeView(); return; }   // the big picture first
  if (viewIdx >= 0 && e.target.tagName !== 'VIDEO' && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) { openView(viewIdx + (e.key === 'ArrowLeft' ? -1 : 1)); return; }
  if (e.key === 'Escape' || (e.altKey && (e.key === 'x' || e.key === 'X'))) { e.preventDefault(); close(); }
});
$('veil').onclick = close;
window.addEventListener('blur', () => document.body.classList.remove('on'));

// ---------------------------------------------------------------- recording
const clock = s => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;

function render() {
  if (!translated) {
    setLang(state.lang);
    translateDom();
    translated = true;
  }
  const r = state.rec;
  $('recReady').style.display = r ? '' : 'none';
  $('recNone').style.display = r ? 'none' : '';
  if (r) {
    $('enc').textContent = r.encoder;
    const b = $('recBtn');
    b.classList.toggle('rec', r.recording);
    b.textContent = r.recording ? `■ ${t('停止')}  ${clock(r.seconds)}` : `● ${t('録画開始')}`;
    $('saveBtn').disabled = !r.replay;
    $('replayText').textContent = t('直前の {0} 秒をいつも残す', r.replaySeconds);
    $('replaySw').classList.toggle('on', r.replay);
    drawSeconds(r);
    const bad = /^(録画が止|保存できません|録画を始められません)/.test(r.status);
    $('recStatus').textContent = translateStatus(r.status);
    $('recStatus').classList.toggle('bad', bad);
  }
  $('quick').style.display = state.link ? '' : 'none';
  $('perfNote').style.display = state.link ? 'none' : '';
  $('keyBox').style.display = state.link && stats && stats.keys ? '' : 'none';
}

// ---------------------------------------------------------------- how long "the last seconds" are
const SECONDS = [15, 30, 45, 60, 90, 120];
function drawSeconds(r) {
  const box = $('secs');
  if (!box.children.length) {
    for (const s of SECONDS) {
      const b = document.createElement('button');
      b.textContent = t('{0} 秒', s);
      b.onclick = () => fzo.setReplaySeconds(s);
      box.appendChild(b);
    }
  }
  [...box.children].forEach((b, i) => b.classList.toggle('on', SECONDS[i] === r.replaySeconds));
  box.classList.toggle('dim', !r.replay);
}

// ---------------------------------------------------------------- keys (Minecraft key bindings, set through FzClient)
const KEY_LABELS = { overlay: 'オーバーレイ', record: '録画 開始 / 停止', save: '直前を保存' };
let waiting = null;   // the key being chosen

function drawKeys(keys) {
  const box = $('keys');
  if (box.children.length !== keys.length) {
    box.innerHTML = '';
    for (const k of keys) {
      const d = document.createElement('div');
      d.className = 'key';
      d.innerHTML = '<span></span><button></button>';
      d.firstChild.textContent = t(KEY_LABELS[k.id] || k.id);
      d.lastChild.onclick = e => { e.stopPropagation(); startWaiting(k.id); };
      box.appendChild(d);
    }
  }
  keys.forEach((k, i) => {
    const b = box.children[i].lastChild;
    if (waiting === k.id) return;
    b.className = k.code ? '' : 'none';
    b.textContent = !k.code ? t('なし') : (k.id === 'overlay' ? 'Alt + ' : '') + k.name;
  });
}

function startWaiting(id) {
  const keys = (stats && stats.keys) || [];
  const i = keys.findIndex(k => k.id === id);
  if (i < 0) return;
  if (waiting) finishWaiting(null);
  waiting = id;
  const b = $('keys').children[i].lastChild;
  b.className = 'wait';
  b.textContent = t('キーを押す…');
}

function finishWaiting(code) {
  const id = waiting;
  waiting = null;
  if (code != null && id) fzo.bindKey(id, code);
  drawKeys((stats && stats.keys) || []);
}

// Esc: cancel, Backspace / Delete: no key. For the overlay key Alt, Ctrl, Shift alone wait for the next key (it
// is always Alt + something).
document.addEventListener('keydown', e => {
  if (!waiting) return;
  e.preventDefault();
  e.stopPropagation();
  if (e.code === 'Escape') { finishWaiting(null); return; }
  if (e.code === 'Backspace' || e.code === 'Delete') { finishWaiting(0); return; }
  if (/^(Alt|Control|Shift|Meta)/.test(e.code) && waiting === 'overlay') return;
  const code = LWJGL[e.code];
  if (code) finishWaiting(code);
}, true);
// the middle button and the side buttons (Minecraft: -100 + its button number); a click elsewhere cancels
document.addEventListener('mousedown', e => {
  if (!waiting) return;
  const mc = { 1: 2, 3: 3, 4: 4 }[e.button];
  if (mc == null) { if (!e.target.closest('.key')) finishWaiting(null); return; }
  e.preventDefault();
  finishWaiting(waiting === 'overlay' ? null : -100 + mc);   // the overlay key is a keyboard key with Alt
}, true);

/** KeyboardEvent.code -> LWJGL 2 key code (the keyboard's scan codes, what Minecraft saves). */
const LWJGL = {
  Digit1: 2, Digit2: 3, Digit3: 4, Digit4: 5, Digit5: 6, Digit6: 7, Digit7: 8, Digit8: 9, Digit9: 10, Digit0: 11,
  Minus: 12, Equal: 13, Tab: 15, KeyQ: 16, KeyW: 17, KeyE: 18, KeyR: 19, KeyT: 20, KeyY: 21, KeyU: 22, KeyI: 23,
  KeyO: 24, KeyP: 25, BracketLeft: 26, BracketRight: 27, Enter: 28, ControlLeft: 29, KeyA: 30, KeyS: 31, KeyD: 32,
  KeyF: 33, KeyG: 34, KeyH: 35, KeyJ: 36, KeyK: 37, KeyL: 38, Semicolon: 39, Quote: 40, Backquote: 41, ShiftLeft: 42,
  Backslash: 43, KeyZ: 44, KeyX: 45, KeyC: 46, KeyV: 47, KeyB: 48, KeyN: 49, KeyM: 50, Comma: 51, Period: 52, Slash: 53,
  ShiftRight: 54, NumpadMultiply: 55, AltLeft: 56, Space: 57, CapsLock: 58, F1: 59, F2: 60, F3: 61, F4: 62, F5: 63,
  F6: 64, F7: 65, F8: 66, F9: 67, F10: 68, NumLock: 69, ScrollLock: 70, Numpad7: 71, Numpad8: 72, Numpad9: 73,
  NumpadSubtract: 74, Numpad4: 75, Numpad5: 76, Numpad6: 77, NumpadAdd: 78, Numpad1: 79, Numpad2: 80, Numpad3: 81,
  Numpad0: 82, NumpadDecimal: 83, F11: 87, F12: 88, KanaMode: 112, IntlRo: 115, Convert: 121, NonConvert: 123,
  IntlYen: 125, NumpadEnter: 156, ControlRight: 157, NumpadDivide: 181, AltRight: 184, Pause: 197, Home: 199,
  ArrowUp: 200, PageUp: 201, ArrowLeft: 203, ArrowRight: 205, End: 207, ArrowDown: 208, PageDown: 209, Insert: 210,
};

/** "保存しました: name.mp4" -> translated prefix + the name. */
function translateStatus(s) {
  const m = /^(.*?: )(.+)$/.exec(s || '');
  return m ? t(m[1]) + m[2] : t(s || '');
}

$('recBtn').onclick = () => fzo.record();
$('saveBtn').onclick = () => fzo.saveReplay();
$('replayRow').onclick = () => fzo.setReplay(!(state.rec && state.rec.replay));
$('folderBtn').onclick = () => fzo.openFolder();
$('menuBtn').onclick = () => fzo.openMenu();

fzo.onState(s => {
  state = s;
  render();
});

// ---------------------------------------------------------------- the game's numbers and switches
const fpsColor = f => (f <= 0 ? 'var(--muted)' : f >= 120 ? 'var(--cyan)' : f >= 60 ? '#c6f35a' : f >= 30 ? '#ffb454' : '#ff6b6b');

function drawStats() {
  const s = stats;
  const tiles = [
    ['FPS', s ? String(s.fps) : '-', s ? fpsColor(s.fps) : '', true],
    ['1% Low', s && s.low > 0 ? String(s.low) : '-', s ? fpsColor(s.low) : '', true],
    ['CPU（ゲーム）', s && s.cores >= 0 ? `${s.cores.toFixed(1)} ${t('コア分')}` : '-'],
    ['CPU（PC 全体）', s && s.sys >= 0 ? Math.round(s.sys * 100) + '%' : '-'],
    ['メモリ', s ? `${s.mem} / ${s.memMax} MB` : '-'],
    ['Ping', s ? s.ping : '-'],
    ['エンティティ（描画 / 全部）', s ? s.entities : '-'],
    ['パーティクル', s ? s.particles : '-'],
  ];
  const box = $('tiles');
  if (box.children.length !== tiles.length) {
    box.innerHTML = '';
    for (const [label, , , big] of tiles) {
      const d = document.createElement('div');
      d.className = 'tile' + (big ? ' big' : '');
      d.innerHTML = '<small></small><b></b>';
      d.firstChild.textContent = t(label);
      box.appendChild(d);
    }
  }
  tiles.forEach(([, value, color], i) => {
    const b = box.children[i].lastChild;
    b.textContent = value;
    b.style.color = color || '';
  });
  const frames = s ? s.frames || [] : [];
  $('ms').textContent = frames.length ? frames[0].toFixed(1) + ' ms' : '';
  drawGraph(frames);
  drawSwitches(s ? s.switches || [] : []);
}

/** One bar per frame, newest on the right; the line is 60 FPS (16.7 ms). */
function drawGraph(frames) {
  const c = $('graph'), g = c.getContext('2d');
  const w = c.width, h = c.height;
  g.clearRect(0, 0, w, h);
  let top = 33.3;
  for (const v of frames) if (v > top) top = Math.min(100, v);
  const bar = w / 240;
  frames.forEach((v, i) => {
    const bh = Math.max(2, Math.min(h - 8, v / top * (h - 8)));
    g.fillStyle = v <= 8.4 ? '#5ce1e6' : v <= 16.8 ? '#9b8ff0' : v <= 33.4 ? '#ffb454' : '#ff6b6b';
    g.globalAlpha = 0.8;
    g.fillRect(w - (i + 1) * bar, h - 4 - bh, Math.max(1, bar - 0.5), bh);
  });
  g.globalAlpha = 1;
  const y = h - 4 - 16.7 / top * (h - 8);
  g.fillStyle = 'rgba(255,255,255,.4)';
  g.fillRect(4, y, w - 8, 1);
  g.font = '18px Segoe UI';
  g.fillStyle = '#8fa6aa';
  g.fillText('60', 8, y - 6);
}

function drawSwitches(list) {
  const box = $('switches');
  if (box.children.length !== list.length) {
    box.innerHTML = '';
    list.forEach((sw, i) => {
      const d = document.createElement('div');
      d.innerHTML = '<span></span><button class="sw"></button>';
      d.onclick = () => {
        const on = !d.lastChild.classList.contains('on');
        d.lastChild.classList.toggle('on', on);   // right away; the game confirms with its next numbers
        fzo.setSwitch(i, on);
      };
      box.appendChild(d);
    });
  }
  list.forEach((sw, i) => {
    const d = box.children[i];
    d.className = 'item' + (sw.sub ? ' sub' : '') + (sw.live ? '' : ' dim');
    d.firstChild.textContent = sw.label;
    d.lastChild.classList.toggle('on', !!sw.on);
  });
}

fzo.onStats(s => {
  stats = s;
  drawStats();
  drawKeys(s.keys || []);
  $('keyBox').style.display = state.link && s.keys ? '' : 'none';
});

// ---------------------------------------------------------------- the bar: which panels are shown (kept on this PC)
const PANELS = ['perf', 'rec', 'gallery', 'quick'];
let shown = { perf: true, rec: true, gallery: true, quick: true };
try { Object.assign(shown, JSON.parse(localStorage.getItem('ovPanels') || '{}')); } catch { /* fresh */ }
function applyPanels() {
  for (const p of PANELS) {
    $(p).classList.toggle('hidden', !shown[p]);
    document.querySelector(`#bar [data-p="${p}"]`).classList.toggle('on', !!shown[p]);
  }
  if (!shown.gallery) closeView();
}
document.querySelectorAll('#bar button').forEach(b => {
  b.onclick = () => {
    shown[b.dataset.p] = !shown[b.dataset.p];
    try { localStorage.setItem('ovPanels', JSON.stringify(shown)); } catch { /* not kept */ }
    applyPanels();
    if (b.dataset.p === 'gallery' && shown.gallery) loadGallery();
  };
});

// ---------------------------------------------------------------- gallery: the newest screenshots and clips
let gallery = [], viewIdx = -1, lastStatus = '';
const src = (s, big) => 'fzshot://' + (s.kind === 'clip' ? (big ? 'clip' : 'cthumb') : (big ? 'img' : 'thumb')) + '/' + encodeURIComponent(s.file);

async function loadGallery() {
  if (!shown.gallery) return;
  try { gallery = await fzo.gallery(); } catch { gallery = []; }
  const box = $('strip');
  box.innerHTML = '';
  $('galEmpty').style.display = gallery.length ? 'none' : '';
  gallery.forEach((s, i) => {
    const b = document.createElement('button');
    b.className = 'thumb' + (s.kind === 'clip' ? ' clip' : '');
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.alt = '';
    img.src = src(s, false);
    img.onerror = () => { const d = document.createElement('div'); d.className = 'noimg'; d.textContent = t('クリップ'); img.replaceWith(d); };
    const cap = document.createElement('span');
    cap.textContent = new Date(s.time).toLocaleString();
    b.append(img, cap);
    b.onclick = () => openView(i);
    box.appendChild(b);
  });
}

function openView(i) {
  if (!gallery.length) return;
  viewIdx = (i + gallery.length) % gallery.length;
  const s = gallery[viewIdx], clip = s.kind === 'clip', v = $('ovVid');
  $('ovView').classList.toggle('vid', clip);
  if (clip) {
    $('ovImg').removeAttribute('src');
    v.src = src(s, true);
    v.play().catch(() => {});
  } else {
    stopVideo();
    $('ovImg').src = src(s, true);
  }
  $('ovCap').textContent = `${s.file}  ·  ${viewIdx + 1} / ${gallery.length}`;
  $('ovView').classList.add('on');
}
function stopVideo() {
  const v = $('ovVid');
  v.pause();
  v.removeAttribute('src');
  v.load();
}
function closeView() {
  stopVideo();
  $('ovView').classList.remove('on');
  viewIdx = -1;
}
$('ovPrev').onclick = () => openView(viewIdx - 1);
$('ovNext').onclick = () => openView(viewIdx + 1);
$('ovClose').onclick = closeView;
$('ovReveal').onclick = () => { const s = gallery[viewIdx]; if (s) { closeView(); fzo.reveal(s.file, s.kind); } };
$('galFolder').onclick = () => fzo.openFolder();
// a new clip was saved while the overlay is open: show it
fzo.onState(s => {
  const st = (s.rec && s.rec.status) || '';
  if (st !== lastStatus && /^保存しました/.test(st)) loadGallery();
  lastStatus = st;
});
window.addEventListener('blur', closeView);
applyPanels();
