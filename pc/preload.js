'use strict';
// Gives the shared NSXL page the same window.Android bridge it has on phones.
// Every call goes to main.js; results come back the same way the Android app returns them.
const { contextBridge, ipcRenderer } = require('electron');

const names = ipcRenderer.sendSync('nb-names') || [];
const bridge = {};
for (const n of names) bridge[n] = (...args) => ipcRenderer.sendSync('nb', n, args);
contextBridge.exposeInMainWorld('Android', bridge);
