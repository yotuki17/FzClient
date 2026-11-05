'use strict';
// PC optimisation for Minecraft PvP (the "最適化" page): well-known Windows, network, NVIDIA and Java settings,
// each one switchable on and off again. Before a setting is changed for the first time its old value is saved
// (optimize-backup.json), so "元に戻す" puts back exactly what was there.
//
// How things are changed:
//  - settings of your own user (mouse, Game Bar, GPU choice, power plan): a normal PowerShell script, no prompt;
//  - settings of the whole PC (HKLM, core parking, network card, NVIDIA driver): one PowerShell script run as
//    administrator, so Windows asks once (UAC) per "適用";
//  - NVIDIA Profile Inspector and PresentMon (both MIT-licensed, by their authors on GitHub) are downloaded
//    from their official GitHub releases the first time they are needed.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const { app, net } = require('electron');
const AdmZip = require('adm-zip');

const HIGH_PERF = '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c';
const NV = { power: 274197361, texture: 13510289, threaded: 549528094 };   // NVIDIA driver setting ids
const GAME_EXE = 'FzClient.exe';   // the launcher runs Java under this name (see game.js)

function dataDir() {
  return app.getPath('userData');
}
function toolsDir() {
  return path.join(dataDir(), 'tools');
}
function backupFile() {
  return path.join(dataDir(), 'optimize-backup.json');
}
function readJson(f, def) {
  try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return def; }
}
function writeJson(f, v) {
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, JSON.stringify(v, null, 2));
}

// ---------------------------------------------------------------- PowerShell

const psq = s => "'" + String(s).replace(/'/g, "''") + "'";   // PowerShell single-quoted string

/** Writes a script (UTF-8 with BOM, so PowerShell 5 reads non-ASCII paths) and runs it. */
function runScript(body, { admin = false, timeoutMs = 120000 } = {}) {
  const dir = path.join(os.tmpdir(), 'fzclient-opt');
  fs.mkdirSync(dir, { recursive: true });
  const stamp = Date.now() + '-' + Math.random().toString(36).slice(2, 8);
  const file = path.join(dir, `run-${stamp}.ps1`);
  const out = path.join(dir, `out-${stamp}.txt`);
  const script = '$ErrorActionPreference = "Continue"\n'
    + '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n'
    + `$FZ_OUT = ${psq(out)}\n`
    + body + '\n';
  fs.writeFileSync(file, '﻿' + script, 'utf8');
  const args = admin
    ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
      `try { $p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList ${psq(`-NoProfile -ExecutionPolicy Bypass -File "${file}"`)}; exit $p.ExitCode } catch { exit 1223 }`]
    : ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', file];
  return new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', args, { windowsHide: true });
    let stdout = '', stderr = '';
    child.stdout.on('data', d => { stdout += d.toString('utf8'); });
    child.stderr.on('data', d => { stderr += d.toString('utf8'); });
    const timer = setTimeout(() => { try { child.kill(); } catch { /* gone */ } }, timeoutMs);
    child.on('error', e => { clearTimeout(timer); reject(e); });
    child.on('close', code => {
      clearTimeout(timer);
      let result = '';
      try { result = fs.readFileSync(out, 'utf8').replace(/^﻿/, ''); } catch { /* the script wrote nothing */ }
      try { fs.unlinkSync(file); fs.unlinkSync(out); } catch { /* fine */ }
      if (admin && code === 1223) return reject(new Error('管理者の許可がキャンセルされました'));
      resolve({ code, stdout, stderr, result });
    });
  });
}

// ---------------------------------------------------------------- reading the current state

// Plain "key=value" lines instead of ConvertTo-Json: Windows PowerShell 5.1 sometimes writes arrays and
// registry values in odd JSON shapes, which made every setting look "off".
const STATE_SCRIPT = String.raw`
$ErrorActionPreference = 'SilentlyContinue'
$L = New-Object System.Collections.Generic.List[string]
function Get-FzValue($p, $n) { try { $v = (Get-ItemProperty -LiteralPath $p -Name $n -ErrorAction Stop).$n; if ($null -eq $v) { '' } else { [string]$v } } catch { '' } }
function Put($k, $v) { $L.Add($k + '=' + [string]$v) }
Put 'mouse0' (Get-FzValue 'HKCU:\Control Panel\Mouse' 'MouseSpeed')
Put 'mouse1' (Get-FzValue 'HKCU:\Control Panel\Mouse' 'MouseThreshold1')
Put 'mouse2' (Get-FzValue 'HKCU:\Control Panel\Mouse' 'MouseThreshold2')
Put 'gameMode' (Get-FzValue 'HKCU:\Software\Microsoft\GameBar' 'AutoGameModeEnabled')
Put 'dvr' (Get-FzValue 'HKCU:\System\GameConfigStore' 'GameDVR_Enabled')
Put 'appCapture' (Get-FzValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' 'AppCaptureEnabled')
Put 'gpuPref' (Get-FzValue 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences' $env:FZ_JAVA)
$s = (powercfg /getactivescheme) | Out-String
if ($s -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { Put 'scheme' $Matches[1].ToLower() }
$q = (powercfg /q scheme_current sub_processor CPMINCORES) | Out-String
if ($q -match 'AC[^\r\n]*?0x([0-9a-fA-F]+)') { Put 'cpMinCores' ([Convert]::ToInt32($Matches[1], 16)) }
Put 'sysResp' (Get-FzValue 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile' 'SystemResponsiveness')
Put 'netThrottle' (Get-FzValue 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile' 'NetworkThrottlingIndex')
Put 'prioSep' (Get-FzValue 'HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl' 'Win32PrioritySeparation')
$cls = 'HKLM:\SYSTEM\CurrentControlSet\Control\Class\{4d36e972-e325-11ce-bfc1-08002be10318}'
$nicKeys = @(Get-ChildItem -LiteralPath $cls -ErrorAction SilentlyContinue | Where-Object { $_.PSChildName -match '^\d{4}$' })
foreach ($a in @(Get-NetAdapter -Physical | Where-Object { $_.Status -ne 'Disabled' })) {
  $k = $nicKeys | Where-Object { (Get-FzValue $_.PSPath 'NetCfgInstanceId') -eq [string]$a.InterfaceGuid } | Select-Object -First 1
  $id = ''; $pnp = ''
  if ($k) { $id = $k.PSChildName; $pnp = Get-FzValue $k.PSPath 'PnPCapabilities' }
  Put 'nic' ($a.Name + '|' + $id + '|' + $pnp)
}
foreach ($g in @(Get-CimInstance Win32_VideoController)) { Put 'gpu' $g.Name }
[System.IO.File]::WriteAllLines($FZ_OUT, $L, (New-Object System.Text.UTF8Encoding $false))
`;

/** "key=value" lines into the state object the tweaks read. */
function parseState(text) {
  const st = { mouse: ['', '', ''], nic: [], gpus: [] };
  for (const line of String(text || '').split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i < 0) continue;
    const k = line.slice(0, i), v = line.slice(i + 1);
    if (k === 'mouse0') st.mouse[0] = v;
    else if (k === 'mouse1') st.mouse[1] = v;
    else if (k === 'mouse2') st.mouse[2] = v;
    else if (k === 'nic') {
      const parts = v.split('|'), pnp = parts.pop(), key = parts.pop();
      st.nic.push({ name: parts.join('|'), key, pnp });
    }
    else if (k === 'gpu') st.gpus.push(v);
    else st[k] = v === '' ? null : v;
  }
  return st;
}

let javaPath = '';
let lastStateText = '', lastRunText = '';
/** What the last state read printed (for the launcher log). */
function lastState() {
  return lastStateText;
}
/** For the page's "詳細": what Windows reported last time, and what the last change printed. */
function debugInfo() {
  return { state: lastStateText, run: lastRunText };
}
/** The game's Java (bin/FzClient.exe): the GPU choice is stored per program path. */
function setJavaPath(p) {
  javaPath = p || '';
}

async function readState() {
  process.env.FZ_JAVA = javaPath;
  const r = await runScript(STATE_SCRIPT, { timeoutMs: 45000 });
  lastStateText = r.result + (r.stderr ? '\n[stderr] ' + r.stderr.slice(0, 1000) : '');
  if (!r.result) throw new Error('Windows の設定を読み取れませんでした');
  return parseState(r.result);
}

// ---------------------------------------------------------------- the tweaks

const num = v => (v == null || v === '' ? null : Number(v));
const S = v => (v == null ? null : String(v));

/**
 * Each tweak: id, group, admin (needs the UAC prompt), on(state, settings) = is it applied,
 * backup(state) = values to remember, apply() / revert(backup) = PowerShell lines (or settings changes).
 */
const TWEAKS = [
  // ---- Windows (your user)
  {
    id: 'mouseAccel', group: 'windows', recommended: true,
    on: s => S(s.mouse && s.mouse[0]) === '0',
    backup: s => ({ mouse: s.mouse }),
    apply: () => `
Add-Type -Namespace FzOpt -Name Mouse -MemberDefinition '[DllImport("user32.dll")] public static extern bool SystemParametersInfo(int a, int b, int[] c, int d);'
[FzOpt.Mouse]::SystemParametersInfo(4, 0, @(0, 0, 0), 3) | Out-Null`,
    revert: b => {
      const m = (b && b.mouse) || ['1', '6', '10'];
      const sp = Number(m[0] || 1), t1 = Number(m[1] || 6), t2 = Number(m[2] || 10);
      return `
Add-Type -Namespace FzOpt -Name Mouse -MemberDefinition '[DllImport("user32.dll")] public static extern bool SystemParametersInfo(int a, int b, int[] c, int d);'
[FzOpt.Mouse]::SystemParametersInfo(4, 0, @(${t1}, ${t2}, ${sp}), 3) | Out-Null`;
    },
  },
  {
    id: 'gameMode', group: 'windows', recommended: true,
    on: s => num(s.gameMode) === 1,
    backup: s => ({ gameMode: s.gameMode }),
    apply: () => `if (-not (Test-Path -LiteralPath 'HKCU:\\Software\\Microsoft\\GameBar')) { New-Item -Path 'HKCU:\\Software\\Microsoft\\GameBar' -Force | Out-Null }
Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\GameBar' -Name AutoGameModeEnabled -Type DWord -Value 1`,
    revert: b => (b && b.gameMode != null
      ? `Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\GameBar' -Name AutoGameModeEnabled -Type DWord -Value ${Number(b.gameMode)}`
      : `Remove-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\GameBar' -Name AutoGameModeEnabled -ErrorAction SilentlyContinue`),
  },
  {
    id: 'gameDvr', group: 'windows', recommended: true,
    on: s => num(s.dvr) === 0 && num(s.appCapture) === 0,
    backup: s => ({ dvr: s.dvr, appCapture: s.appCapture }),
    apply: () => `if (-not (Test-Path -LiteralPath 'HKCU:\\System\\GameConfigStore')) { New-Item -Path 'HKCU:\\System\\GameConfigStore' -Force | Out-Null }
Set-ItemProperty -Path 'HKCU:\\System\\GameConfigStore' -Name GameDVR_Enabled -Type DWord -Value 0
if (-not (Test-Path -LiteralPath 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\GameDVR')) { New-Item -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\GameDVR' -Force | Out-Null }
Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\GameDVR' -Name AppCaptureEnabled -Type DWord -Value 0`,
    revert: b => `Set-ItemProperty -Path 'HKCU:\\System\\GameConfigStore' -Name GameDVR_Enabled -Type DWord -Value ${b && b.dvr != null ? Number(b.dvr) : 1}
Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\GameDVR' -Name AppCaptureEnabled -Type DWord -Value ${b && b.appCapture != null ? Number(b.appCapture) : 1}`,
  },
  {
    id: 'gpuPref', group: 'windows', recommended: true,
    on: s => /GpuPreference=2/.test(S(s.gpuPref) || ''),
    backup: s => ({ gpuPref: s.gpuPref }),
    apply: () => `if (-not (Test-Path -LiteralPath 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences')) { New-Item -Path 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences' -Force | Out-Null }
Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences' -Name ${psq(javaPath)} -Value 'GpuPreference=2;'`,
    revert: b => (b && b.gpuPref
      ? `Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences' -Name ${psq(javaPath)} -Value ${psq(b.gpuPref)}`
      : `Remove-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences' -Name ${psq(javaPath)} -ErrorAction SilentlyContinue`),
  },
  {
    id: 'powerPlan', group: 'windows', recommended: true,
    on: s => s.scheme === HIGH_PERF || (s.scheme && s.scheme === (readJson(backupFile(), {}).createdScheme || '')),
    backup: s => ({ scheme: s.scheme }),
    // laptops with Modern Standby hide "High performance": then a copy of it is made
    apply: () => `$l = (powercfg /list) | Out-String
if ($l -match '${HIGH_PERF}') { powercfg /setactive ${HIGH_PERF} | Out-Null }
else {
  $d = (powercfg -duplicatescheme ${HIGH_PERF}) | Out-String
  if ($d -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { powercfg /setactive $Matches[1] | Out-Null; Add-Content -Encoding UTF8 -Path $FZ_OUT -Value ("created=" + $Matches[1]) }
}`,
    revert: b => (b && b.scheme ? `powercfg /setactive ${b.scheme} | Out-Null` : `powercfg /setactive 381b4222-f694-41f0-9685-ff5bb260df2e | Out-Null`),
  },
  // ---- Windows (whole PC, administrator)
  {
    id: 'coreParking', group: 'windows', admin: true, recommended: true,
    on: s => num(s.cpMinCores) === 100,
    backup: s => ({ cpMinCores: s.cpMinCores }),
    apply: () => `powercfg /setacvalueindex scheme_current sub_processor CPMINCORES 100
powercfg /setactive scheme_current`,
    revert: b => `powercfg /setacvalueindex scheme_current sub_processor CPMINCORES ${b && b.cpMinCores != null ? Number(b.cpMinCores) : 5}
powercfg /setactive scheme_current`,
  },
  {
    id: 'sysResp', group: 'windows', admin: true, recommended: true,
    on: s => num(s.sysResp) === 10,
    backup: s => ({ sysResp: s.sysResp }),
    apply: () => `Set-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Multimedia\\SystemProfile' -Name SystemResponsiveness -Type DWord -Value 10`,
    revert: b => `Set-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Multimedia\\SystemProfile' -Name SystemResponsiveness -Type DWord -Value ${b && b.sysResp != null ? Number(b.sysResp) : 20}`,
  },
  {
    id: 'prioSep', group: 'windows', admin: true,
    on: s => num(s.prioSep) === 0x26,
    backup: s => ({ prioSep: s.prioSep }),
    apply: () => `Set-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\PriorityControl' -Name Win32PrioritySeparation -Type DWord -Value 38`,
    revert: b => `Set-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\PriorityControl' -Name Win32PrioritySeparation -Type DWord -Value ${b && b.prioSep != null ? Number(b.prioSep) : 2}`,
  },
  // ---- network
  {
    id: 'netThrottle', group: 'network', admin: true, recommended: true,
    on: s => num(s.netThrottle) === 4294967295 || num(s.netThrottle) === -1,
    backup: s => ({ netThrottle: s.netThrottle }),
    apply: () => `Set-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Multimedia\\SystemProfile' -Name NetworkThrottlingIndex -Type DWord -Value 0xffffffff`,
    revert: b => `Set-ItemProperty -Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Multimedia\\SystemProfile' -Name NetworkThrottlingIndex -Type DWord -Value ${b && b.netThrottle != null && Number(b.netThrottle) >= 0 ? Number(b.netThrottle) : 10}`,
  },
  {
    // "Allow the computer to turn off this device": stored as PnPCapabilities = 24 on the adapter's driver key
    // (works on every Windows version; Set-NetAdapterPowerManagement lacks the switch on some). Takes effect
    // after the adapter restarts or the PC reboots.
    id: 'nicPower', group: 'network', admin: true, recommended: true,
    on: s => Array.isArray(s.nic) && s.nic.some(n => n.key) && s.nic.filter(n => n.key).every(n => num(n.pnp) === 24),
    backup: s => ({ nic: s.nic }),
    apply: () => `$cls = 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e972-e325-11ce-bfc1-08002be10318}'
$n = 0
foreach ($a in @(Get-NetAdapter -Physical | Where-Object { $_.Status -ne 'Disabled' })) {
  foreach ($k in @(Get-ChildItem -LiteralPath $cls -ErrorAction SilentlyContinue | Where-Object { $_.PSChildName -match '^\\d{4}$' })) {
    $g = (Get-ItemProperty -LiteralPath $k.PSPath -Name NetCfgInstanceId -ErrorAction SilentlyContinue).NetCfgInstanceId
    if ($g -eq [string]$a.InterfaceGuid) { Set-ItemProperty -LiteralPath $k.PSPath -Name PnPCapabilities -Type DWord -Value 24; $n++ }
  }
}
if ($n -eq 0) { throw 'no network adapter found' }`,
    revert: b => ((b && Array.isArray(b.nic) ? b.nic : []).filter(n => n.key && /^\d{4}$/.test(n.key)).map(n => {
      const k = `'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e972-e325-11ce-bfc1-08002be10318}\\${n.key}'`;
      return n.pnp === '' || n.pnp == null
        ? `Remove-ItemProperty -LiteralPath ${k} -Name PnPCapabilities -ErrorAction SilentlyContinue`
        : `Set-ItemProperty -LiteralPath ${k} -Name PnPCapabilities -Type DWord -Value ${Number(n.pnp)}`;
    }).join('\n') || '# nothing to restore'),
  },
  // ---- NVIDIA (a driver profile only for FzClient's Java, so other games are not touched)
  {
    id: 'nvidia', group: 'gpu', admin: true, recommended: true, nvidia: true,
    on: (s, st, b) => !!(b && b.nvidiaApplied),
    backup: () => ({}),
    apply: () => nvidiaLines({ power: 1, texture: 20, threaded: 1 }),
    revert: () => nvidiaLines({ power: 5, texture: 0, threaded: 0 }),
  },
  // ---- Minecraft (launcher settings, no Windows changes)
  {
    id: 'jvm', group: 'minecraft', launcher: true, recommended: true,
    on: (s, st) => !!st.optimizeJvm,
  },
  {
    id: 'priority', group: 'minecraft', launcher: true, recommended: true,
    on: (s, st) => st.gamePriority === 'high',
  },
  {
    // only on Ryzen X3D chips with two core groups (see cacheCores)
    id: 'x3d', group: 'minecraft', launcher: true, recommended: true, x3d: true,
    on: (s, st) => !!st.gameX3d,
  },
];

/**
 * Ryzen 7900X3D / 7950X3D / 9900X3D / 9950X3D: two groups of cores (CCDs), and only the first one has the big
 * 3D V-Cache that games run much faster on. Windows moves a program there only when it recognises it as a game,
 * which it often does not for Minecraft (Java). Returns { model, mask } with the first group's logical
 * processors (Windows numbers them first), or null on every other CPU.
 */
function cacheCores() {
  const cpus = os.cpus();
  const model = (cpus[0] && cpus[0].model || '').trim();
  if (!/Ryzen.*\b(7900|7950|9900|9950)X3D\b/i.test(model) || cpus.length < 4) return null;
  const half = Math.floor(cpus.length / 2);
  return { model, mask: 2 ** half - 1, count: half };
}

let nvidiaExe = '';
function nvidiaLines(v) {
  const nip = path.join(toolsDir(), `fzclient-${Date.now()}.nip`);
  const set = (id, val, name) => `      <ProfileSetting>
        <SettingNameInfo>${name}</SettingNameInfo>
        <SettingID>${id}</SettingID>
        <SettingValue>${val}</SettingValue>
        <ValueType>Dword</ValueType>
      </ProfileSetting>`;
  const xml = `<?xml version="1.0" encoding="utf-16"?>
<ArrayOfProfile xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Profile>
    <ProfileName>FzClient</ProfileName>
    <Executeables>
      <string>${GAME_EXE.toLowerCase()}</string>
    </Executeables>
    <Settings>
${set(NV.power, v.power, 'Power management mode')}
${set(NV.texture, v.texture, 'Texture filtering - Quality')}
${set(NV.threaded, v.threaded, 'Threaded optimization')}
    </Settings>
  </Profile>
</ArrayOfProfile>
`;
  fs.mkdirSync(toolsDir(), { recursive: true });
  fs.writeFileSync(nip, Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(xml, 'utf16le')]));
  return `$np = Start-Process -FilePath ${psq(nvidiaExe)} -ArgumentList @('-silentImport', ${psq('"' + nip + '"')}) -Wait -PassThru -WindowStyle Hidden
if ($np.ExitCode -ne 0) { throw "NVIDIA Profile Inspector exit $($np.ExitCode)" }
Remove-Item -LiteralPath ${psq(nip)} -ErrorAction SilentlyContinue`;
}

// ---------------------------------------------------------------- tools from GitHub

async function latestAsset(repo, pattern) {
  const res = await net.fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
    headers: { 'User-Agent': 'FzClient-Launcher', Accept: 'application/vnd.github+json' },
  });
  if (!res.ok) throw new Error(`GitHub に接続できませんでした（HTTP ${res.status}）`);
  const rel = await res.json();
  const asset = (rel.assets || []).find(a => pattern.test(a.name));
  if (!asset) throw new Error(`${repo} のダウンロードが見つかりませんでした`);
  return asset;
}

async function download(url) {
  const res = await net.fetch(url, { headers: { 'User-Agent': 'FzClient-Launcher' } });
  if (!res.ok) throw new Error(`ダウンロードに失敗しました（HTTP ${res.status}）`);
  return Buffer.from(await res.arrayBuffer());
}

function findFile(dir, name) {
  if (!fs.existsSync(dir)) return '';
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, f.name);
    if (f.isDirectory()) { const r = findFile(p, name); if (r) return r; }
    else if (f.name.toLowerCase() === name.toLowerCase()) return p;
  }
  return '';
}

/** NVIDIA Profile Inspector (MIT, github.com/Orbmu2k/nvidiaProfileInspector). */
async function ensureNvidiaTool() {
  const dir = path.join(toolsDir(), 'nvidiaProfileInspector');
  let exe = findFile(dir, 'nvidiaProfileInspector.exe');
  if (!exe) {
    const asset = await latestAsset('Orbmu2k/nvidiaProfileInspector', /\.zip$/i);
    const zip = new AdmZip(await download(asset.browser_download_url));
    fs.mkdirSync(dir, { recursive: true });
    zip.extractAllTo(dir, true);
    exe = findFile(dir, 'nvidiaProfileInspector.exe');
    if (!exe) throw new Error('NVIDIA Profile Inspector を準備できませんでした');
  }
  nvidiaExe = exe;
  return exe;
}

/** PresentMon (MIT, github.com/GameTechDev/PresentMon): the console version. */
async function ensurePresentMon() {
  const dir = path.join(toolsDir(), 'PresentMon');
  const have = fs.existsSync(dir) ? fs.readdirSync(dir).find(f => /^PresentMon.*\.exe$/i.test(f)) : null;
  if (have) return path.join(dir, have);
  const asset = await latestAsset('GameTechDev/PresentMon', /^PresentMon-[\d.]+-x64\.exe$/i);
  fs.mkdirSync(dir, { recursive: true });
  const exe = path.join(dir, asset.name);
  fs.writeFileSync(exe, await download(asset.browser_download_url));
  return exe;
}

// ---------------------------------------------------------------- public

function hasNvidia(state) {
  return (state.gpus || []).some(g => /nvidia/i.test(g));
}

/** Every tweak with its current state. */
async function status(settings) {
  const state = await readState();
  const backup = readJson(backupFile(), {});
  return {
    nvidia: hasNvidia(state),
    tweaks: TWEAKS.map(t => ({
      id: t.id, group: t.group, admin: !!t.admin, recommended: !!t.recommended,
      on: !!t.on(state, settings, backup),
      available: !(t.nvidia && !hasNvidia(state)) && !(t.x3d && !cacheCores()),
    })),
    cpu: cacheCores(),
  };
}

/**
 * Turns tweaks on or off: want = { id: true | false } for the ones to change.
 * Resolves with { done: [ids], errors: { id: message }, settings: launcher settings patch }.
 */
async function change(want, settings) {
  const state = await readState();
  const backup = readJson(backupFile(), {});
  const user = [], admin = [], patch = {}, done = [], errors = {};
  for (const t of TWEAKS) {
    if (!(t.id in want)) continue;
    const on = !!want[t.id];
    if (t.launcher) {
      if (t.id === 'jvm') patch.optimizeJvm = on;
      if (t.id === 'priority') patch.gamePriority = on ? 'high' : 'normal';
      if (t.id === 'x3d') patch.gameX3d = on && !!cacheCores();
      done.push(t.id);
      continue;
    }
    if (t.nvidia) {
      if (!hasNvidia(state)) { errors[t.id] = 'NVIDIA のグラフィックカードが見つかりません'; continue; }
      try { await ensureNvidiaTool(); } catch (e) { errors[t.id] = e.message; continue; }
    }
    if (on && !backup[t.id]) backup[t.id] = t.backup(state);   // remember the original only once
    const lines = on ? t.apply() : t.revert(backup[t.id]);
    // every error inside a block stops that block and is reported for its item (not silently skipped)
    const block = `try {\n$ErrorActionPreference = 'Stop'\n${lines}\nAdd-Content -Encoding UTF8 -Path $FZ_OUT -Value ('ok=${t.id}')\n} catch { $ErrorActionPreference = 'Continue'; Add-Content -Encoding UTF8 -Path $FZ_OUT -Value ('err=${t.id}=' + ($_.Exception.Message -replace '[\\r\\n]+', ' ')) }`;
    (t.admin ? admin : user).push(block);
  }
  writeJson(backupFile(), backup);

  let logText = '';
  const collect = (text, r) => {
    logText += String(text || '') + (r && r.stderr ? '\n[stderr] ' + r.stderr : '');
    for (const line of String(text || '').split(/\r?\n/)) {
      const ok = /^ok=(\w+)/.exec(line), err = /^err=(\w+)=(.*)$/.exec(line), made = /^created=([0-9a-f-]{36})/i.exec(line);
      if (ok) done.push(ok[1]);
      if (err) errors[err[1]] = err[2];
      if (made) { backup.createdScheme = made[1].toLowerCase(); writeJson(backupFile(), backup); }
    }
  };
  if (user.length) { const r = await runScript(user.join('\n')); collect(r.result, r); }
  if (admin.length) {
    try {
      const r = await runScript(admin.join('\n'), { admin: true, timeoutMs: 180000 });
      if (!r.result) errors._admin = '管理者のスクリプトが動きませんでした（コード ' + r.code + '）';
      collect(r.result, r);
    } catch (e) {
      for (const t of TWEAKS) if (t.admin && t.id in want && !(t.id in errors)) errors[t.id] = e.message;
    }
  }
  if ('nvidia' in want && done.includes('nvidia')) { backup.nvidiaApplied = !!want.nvidia; writeJson(backupFile(), backup); }
  for (const id of Object.keys(want)) if (!done.includes(id) && !errors[id]) errors[id] = '変更できませんでした';
  // read Windows again: a step that "worked" but changed nothing is reported too
  try {
    const after = await readState();
    const b2 = readJson(backupFile(), {});
    for (const t of TWEAKS) {
      if (!(t.id in want) || t.launcher || t.nvidia || errors[t.id]) continue;
      if (!!t.on(after, settings, b2) !== !!want[t.id]) errors[t.id] = '実行しましたが、設定が変わっていません';
    }
  } catch { /* keep what we have */ }
  lastRunText = logText;
  return { done: done.filter(id => !errors[id]), errors, settings: patch, log: logText };
}

/** The recommended set: every recommended tweak that this PC can use. */
function recommended(nvidia) {
  const x3d = !!cacheCores();
  return TWEAKS.filter(t => t.recommended && !(t.nvidia && !nvidia) && !(t.x3d && !x3d)
    // on two-group X3D chips AMD's driver parks the group without the cache for games; keeping every core
    // awake works against that
    && !(t.id === 'coreParking' && x3d)).map(t => t.id);
}

/** Extra Java options when "Java の最適化" is on (Java 8, Minecraft 1.8.9). */
function jvmExtras() {
  return ['-XX:+DisableExplicitGC', '-XX:+ParallelRefProcEnabled', '-XX:+PerfDisableSharedMem', '-XX:+UseStringDeduplication'];
}

/** Clears the DNS cache (no administrator needed). */
async function flushDns() {
  await runScript('ipconfig /flushdns | Out-Null; Add-Content -Encoding UTF8 -Path $FZ_OUT -Value ok');
}

// ---------------------------------------------------------------- measuring (PresentMon)

/**
 * Records the running game for `seconds` and returns frame statistics:
 * { frames, avgFps, low1, low01, avgMs, worstMs, stutters }.
 */
async function measure(seconds = 20) {
  const exe = await ensurePresentMon();
  const csv = path.join(os.tmpdir(), 'fzclient-opt', `frames-${Date.now()}.csv`);
  fs.mkdirSync(path.dirname(csv), { recursive: true });
  const body = `$a = @('--process_name', ${psq(GAME_EXE)}, '--output_file', ${psq('"' + csv + '"')}, '--timed', '${seconds | 0}', '--terminate_after_timed', '--no_console_stats')
$p = Start-Process -FilePath ${psq(exe)} -ArgumentList $a -Wait -PassThru -WindowStyle Hidden
Add-Content -Encoding UTF8 -Path $FZ_OUT -Value ("exit=" + $p.ExitCode)`;
  await runScript(body, { admin: true, timeoutMs: (seconds + 60) * 1000 });
  let text = '';
  try { text = fs.readFileSync(csv, 'utf8'); } catch { /* none */ }
  try { fs.unlinkSync(csv); } catch { /* fine */ }
  const lines = text.split(/\r?\n/).filter(Boolean);
  if (lines.length < 3) throw new Error('フレームを記録できませんでした（ゲームを起動してから計測してください）');
  const head = lines[0].split(',');
  let col = head.indexOf('MsBetweenPresents');
  if (col < 0) col = head.indexOf('FrameTime');
  if (col < 0) throw new Error('計測結果を読めませんでした');
  const ms = lines.slice(1).map(l => Number(l.split(',')[col])).filter(v => v > 0 && v < 1000);
  if (ms.length < 10) throw new Error('フレームが少なすぎます（ゲームの画面を表示したまま計測してください）');
  const sorted = ms.slice().sort((a, b) => a - b);
  const pct = p => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
  const avgMs = ms.reduce((a, b) => a + b, 0) / ms.length;
  const median = pct(0.5);
  return {
    frames: ms.length,
    avgFps: Math.round(1000 / avgMs),
    low1: Math.round(1000 / pct(0.99)),
    low01: Math.round(1000 / pct(0.999)),
    avgMs: Math.round(avgMs * 100) / 100,
    worstMs: Math.round(sorted[sorted.length - 1] * 10) / 10,
    stutters: ms.filter(v => v > median * 2.5 && v > 8).length,
    seconds,
  };
}

module.exports = { debugInfo, lastState, setJavaPath, status, change, recommended, jvmExtras, cacheCores, flushDns, measure, TWEAK_IDS: TWEAKS.map(t => t.id) };
