'use strict';
// The overlay window's bridge (renderer/overlay/overlay.js).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('fzo', {
  /** Everything the overlay shows: { game, rec, link } and the language; called on every change. */
  onState: cb => ipcRenderer.on('ov:state', (e, s) => cb(s)),
  /** The game's numbers and switches (while the overlay is open). */
  onStats: cb => ipcRenderer.on('ov:stats', (e, s) => cb(s)),
  /** open: null, or { file } from the game's title menu: the gallery, with that file big ('' = none) */
  onShow: cb => ipcRenderer.on('ov:show', (e, open) => cb(open || null)),
  hide: () => ipcRenderer.send('ov:hide'),
  record: () => ipcRenderer.send('ov:record'),
  saveReplay: () => ipcRenderer.send('ov:save'),
  setReplay: on => ipcRenderer.send('ov:replay', !!on),
  setSwitch: (i, on) => ipcRenderer.send('ov:switch', i | 0, !!on),
  /** A key for 'overlay' / 'record' / 'save' (Minecraft key code, mouse buttons negative, 0 = none) with its Ctrl 1 / Shift 2 / Alt 4. */
  bindKey: (k, code, mods) => ipcRenderer.send('ov:bind', String(k), code | 0, mods | 0),
  setReplaySeconds: s => ipcRenderer.send('ov:seconds', s | 0),
  openMenu: () => ipcRenderer.send('ov:menu'),
  openFolder: () => ipcRenderer.send('ov:folder'),
  /** The newest screenshots and clips: [{ file, kind, time }] */
  gallery: () => ipcRenderer.invoke('ov:gallery'),
  reveal: (file, kind) => ipcRenderer.send('ov:reveal', String(file), String(kind)),
});
