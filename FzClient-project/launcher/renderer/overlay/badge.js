'use strict';
// The REC mark in the corner of the game while the recorder runs (main.js: updateBadge). Clicks go through it.
const clock = s => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
fzo.onState(s => {
  setLang(s.lang);
  const r = s.rec || {};
  const b = document.getElementById('badge');
  b.className = 'badge ' + (r.recording ? 'rec' : 'replay');
  document.getElementById('text').textContent = r.recording ? `REC ${clock(r.seconds)}` : t('直前 {0} 秒を記録中', r.replaySeconds);
});
