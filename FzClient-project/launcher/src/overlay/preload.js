'use strict';
// The overlay window's bridge (renderer/overlay/overlay.js).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('fzo', {
  /** Everything the overlay shows: { game, rec, link } and the language; called on every change. */
  onState: cb => ipcRenderer.on('ov:state', (e, s) => cb(s)),
  /** The game's numbers and switches (while the overlay is open). */
  onStats: cb => ipcRenderer.on('ov:stats', (e, s) => cb(s)),
  onShow: cb => ipcRenderer.on('ov:show', () => cb()),
  hide: () => ipcRenderer.send('ov:hide'),
  record: () => ipcRenderer.send('ov:record'),
  saveReplay: () => ipcRenderer.send('ov:save'),
  setReplay: on => ipcRenderer.send('ov:replay', !!on),
  setSwitch: (i, on) => ipcRenderer.send('ov:switch', i | 0, !!on),
  openMenu: () => ipcRenderer.send('ov:menu'),
  openFolder: () => ipcRenderer.send('ov:folder'),
});
