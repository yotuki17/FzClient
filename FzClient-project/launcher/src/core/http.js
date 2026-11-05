'use strict';
// Small download helpers: JSON fetch, file download with sha1 check, and a parallel queue with progress.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app, net } = require('electron');

const UA = 'FzClientLauncher/' + app.getVersion();

async function fetchRetry(url, opts = {}, tries = 3) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      const res = await net.fetch(url, { ...opts, headers: { 'User-Agent': UA, ...(opts.headers || {}) } });
      if (res.status >= 500 && i < tries - 1) { last = new Error(`HTTP ${res.status} ${url}`); await sleep(800 * (i + 1)); continue; }
      return res;
    } catch (e) {
      last = e;
      await sleep(800 * (i + 1));
    }
  }
  throw last;
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function getJson(url) {
  const res = await fetchRetry(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  return res.json();
}

function sha1File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha1');
    fs.createReadStream(file).on('error', reject).on('data', d => h.update(d)).on('end', () => resolve(h.digest('hex')));
  });
}

/** True when the file exists and matches (sha1 if given, else size if given, else just exists). */
async function isValid(file, sha1, size) {
  try {
    const st = fs.statSync(file);
    if (size && st.size !== size) return false;
    if (sha1) return (await sha1File(file)) === sha1.toLowerCase();
    return st.size > 0;
  } catch {
    return false;
  }
}

/** Downloads url -> file (via a .part file), checking sha1 when given. Tries the alternative urls on failure. */
async function download(urls, file, sha1) {
  if (!Array.isArray(urls)) urls = [urls];
  fs.mkdirSync(path.dirname(file), { recursive: true });
  let lastErr;
  for (const url of urls) {
    try {
      const res = await fetchRetry(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
      const buf = Buffer.from(await res.arrayBuffer());
      if (sha1) {
        const got = crypto.createHash('sha1').update(buf).digest('hex');
        if (got !== sha1.toLowerCase()) throw new Error(`checksum mismatch: ${url}`);
      }
      const part = file + '.part';
      fs.writeFileSync(part, buf);
      fs.renameSync(part, file);
      return;
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error('download failed: ' + file);
}

/**
 * Runs download jobs ({urls, file, sha1, size}) with a few in parallel, skipping files that are already fine.
 * onProgress(done, total, bytesDone, bytesTotal)
 */
async function downloadAll(jobs, onProgress, parallel = 8) {
  const total = jobs.length;
  const bytesTotal = jobs.reduce((s, j) => s + (j.size || 0), 0);
  let done = 0, bytesDone = 0, idx = 0;
  const report = () => onProgress && onProgress(done, total, bytesDone, bytesTotal);
  report();
  async function worker() {
    while (idx < jobs.length) {
      const j = jobs[idx++];
      if (!(await isValid(j.file, j.sha1, j.size))) await download(j.urls, j.file, j.sha1);
      done++;
      bytesDone += j.size || 0;
      report();
    }
  }
  await Promise.all(Array.from({ length: Math.min(parallel, jobs.length || 1) }, worker));
}

module.exports = { fetchRetry, getJson, download, downloadAll, isValid, sha1File, sleep };
