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
  requestAnimationFrame(() => document.body.classList.add('on'));
});
document.addEventListener('keydown', e => {
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
    const bad = /^(録画が止|保存できません|録画を始められません)/.test(r.status);
    $('recStatus').textContent = translateStatus(r.status);
    $('recStatus').classList.toggle('bad', bad);
  }
  $('quick').style.display = state.link ? '' : 'none';
  $('perfNote').style.display = state.link ? 'none' : '';
}

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
});
