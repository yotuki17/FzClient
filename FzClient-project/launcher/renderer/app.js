'use strict';
/* global fz */
const $ = id => document.getElementById(id);
let info = null;
let busy = false;

// ---------------------------------------------------------------- helpers
let toastTimer;
function toast(text, err) {
  const t = $('toast');
  t.textContent = text;
  t.className = 'toast on' + (err ? ' err' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = 'toast' + (err ? ' err' : ''); }, err ? 6000 : 3000);
}
const errText = e => t(String((e && e.message) || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''));
function el(tag, cls, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  return e;
}
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const face = uuid => `https://mc-heads.net/avatar/${uuid}/64`;

// ---------------------------------------------------------------- window + navigation
$('wMin').onclick = () => fz.win.min();
$('wMax').onclick = () => fz.win.max();
$('wClose').onclick = () => fz.win.close();
document.querySelectorAll('.nav').forEach(b => b.onclick = () => {
  document.querySelectorAll('.nav').forEach(x => x.classList.toggle('on', x === b));
  document.querySelectorAll('.page').forEach(p => p.classList.toggle('on', p.id === 'page-' + b.dataset.page));
  if (b.dataset.page === 'mods') { loadMods(true); fz.settings.get().then(st => { $('modAuto').checked = st.autoUpdateMods !== false; }); }
  if (b.dataset.page === 'shots') loadShots();
  if (b.dataset.page === 'skins') loadSkins();
  if (b.dataset.page === 'opt') { loadOpt(); loadOptiFine(); }
  if (b.dataset.page !== 'shots') closeShot();
  if (b.dataset.page !== 'mods' && $('modModal').classList.contains('on')) $('mdClose').onclick();
});

// In a wide window the screenshot preview and the mod details are a panel on the right (style.css): the page
// gets narrower while one of them is open, so nothing is hidden under it.
(function paneWatch() {
  const panes = [$('viewer'), $('modModal')];
  const sync = () => document.body.classList.toggle('pane-open', panes.some(p => p.classList.contains('on')));
  const mo = new MutationObserver(sync);
  for (const p of panes) mo.observe(p, { attributes: true, attributeFilter: ['class'] });
})();

// ---------------------------------------------------------------- background particles
(function stars() {
  const c = $('stars'), g = c.getContext('2d');
  const cols = ['92,225,230', '255,255,255', '232,62,206'];
  let pts = [];
  function size() {
    c.width = innerWidth; c.height = innerHeight;
    pts = Array.from({ length: 60 }, (_, i) => ({ x: Math.random() * c.width, y: Math.random() * c.height,
      s: .6 + Math.random() * 1.6, v: 4 + Math.random() * 10, p: Math.random() * 6.28, c: cols[i % 3] }));
  }
  size();
  addEventListener('resize', size);
  let last = performance.now();
  function frame(now) {
    const dt = Math.min(.1, (now - last) / 1000); last = now;
    if (!document.hidden) {
      g.clearRect(0, 0, c.width, c.height);
      for (const p of pts) {
        p.y -= p.v * dt; if (p.y < -5) { p.y = c.height + 5; p.x = Math.random() * c.width; }
        const a = .25 + .5 * (.5 + .5 * Math.sin(now / 450 + p.p * 3));
        g.fillStyle = `rgba(${p.c},${a.toFixed(2)})`;
        g.fillRect(p.x + Math.sin(now / 2500 + p.p) * 6, p.y, p.s, p.s);
      }
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
})();

// ---------------------------------------------------------------- accounts
let accounts = { list: [], selected: '' };
function current() {
  return accounts.list.find(a => a.uuid === accounts.selected) || accounts.list[0];
}
async function loadAccounts() {
  const before = accounts.selected;
  accounts = await fz.accounts.list();
  if (before && before !== accounts.selected && $('page-skins').classList.contains('on')) { skinSel = null; loadSkins(); }
  const a = current();
  const img = $('acctFace');
  if (a) {
    img.onload = () => img.classList.add('ok');
    img.onerror = () => img.classList.remove('ok');
    img.src = face(a.uuid);
    $('acctInit').textContent = a.name[0].toUpperCase();
    $('who').textContent = a.name + (a.type === 'offline' ? t('（オフライン）') : '');
  } else {
    img.classList.remove('ok');
    $('acctInit').textContent = '?';
    $('who').textContent = t('アカウント未設定');
  }
  const list = $('acctList');
  list.innerHTML = '';
  if (!accounts.list.length) list.appendChild(el('div', 'empty', t('まだアカウントがありません')));
  for (const acc of accounts.list) {
    const row = el('div', 'card acc-row' + (a && acc.uuid === a.uuid ? ' sel' : ''),
      `<img class="acc-face" src="${face(acc.uuid)}" alt=""><div><b>${esc(acc.name)}</b><small>${acc.type === 'msa' ? 'Microsoft' : t('オフライン')}</small></div><button class="rm" title="${t('削除')}">✕</button>`);
    row.onclick = async ev => {
      if (ev.target.classList.contains('rm')) { await fz.accounts.remove(acc.uuid); toast(t('{0} を削除しました', acc.name)); }
      else await fz.accounts.select(acc.uuid);
      loadAccounts();
    };
    list.appendChild(row);
  }
}
$('acctBtn').onclick = () => $('acctModal').classList.add('on');
$('acctClose').onclick = () => { $('acctModal').classList.remove('on'); fz.accounts.msaCancel(); };
$('acctModal').onclick = e => { if (e.target === $('acctModal')) $('acctClose').onclick(); };
$('offAdd').onclick = async () => {
  try {
    const a = await fz.accounts.offline($('offName').value.trim());
    $('offName').value = '';
    toast(t('{0} を追加しました', a.name));
    loadAccounts();
  } catch (e) { toast(errText(e), true); }
};
$('msaBtn').onclick = async () => {
  try {
    $('msaBtn').disabled = true;
    const r = await fz.accounts.msaStart();
    $('msaNote').textContent = t('ブラウザで {0} が開きます。このコードを入力してログインしてください。', r.url);
    $('msaCode').textContent = r.code;
  } catch (e) {
    $('msaBtn').disabled = false;
    toast(errText(e), true);
  }
};
fz.accounts.onMsaDone(d => {
  $('msaBtn').disabled = false;
  $('msaCode').textContent = '';
  $('msaNote').textContent = '';
  if (d.ok) { toast(t('{0} でログインしました', d.account.name)); loadAccounts(); $('acctModal').classList.remove('on'); }
  else toast(t(d.error), true);
});

// ---------------------------------------------------------------- play
const fmtMb = b => (b / 1048576).toFixed(1) + ' MB';
$('play').onclick = async () => {
  if (busy) return;
  if (!current()) { $('acctModal').classList.add('on'); return; }
  busy = true;
  $('play').disabled = true;
  $('playLabel').textContent = t('準備中…');
  $('progress').classList.add('on');
  $('status').textContent = '';
  try {
    await fz.game.launch();
    $('playLabel').textContent = t('プレイ中');
    $('status').textContent = t('Minecraft を起動しました');
    $('bar').style.width = '100%';
  } catch (e) {
    busy = false;
    $('play').disabled = false;
    $('playLabel').textContent = t('プレイ');
    $('progress').classList.remove('on');
    $('status').textContent = '';
    toast(errText(e), true);
  }
};
fz.game.onProgress(p => {
  const frac = p.bytesTotal ? p.bytesDone / p.bytesTotal : (p.total ? p.done / p.total : 0);
  $('bar').style.width = Math.round(frac * 100) + '%';
  $('playLabel').textContent = p.stage === '起動中' ? t('起動中…') : t('ダウンロード中…');
  $('status').textContent = p.bytesTotal
    ? `${t(p.stage)}  ${fmtMb(p.bytesDone)} / ${fmtMb(p.bytesTotal)}`
    : `${t(p.stage)}  ${p.done} / ${p.total}`;
});
fz.game.onState(s => {
  if (!s.running) {
    busy = false;
    $('play').disabled = false;
    $('playLabel').textContent = t('プレイ');
    $('progress').classList.remove('on');
    $('status').textContent = s.code ? t('Minecraft が終了しました（コード {0}）', s.code) : '';
  }
});

// ---------------------------------------------------------------- mods
const fmtNum = n => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(0) + 'K' : String(n);
let modsBusy = false;

document.querySelectorAll('.tab').forEach(b => b.onclick = () => {
  document.querySelectorAll('.tab').forEach(x => x.classList.toggle('on', x === b));
  document.querySelectorAll('.tabpage').forEach(p => p.classList.toggle('on', p.id === 'tab-' + b.dataset.tab));
  if (b.dataset.tab === 'find' && !$('mrList').children.length) mrSearch(true);
  if (b.dataset.tab === 'have') loadMods();
});

function icon(src, name) {
  if (src) { const i = el('img', 'ico'); i.src = src; i.alt = ''; return i; }
  return el('div', 'ico blank', esc((name || '?').slice(0, 1).toUpperCase()));
}

async function loadMods(checkUpdates) {
  const list = $('modList');
  let mods;
  try { mods = await fz.mods.installed(!!checkUpdates); } catch (e) { toast(errText(e), true); return; }
  list.innerHTML = '';
  if (!mods.length) list.appendChild(el('div', 'empty', t('ほかの MOD はまだありません。「MOD を探す」から入れられます。')));
  const updatable = mods.filter(m => m.update && !m.pinned);
  $('modUpdateAll').disabled = !updatable.length;
  $('modUpdateAll').textContent = updatable.length ? t('すべて更新（{0}）', updatable.length) : t('すべて更新');
  for (const m of mods) {
    const row = el('div', 'card clickable' + (m.enabled ? '' : ' off'));
    row.dataset.file = m.file;
    row.title = t('クリックで詳細');
    // the buttons in the row do their own thing; anywhere else opens the details
    row.onclick = ev => { if (!ev.target.closest('button')) openModDetails(m); };
    row.appendChild(icon(m.project && m.project.icon, m.name));
    const ver = m.version ? m.version.number : t('手動で追加');
    const src = m.source === 'curseforge' ? '<span class="src">CurseForge</span>' : '';
    const pin = m.pinned ? `<span class="pin">${esc(t('固定'))}</span>` : '';
    row.appendChild(el('div', null, `<b>${esc(m.name)}${src}${pin}</b><small>${esc(ver)} · ${(m.size / 1024).toFixed(0)} KB</small>`));
    if (m.update) {
      row.appendChild(el('span', 'tag', esc(t('更新あり {0}', m.update.number))));
      const up = el('button', 'btn mini primary', esc(t('更新')));
      up.onclick = () => runUpdate([m.file]);
      row.appendChild(up);
    }
    const sw = el('button', 'sw' + (m.enabled ? ' on' : ''));
    sw.title = m.enabled ? 'ON' : 'OFF';
    sw.onclick = async () => {
      try {
        await fz.mods.toggle(m.file);
        // the details panel may show this mod; toggling renames the jar (foo.jar <-> foo.jar.disabled)
        if (mdMod && mdMod.file === m.file) await refreshModDetails(m.enabled ? m.file + '.disabled' : m.file.replace(/\.disabled$/i, ''));
        else loadMods();
      } catch (e) { toast(errText(e), true); }
    };
    row.appendChild(sw);
    const del = el('button', 'iconbtn', '🗑');
    del.title = t('削除（ごみ箱へ）');
    del.onclick = async () => {
      if (!del.classList.contains('sure')) { del.classList.add('sure'); del.textContent = t('本当?'); setTimeout(() => { del.classList.remove('sure'); del.textContent = '🗑'; }, 3000); return; }
      try { await fz.mods.remove(m.file); toast(t('{0} をごみ箱に移しました', m.name)); loadMods(); } catch (e) { toast(errText(e), true); }
    };
    row.appendChild(del);
    list.appendChild(row);
  }
  markModRow();
}

/** Highlights the mod whose details are open (useful when they are the panel next to the list). */
function markModRow() {
  document.querySelectorAll('#modList .card').forEach(r => r.classList.toggle('sel', !!mdMod && r.dataset.file === mdMod.file));
}

// ---------------------------------------------------------------- one mod's details
let mdMod = null;      // the mod shown in the dialog
let mdVersions = [];   // its versions on the site, newest first

function modPage(m) {
  if (!m.project) return '';
  if (m.project.url) return m.project.url;
  return m.source === 'curseforge' ? '' : `https://modrinth.com/mod/${encodeURIComponent(m.project.slug || m.project.id)}`;
}

function fillModDetails(m) {
  mdMod = m;
  markModRow();
  const ico = $('mdIcon');
  ico.innerHTML = '';
  ico.appendChild(icon(m.project && m.project.icon, m.name));
  $('mdName').textContent = m.name;
  const from = m.source === 'curseforge' ? 'CurseForge' : m.source === 'modrinth' ? 'Modrinth' : t('手動で追加');
  $('mdMeta').textContent = `${from} · ${m.version ? m.version.number : ''}${m.version ? ' · ' : ''}`
    + t('ファイル: {0} · {1} KB', m.file.replace(/\.disabled$/i, ''), (m.size / 1024).toFixed(0));
  $('mdOn').classList.toggle('on', m.enabled);
  const site = !!m.project;
  $('mdAutoRow').style.display = site ? '' : 'none';
  $('mdVerSec').style.display = site ? '' : 'none';
  $('mdAuto').classList.toggle('on', !m.pinned);
  $('mdPage').style.display = modPage(m) ? '' : 'none';
  $('mdDel').classList.remove('sure');
  $('mdDel').textContent = t('削除');
}

async function loadModVersions(m) {
  const sel = $('mdVer');
  sel.innerHTML = `<option>${esc(t('バージョンを読み込み中…'))}</option>`;
  sel.disabled = true;
  $('mdVerApply').disabled = true;
  mdVersions = [];
  try {
    const r = await fz.mods.versions(m.file);
    if (mdMod !== m) return;   // another mod was opened meanwhile
    mdVersions = r.list;
    sel.innerHTML = '';
    r.list.forEach((v, i) => {
      const tags = [i === 0 ? t('最新') : '', v.id === r.current ? t('いま入っている版') : '', v.type !== 'release' ? v.type : '']
        .filter(Boolean).join(', ');
      const day = v.date ? new Date(v.date).toLocaleDateString() : '';
      const o = el('option', null, esc(`${v.number}${tags ? `（${tags}）` : ''}  ${day}`));
      o.value = v.id;
      if (v.id === r.current) o.selected = true;
      sel.appendChild(o);
    });
    sel.disabled = !r.list.length;
    $('mdVerApply').disabled = !r.list.length;
  } catch (e) {
    if (mdMod !== m) return;
    sel.innerHTML = `<option>${esc(t('バージョンを読み込めませんでした'))}</option>`;
    toast(errText(e), true);
  }
}

function openModDetails(m) {
  fillModDetails(m);
  $('modModal').classList.add('on');
  if (m.project) loadModVersions(m);
}

/** Re-reads the list and shows the (possibly renamed) jar again in the dialog. */
async function refreshModDetails(file) {
  await loadMods();
  const list = await fz.mods.installed(false);
  const m = list.find(x => x.file === file);
  if (m) fillModDetails(m);
  else $('modModal').classList.remove('on');
  return m;
}

$('mdClose').onclick = () => { $('modModal').classList.remove('on'); mdMod = null; markModRow(); };
$('modModal').onclick = e => { if (e.target === $('modModal') && !docked()) $('mdClose').onclick(); };
$('mdOn').onclick = async () => {
  const m = mdMod;
  try {
    await fz.mods.toggle(m.file);
    // toggling renames the jar: foo.jar <-> foo.jar.disabled
    await refreshModDetails(m.enabled ? m.file + '.disabled' : m.file.replace(/\.disabled$/i, ''));
  } catch (e) { toast(errText(e), true); }
};
$('mdAuto').onclick = async () => {
  const m = mdMod;
  try {
    await fz.mods.pin(m.file, !m.pinned);
    toast(t(m.pinned ? '{0} を自動で更新します' : '{0} は自動で更新しません', m.name));
    await refreshModDetails(m.file);
  } catch (e) { toast(errText(e), true); }
};
$('mdVerApply').onclick = async () => {
  const m = mdMod;
  const id = $('mdVer').value;
  const v = mdVersions.find(x => x.id === id);
  if (!v || (m.version && m.version.id === id)) return;
  if (v.external) { toast(t('この版は CurseForge のサイトからしかダウンロードできません'), true); return; }
  // anything but the newest would be undone by the next update: pin it; picking the newest unpins
  const older = mdVersions[0] && mdVersions[0].id !== id;
  $('mdVerApply').disabled = true;
  try {
    const file = await fz.mods.setVersion(m.file, id, older);
    toast(t('{0} を {1} にしました', m.name, v.number) + (older ? ' — ' + t('古い版なので自動更新を止めました') : ''));
    const now = await refreshModDetails(file);
    if (now) loadModVersions(now);
  } catch (e) {
    toast(errText(e), true);
    $('mdVerApply').disabled = false;
  }
};
$('mdPage').onclick = () => { const u = mdMod && modPage(mdMod); if (u) fz.open.url(u); };
$('mdConfig').onclick = () => fz.open.folder('config');
$('mdDel').onclick = async () => {
  const b = $('mdDel'), m = mdMod;
  if (!b.classList.contains('sure')) {
    b.classList.add('sure');
    b.textContent = t('本当?');
    setTimeout(() => { if (mdMod === m) { b.classList.remove('sure'); b.textContent = t('削除'); } }, 3000);
    return;
  }
  try {
    await fz.mods.remove(m.file);
    toast(t('{0} をごみ箱に移しました', m.name));
    $('modModal').classList.remove('on');
    mdMod = null;
    loadMods();
  } catch (e) { toast(errText(e), true); }
};

async function runUpdate(files) {
  if (modsBusy) return;
  modsBusy = true;
  toast(t('更新中…'));
  try {
    const n = await fz.mods.update(files);
    toast(n ? t('{0} 個の MOD を更新しました', n) : t('すべて最新です'));
  } catch (e) { toast(errText(e), true); }
  modsBusy = false;
  loadMods(true);
}
$('modUpdateAll').onclick = () => runUpdate(null);
$('modCheck').onclick = async () => { toast(t('更新を確認中…')); await loadMods(true); if ($('modUpdateAll').disabled) toast(t('すべて最新です')); };
$('modAdd').onclick = async () => { const n = await fz.mods.add(); if (n) { toast(t('{0} 個の MOD を追加しました', n)); loadMods(); } };
$('modFolder').onclick = () => fz.open.folder('mods');
$('modAuto').onchange = () => save({ autoUpdateMods: $('modAuto').checked });
fz.mods.onAutoUpdated(n => toast(t('{0} 個の MOD を更新しました', n)));

// MOD search: Modrinth or CurseForge (same grid, same buttons)
const SRC = {
  modrinth: { name: 'Modrinth', site: 'https://modrinth.com', page: h => `https://modrinth.com/mod/${encodeURIComponent(h.slug)}` },
  curseforge: { name: 'CurseForge', site: 'https://www.curseforge.com/minecraft', page: h => h.url },
};
let mrSrc = 'modrinth', mrOffset = 0, mrTotal = 0, mrToken = 0, mrTimer;
async function mrSearch(reset) {
  const token = ++mrToken;
  const src = mrSrc, S = SRC[src];
  if (reset) mrOffset = 0;
  const grid = $('mrList');
  if (reset) { grid.innerHTML = ''; grid.appendChild(el('div', 'empty', t('検索中…'))); }
  let r, have;
  try {
    [r, have] = await Promise.all([fz.mods.search($('mrQuery').value, mrOffset, src), fz.mods.installedProjects(src)]);
  } catch (e) {
    if (token !== mrToken) return;
    if (reset) { grid.innerHTML = ''; grid.appendChild(el('div', 'empty', esc(errText(e)))); $('mrMore').parentElement.hidden = true; }
    else toast(errText(e), true);
    return;
  }
  if (token !== mrToken) return;   // a newer search already started
  if (reset) grid.innerHTML = '';
  mrTotal = r.total;
  mrOffset += r.count != null ? r.count : r.hits.length;
  if (reset && !r.hits.length && mrOffset >= mrTotal) grid.appendChild(el('div', 'empty', t('見つかりませんでした')));
  const installed = new Set(have);
  for (const h of r.hits) {
    const c = el('div', 'mr');
    if (h.icon) { const i = el('img'); i.src = h.icon; i.alt = ''; c.appendChild(i); } else c.appendChild(el('div', 'ph'));
    const body = el('div', 'body', `<b>${esc(h.title)}</b><small>by ${esc(h.author)} · ⬇ ${fmtNum(h.downloads)}</small><p>${esc(h.description)}</p>`);
    const act = el('div', 'act');
    const link = el('a', null, esc(S.name) + ' ↗');
    link.href = S.page(h);
    link.target = '_blank';
    link.className = 'credit';
    act.appendChild(link);
    if (installed.has(h.id)) {
      act.appendChild(el('span', 'done', esc(t('入っています'))));
    } else if (h.external) {
      // the author only allows downloads from the CurseForge site
      const b = el('button', 'btn', esc(t('サイトで入手')));
      b.title = t('作者の設定で、CurseForge のサイトからしかダウンロードできません');
      b.onclick = () => fz.open.url(S.page(h));
      act.appendChild(b);
    } else {
      const b = el('button', 'btn primary', esc(t('入れる')));
      b.onclick = async () => {
        b.disabled = true;
        b.textContent = t('ダウンロード中…');
        try {
          const n = await fz.mods.install(h.id, src);
          b.replaceWith(el('span', 'done', esc(t('入っています'))));
          toast(n > 1 ? t('{0} を入れました（必要な MOD {1} 個も一緒に）', h.title, n - 1) : t('{0} を入れました', h.title));
        } catch (e) {
          b.disabled = false;
          b.textContent = t('入れる');
          toast(errText(e), true);
        }
      };
      act.appendChild(b);
    }
    body.appendChild(act);
    c.appendChild(body);
    grid.appendChild(c);
  }
  $('mrMore').parentElement.hidden = mrOffset >= mrTotal;
  // every result on this page was dropped (no Forge 1.8.9 build): read the next page by itself
  if (!r.hits.length && mrOffset < mrTotal && grid.children.length < 6) mrSearch(false);
}
$('mrQuery').oninput = () => { clearTimeout(mrTimer); mrTimer = setTimeout(() => mrSearch(true), 350); };
$('mrMore').onclick = () => mrSearch(false);
document.querySelectorAll('#mrSource button').forEach(b => b.onclick = () => {
  if (mrSrc === b.dataset.src) return;
  mrSrc = b.dataset.src;
  document.querySelectorAll('#mrSource button').forEach(x => x.classList.toggle('on', x === b));
  const S = SRC[mrSrc];
  $('mrCredit').innerHTML = t('MOD の情報とファイルは {0} から取得しています', `<a href="${S.site}" target="_blank">${S.name}</a>`);
  mrSearch(true);
});

// ---------------------------------------------------------------- screenshots
let shotList = [], shotIdx = -1;
async function loadShots() {
  shotList = await fz.shots.list();
  const g = $('shotGrid');
  g.innerHTML = '';
  $('shotCount').textContent = t('{0} 枚', shotList.length);
  if (!shotList.length) g.appendChild(el('div', 'empty', t('まだスクリーンショットがありません。ゲーム中に F2 で撮れます。')));
  shotList.forEach((s, i) => {
    const b = el('button', 'shot');
    const img = el('img');
    img.loading = 'lazy';
    img.alt = '';
    img.src = 'fzshot://thumb/' + encodeURIComponent(s.file);
    b.appendChild(img);
    b.appendChild(el('span', null, esc(new Date(s.time).toLocaleString())));
    b.onclick = () => openShot(i);
    g.appendChild(b);
  });
}
function openShot(i) {
  if (!shotList.length) { closeShot(); return; }
  shotIdx = (i + shotList.length) % shotList.length;
  const s = shotList[shotIdx];
  $('vImg').src = 'fzshot://img/' + encodeURIComponent(s.file);
  $('vCap').textContent = `${s.file}  ·  ${shotIdx + 1} / ${shotList.length}`;
  $('vDel').textContent = t('削除');
  $('vDel').classList.remove('sure');
  $('viewer').classList.add('on');
  document.querySelectorAll('#shotGrid .shot').forEach((b, j) => b.classList.toggle('sel', j === shotIdx));
}
function closeShot() {
  $('viewer').classList.remove('on');
  shotIdx = -1;
  document.querySelectorAll('#shotGrid .shot.sel').forEach(b => b.classList.remove('sel'));
}
/** Wide window: the preview / details are a docked panel, so a click on its empty part must not close it. */
const docked = () => matchMedia('(min-width: 1200px)').matches;
$('vPrev').onclick = () => openShot(shotIdx - 1);
$('vNext').onclick = () => openShot(shotIdx + 1);
$('vClose').onclick = closeShot;
$('viewer').onclick = e => { if (e.target === $('viewer') && !docked()) closeShot(); };
$('vCopy').onclick = async () => { try { await fz.shots.copy(shotList[shotIdx].file); toast(t('画像をコピーしました')); } catch (e) { toast(errText(e), true); } };
$('vReveal').onclick = () => fz.shots.reveal(shotList[shotIdx].file);
$('vDel').onclick = async () => {
  const b = $('vDel');
  if (!b.classList.contains('sure')) { b.classList.add('sure'); b.textContent = t('もう一度押すと削除'); return; }
  try {
    await fz.shots.remove(shotList[shotIdx].file);
    toast(t('ごみ箱に移しました'));
    const keep = shotIdx;
    await loadShots();
    if (shotList.length) openShot(Math.min(keep, shotList.length - 1)); else closeShot();
  } catch (e) { toast(errText(e), true); }
};
document.addEventListener('keydown', e => {
  if (shotIdx < 0) return;
  if (e.key === 'ArrowLeft') openShot(shotIdx - 1);
  else if (e.key === 'ArrowRight') openShot(shotIdx + 1);
  else if (e.key === 'Escape') closeShot();
});
$('shotFolder').onclick = () => fz.open.folder('screenshots');
$('shotReload').onclick = loadShots;

// ---------------------------------------------------------------- settings
async function loadSettings() {
  const s = await fz.settings.get();
  const maxMem = Math.max(2048, Math.min(16384, Math.floor(info.totalMemMb * 0.75 / 256) * 256));
  $('mem').max = maxMem;
  $('mem').value = Math.min(s.memoryMb, maxMem);
  $('memVal').textContent = (s.memoryMb / 1024).toFixed(2).replace(/\.?0+$/, '') + ' GB';
  $('w').value = s.width; $('h').value = s.height; $('fs').checked = s.fullscreen;
  $('after').value = s.afterLaunch;
  $('jvm').value = s.jvmArgs;
  $('lang').value = s.lang || 'auto';
  $('gameDir').textContent = s.gameDir || info.dirs.root + t('（初期）');
}
const save = patch => fz.settings.set(patch);
$('mem').oninput = () => { $('memVal').textContent = ($('mem').value / 1024).toFixed(2).replace(/\.?0+$/, '') + ' GB'; };
$('mem').onchange = () => save({ memoryMb: +$('mem').value });
$('w').onchange = () => save({ width: Math.max(640, +$('w').value || 1280) });
$('h').onchange = () => save({ height: Math.max(360, +$('h').value || 720) });
$('fs').onchange = () => save({ fullscreen: $('fs').checked });
$('after').onchange = () => save({ afterLaunch: $('after').value });
$('jvm').onchange = () => save({ jvmArgs: $('jvm').value.trim() });
$('dirPick').onclick = async () => { const d = await fz.pickFolder(); if (d) { await save({ gameDir: d }); info = await fz.info(); loadSettings(); } };
$('dirReset').onclick = async () => { await save({ gameDir: '' }); info = await fz.info(); loadSettings(); };
$('dirOpen').onclick = () => fz.open.folder('game');
$('lang').onchange = async () => { await save({ lang: $('lang').value }); location.reload(); };
$('logsOpen').onclick = () => fz.open.folder('logs');

// ---------------------------------------------------------------- start
(async () => {
  info = await fz.info();
  const st = await fz.settings.get();
  const pref = st.lang || 'auto';
  setLang(pref === 'auto' ? (/^ja/i.test(info.locale || '') ? 'ja' : 'en') : pref);
  translateDom();
  document.body.style.visibility = 'visible';
  $('ver').textContent = `FzClient Launcher ${info.version} · Minecraft ${info.mc}`;
  if (!info.msa) {
    $('msaBtn').disabled = true;
    $('msaNote').textContent = t('Microsoft ログインは準備中です（Mojang の許可待ち）。今はオフラインで試せます。');
  }
  await loadAccounts();
  await loadSettings();
  // first start without OptiFine: offer it once
  try {
    const of = await fz.optifine.status();
    if (!of.installed && !st.ofAsked) $('ofModal').classList.add('on');
  } catch { /* fine */ }
})();

// ---------------------------------------------------------------- OptiFine (最適化 page + first-start dialog)
let ofWaiting = false;
async function loadOptiFine() {
  let s;
  try { s = await fz.optifine.status(); } catch (e) { $('ofState').textContent = errText(e); return; }
  const i = s.installed;
  if (ofWaiting) {
    $('ofState').textContent = t('ダウンロードを待っています…');
    $('ofNote').textContent = t('開いたページで「Download」を押してください。終わると自動で入ります');
  } else if (i) {
    $('ofState').textContent = t('OptiFine {0} が入っています', i.version || '') + (i.enabled ? '' : t('（OFF）'));
    $('ofNote').textContent = i.preview
      ? t('プレビュー版です。不安定なときは安定版の {0} を入れてください', s.latest)
      : t('FPS を大きく上げる定番の MOD です');
  } else {
    $('ofState').textContent = t('OptiFine は入っていません');
    $('ofNote').textContent = t('FPS を大きく上げる定番の MOD です');
  }
  $('ofInstall').textContent = ofWaiting ? t('やめる') : i ? t('入れ直す') : t('OptiFine を入れる');
  $('ofPvp').classList.toggle('on-preset', s.preset === 'pvp');
  $('ofFps').classList.toggle('on-preset', s.preset === 'fps');
  $('ofRevert').disabled = !s.canRevert;
}

async function startOptiFine() {
  if (ofWaiting) {
    ofWaiting = false;
    await fz.optifine.cancel();
  } else {
    ofWaiting = true;
    await fz.optifine.install();
    toast(t('optifine.net を開きました。「Download」を押してください'));
  }
  loadOptiFine();
}

fz.optifine.onInstalled(r => {
  ofWaiting = false;
  if (r.ok) toast(t('OptiFine {0} を入れました', r.version || ''));
  else toast(t(r.error || 'OptiFine を入れられませんでした'), true);
  loadOptiFine();
  if ($('page-mods').classList.contains('on')) loadMods();
});
$('ofInstall').onclick = startOptiFine;
$('ofFile').onclick = async () => {
  try {
    const r = await fz.optifine.addFile();
    if (r) { toast(t('OptiFine {0} を入れました', r.version || '')); loadOptiFine(); }
  } catch (e) { toast(errText(e), true); }
};
async function ofPreset(id) {
  try {
    await fz.optifine.preset(id);
    toast(t('OptiFine の設定を変えました。次にゲームを起動すると効きます'));
  } catch (e) { toast(errText(e), true); }
  loadOptiFine();
}
$('ofPvp').onclick = () => ofPreset('pvp');
$('ofFps').onclick = () => ofPreset('fps');
$('ofRevert').onclick = async () => {
  try { await fz.optifine.revert(); toast(t('OptiFine の設定を元に戻しました')); } catch (e) { toast(errText(e), true); }
  loadOptiFine();
};
const ofAsked = () => { $('ofModal').classList.remove('on'); fz.settings.set({ ofAsked: true }); };
$('ofLater').onclick = ofAsked;
$('ofNever').onclick = ofAsked;
$('ofModalInstall').onclick = () => { ofAsked(); if (!ofWaiting) startOptiFine(); };

// ---------------------------------------------------------------- automatic updates
(function updates() {
  const pill = $('upd'), text = $('updText');
  let readyVersion = null;
  const show = (msg, ready) => { text.textContent = msg; pill.className = 'upd on' + (ready ? ' ready' : ''); };
  fz.update.onLauncher(d => {
    if (d.state === 'downloading') show(t('ランチャーの更新をダウンロード中… {0}%', d.percent || 0), false);
    if (d.state === 'ready') { readyVersion = d.version; show(t('v{0} に更新できます — クリックで再起動', d.version), true); }
  });
  fz.update.onMod(d => {
    if (d.state === 'done') toast(t('FzClient を {0} に更新しました', d.version));
    if (d.state === 'failed') toast(t('FzClient の更新をダウンロードできませんでした（今のまま起動します）'), true);
  });
  pill.onclick = async () => {
    if (!readyVersion) return;
    try { await fz.update.install(); } catch (e) { toast(errText(e), true); }
  };
  fz.update.status().then(s => { if (s && s.ready) { readyVersion = s.ready; show(t('v{0} に更新できます — クリックで再起動', s.ready), true); } });
})();


// ---------------------------------------------------------------- skins
// The 3D preview on the left, the library and the account's current skin / capes on the right.
let skinViewer = null, skinSel = null, skinCur = null, skinBusy = false, skinCapeId = null;

function skinImg(src) {
  return new Promise((ok, bad) => { const i = new Image(); i.onload = () => ok(i); i.onerror = bad; i.src = src; });
}

/** Front view of a skin as a small 16×32 picture (head, body, arms, legs and their outer layers). */
async function skinFront(src, slim) {
  const img = await skinImg(src);
  const c = document.createElement('canvas');
  c.width = 16; c.height = 32;
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  const old = img.height === 32, aw = slim ? 3 : 4;
  const part = (sx, sy, w, h, dx, dy, flip) => {
    if (!flip) { g.drawImage(img, sx, sy, w, h, dx, dy, w, h); return; }
    g.save(); g.translate(dx + w, dy); g.scale(-1, 1); g.drawImage(img, sx, sy, w, h, 0, 0, w, h); g.restore();
  };
  part(8, 8, 8, 8, 4, 0);                                   // head
  part(20, 20, 8, 12, 4, 8);                                // body
  part(44, 20, aw, 12, 4 - aw, 8);                          // right arm (left on screen)
  part(4, 20, 4, 12, 4, 20);                                // right leg
  if (old) { part(44, 20, aw, 12, 12, 8, true); part(4, 20, 4, 12, 8, 20, true); }
  else { part(36, 52, aw, 12, 12, 8); part(20, 52, 4, 12, 8, 20); }
  part(40, 8, 8, 8, 4, 0);                                  // hat
  if (!old) {
    part(20, 36, 8, 12, 4, 8); part(44, 36, aw, 12, 4 - aw, 8); part(52, 52, aw, 12, 12, 8);
    part(4, 36, 4, 12, 4, 20); part(4, 52, 4, 12, 8, 20);
  }
  return c;
}

/** Back of a cape (the part you see in game) as a small picture. */
async function capeFront(src) {
  const img = await skinImg(src);
  const c = document.createElement('canvas');
  c.width = 10; c.height = 16;
  const g = c.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.drawImage(img, 1, 1, 10, 16, 0, 0, 10, 16);
  return c;
}

function skinViewerReady() {
  if (skinViewer || !window.skinview3d) return skinViewer;
  const canvas = $('skinView');
  const box = canvas.parentElement;
  skinViewer = new skinview3d.SkinViewer({ canvas, width: box.clientWidth || 300, height: box.clientHeight || 360 });
  skinViewer.animation = new skinview3d.IdleAnimation();
  skinViewer.autoRotate = true;
  skinViewer.autoRotateSpeed = 0.6;
  skinViewer.zoom = 0.7;
  skinViewer.fov = 40;
  canvas.addEventListener('pointerdown', () => { skinViewer.autoRotate = false; });
  new ResizeObserver(() => { if (box.clientWidth && box.clientHeight) skinViewer.setSize(box.clientWidth, box.clientHeight); }).observe(box);
  return skinViewer;
}

async function skinShow() {
  const v = skinViewerReady();
  const sel = skinSel;
  $('skinTitle').textContent = sel ? sel.name : t('スキンがありません');
  document.querySelectorAll('#skinModel button').forEach(b => b.classList.toggle('on', sel && b.dataset.v === sel.variant));
  if (v) {
    if (sel && sel.image) await v.loadSkin(sel.image, { model: sel.variant === 'slim' ? 'slim' : 'default' });
    else v.loadSkin(null);
    const cape = skinCur && skinCur.capes.find(c => c.id === skinCapeId);
    if (cape && cape.image) await v.loadCape(cape.image); else v.loadCape(null);
  }
  const msa = skinCur && !skinCur.error;
  const isNow = sel && sel.kind === 'now';
  $('skinApply').disabled = !msa || !sel || isNow || skinBusy;
  $('skinApply').textContent = isNow ? t('いま使っているスキンです') : t('このスキンにする');
  $('skinSave').hidden = !isNow || !sel.image;
  $('skinNote').textContent = skinCur && skinCur.error ? skinCur.error
    : isNow ? t('アカウントで使っているスキン') : sel ? t('ライブラリのスキン') : '';
  document.querySelectorAll('#skinNow .sk, #skinLib .sk').forEach(e => e.classList.toggle('sel', !!sel && e.dataset.key === sel.key));
}

function skinCard(item, onRemove) {
  const card = el('div', 'sk');
  card.dataset.key = item.key;
  skinFront(item.image, item.variant === 'slim').then(c => card.prepend(c)).catch(() => {});
  card.appendChild(el('span', null, esc(item.name)));
  if (item.kind === 'now') card.appendChild(el('span', 'badge', esc(t('使用中'))));
  card.onclick = () => { skinSel = item; skinShow(); };
  if (onRemove) {
    const x = el('button', 'x', '✕');
    x.title = t('ライブラリから消す');
    x.onclick = async ev => {
      ev.stopPropagation();
      if (!x.classList.contains('sure')) { x.classList.add('sure'); x.textContent = t('消す?'); setTimeout(() => { x.classList.remove('sure'); x.textContent = '✕'; }, 3000); return; }
      await onRemove();
    };
    card.appendChild(x);
  }
  return card;
}

async function skinLibrary(selectId) {
  const list = await fz.skins.list();
  const box = $('skinLib');
  box.innerHTML = '';
  if (!list.length) box.appendChild(el('div', 'empty', t('まだありません。PNG かプレイヤー名から追加できます。')));
  for (const e of list) {
    const item = { kind: 'lib', key: 'lib:' + e.id, id: e.id, name: e.name, variant: e.variant, image: e.image };
    box.appendChild(skinCard(item, async () => {
      await fz.skins.remove(e.id);
      if (skinSel && skinSel.key === item.key) skinSel = null;
      await skinLibrary();
      if (!skinSel) skinSel = skinNowItem() || null;
      skinShow();
    }));
    if (selectId && e.id === selectId) skinSel = item;
  }
  return list;
}

function skinNowItem() {
  if (!skinCur || !skinCur.skin) return null;
  const acc = current();
  return { kind: 'now', key: 'now', name: acc ? acc.name : t('いまのスキン'), variant: skinCur.skin.variant, image: skinCur.skin.image };
}

function skinCapes() {
  const box = $('skinCapes');
  box.innerHTML = '';
  if (!skinCur || skinCur.error) { box.appendChild(el('div', 'empty', esc(skinCur ? skinCur.error : ''))); return; }
  if (!skinCur.capes.length) { box.appendChild(el('div', 'empty', t('このアカウントはマントを持っていません'))); return; }
  const opts = [{ id: null, name: t('なし') }, ...skinCur.capes];
  for (const c of opts) {
    const card = el('div', 'cp' + ((skinCapeId || null) === c.id ? ' sel' : ''));
    if (c.image) capeFront(c.image).then(cv => card.prepend(cv)).catch(() => {});
    else card.appendChild(el('div', 'none', '–'));
    card.appendChild(el('span', null, esc(c.name)));
    card.onclick = async () => {
      if (skinBusy || (skinCapeId || null) === c.id) return;
      skinBusy = true;
      try {
        skinCur = await fz.skins.cape(c.id);
        skinCapeId = (skinCur.capes.find(x => x.active) || {}).id || null;
        toast(c.id ? t('マントを {0} にしました', c.name) : t('マントを外しました'));
      } catch (e) { toast(errText(e), true); }
      skinBusy = false;
      skinCapes();
      skinShow();
    };
    box.appendChild(card);
  }
}

async function loadSkins() {
  skinViewerReady();
  const now = $('skinNow');
  now.innerHTML = '';
  now.appendChild(el('div', 'empty', t('読み込み中…')));
  const libP = skinLibrary();
  try {
    skinCur = await fz.skins.current();
  } catch (e) {
    skinCur = { error: errText(e), capes: [] };
  }
  await libP;
  skinCapeId = (skinCur.capes.find(c => c.active) || {}).id || null;
  now.innerHTML = '';
  const item = skinNowItem();
  if (item) now.appendChild(skinCard(item));
  else now.appendChild(el('div', 'empty', esc(skinCur.error || t('標準のスキン（Steve / Alex）です'))));
  if (!skinSel || (skinSel.kind === 'now')) skinSel = item;
  if (!skinSel) { const first = document.querySelector('#skinLib .sk'); if (first) first.click(); }
  skinCapes();
  skinShow();
}

document.querySelectorAll('#skinModel button').forEach(b => b.onclick = async () => {
  if (!skinSel || skinSel.variant === b.dataset.v) return;
  skinSel.variant = b.dataset.v;
  if (skinSel.kind === 'lib') { try { await fz.skins.update(skinSel.id, { variant: b.dataset.v }); } catch (e) { toast(errText(e), true); } }
  skinShow();
  if (skinSel.kind === 'lib') skinLibrary(skinSel.id).then(skinShow);
});

$('skinApply').onclick = async () => {
  if (!skinSel || skinSel.kind !== 'lib' || skinBusy) return;
  skinBusy = true;
  $('skinApply').disabled = true;
  $('skinApply').textContent = t('変更中…');
  try {
    skinCur = await fz.skins.apply(skinSel.id, skinSel.variant);
    toast(t('スキンを {0} にしました（ゲームには次に入ったときから反映されます）', skinSel.name));
    skinBusy = false;
    skinSel = null;
    await loadSkins();
  } catch (e) {
    skinBusy = false;
    toast(errText(e), true);
    skinShow();
  }
};

$('skinSave').onclick = async () => {
  if (!skinSel || skinSel.kind !== 'now') return;
  try {
    const e = await fz.skins.addData(skinSel.image.split(',')[1], skinSel.name, skinSel.variant);
    toast(t('ライブラリに保存しました'));
    await skinLibrary();
    skinShow();
    void e;
  } catch (e) { toast(errText(e), true); }
};

$('skinAddFile').onclick = async () => {
  try {
    const added = await fz.skins.addFile(skinSel ? skinSel.variant : 'classic');
    if (!added.length) return;
    await skinLibrary(added[0].id);
    skinShow();
    toast(t('{0} 個のスキンを追加しました', added.length));
  } catch (e) { toast(errText(e), true); }
};

async function skinAddPlayer() {
  const name = $('skinPlayer').value.trim();
  if (!name) return;
  $('skinAddPlayer').disabled = true;
  try {
    const e = await fz.skins.addPlayer(name);
    $('skinPlayer').value = '';
    await skinLibrary(e.id);
    skinShow();
    toast(t('{0} のスキンを追加しました', e.name));
  } catch (e) { toast(errText(e), true); }
  $('skinAddPlayer').disabled = false;
}
$('skinAddPlayer').onclick = skinAddPlayer;
$('skinPlayer').onkeydown = e => { if (e.key === 'Enter') skinAddPlayer(); };

// drop PNG files anywhere on the skin page
(function skinDrop() {
  const page = $('page-skins');
  page.addEventListener('dragover', e => { e.preventDefault(); page.classList.add('drop'); });
  page.addEventListener('dragleave', e => { if (e.target === page) page.classList.remove('drop'); });
  page.addEventListener('drop', async e => {
    e.preventDefault();
    page.classList.remove('drop');
    let last = null, n = 0;
    for (const f of e.dataTransfer.files) {
      if (!/\.png$/i.test(f.name)) continue;
      try {
        const b64 = await new Promise((ok, bad) => { const r = new FileReader(); r.onload = () => ok(String(r.result).split(',')[1]); r.onerror = bad; r.readAsDataURL(f); });
        last = await fz.skins.addData(b64, f.name, skinSel ? skinSel.variant : 'classic');
        n++;
      } catch (err) { toast(errText(err), true); }
    }
    if (last) { await skinLibrary(last.id); skinShow(); toast(t('{0} 個のスキンを追加しました', n)); }
  });
})();


// ---------------------------------------------------------------- PC optimisation
const OPT_GROUPS = [['windows', 'Windows'], ['network', 'ネットワーク'], ['gpu', 'グラフィック'], ['minecraft', 'Minecraft']];
const OPT_TEXT = {
  mouseAccel: ['マウス加速をオフ', '動かす速さで移動量が変わらなくなり、エイムが安定します。'],
  gameMode: ['ゲームモードをオン', 'ゲーム中は Windows Update などの裏の処理を抑えます。'],
  gameDvr: ['ゲームの録画（Xbox Game Bar）をオフ', '裏で録画し続ける機能を止めて、FPS が下がるのを防ぎます。'],
  gpuPref: ['Minecraft を高性能 GPU で動かす', 'ノート PC で、内蔵 GPU ではなく性能の高い GPU を使わせます。'],
  powerPlan: ['電源プランを「高パフォーマンス」に', 'CPU が省電力で遅くなるのを防ぎます（消費電力は増えます）。'],
  coreParking: ['CPU コアを眠らせない', 'コアパーキングを止めて、急に重くなったときのカクつきを減らします。'],
  sysResp: ['ゲームを優先する（マルチメディア設定）', '裏の処理に取っておく CPU の割合を減らします。'],
  prioSep: ['前面のウィンドウを優先', '今操作しているウィンドウに CPU の時間を多く回します。'],
  netThrottle: ['通信の制限を外す', '音楽や動画の再生中に通信を絞る Windows の機能を止めます。'],
  nicPower: ['LAN カードの省電力をオフ', '省電力で通信が一瞬止まるのを防ぎます（PC を再起動すると効きます）。'],
  nvidia: ['NVIDIA：FzClient 専用の設定', '電源を最大パフォーマンス、テクスチャ品質を高パフォーマンス、スレッド最適化をオンにします。ほかのゲームには影響しません。'],
  jvm: ['Java の最適化', 'メモリを最初から確保して、ガベージコレクションを Minecraft 向けに整えます。'],
  priority: ['ゲームの優先度を高く', '起動した Minecraft の CPU 優先度を「高」にします。'],
  x3d: ['キャッシュの大きいコアで動かす（Ryzen X3D）', 'Minecraft を 3D V-Cache の付いたコアだけで動かします。Windows がゲームだと気づかず、キャッシュの無いコアで動いてしまうのを防ぎます。'],
};
let optState = null, optWant = {}, optErrors = {}, optBusy = false;

async function loadOpt() {
  if (optBusy) return;
  const list = $('optList');
  if (!optState) list.innerHTML = '<div class="empty">' + esc(t('読み込み中…')) + '</div>';
  try {
    optState = await fz.opt.status();
  } catch (e) {
    list.innerHTML = '';
    list.appendChild(el('div', 'empty', esc(errText(e))));
    return;
  }
  optWant = {};
  renderOpt();
  renderOptResults();
  try {
    const d = await fz.opt.debug();
    $('optDebugText').textContent = '[state]\n' + (d.state || '-') + '\n\n[last change]\n' + (d.run || '-');
  } catch { /* fine */ }
}

function renderOpt() {
  const list = $('optList');
  list.innerHTML = '';
  for (const [g, label] of OPT_GROUPS) {
    const items = optState.tweaks.filter(x => x.group === g);
    if (!items.length) continue;
    list.appendChild(el('h3', null, esc(t(label))));
    for (const tw of items) {
      const want = tw.id in optWant ? optWant[tw.id] : tw.on;
      const row = el('div', 'opt' + (tw.id in optWant ? ' changed' : '') + (tw.available ? '' : ' na'));
      const [title, desc] = OPT_TEXT[tw.id] || [tw.id, ''];
      const tags = [];
      if (tw.recommended) tags.push(`<span class="tg rec">${esc(t('おすすめ'))}</span>`);
      if (tw.admin) tags.push(`<span class="tg adm">🛡 ${esc(t('管理者'))}</span>`);
      if (!tw.available) tags.push(`<span class="tg">${esc(t('この PC では使えません'))}</span>`);
      if (tw.id === 'coreParking' && optState.cpu) tags.push(`<span class="tg err">${esc(t('X3D の CPU では OFF がおすすめ'))}</span>`);
      if (optErrors[tw.id]) tags.push(`<span class="tg err">${esc(t(optErrors[tw.id]))}</span>`);
      row.appendChild(el('div', 'txt', `<b>${esc(t(title))}</b><small>${esc(t(desc))}</small><div class="tags">${tags.join('')}</div>`));
      row.appendChild(el('span', 'state' + (tw.on ? ' on' : ''), esc(t(tw.on ? '適用中' : '未適用'))));
      const sw = el('button', 'sw' + (want ? ' on' : ''));
      sw.disabled = !tw.available;
      sw.onclick = () => {
        const next = !want;
        if (next === tw.on) delete optWant[tw.id]; else optWant[tw.id] = next;
        renderOpt();
      };
      row.appendChild(sw);
      if (tw.id === 'netThrottle') {
        const dns = el('button', 'btn mini', esc(t('DNS キャッシュを消す')));
        dns.onclick = async () => { try { await fz.opt.flushDns(); toast(t('DNS キャッシュを消しました')); } catch (e) { toast(errText(e), true); } };
        row.insertBefore(dns, row.querySelector('.state'));
      }
      list.appendChild(row);
    }
  }
  const n = Object.keys(optWant).length;
  $('optApply').disabled = !n || optBusy;
  $('optApply').textContent = n ? t('適用（{0}）', n) : t('適用');
  const admin = optState.tweaks.some(x => x.admin && x.id in optWant);
  $('optMsg').textContent = admin ? t('🛡 のついた項目は、Windows が管理者の許可を求めます') : '';
}

async function optRun(want) {
  if (optBusy || !Object.keys(want).length) return;
  optBusy = true;
  $('optApply').disabled = true;
  $('optMsg').textContent = t('変更中…');
  try {
    const r = await fz.opt.change(want);
    optErrors = r.errors || {};
    const failed = Object.keys(optErrors).filter(k => k !== '_admin').length;
    const first = optErrors._admin || Object.values(optErrors)[0];
    toast(failed || optErrors._admin ? t('{0} 個は変更できませんでした', failed) + (first ? ' ― ' + t(first) : '') : t('{0} 個の設定を変更しました', r.done.length), !!(failed || optErrors._admin));
  } catch (e) {
    toast(errText(e), true);
  }
  optBusy = false;
  await loadOpt();
}

$('optApply').onclick = () => optRun(optWant);
$('optRec').onclick = async () => {
  if (!optState) return;
  const ids = await fz.opt.recommended(optState.nvidia);
  optWant = {};
  for (const tw of optState.tweaks) if (ids.includes(tw.id) && tw.available && !tw.on) optWant[tw.id] = true;
  renderOpt();
  if (!Object.keys(optWant).length) toast(t('おすすめの設定はもう全部適用されています'));
};
$('optRevert').onclick = () => {
  if (!optState) return;
  const btn = $('optRevert');
  if (!btn.classList.contains('sure')) {
    btn.classList.add('sure', 'danger');
    btn.textContent = t('本当に元に戻す?');
    setTimeout(() => { btn.classList.remove('sure', 'danger'); btn.textContent = t('すべて元に戻す'); }, 3000);
    return;
  }
  btn.classList.remove('sure', 'danger');
  btn.textContent = t('すべて元に戻す');
  const want = {};
  for (const tw of optState.tweaks) if (tw.on) want[tw.id] = false;
  if (!Object.keys(want).length) { toast(t('元に戻すものはありません')); return; }
  optRun(want);
};

// ---- measuring
function optHistory() {
  try { return JSON.parse(localStorage.getItem('fzOptResults') || '[]'); } catch { return []; }
}
function renderOptResults() {
  const box = $('optResults');
  box.innerHTML = '';
  const hist = optHistory();
  hist.forEach((r, i) => {
    const prev = hist[i + 1];
    const d = (k, better) => {
      if (!prev) return '';
      const diff = r[k] - prev[k];
      if (!diff) return '';
      const good = better === 'up' ? diff > 0 : diff < 0;
      return `<span class="${good ? 'up' : 'down'}">${diff > 0 ? '+' : ''}${Math.round(diff * 10) / 10}</span>`;
    };
    const card = el('div', 'res', `<div class="when">${esc(new Date(r.at).toLocaleString())} · ${r.seconds}${esc(t('秒'))} · ${r.frames} ${esc(t('フレーム'))}</div>
      <div class="nums">
        <div><b>${r.avgFps}</b><small>${esc(t('平均 FPS'))}</small>${d('avgFps', 'up')}</div>
        <div><b>${r.low1}</b><small>1% Low</small>${d('low1', 'up')}</div>
        <div><b>${r.low01}</b><small>0.1% Low</small>${d('low01', 'up')}</div>
        <div><b>${r.avgMs}</b><small>${esc(t('平均 ms'))}</small>${d('avgMs', 'down')}</div>
        <div><b>${r.worstMs}</b><small>${esc(t('最大 ms'))}</small>${d('worstMs', 'down')}</div>
        <div><b>${r.stutters}</b><small>${esc(t('カクつき'))}</small>${d('stutters', 'down')}</div>
      </div>`);
    box.appendChild(card);
  });
}
$('optMeasure').onclick = async () => {
  const btn = $('optMeasure');
  if (btn.disabled) return;
  const sec = +$('optSec').value || 20;
  btn.disabled = true;
  btn.textContent = t('計測中…');
  toast(t('{0} 秒間、ゲームの画面に戻って遊んでください', sec));
  try {
    const r = await fz.opt.measure(sec);
    r.at = Date.now();
    const hist = [r, ...optHistory()].slice(0, 5);
    try { localStorage.setItem('fzOptResults', JSON.stringify(hist)); } catch { /* fine */ }
    renderOptResults();
    toast(t('計測しました：平均 {0} FPS', r.avgFps));
  } catch (e) {
    toast(errText(e), true);
  }
  btn.disabled = false;
  btn.textContent = t('計測');
};
