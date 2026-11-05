'use strict';
// Installs everything Minecraft 1.8.9 + Forge + FzClient needs (from the official servers) and starts the game.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const AdmZip = require('adm-zip');
const { getJson, download, downloadAll, isValid } = require('./http');

const MC_VERSION = '1.8.9';
const FORGE_VERSION = '1.8.9-11.15.1.2318-1.8.9';
const VERSION_MANIFESTS = ['https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',
  'https://launchermeta.mojang.com/mc/game/version_manifest_v2.json'];
const JAVA_MANIFESTS = ['https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json',
  'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json'];

/** First URL that answers. */
async function getJsonAny(urls) {
  let last;
  for (const u of urls) {
    try { return await getJson(u); } catch (e) { last = e; }
  }
  throw last;
}
const LIBRARIES = 'https://libraries.minecraft.net/';
const RESOURCES = 'https://resources.download.minecraft.net/';
const FORGE_MAVEN = 'https://maven.minecraftforge.net/';
const MAVEN_CENTRAL = 'https://repo1.maven.org/maven2/';

/** Maven coordinate "group:artifact:version[:classifier]" -> relative path. */
function mavenPath(name, classifier) {
  const parts = name.split(':');
  const [group, artifact, version] = parts;
  const cls = classifier || parts[3];
  return `${group.replace(/\./g, '/')}/${artifact}/${version}/${artifact}-${version}${cls ? '-' + cls : ''}.jar`;
}

/** Vanilla library rules (os allow / disallow) for Windows. */
function allowed(lib) {
  if (!lib.rules) return true;
  let ok = false;
  for (const r of lib.rules) {
    const matches = !r.os || r.os.name === 'windows';
    if (matches) ok = r.action === 'allow';
  }
  return ok;
}

class Game {
  /** dirs: { root (shared files), gameDir (saves / mods / options) } */
  constructor(dirs, log) {
    this.root = dirs.root;
    this.gameDir = dirs.gameDir;
    this.log = log || (() => {});
    this.libDir = path.join(this.root, 'libraries');
    this.assetsDir = path.join(this.root, 'assets');
    this.versionDir = path.join(this.root, 'versions', MC_VERSION);
    this.runtimeDir = path.join(this.root, 'runtime', 'jre-legacy');
    this.nativesDir = path.join(this.root, 'natives', MC_VERSION);
  }

  // ------------------------------------------------------------------ vanilla

  async vanillaJson() {
    const cache = path.join(this.versionDir, MC_VERSION + '.json');
    try {
      const manifest = await getJsonAny(VERSION_MANIFESTS);
      const v = manifest.versions.find(x => x.id === MC_VERSION);
      await download(v.url, cache, v.sha1);
    } catch (e) {
      if (!fs.existsSync(cache)) throw e; // offline: use what we have
    }
    return JSON.parse(fs.readFileSync(cache, 'utf8'));
  }

  async installVanilla(progress) {
    const v = await this.vanillaJson();
    const jobs = [];
    // client jar
    jobs.push({ urls: [v.downloads.client.url], file: path.join(this.versionDir, MC_VERSION + '.jar'),
      sha1: v.downloads.client.sha1, size: v.downloads.client.size });
    // libraries (+ Windows natives)
    const natives = [];
    for (const lib of v.libraries) {
      if (!allowed(lib)) continue;
      const d = lib.downloads || {};
      if (d.artifact) {
        jobs.push({ urls: [d.artifact.url], file: path.join(this.libDir, d.artifact.path), sha1: d.artifact.sha1, size: d.artifact.size });
      }
      if (lib.natives && lib.natives.windows) {
        const key = lib.natives.windows.replace('${arch}', '64');
        const n = d.classifiers && d.classifiers[key];
        if (n) {
          const file = path.join(this.libDir, n.path);
          jobs.push({ urls: [n.url], file, sha1: n.sha1, size: n.size });
          natives.push({ file, exclude: (lib.extract && lib.extract.exclude) || ['META-INF/'] });
        }
      }
    }
    // assets
    const idx = v.assetIndex;
    const idxFile = path.join(this.assetsDir, 'indexes', idx.id + '.json');
    await download(idx.url, idxFile, idx.sha1).catch(e => { if (!fs.existsSync(idxFile)) throw e; });
    const objects = JSON.parse(fs.readFileSync(idxFile, 'utf8')).objects;
    for (const k of Object.keys(objects)) {
      const o = objects[k];
      const sub = o.hash.substring(0, 2) + '/' + o.hash;
      jobs.push({ urls: [RESOURCES + sub], file: path.join(this.assetsDir, 'objects', sub), sha1: o.hash, size: o.size });
    }
    await downloadAll(jobs, (d, t, bd, bt) => progress('Minecraft 1.8.9', d, t, bd, bt));
    this.extractNatives(natives);
    return v;
  }

  extractNatives(natives) {
    fs.mkdirSync(this.nativesDir, { recursive: true });
    for (const n of natives) {
      const zip = new AdmZip(n.file);
      for (const e of zip.getEntries()) {
        if (e.isDirectory || n.exclude.some(x => e.entryName.startsWith(x))) continue;
        const out = path.join(this.nativesDir, path.basename(e.entryName));
        if (!fs.existsSync(out)) fs.writeFileSync(out, e.getData());
      }
    }
  }

  // ------------------------------------------------------------------ Forge

  async installForge(progress) {
    const forgeJar = path.join(this.libDir, mavenPath('net.minecraftforge:forge:' + FORGE_VERSION));
    const universal = `${FORGE_MAVEN}net/minecraftforge/forge/${FORGE_VERSION}/forge-${FORGE_VERSION}-universal.jar`;
    if (!(await isValid(forgeJar))) {
      progress('Forge', 0, 1, 0, 0);
      await download([universal], forgeJar);
    }
    // the universal jar carries its own version.json (library list, main class, arguments);
    // if it doesn't, the installer's install_profile.json has the same thing under "versionInfo"
    let fv;
    const inJar = new AdmZip(forgeJar).getEntry('version.json');
    if (inJar) {
      fv = JSON.parse(inJar.getData().toString('utf8'));
    } else {
      const inst = path.join(this.root, 'cache', `forge-${FORGE_VERSION}-installer.jar`);
      if (!(await isValid(inst))) await download([`${FORGE_MAVEN}net/minecraftforge/forge/${FORGE_VERSION}/forge-${FORGE_VERSION}-installer.jar`], inst);
      fv = JSON.parse(new AdmZip(inst).readAsText('install_profile.json')).versionInfo;
    }
    const jobs = [];
    fv.libraries = fv.libraries.filter(l => l.clientreq !== false); // server-only libraries (jline) aren't needed
    for (const lib of fv.libraries) {
      if (lib.name.startsWith('net.minecraftforge:forge:')) continue;
      const rel = mavenPath(lib.name);
      const file = path.join(this.libDir, rel);
      const urls = [];
      if (lib.url) urls.push(lib.url.replace(/^https?:\/\/files\.minecraftforge\.net\/maven\//, FORGE_MAVEN).replace(/\/?$/, '/') + rel);
      urls.push(LIBRARIES + rel, FORGE_MAVEN + rel, MAVEN_CENTRAL + rel);
      jobs.push({ urls: [...new Set(urls)], file, sha1: lib.checksums && lib.checksums.length === 1 ? lib.checksums[0] : undefined });
    }
    await downloadAll(jobs, (d, t) => progress('Forge', d, t, 0, 0));
    return fv;
  }

  // ------------------------------------------------------------------ Java 8 (Mojang's own runtime)

  /** Java under its own name, so Discord (and Task Manager) see "FzClient.exe" instead of a generic javaw.exe. */
  javaExe() {
    return path.join(this.runtimeDir, 'bin', 'FzClient.exe');
  }

  /** Keeps bin/FzClient.exe an exact copy of bin/javaw.exe (Java runs fine under another name). */
  ensureNamedJava() {
    const src = path.join(this.runtimeDir, 'bin', 'javaw.exe');
    const dst = this.javaExe();
    if (!fs.existsSync(src)) return;
    if (fs.existsSync(dst) && fs.statSync(dst).size === fs.statSync(src).size) return;
    fs.copyFileSync(src, dst);
  }

  async installJava(progress) {
    const marker = path.join(this.runtimeDir, '.fz-ok');
    if (fs.existsSync(marker) && fs.existsSync(path.join(this.runtimeDir, 'bin', 'javaw.exe'))) {
      this.ensureNamedJava();
      return;
    }
    const all = await getJsonAny(JAVA_MANIFESTS);
    const entry = all['windows-x64'] && all['windows-x64']['jre-legacy'] && all['windows-x64']['jre-legacy'][0];
    if (!entry) throw new Error('Java 8 runtime not found');
    const files = (await getJson(entry.manifest.url)).files;
    const jobs = [];
    for (const rel of Object.keys(files)) {
      const f = files[rel];
      const out = path.join(this.runtimeDir, rel);
      if (f.type === 'directory') { fs.mkdirSync(out, { recursive: true }); continue; }
      if (f.type !== 'file') continue;
      const raw = f.downloads.raw;
      jobs.push({ urls: [raw.url], file: out, sha1: raw.sha1, size: raw.size });
    }
    await downloadAll(jobs, (d, t, bd, bt) => progress('Java 8', d, t, bd, bt));
    fs.writeFileSync(marker, entry.version ? entry.version.name : 'ok');
    this.ensureNamedJava();
  }

  // ------------------------------------------------------------------ FzClient + mods

  /** Puts the bundled FzClient jar into mods/ (replacing older FzClient / FzHUD jars). */
  installFzClient(bundledJar) {
    const mods = path.join(this.gameDir, 'mods');
    fs.mkdirSync(mods, { recursive: true });
    const target = path.join(mods, 'FzClient.jar');
    for (const f of fs.readdirSync(mods)) {
      if (/^(fzclient|fzhud)-.*\.jar$/i.test(f)) fs.renameSync(path.join(mods, f), path.join(mods, f + '.old'));
    }
    const want = fs.readFileSync(bundledJar);
    const same = fs.existsSync(target) && crypto.createHash('sha1').update(fs.readFileSync(target)).digest('hex')
      === crypto.createHash('sha1').update(want).digest('hex');
    if (!same) fs.writeFileSync(target, want);
    this.installFzClientOpt(bundledJar);
  }

  /** Puts the bundled FzClientOpt (performance mod) into mods/. Respects a user-disabled copy. */
  installFzClientOpt(bundledJar) {
    // next to the jar in use, or in the install folder (the jar may come from an update download)
    const opt = [path.join(path.dirname(bundledJar), 'fzclientopt.jar'), path.join(process.resourcesPath || '', 'fzclientopt.jar')]
      .find(f => fs.existsSync(f));
    if (!opt) return;
    const mods = path.join(this.gameDir, 'mods');
    if (fs.existsSync(path.join(mods, 'FzClientOpt.jar.disabled'))) return;
    const target = path.join(mods, 'FzClientOpt.jar');
    const want = fs.readFileSync(opt);
    const same = fs.existsSync(target) && crypto.createHash('sha1').update(fs.readFileSync(target)).digest('hex')
      === crypto.createHash('sha1').update(want).digest('hex');
    if (!same) fs.writeFileSync(target, want);
  }

  // ------------------------------------------------------------------ launch

  buildClasspath(vanilla, forge) {
    const seen = new Set();
    const cp = [];
    const add = (name, file) => {
      const ga = name.split(':').slice(0, 2).join(':');
      if (seen.has(ga)) return;
      seen.add(ga);
      cp.push(file);
    };
    for (const lib of forge.libraries) add(lib.name, path.join(this.libDir, mavenPath(lib.name)));
    for (const lib of vanilla.libraries) {
      if (!allowed(lib) || lib.natives || !(lib.downloads && lib.downloads.artifact)) continue;
      add(lib.name, path.join(this.libDir, lib.downloads.artifact.path));
    }
    cp.push(path.join(this.versionDir, MC_VERSION + '.jar'));
    return cp;
  }

  /**
   * opts: { account: {name, uuid, accessToken, type}, memoryMb, width, height, fullscreen, jvmArgs, bundledJar,
   *         onProgress(stage, done, total, bytesDone, bytesTotal), onLog(line) }
   * Resolves with the child process once Minecraft has started.
   */
  async launch(opts) {
    const progress = opts.onProgress || (() => {});
    await this.installJava(progress);
    const vanilla = await this.installVanilla(progress);
    const forge = await this.installForge(progress);
    this.installFzClient(opts.bundledJar);
    progress('起動中', 1, 1, 0, 0);

    fs.mkdirSync(this.gameDir, { recursive: true });
    const acc = opts.account;
    const vars = {
      auth_player_name: acc.name,
      version_name: FORGE_VERSION,
      game_directory: this.gameDir,
      assets_root: this.assetsDir,
      assets_index_name: vanilla.assetIndex.id,
      auth_uuid: acc.uuid.replace(/-/g, ''),
      auth_access_token: acc.accessToken,
      user_properties: '{}',
      user_type: acc.type === 'msa' ? 'msa' : 'legacy',
    };
    const gameArgs = forge.minecraftArguments.split(' ').map(a => a.replace(/\$\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m)));
    if (opts.fullscreen) gameArgs.push('--fullscreen');
    else if (opts.width && opts.height) gameArgs.push('--width', String(opts.width), '--height', String(opts.height));

    const mem = Math.max(1024, opts.memoryMb || 3072);
    const jvm = [
      // "Java の最適化" (最適化 page): the whole heap from the start, so it never has to grow mid-game
      `-Xmx${mem}M`, `-Xms${opts.optimizeJvm ? mem : Math.min(mem, 1024)}M`,
      '-XX:+UseG1GC', '-XX:+UnlockExperimentalVMOptions', '-XX:G1NewSizePercent=20', '-XX:G1ReservePercent=20',
      '-XX:MaxGCPauseMillis=50', '-XX:G1HeapRegionSize=32M',
      '-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump',
      `-Djava.library.path=${this.nativesDir}`,
      '-Dfml.ignoreInvalidMinecraftCertificates=true', '-Dfml.ignorePatchDiscrepancies=true',
      '-Dminecraft.launcher.brand=FzClient', `-Dminecraft.launcher.version=${opts.launcherVersion || '1.0.0'}`,
      ...(opts.jvmExtras || []),
      ...(opts.jvmArgs ? opts.jvmArgs.split(/\s+/).filter(Boolean) : []),
      '-cp', this.buildClasspath(vanilla, forge).join(';'),
      forge.mainClass,
    ];
    const args = [...jvm, ...gameArgs];
    this.log('[launch] ' + this.javaExe() + ' ' + args.map(a => a === acc.accessToken ? '***' : a).join(' '));
    const child = spawn(this.javaExe(), args, { cwd: this.gameDir, detached: false, windowsHide: false });
    if (opts.highPriority && child.pid) {
      // "ゲームの優先度を高く" (最適化 page): Windows gives the game the CPU first
      try { require('os').setPriority(child.pid, require('os').constants.priority.PRIORITY_HIGH); } catch (e) { this.log('[launch] priority: ' + e.message); }
    }
    if (opts.affinityMask && child.pid) {
      // "キャッシュの大きいコアで動かす" (最適化 page): Node has no call for this, Windows' own PowerShell does
      const ps = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        `(Get-Process -Id ${child.pid}).ProcessorAffinity = [IntPtr]${Number(opts.affinityMask)}`], { windowsHide: true });
      ps.on('error', e => this.log('[launch] affinity: ' + e.message));
      ps.on('close', code => this.log('[launch] affinity 0x' + Number(opts.affinityMask).toString(16) + (code ? ' failed (' + code + ')' : ' set')));
    }
    const onLog = opts.onLog || (() => {});
    child.stdout.on('data', d => onLog(d.toString()));
    child.stderr.on('data', d => onLog(d.toString()));
    return child;
  }
}

module.exports = { Game, MC_VERSION, FORGE_VERSION };
