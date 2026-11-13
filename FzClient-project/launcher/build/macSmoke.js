'use strict';
// CI only (.github/workflows/mac-smoke.yml), never part of the launcher that ships.
// Starts the real game on a Mac with the launcher's own code (src/core/game.js) and a made-up offline player,
// takes a picture of the screen every few seconds and keeps the logs, to see what a Mac user sees.
//   npx electron build/macSmoke.js
//   SMOKE_OUT (folder), SMOKE_JAR (fzclient.jar), SMOKE_SECONDS, FZ_MOJANG_JAVA=1 (Mojang's Java instead of Temurin)
//   SMOKE_MODE:
//     windowed     nothing special
//     arg          the launcher's "start in fullscreen" setting
//     option       fullscreen:true in options.txt before the launcher starts the game
//     option-late  fullscreen:true in options.txt written after the launcher did its part (= an older launcher)
//     flag         "--fullscreen true" really reaching the game (Forge drops a "--fullscreen" at the end)
const { app } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');
const { spawnSync } = cp;

const out = path.resolve(process.env.SMOKE_OUT || 'out');
const jar = path.resolve(process.env.SMOKE_JAR || 'resources/fzclient.jar');
const seconds = Number(process.env.SMOKE_SECONDS) || 240;
const mode = process.env.SMOKE_MODE || 'windowed';
fs.mkdirSync(out, { recursive: true });

// "flag": extra words after the game's own arguments (game.js takes spawn from child_process when it loads)
const extraGameArgs = mode === 'flag' ? ['--fullscreen', 'true'] : [];
const spawn0 = cp.spawn;
cp.spawn = function (cmd, args, opts) {
  if (extraGameArgs.length && /\/java$/.test(String(cmd)) && Array.isArray(args)) args = [...args, ...extraGameArgs];
  return spawn0.call(this, cmd, args, opts);
};

const lines = [];
function log(line) {
  const s = `[${new Date().toISOString()}] ${line}`;
  lines.push(s);
  console.log(s);
  try { fs.writeFileSync(path.join(out, 'smoke.log'), lines.join('\n') + '\n'); } catch { /* keep going */ }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

function shot(name) {
  const r = spawnSync('/usr/sbin/screencapture', ['-x', '-t', 'jpg', path.join(out, name + '.jpg')], { encoding: 'utf8' });
  if (r.status !== 0) log('[shot] ' + name + ': ' + (r.stderr || r.status));
}

function keep(from, name) {
  try {
    if (fs.existsSync(from)) fs.cpSync(from, path.join(out, name), { recursive: true });
  } catch (e) { log('[keep] ' + name + ': ' + e.message); }
}

async function run() {
  const { Game } = require('../src/core/game');
  const appData = path.join(os.homedir(), 'Library', 'Application Support');
  const root = path.join(appData, '.fzclient');
  const userData = path.join(appData, 'FzClient Launcher');
  const options = path.join(root, 'options.txt');
  fs.mkdirSync(root, { recursive: true });
  fs.mkdirSync(userData, { recursive: true });
  log('[smoke] mode ' + mode + ', ' + os.release() + ' ' + process.arch + ', jar ' + fs.statSync(jar).size + ' bytes');
  if (mode === 'option') fs.writeFileSync(options, 'fullscreen:true\n');

  // the launch ticket, as main.js writes it
  const token = require('crypto').randomBytes(24).toString('hex');
  fs.writeFileSync(path.join(userData, 'launch.ticket'), token + ' ' + Date.now());

  const game = new Game({ root, gameDir: root }, log);
  const gameOut = fs.createWriteStream(path.join(out, 'game-output.log'));
  let bytes = 0, lastStage = '';
  const t0 = Date.now();
  const child = await game.launch({
    account: { name: 'FzSmokeTest', uuid: '00000000000000000000000000000001', accessToken: '0', type: 'legacy' },
    memoryMb: 2048, width: 960, height: 540, fullscreen: mode === 'arg', jvmArgs: process.env.SMOKE_JVM || '', bundledJar: jar,
    launcherVersion: 'smoke', jvmExtras: ['-Dfzclient.ticket=' + token],
    onProgress: stage => { if (stage !== lastStage) { lastStage = stage; log('[progress] ' + stage); } },
    onLog: text => { bytes += text.length; gameOut.write(text); },
  });
  log('[smoke] game started (pid ' + child.pid + ') after ' + Math.round((Date.now() - t0) / 1000) + ' s of installing');
  if (mode === 'option') log('[smoke] options.txt after the launcher: ' + JSON.stringify(fs.existsSync(options) ? fs.readFileSync(options, 'utf8') : null));
  if (mode === 'option-late') {
    fs.writeFileSync(options, 'fullscreen:true\n');   // the game reads it some seconds from now
    log('[smoke] options.txt written after the launcher: fullscreen:true');
  }
  const v = spawnSync(game.javaExe(), ['-version'], { encoding: 'utf8' });
  log('[smoke] java: ' + game.javaExe() + ' :: ' + String(v.stderr || v.stdout).replace(/\s+/g, ' ').trim());

  let exited = null;
  child.on('exit', (code, signal) => { exited = { code, signal }; log('[smoke] the game ended: code ' + code + ', signal ' + signal); });
  const start = Date.now();
  const elapsed = () => Math.round((Date.now() - start) / 1000);
  let dumped = false;
  while (!exited && elapsed() < seconds) {
    await sleep(elapsed() < 90 ? 5000 : 15000);
    const name = 'game-' + String(elapsed()).padStart(3, '0') + 's';
    shot(name);
    log('[smoke] ' + name + ', ' + bytes + ' bytes of game output');
    if (!dumped && elapsed() >= 150 && !exited) {
      dumped = true;
      try { process.kill(child.pid, 'SIGQUIT'); } catch { /* gone */ }   // Java prints every thread's stack
    }
  }
  // what the game's options.txt says now (the game writes all of it; only this line matters here)
  try {
    const m = /^fullscreen:.*$/m.exec(fs.readFileSync(options, 'utf8'));
    log('[smoke] options.txt at the end: ' + (m ? m[0] : 'no fullscreen line'));
  } catch { log('[smoke] options.txt at the end: no file'); }
  if (!exited) {
    try { child.kill('SIGKILL'); } catch { /* gone */ }
    await sleep(1500);
  }
  gameOut.end();
  keep(path.join(root, 'logs'), 'logs');
  keep(path.join(root, 'crash-reports'), 'crash-reports');
  keep(options, 'options.txt');
  for (const f of fs.readdirSync(root)) if (/^hs_err/.test(f)) keep(path.join(root, f), f);
  log('[smoke] done');
}

app.whenReady().then(async () => {
  try { if (app.dock) app.dock.hide(); } catch { /* not a Mac */ }
  let code = 0;
  try { await run(); } catch (e) { code = 1; log('[smoke] FAILED ' + (e && e.stack || e)); }
  app.exit(code);
});
