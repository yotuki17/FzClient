'use strict';
// CI only (.github/workflows/mac-smoke.yml): asks the running launcher (started with --remote-debugging-port) what
// its page looks like from the inside: errors, the text on it, a picture of every tab.   Node 22 (WebSocket).
const fs = require('fs');
const path = require('path');

const out = path.resolve(process.argv[2] || 'out');
const port = process.argv[3] || '9222';
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  fs.writeFileSync(path.join(out, 'cdp-targets.json'), JSON.stringify(targets, null, 2));
  let n = 0;
  for (const t of targets) {
    if (t.type !== 'page' || !t.webSocketDebuggerUrl) continue;
    await inspect(t, ++n);
  }
}

function inspect(target, n) {
  return new Promise(resolve => {
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    const events = [];
    const waiting = new Map();
    let id = 0, finished = false;
    const call = (method, params) => new Promise(res => {
      const i = ++id;
      waiting.set(i, res);
      ws.send(JSON.stringify({ id: i, method, params: params || {} }));
    });
    const done = () => { if (finished) return; finished = true; try { ws.close(); } catch { /* closed */ } resolve(); };
    const timer = setTimeout(done, 60000);
    ws.onerror = e => { fs.writeFileSync(path.join(out, `cdp-page-${n}-error.txt`), String(e && e.message)); done(); };
    ws.onmessage = m => {
      const msg = JSON.parse(m.data);
      if (msg.id && waiting.has(msg.id)) { waiting.get(msg.id)(msg.result || { error: msg.error }); waiting.delete(msg.id); }
      else if (msg.method) events.push(msg);
    };
    ws.onopen = async () => {
      const report = { url: target.url, title: target.title };
      const ev = async expression => {
        const r = await call('Runtime.evaluate', { expression, returnByValue: true });
        return r && r.result ? r.result.value : r;
      };
      const picture = async name => {
        const s = await call('Page.captureScreenshot', { format: 'png' });
        if (s && s.data) fs.writeFileSync(path.join(out, name + '.png'), Buffer.from(s.data, 'base64'));
      };
      try {
        await call('Runtime.enable');
        await call('Log.enable');
        report.state = await ev(`({ ready: document.readyState, htmlClass: document.documentElement.className,
          theme: document.documentElement.dataset.theme || '', bodyVisibility: getComputedStyle(document.body).visibility,
          bodyBg: getComputedStyle(document.body).backgroundColor, htmlBg: getComputedStyle(document.documentElement).backgroundColor,
          size: [innerWidth, innerHeight], dpr: devicePixelRatio, fz: typeof fz, platform: typeof fz === 'object' ? fz.platform : null,
          font: getComputedStyle(document.body).fontFamily, text: document.body.innerText.slice(0, 1500) })`);
        await picture(`cdp-page-${n}`);
        const tabs = await ev(`[...document.querySelectorAll('.nav[data-page]')].map(b => b.dataset.page)`);
        if (Array.isArray(tabs)) {
          for (const tab of tabs) {
            await ev(`document.querySelector('.nav[data-page="${tab}"]').click()`);
            await sleep(1500);
            await picture(`cdp-tab-${tab}`);
          }
        }
        await sleep(300);
        report.events = events.filter(e => /Log\.entryAdded|Runtime\.exceptionThrown|Runtime\.consoleAPICalled/.test(e.method))
          .map(e => ({ method: e.method, params: e.params }));
      } catch (e) {
        report.failed = String(e && e.stack || e);
      }
      fs.writeFileSync(path.join(out, `cdp-page-${n}.json`), JSON.stringify(report, null, 2));
      clearTimeout(timer);
      done();
    };
  });
}

main().catch(e => {
  console.error(e);
  fs.writeFileSync(path.join(out, 'cdp-error.txt'), String(e && e.stack || e));
  process.exit(1);
});
