'use strict';
// Pictures for mods that no site knows (added by hand). Forge 1.8.9 mods name their picture in mcmod.info
// ("logoFile"); otherwise a pack.png / logo.png / icon.png at the top of the jar or in its assets folder.
// Each jar is read once (asynchronously, so the window does not freeze) and its picture handed to the page as a
// small data: URL.
const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');

let nativeImage = null;
try { ({ nativeImage } = require('electron')); } catch { /* tests outside Electron */ }

const MAX_READ = 2 * 1024 * 1024;   // a picture file bigger than this is not read
const SIZE = 128;                   // bigger pictures are made this small before they go to the page
const MAX_SEND = 96 * 1024;         // never send more than this per mod (base64 is a third bigger)
const cache = new Map();            // "name|size|time" -> { icon, pixel } | null

const mime = n => (/\.png$/i.test(n) ? 'image/png' : /\.jpe?g$/i.test(n) ? 'image/jpeg' : null);

/** Width and height of a PNG, from its header. */
function pngSize(buf) {
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

/** The "logoFile" of the first mod in mcmod.info (a list, or { modList: [...] } in the newer format). */
function infoLogo(zip) {
  const e = zip.getEntry('mcmod.info');
  if (!e) return null;
  const text = zip.readAsText(e).replace(/^﻿/, '');
  let logo = null;
  try {
    let j = JSON.parse(text);
    if (!Array.isArray(j)) j = (j && (j.modList || j.modlist)) || [];
    for (const m of j) if (m && typeof m.logoFile === 'string' && m.logoFile.trim()) { logo = m.logoFile; break; }
  } catch {
    // loose JSON that Java accepts (newlines in strings, trailing commas): just find the value
    const m = text.match(/"logoFile"\s*:\s*"([^"]*)"/);
    if (m) logo = m[1];
  }
  return logo && logo.trim() ? logo.trim().replace(/\\/g, '/').replace(/^\/+/, '') : null;
}

/** Lower is better; Infinity = not a mod picture. */
function rank(name) {
  const n = name.toLowerCase(), b = path.posix.basename(n), depth = n.split('/').length - 1;
  if (n === 'pack.png') return 0;
  if (depth === 0 && /^(logo|icon)\.png$/.test(b)) return 1;
  if (/^assets\/[^/]+\/(logo|icon)\.png$/.test(n)) return 2;
  if (/^assets\/[^/]+\/textures\/(gui\/)?(logo|icon)\.png$/.test(n)) return 3;
  // "modid_logo.png" in assets/modid/...; not "discord_logo.png" (pictures of other things)
  const id = (n.match(/^assets\/([^/]+)\//) || [])[1];
  if (id && /(^|[_-])(logo|icon)\.png$/.test(b) && b.replace(/[_-]?(logo|icon)\.png$/, '') === id) return 4 + depth;
  return Infinity;
}

/** The pictures worth trying, best first: the one mcmod.info names, then the usual names. */
function candidates(zip) {
  const files = zip.getEntries().filter(e => !e.isDirectory && mime(e.entryName) && e.header.size > 0 && e.header.size <= MAX_READ);
  const out = [];
  const logo = infoLogo(zip);
  if (logo) {
    const want = logo.toLowerCase();
    const hit = files.find(e => e.entryName.toLowerCase() === want) || files.find(e => e.entryName.toLowerCase() === 'assets/' + want);
    if (hit) out.push(hit);
    // often only the file name is given ("fbw_logo.png" for assets/bwstats/textures/gui/fbw_logo.png)
    const base = path.posix.basename(want);
    for (const e of files) if (!out.includes(e) && path.posix.basename(e.entryName.toLowerCase()) === base) out.push(e);
  }
  const ranked = files.map(e => [rank(e.entryName), e]).filter(([r]) => r !== Infinity).sort((x, y) => x[0] - y[0]);
  for (const [, e] of ranked) if (!out.includes(e)) out.push(e);
  return out;
}

/** The picture as a small data: URL, or null when it is not a usable square-ish picture. */
function toIcon(buf, type) {
  let sz = pngSize(buf);
  let img = null;
  if (nativeImage) {
    img = nativeImage.createFromBuffer(buf);
    if (img.isEmpty()) img = null; else { const s = img.getSize(); sz = { w: s.width, h: s.height }; }
  }
  if (!sz) return null;   // a JPEG we cannot measure (outside Electron)
  // a wide banner or a tiny sprite is not a picture of the mod
  if (sz.w < 8 || sz.h < 8 || sz.w > sz.h * 3 || sz.h > sz.w * 3) return null;
  if (Math.max(sz.w, sz.h) > SIZE) {
    if (!img) return null;
    buf = img.resize(sz.w >= sz.h ? { width: SIZE, quality: 'best' } : { height: SIZE, quality: 'best' }).toPNG();
    type = 'image/png';
  }
  if (buf.length > MAX_SEND) return null;
  return { icon: `data:${type};base64,${buf.toString('base64')}`, pixel: Math.max(sz.w, sz.h) <= 64 };
}

/** { icon, pixel } for the jar's picture, or null. pixel = small pixel art (drawn sharp). */
async function jarIcon(file) {
  let st;
  try { st = await fs.promises.stat(file); } catch { return null; }
  // turning a mod off renames foo.jar to foo.jar.disabled: same picture
  const key = path.basename(file).replace(/\.disabled$/i, '') + '|' + st.size + '|' + st.mtimeMs;
  if (cache.has(key)) return cache.get(key);
  let out = null;
  try {
    const zip = new AdmZip(await fs.promises.readFile(file));
    for (const e of candidates(zip)) {
      out = toIcon(e.getData(), mime(e.entryName));
      if (out) break;
    }
  } catch { out = null; }   // not a zip, or a broken one
  cache.set(key, out);
  return out;
}

/** Pictures for many jars, one after another, letting other work run in between. Forgets jars that are gone. */
async function jarIcons(files) {
  const out = [];
  for (const f of files) {
    out.push(await jarIcon(f));
    await new Promise(r => setImmediate(r));
  }
  if (cache.size > 300) cache.clear();
  return out;
}

module.exports = { jarIcon, jarIcons, rank, infoLogo };
