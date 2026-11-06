'use strict';
// The small recording mark in the bottom right of the game's monitor (main.js: updateBadge). Clicks go through it.
fzo.onState(s => {
  document.getElementById('dot').className = 'dot' + (s.rec && s.rec.recording ? ' rec' : '');
});
