'use strict';
// The NSXL Web window's small bridge: closing, opening a page in your own browser, clearing data,
// and knowing whether the window has focus (so the controller only drives the browser when it's in front).
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('NSXLB', {
  close: () => ipcRenderer.send('br-close'),
  external: (url) => ipcRenderer.send('br-external', url),
  clearData: () => ipcRenderer.send('br-clear'),
  onFocus: (cb) => ipcRenderer.on('br-focus', (e, f) => cb(!!f)),
});
