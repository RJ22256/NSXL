'use strict';
/*
 * NSXL for Windows.
 *
 * The interface is the same page the Android app uses (copied into www/ at build time).
 * This file plays the part of the Android side: it serves the page and its images from
 * nsxl://app/, and answers the page's bridge calls (window.Android.*) with a Windows
 * equivalent: your Steam, Epic and own games, launching them, covers, wallpaper and more.
 */
const { app, BrowserWindow, protocol, ipcMain, shell, dialog, nativeImage, net, clipboard, powerMonitor, session: electronSession } = require('electron');
const fs = require('fs');
const path = require('path');
const { spawn, exec } = require('child_process');
const { Readable } = require('stream');
const lib = require('./lib/library');
const Native = require('./lib/native');
const Pia = require('./lib/pia');
const Spotify = require('./lib/spotify');

const WWW = path.join(__dirname, 'www');
const DATA = app.getPath('userData');
const COVERS = path.join(DATA, 'covers');
const custom = new lib.Custom(path.join(DATA, 'library.json'));

protocol.registerSchemesAsPrivileged([{ scheme: 'nsxl', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } }]);

if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null;

// Windows extras ship in resources/ (built by native/build.ps1)
const RES = app.isPackaged ? process.resourcesPath : path.join(__dirname, 'resources');
const native = new Native(path.join(RES, 'native', 'nsxl-native.exe'));

// ------------------------------------------------------------------ small stores

function readJson(file, dflt) { try { return JSON.parse(fs.readFileSync(path.join(DATA, file), 'utf8')); } catch (e) { return dflt; } }
function writeJson(file, v) { fs.mkdirSync(DATA, { recursive: true }); fs.writeFileSync(path.join(DATA, file), JSON.stringify(v)); }
const prefs = { get: (k, d) => { const p = readJson('prefs.json', {}); return k in p ? p[k] : d; }, set: (k, v) => { const p = readJson('prefs.json', {}); p[k] = v; writeJson('prefs.json', p); } };

const js = code => { if (win && !win.isDestroyed()) win.webContents.executeJavaScript(code).catch(() => {}); };
const toast = msg => js('window.showToast&&showToast(' + JSON.stringify(msg) + ',2600)');
const done = (id, data) => js('window.onAsync(' + JSON.stringify(id) + ',true,' + JSON.stringify(data) + ')');
const fail = (id, err) => js('window.onAsync(' + JSON.stringify(id) + ',false,' + JSON.stringify(String((err && err.message) || err)) + ')');
const safe = s => String(s).replace(/[^a-z0-9._-]/gi, '_');
const coverFile = pkg => path.join(COVERS, safe(pkg) + '.jpg');

// ------------------------------------------------------------------ library

let apps = [], byPkg = {};
function scan() {
  const list = [].concat(lib.steamGames(), lib.epicGames(), lib.customGames(custom));
  const seen = new Set();
  apps = list.filter(a => (seen.has(a.pkg) ? false : seen.add(a.pkg)));
  byPkg = Object.fromEntries(apps.map(a => [a.pkg, a]));
}

function iconSource(a) {
  if (!a) return null;
  if (a.source === 'steam') return lib.steamImage(a.appid, 'icon');
  if (a.source === 'epic') return a.exe;
  if (a.source === 'custom') return a.entry.icon || a.entry.target || null;
  return null;
}

// ------------------------------------------------------------------ play time (same rules as Android)

let session = null;
function playStart(pkg) { session = { pkg, start: Date.now(), left: false }; }
function playFinish() {
  if (!session || !session.left) return;
  const ms = Date.now() - session.start, pkg = session.pkg;
  session = null;
  if (ms < 5000 || ms > 12 * 3600e3) return;
  const all = readJson('playtime.json', {});
  const o = all[pkg] || { ms: 0, n: 0, first: Date.now() - ms };
  o.ms += ms; o.n += 1; o.last = Date.now();
  all[pkg] = o;
  writeJson('playtime.json', all);
}

// ------------------------------------------------------------------ album: Windows and Steam screenshots

let shots = {};
function listShots() {
  const home = app.getPath('home'), out = [], shotTags = readJson('shotTags.json', {});
  const add = (dir, tag) => {
    let files = [];
    try { files = fs.readdirSync(dir); } catch (e) { return; }
    for (const f of files) {
      if (!/\.(png|jpe?g|webp)$/i.test(f)) continue;
      const p = path.join(dir, f);
      try { const st = fs.statSync(p); out.push({ p, name: f, date: st.mtimeMs, tag: tag || shotTags[f] }); } catch (e) { }
    }
  };
  add(path.join(app.getPath('pictures'), 'Screenshots'));
  add(path.join(home, 'OneDrive', 'Pictures', 'Screenshots'));
  add(path.join(app.getPath('videos'), 'Captures'));
  const root = lib.steamRoot();
  if (root) {
    try {
      for (const user of fs.readdirSync(path.join(root, 'userdata'))) {
        const remote = path.join(root, 'userdata', user, '760', 'remote');
        let ids = [];
        try { ids = fs.readdirSync(remote); } catch (e) { continue; }
        for (const appid of ids) add(path.join(remote, appid, 'screenshots'), 'steam:' + appid);
      }
    } catch (e) { }
  }
  out.sort((a, b) => b.date - a.date);
  shots = {};
  return out.slice(0, 600).map((s, i) => { shots[i + 1] = s.p; return { id: i + 1, name: s.name, date: s.date, w: 0, h: 0, tag: s.tag }; });
}

// ------------------------------------------------------------------ images

function squareJpeg(src, size) {
  let img = typeof src === 'string' ? nativeImage.createFromPath(src) : src;
  if (img.isEmpty()) throw new Error("Couldn't read that picture");
  const { width, height } = img.getSize(), s = Math.min(width, height);
  img = img.crop({ x: Math.floor((width - s) / 2), y: Math.floor((height - s) / 2), width: s, height: s }).resize({ width: size, height: size, quality: 'best' });
  return img.toJPEG(90);
}

async function pickImage(title) {
  const r = await dialog.showOpenDialog(win, { title, properties: ['openFile'], filters: [{ name: 'Pictures', extensions: ['png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif'] }] });
  return r.canceled ? null : r.filePaths[0];
}

async function copyWithProgress(src, dest, onPct) {
  const total = fs.statSync(src).size || 1;
  let copied = 0, last = -1;
  await new Promise((resolve, reject) => {
    const r = fs.createReadStream(src), w = fs.createWriteStream(dest);
    r.on('data', c => { copied += c.length; const p = Math.floor(copied / total * 100); if (p !== last && p % 5 === 0) { last = p; onPct(p); } });
    r.on('error', reject); w.on('error', reject); w.on('finish', resolve);
    r.pipe(w);
  });
}

// ------------------------------------------------------------------ the bridge

// ------------------------------------------------------------------ controllers (Bluetooth through the helper)

const pads = { supported: true, permission: true, enabled: false, scanning: false, pairing: null, connected: [], paired: [], found: [] };
const pushPads = () => js('window.onPads&&window.onPads(' + JSON.stringify(pads) + ')');
async function refreshPads() {
  try {
    const r = await native.call('bt.radio');
    pads.supported = r.present; pads.enabled = r.on;
    pads.paired = r.on ? await native.call('bt.paired') : [];
  } catch (e) { pads.supported = false; }
  pushPads();
}
native.on('bt.found', m => {
  if (!pads.found.some(d => d.addr === m.addr) && !pads.paired.some(d => d.addr === m.addr)) { pads.found.push({ addr: m.addr, name: m.name, pad: !!m.pad }); pushPads(); }
});
native.on('bt.scan', m => { pads.scanning = !!m.scanning; pushPads(); });

// ------------------------------------------------------------------ battery (the page reads it) and VPN

let battery = { level: null, charging: false };
const pia = new Pia(st => { js('window.onVpn&&window.onVpn(' + JSON.stringify(st) + ')'); if (overlayWin && !overlayWin.isDestroyed()) overlayWin.webContents.executeJavaScript('window.onVpn&&window.onVpn(' + JSON.stringify(st) + ')').catch(() => {}); });

// ------------------------------------------------------------------ music: Windows media session + Spotify account

const spotify = new Spotify(prefs, (u, o) => net.fetch(u, o), u => shell.openExternal(u));
async function music(withArt) {
  const out = { connected: spotify.connected, hasClientId: !!spotify.clientId, session: null, spotify: null, error: null };
  try { out.session = await native.call('media.get', { art: !!withArt }, 8000); } catch (e) { }
  if (spotify.connected) { try { out.spotify = await spotify.player(); } catch (e) { out.error = e.message; } }
  return out;
}

// ------------------------------------------------------------------ the quick menu (over games)

let overlayWin = null, overlayGame = null, overlayFocused = false, xinputPads = 0, menuShortcut = '';
function overlayOpen() {
  if (overlayWin && overlayWin.isVisible()) { overlayClose(); return; }
  native.call('fg.get', {}, 3000).catch(() => null).then(fg => {
    overlayGame = fg && fg.pid && fg.pid !== process.pid && !/^(NSXL|electron)$/i.test(fg.name || '') ? fg : overlayGame;
    const { screen } = require('electron');
    const d = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
    if (!overlayWin || overlayWin.isDestroyed()) {
      overlayWin = new BrowserWindow({
        ...d.bounds, show: false, frame: false, transparent: true, resizable: false, movable: false, skipTaskbar: true, alwaysOnTop: true, hasShadow: false,
        backgroundColor: '#00000000', title: 'NSXL quick menu',
        webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false },
      });
      overlayWin.setAlwaysOnTop(true, 'screen-saver');
      overlayWin.loadURL('nsxl://app/overlay.html');
      overlayWin.on('focus', () => { overlayFocused = true; });
      overlayWin.on('blur', () => { if (overlayFocused && overlayWin && overlayWin.isVisible()) overlayClose(); });
      overlayWin.webContents.once('did-finish-load', () => { overlayWin.show(); grabFocus(); overlayWin.webContents.executeJavaScript('window.onOpen&&window.onOpen()').catch(() => {}); });
      return;
    }
    overlayWin.setBounds(d.bounds);
    overlayWin.show(); grabFocus();
    overlayWin.webContents.executeJavaScript('window.onOpen&&window.onOpen()').catch(() => {});
  });
}
function grabFocus() {
  overlayFocused = false;
  overlayWin.focus();
  const h = overlayWin.getNativeWindowHandle();
  const hwnd = h.length >= 8 ? Number(h.readBigUInt64LE(0)) : h.readUInt32LE(0);
  if (!overlayWin.isFocused()) native.call('fg.set', { hwnd }, 3000).catch(() => {});
  // if Windows still won't hand over focus, the menu reads the controller anyway
  setTimeout(() => { if (overlayWin && !overlayWin.isDestroyed()) overlayWin.webContents.executeJavaScript(`window.forcePads=${!overlayWin.isFocused()}`).catch(() => {}); }, 600);
}
function overlayClose() { overlayFocused = false; if (overlayWin && !overlayWin.isDestroyed() && overlayWin.isVisible()) overlayWin.hide(); }
function bringHome() {
  overlayClose();
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show(); win.setAlwaysOnTop(true); win.focus(); win.setAlwaysOnTop(false);
}
native.on('pad', m => {
  const want = prefs.get('menuButton', 'viewmenu');
  if (m.combo === want || (want === 'both' && (m.combo === 'guide' || m.combo === 'viewmenu'))) overlayOpen();
});
native.on('pads', m => { xinputPads = (m && m.count) || 0; js('window.updateStatus&&updateStatus()'); });

async function screenshot() {
  overlayClose();
  await new Promise(r => setTimeout(r, 400));
  const { desktopCapturer, screen } = require('electron');
  const d = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
  const size = { width: Math.round(d.size.width * d.scaleFactor), height: Math.round(d.size.height * d.scaleFactor) };
  const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: size });
  const src = sources.find(x => String(x.display_id) === String(d.id)) || sources[0];
  if (!src) throw new Error('No screen to capture');
  const dir = path.join(app.getPath('pictures'), 'Screenshots');
  fs.mkdirSync(dir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
  const game = overlayGame && session ? (byPkg[session.pkg] || {}).label : null;
  const file = path.join(dir, 'NSXL ' + (game ? safe(game) + ' ' : '') + stamp + '.png');
  fs.writeFileSync(file, src.thumbnail.toPNG());
  if (session) { const tags = readJson('shotTags.json', {}); tags[path.basename(file)] = session.pkg; writeJson('shotTags.json', tags); }
  const { Notification } = require('electron');
  if (Notification.isSupported()) new Notification({ title: 'Screenshot saved', body: 'It\'s in your Album.', silent: true }).show();
}

// ------------------------------------------------------------------ Xbox mode home app (full screen experience)

let fse = { installed: false, checked: false };
function fseCheck() {
  exec('powershell -NoProfile -Command "(Get-AppxPackage -Name NSXL.HomeApp) -ne $null"', { windowsHide: true }, (e, out) => {
    fse = { installed: /True/i.test(String(out)), checked: true };
  });
}
function fseRun(mode) {
  const script = path.join(RES, 'fse', 'setup.ps1');
  if (!fs.existsSync(script)) { toast('The Xbox mode files are missing from this build'); return; }
  const inner = `-NoProfile -ExecutionPolicy Bypass -File \\"${script}\\" -${mode}`;
  toast(mode === 'Install' ? 'Windows will ask for permission…' : 'Removing…');
  exec(`powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '${inner}'"`, { windowsHide: true }, err => {
    exec('powershell -NoProfile -Command "(Get-AppxPackage -Name NSXL.HomeApp) -ne $null"', { windowsHide: true }, (e, out) => {
      fse = { installed: /True/i.test(String(out)), checked: true };
      js('window.refreshOverlays&&refreshOverlays()');
      if (mode === 'Install') {
        if (fse.installed) { toast('NSXL can now be your Xbox mode home app'); shell.openExternal('ms-settings:gaming').catch(() => {}); }
        else toast(err ? 'Set-up was cancelled' : 'Set-up didn\'t finish. See %TEMP%\\nsxl-xbox-mode.log');
      } else toast(fse.installed ? 'Couldn\'t remove it' : 'Removed from Xbox mode');
    });
  });
}

const H = {
  platform: () => 'pc',
  version: () => app.getVersion(),
  sdk: () => 99,
  region: () => (app.getLocaleCountryCode() || 'GB'),
  firstLoad: () => { const f = !H._loaded; H._loaded = true; return f; },
  isDefaultHome: () => true,
  pageReady: () => {},
  getApps: () => { if (!apps.length) scan(); return JSON.stringify(apps.map(a => ({ pkg: a.pkg, label: a.label, game: a.game, installed: a.installed, cover: fs.existsSync(coverFile(a.pkg)) }))); },
  getStatus: () => JSON.stringify({ net: net.isOnline() ? 'wifi' : 'none', bars: 3, time24: false, battery: battery.level, charging: battery.charging, controllers: [] }),
  reportBattery: (level, charging) => { battery = { level, charging: !!charging }; },

  launch: (pkg) => {
    const a = byPkg[pkg];
    if (!a) { toast("Can't find that game any more"); return; }
    try {
      if (a.source === 'steam') shell.openExternal('steam://rungameid/' + a.appid);
      else if (a.source === 'epic') shell.openExternal(lib.epicLaunchUrl(a));
      else if (a.entry.url) shell.openExternal(a.entry.url);
      else {
        const child = spawn(a.entry.target, a.entry.args ? [a.entry.args] : [], { cwd: a.entry.cwd || path.dirname(a.entry.target), detached: true, stdio: 'ignore', windowsVerbatimArguments: true });
        child.on('error', () => toast("Couldn't start " + a.label));
        child.unref();
      }
      playStart(pkg);
    } catch (e) { toast("Couldn't start " + a.label); }
  },

  // themes, wallpaper, music
  setMode: (m) => { prefs.set('mode', m); if (win) win.setBackgroundColor(m === 'light' ? '#e9e9e9' : '#000000'); },
  setVideoBlur: () => {},
  hasVideo: () => !!prefs.get('videoExt', null) && fs.existsSync(path.join(DATA, 'wallpaper' + prefs.get('videoExt'))),
  pickVideo: async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a live wallpaper video', properties: ['openFile'], filters: [{ name: 'Videos', extensions: ['mp4', 'webm', 'mov', 'm4v', 'mkv'] }] });
    if (r.canceled) { js('window.onVideoPicked(false,"")'); return; }
    const ext = path.extname(r.filePaths[0]).toLowerCase();
    try {
      for (const f of fs.readdirSync(DATA)) if (f.startsWith('wallpaper')) fs.rmSync(path.join(DATA, f), { force: true });
      await copyWithProgress(r.filePaths[0], path.join(DATA, 'wallpaper' + ext), p => js('window.onVideoProgress(' + p + ')'));
      prefs.set('videoExt', ext); prefs.set('videoV', Date.now());
      js('window.onVideoPicked(true,"")');
    } catch (e) { js('window.onVideoPicked(false,' + JSON.stringify("Couldn't copy that video") + ')'); }
  },
  videoVersion: () => prefs.get('videoV', 0),
  hasMusic: () => !!prefs.get('musicExt', null) && fs.existsSync(path.join(DATA, 'music' + prefs.get('musicExt'))),
  pickMusic: async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a music file', properties: ['openFile'], filters: [{ name: 'Audio', extensions: ['mp3', 'm4a', 'aac', 'ogg', 'opus', 'wav', 'flac'] }] });
    if (r.canceled) { js('window.onMusicPicked(false,"")'); return; }
    const ext = path.extname(r.filePaths[0]).toLowerCase();
    try {
      for (const f of fs.readdirSync(DATA)) if (f.startsWith('music.')) fs.rmSync(path.join(DATA, f), { force: true });
      fs.copyFileSync(r.filePaths[0], path.join(DATA, 'music' + ext));
      prefs.set('musicExt', ext);
      js('window.onMusicPicked(true,"")');
    } catch (e) { js('window.onMusicPicked(false,' + JSON.stringify("Couldn't use that file") + ')'); }
  },

  // covers and avatar
  pickCover: async (pkg) => {
    const f = await pickImage('Choose cover art');
    if (!f) return;
    try { fs.mkdirSync(COVERS, { recursive: true }); fs.writeFileSync(coverFile(pkg), squareJpeg(f, 720)); js('window.onImagePicked("cover",' + JSON.stringify(pkg) + ',true)'); }
    catch (e) { js('window.onImagePicked("cover",' + JSON.stringify(pkg) + ',false)'); }
  },
  removeCover: (pkg) => { fs.rmSync(coverFile(pkg), { force: true }); },
  pickAvatar: async () => {
    const f = await pickImage('Choose a profile picture');
    if (!f) return;
    try { fs.writeFileSync(path.join(DATA, 'avatar.jpg'), squareJpeg(f, 512)); js('window.onImagePicked("avatar","",true)'); }
    catch (e) { js('window.onImagePicked("avatar","",false)'); }
  },
  removeAvatar: () => { fs.rmSync(path.join(DATA, 'avatar.jpg'), { force: true }); },

  // SteamGridDB
  sgdbSearch: async (id, key, term) => {
    try {
      const j = await sgdb(key, '/search/autocomplete/' + encodeURIComponent(String(term).trim()));
      done(id, (j.data || []).map(g => ({ id: g.id, name: g.name, year: g.release_date ? new Date(g.release_date * 1000).getFullYear() : undefined })));
    } catch (e) { fail(id, e); }
  },
  sgdbGrids: async (id, key, gameId) => {
    try {
      let j = await sgdb(key, '/grids/game/' + gameId + '?dimensions=512x512,1024x1024&types=static');
      if (!(j.data || []).length) j = await sgdb(key, '/grids/game/' + gameId + '?types=static');
      done(id, (j.data || []).filter(g => g.url).slice(0, 40).map(g => ({ url: g.url, thumb: g.thumb || g.url, width: g.width, height: g.height, style: g.style || '' })));
    } catch (e) { fail(id, e); }
  },
  sgdbApply: async (id, pkg, url) => {
    try {
      const r = await net.fetch(url);
      if (!r.ok) throw new Error('Download failed (' + r.status + ')');
      const img = nativeImage.createFromBuffer(Buffer.from(await r.arrayBuffer()));
      fs.mkdirSync(COVERS, { recursive: true });
      fs.writeFileSync(coverFile(pkg), squareJpeg(img, 720));
      done(id, true);
    } catch (e) { fail(id, e); }
  },
  fetchText: async (id, url) => {
    try {
      if (!/^https:\/\//.test(url)) throw new Error('Bad address');
      const r = await net.fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0 NSXL-PC/1.0' } });
      if (!r.ok) throw new Error('Feed error ' + r.status);
      done(id, (await r.text()).slice(0, 3 * 1024 * 1024));
    } catch (e) { fail(id, e); }
  },

  // album
  albumAccess: () => 'full',
  albumRequest: () => js('window.onAlbumAccess&&window.onAlbumAccess("full")'),
  albumList: (id) => { try { done(id, listShots()); } catch (e) { fail(id, e); } },
  albumShare: (shot) => { if (shots[shot]) shell.showItemInFolder(shots[shot]); },
  albumOpen: (shot) => { if (shots[shot]) shell.openPath(shots[shot]); },
  albumDelete: async (shot) => {
    try { await shell.trashItem(shots[shot]); js('window.onAlbumDeleted&&window.onAlbumDeleted(true)'); }
    catch (e) { toast("Couldn't delete that screenshot"); js('window.onAlbumDeleted&&window.onAlbumDeleted(false)'); }
  },
  albumAsCover: (id, shot, pkg) => {
    try { fs.mkdirSync(COVERS, { recursive: true }); fs.writeFileSync(coverFile(pkg), squareJpeg(shots[shot], 720)); done(id, true); }
    catch (e) { fail(id, e); }
  },

  // play time
  playStats: () => JSON.stringify(readJson('playtime.json', {})),
  playReset: (pkg) => { if (!pkg) writeJson('playtime.json', {}); else { const a = readJson('playtime.json', {}); delete a[pkg]; writeJson('playtime.json', a); } },

  // links and system
  openUrl: (url) => { if (/^(https?|steam|com\.epicgames\.launcher|spotify|ms-settings):/i.test(url)) shell.openExternal(url).catch(() => {}); },
  openBrowser: (url, pad) => openBrowser(url, pad),
  openSettings: (kind) => {
    if (kind === 'joy') { exec('control joy.cpl'); return; }
    const page = { bluetooth: 'ms-settings:bluetooth', wifi: 'ms-settings:network-wifi', battery: 'ms-settings:batterysaver', display: 'ms-settings:display', sound: 'ms-settings:sound' }[kind] || 'ms-settings:';
    shell.openExternal(page).catch(() => {});
  },
  openCategory: (cat) => {
    if (cat === 'market') shell.openExternal('steam://store').catch(() => shell.openExternal('https://store.steampowered.com/'));
    else if (cat === 'music') shell.openExternal('spotify:').catch(() => shell.openExternal('https://open.spotify.com/'));
    return true;
  },
  storePage: (pkg) => {
    const a = byPkg[pkg];
    if (a && a.source === 'steam') shell.openExternal('steam://nav/games/details/' + a.appid);
    else if (a && a.source === 'epic') shell.openExternal('com.epicgames.launcher://library').catch(() => {});
  },
  appInfo: (pkg) => {
    const a = byPkg[pkg];
    if (!a) return;
    if (a.source === 'steam') shell.openExternal('steam://nav/games/details/' + a.appid);
    else if (a.dir) shell.openPath(a.dir);
    else if (a.entry && a.entry.target) shell.showItemInFolder(a.entry.target);
  },
  uninstall: (pkg) => {
    const a = byPkg[pkg];
    if (!a) return;
    if (a.source === 'steam') shell.openExternal('steam://uninstall/' + a.appid);
    else if (a.source === 'epic') shell.openExternal('com.epicgames.launcher://library').catch(() => {});
    else { custom.remove(a.entry.id); scan(); js('window.onAppsChanged&&window.onAppsChanged()'); toast(a.label + ' removed from NSXL'); }
  },
  copyText: (t) => clipboard.writeText(String(t || '')),
  reload: () => { if (win) win.webContents.reload(); },
  canSleep: () => true,
  sleepNow: () => H.power('sleep'),

  // PC only: controllers
  padsState: () => { refreshPads(); return JSON.stringify(pads); },
  padsPermission: () => {},
  padsEnable: () => native.call('bt.power', { on: true }).then(refreshPads).catch(e => toast(e.message)),
  padsScan: () => { pads.found = []; pads.scanning = true; pushPads(); native.call('bt.scan').catch(e => { pads.scanning = false; pushPads(); toast(e.message); }); },
  padsStop: () => { native.call('bt.stop').catch(() => {}); },
  padsPair: (addr) => {
    pads.pairing = addr; pushPads();
    native.call('bt.pair', { dev: addr }, 90000)
      .then(() => { const d = pads.found.find(x => x.addr === addr); toast((d ? d.name : 'Controller') + ' paired'); pads.found = pads.found.filter(x => x.addr !== addr); })
      .catch(e => toast(e.message))
      .then(() => { pads.pairing = null; refreshPads(); });
  },
  padsForget: (addr) => native.call('bt.unpair', { dev: addr }).then(() => toast('Forgotten')).catch(e => toast(e.message)).then(refreshPads),

  // quick menu (overlay.html uses these)
  getVolume: () => JSON.stringify(H._vol || { v: 50, max: 100, muted: false }),
  setVolume: (v) => { native.call('vol.set', { v }).then(r => { H._vol = r; }).catch(() => {}); },
  toggleMute: () => { native.call('vol.mute').then(r => { H._vol = r; }).catch(() => {}); },
  getBrightness: () => JSON.stringify(H._bright || { v: 0, max: 100, canWrite: false, auto: false }),
  setBrightness: (v) => { native.call('bright.set', { v }).catch(() => {}); },
  setAutoBrightness: () => {},
  hasShortcutService: () => true,
  media: (c) => { native.call('media.cmd', { c: c === 'play' ? 'toggle' : c }).catch(e => toast(e.message)); },
  close: () => overlayClose(),
  goHome: () => bringHome(),
  screenshot: () => { screenshot().catch(e => toast("Couldn't take a screenshot: " + e.message)); },
  closeGame: () => {
    overlayClose();
    if (overlayGame && overlayGame.pid) native.call('proc.kill', { pid: overlayGame.pid }).then(() => { overlayGame = null; bringHome(); }).catch(e => toast("Couldn't close it: " + e.message));
  },
  gameName: () => (overlayGame ? (overlayGame.title || overlayGame.name || '') : ''),
  openPanel: (kind) => { overlayClose(); H.openSettings(kind === 'volume' ? 'sound' : kind); },
  openControllersHome: () => { bringHome(); js('window.openControllers&&openControllers()'); },
  openMusicHome: () => { bringHome(); js('window.openMusic&&openMusic()'); },
  sleep: () => H.power('sleep'),
  hideHandle: () => {}, stopOverlay: () => {}, requestWriteSettings: () => {},
  menuButton: () => prefs.get('menuButton', 'viewmenu'),
  setMenuButton: (v) => prefs.set('menuButton', v),
  menuInfo: () => JSON.stringify({ shortcut: menuShortcut, xinput: xinputPads }),

  // music
  musicState: async (id, withArt) => { try { done(id, await music(withArt)); } catch (e) { fail(id, e); } },
  spotifyCmd: async (id, cmd, arg) => {
    try {
      const S = spotify;
      if (cmd === 'toggle') { const p = await S.player(); await (p && p.playing ? S.pause() : S.play()); }
      else if (cmd === 'next') await S.next();
      else if (cmd === 'prev') await S.prev();
      else if (cmd === 'shuffle') await S.shuffle(!!arg);
      else if (cmd === 'repeat') await S.repeat(arg);
      else if (cmd === 'volume') await S.volume(arg);
      else if (cmd === 'seek') await S.seek(arg);
      else if (cmd === 'play') await S.play(arg);
      else if (cmd === 'liked') await S.playLiked(await S.liked());
      else if (cmd === 'transfer') await S.transfer(arg);
      done(id, true);
    } catch (e) { fail(id, e); }
  },
  spotifyLists: async (id) => { try { done(id, await spotify.playlists()); } catch (e) { fail(id, e); } },
  spotifyDevices: async (id) => { try { done(id, await spotify.devices()); } catch (e) { fail(id, e); } },
  spotifyInfo: () => JSON.stringify({ connected: spotify.connected, clientId: spotify.clientId, redirect: Spotify.redirect }),
  spotifySetClient: (cid) => { prefs.set('spotifyClientId', String(cid || '').trim()); spotify.logout(); },
  spotifyLogin: async (id) => { try { await spotify.login(); bringHome(); done(id, true); } catch (e) { fail(id, e); } },
  spotifyLogout: () => spotify.logout(),

  // VPN (Private Internet Access)
  vpnState: () => JSON.stringify(pia.state),   // cached: never refresh here, the page calls this on every redraw
  vpnSet: async (id, on) => { try { on ? await pia.connect() : await pia.disconnect(); done(id, true); } catch (e) { fail(id, e); } },
  vpnRegion: async (id, r) => { try { await pia.setRegion(r); await pia.refresh(); done(id, true); } catch (e) { fail(id, e); } },
  vpnAuto: () => !!prefs.get('vpnOnStart', false),
  setVpnAuto: (v) => prefs.set('vpnOnStart', !!v),

  // Xbox mode
  fseState: () => JSON.stringify(fse),
  fseInstall: () => fseRun('Install'),
  fseRemove: () => fseRun('Remove'),

  // PC only
  addGame: async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Add a game or app', properties: ['openFile', 'multiSelections'], filters: [{ name: 'Games and shortcuts', extensions: ['exe', 'lnk', 'url', 'bat'] }] });
    if (r.canceled) return;
    let n = 0;
    for (const f of r.filePaths) { try { custom.add(lib.entryFromFile(f, shell)); n++; } catch (e) { toast(String(e.message || e)); } }
    scan();
    js('window.onAppsChanged&&window.onAppsChanged()');
    if (n) toast(n === 1 ? 'Game added' : n + ' games added');
  },
  rescan: () => { scan(); js('window.onAppsChanged&&window.onAppsChanged()'); },
  libraryInfo: () => JSON.stringify({ steam: apps.filter(a => a.source === 'steam').length, epic: apps.filter(a => a.source === 'epic').length, custom: apps.filter(a => a.source === 'custom').length, steamFound: !!lib.steamRoot() }),
  autoStart: () => app.getLoginItemSettings().openAtLogin,
  setAutoStart: (on) => app.setLoginItemSettings({ openAtLogin: !!on }),
  isFullscreen: () => !!(win && win.isFullScreen()),
  setFullscreen: (on) => { if (win) win.setFullScreen(!!on); prefs.set('fullscreen', !!on); },
  power: (kind) => {
    if (kind === 'quit') app.quit();
    else if (kind === 'minimize' && win) win.minimize();
    else if (kind === 'sleep') exec('rundll32.exe powrprof.dll,SetSuspendState 0,1,0');
    else if (kind === 'restart') exec('shutdown /r /t 0');
    else if (kind === 'shutdown') exec('shutdown /s /t 0');
  },

  // Android-only features: harmless answers so the page stays happy
  padFamily: () => 'none',
  overlayState: () => '{}', overlayConfig: () => {}, overlayEnable: () => {}, overlayTest: () => overlayOpen(),
  shortcutEnabled: () => false, setShortcut: () => {}, openAccessibility: () => {},
  cloudHostState: () => '{}', cloudPending: () => '[]', cloudSnapshot: () => '{}', cloudHost: () => {}, cloudClearPending: () => {},
  setSwapAB: () => {}, setTyping: () => {}, setOrientation: () => {}, setImmersive: () => {}, setLaunchStyle: () => {}, haptic: () => {},
  openAppInfoSelf: () => shell.openPath(DATA),
};

async function sgdb(key, p) {
  if (!key) throw new Error('Add your SteamGridDB API key in Settings › Cover Art');
  const r = await net.fetch('https://www.steamgriddb.com/api/v2' + p, { headers: { Authorization: 'Bearer ' + key } });
  if (r.status === 401) throw new Error('SteamGridDB says that API key is wrong');
  if (!r.ok) throw new Error('SteamGridDB error ' + r.status);
  return r.json();
}

ipcMain.on('nb-names', e => { e.returnValue = Object.keys(H).filter(k => !k.startsWith('_')); });
ipcMain.on('nb', (e, name, args) => {
  let v;
  try { v = typeof H[name] === 'function' ? H[name](...(args || [])) : undefined; } catch (err) { console.warn(name, err); }
  if (v && typeof v.then === 'function') { v.catch(err => console.warn(name, err)); v = undefined; }
  e.returnValue = v === undefined ? null : v;
});

// ------------------------------------------------------------------ nsxl://app/ : the page, its images and your files

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.wav': 'audio/wav', '.mp3': 'audio/mpeg',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime', '.m4v': 'video/mp4',
  '.mkv': 'video/x-matroska', '.m4a': 'audio/mp4', '.aac': 'audio/aac', '.ogg': 'audio/ogg', '.opus': 'audio/ogg', '.flac': 'audio/flac', '.txt': 'text/plain' };

const notFound = () => new Response('Not found', { status: 404 });
const bytes = (buf, type) => new Response(buf, { headers: { 'Content-Type': type, 'Cache-Control': 'no-cache' } });

/** Serves a file, with byte ranges so videos and music can seek and stream. */
function serveFile(file, req) {
  let st;
  try { st = fs.statSync(file); } catch (e) { return notFound(); }
  const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
  const range = req.headers.get('Range');
  const m = range && range.match(/bytes=(\d*)-(\d*)/);
  if (m) {
    let start = m[1] ? +m[1] : 0, end = m[2] ? +m[2] : st.size - 1;
    if (!m[1] && m[2]) { start = Math.max(0, st.size - +m[2]); end = st.size - 1; }
    end = Math.min(end, st.size - 1);
    if (start > end) return new Response(null, { status: 416, headers: { 'Content-Range': 'bytes */' + st.size } });
    return new Response(Readable.toWeb(fs.createReadStream(file, { start, end })), {
      status: 206, headers: { 'Content-Type': type, 'Content-Length': String(end - start + 1), 'Content-Range': `bytes ${start}-${end}/${st.size}`, 'Accept-Ranges': 'bytes' } });
  }
  return new Response(Readable.toWeb(fs.createReadStream(file)), { headers: { 'Content-Type': type, 'Content-Length': String(st.size), 'Accept-Ranges': 'bytes' } });
}

const iconCache = new Map();
async function icon(pkg) {
  if (iconCache.has(pkg)) return iconCache.get(pkg);
  const src = iconSource(byPkg[pkg]);
  let png = null;
  if (src && fs.existsSync(src)) {
    if (/\.(jpe?g|png|webp|ico)$/i.test(src)) { const im = nativeImage.createFromPath(src); if (!im.isEmpty()) png = im.toPNG(); }
    if (!png) { try { const im = await app.getFileIcon(src, { size: 'large' }); if (!im.isEmpty()) png = im.toPNG(); } catch (e) { } }
  }
  iconCache.set(pkg, png);
  return png;
}

async function handle(req) {
  const u = new URL(req.url);
  let p = decodeURIComponent(u.pathname);
  if (p === '/' || p === '') p = '/index.html';
  if (p.startsWith('/art/')) {
    const pkg = p.slice(5);
    if (fs.existsSync(coverFile(pkg))) return serveFile(coverFile(pkg), req);
    const a = byPkg[pkg];
    if (a && a.source === 'steam') { const f = lib.steamImage(a.appid, 'capsule') || lib.steamImage(a.appid, 'header'); if (f) return serveFile(f, req); }
    return notFound();
  }
  if (p.startsWith('/icon/')) { const png = await icon(p.slice(6)); return png ? bytes(png, 'image/png') : notFound(); }
  if (p === '/avatar') return serveFile(path.join(DATA, 'avatar.jpg'), req);
  if (p === '/music') return serveFile(path.join(DATA, 'music' + prefs.get('musicExt', '.mp3')), req);
  if (p === '/wallpaper') return serveFile(path.join(DATA, 'wallpaper' + prefs.get('videoExt', '.mp4')), req);
  if (p.startsWith('/shot/')) {
    const [id, kind] = p.slice(6).split('/');
    const f = shots[id];
    if (!f) return notFound();
    if (kind === 'full') return serveFile(f, req);
    const im = nativeImage.createFromPath(f);
    return im.isEmpty() ? notFound() : bytes(im.resize({ width: 480, quality: 'good' }).toJPEG(80), 'image/jpeg');
  }
  const file = path.normalize(path.join(WWW, p));
  if (!file.startsWith(WWW)) return notFound();
  return serveFile(file, req);
}

// ------------------------------------------------------------------ NSXL Web: the built-in browser

let browserWin = null;
const BROWSER_PARTITION = 'persist:browser';

function fade(w, from, to, ms, then) {
  const steps = 10;
  let i = 0;
  w.setOpacity(from);
  const t = setInterval(() => {
    if (w.isDestroyed()) { clearInterval(t); return; }
    i++;
    w.setOpacity(from + (to - from) * (i / steps));
    if (i >= steps) { clearInterval(t); if (then) then(); }
  }, ms / steps);
}

function openBrowser(url, pad) {
  const q = '?pad=' + (pad ? '1' : '0') + (url && /^https:\/\//.test(url) ? '&u=' + encodeURIComponent(url) : '');
  if (browserWin && !browserWin.isDestroyed()) {
    if (url) browserWin.loadURL('nsxl://app/browser/shell.html' + q);
    browserWin.show(); browserWin.focus();
    return;
  }
  const b = win ? win.getBounds() : { width: 1280, height: 720 };
  browserWin = new BrowserWindow({
    ...b, show: false, fullscreen: !!(win && win.isFullScreen()), autoHideMenuBar: true, backgroundColor: prefs.get('mode', 'light') === 'light' ? '#ebebeb' : '#15151b',
    title: 'NSXL Web', icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'browser-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, webviewTag: true },
  });
  browserWin.setMenuBarVisibility(false);
  browserWin.loadURL('nsxl://app/browser/shell.html' + q);
  browserWin.once('ready-to-show', () => { browserWin.setOpacity(0); browserWin.show(); fade(browserWin, 0, 1, 180); });
  browserWin.on('focus', () => browserWin.webContents.send('br-focus', true));
  browserWin.on('blur', () => browserWin.webContents.send('br-focus', false));
  browserWin.webContents.on('will-navigate', (e, u) => { if (!u.startsWith('nsxl://app/browser/')) e.preventDefault(); });
  browserWin.on('closed', () => { browserWin = null; if (win && !win.isDestroyed()) win.focus(); });
}

ipcMain.on('br-close', () => { if (browserWin && !browserWin.isDestroyed()) { const w = browserWin; fade(w, 1, 0, 140, () => w.close()); } });
ipcMain.on('br-external', (e, url) => { if (/^https?:\/\//.test(url)) shell.openExternal(url); });
ipcMain.on('br-clear', () => { electronSession.fromPartition(BROWSER_PARTITION).clearStorageData().catch(() => {}); electronSession.fromPartition(BROWSER_PARTITION).clearCache().catch(() => {}); });

// Web pages inside NSXL Web: no node, no preload, their own storage; pop-ups open in the same view,
// and links for other apps (steam://, mailto: ...) go to those apps.
app.on('web-contents-created', (e, contents) => {
  contents.on('will-attach-webview', (ev, prefs2, params) => {
    delete prefs2.preload;
    prefs2.nodeIntegration = false; prefs2.contextIsolation = true; prefs2.sandbox = true;
    params.partition = BROWSER_PARTITION;
    if (!/^(https?:|nsxl:\/\/app\/browser\/|about:blank)/.test(params.src || '')) ev.preventDefault();
  });
  if (contents.getType() !== 'webview') return;
  if (process.argv.includes('--smoke')) contents.on('did-finish-load', () => console.log('NSXL Web: loaded', contents.getURL()));
  contents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) contents.loadURL(url); else shell.openExternal(url).catch(() => {}); return { action: 'deny' }; });
  contents.on('will-navigate', (ev, url) => { if (!/^(https?:|nsxl:\/\/app\/browser\/|about:)/.test(url)) { ev.preventDefault(); shell.openExternal(url).catch(() => {}); } });
});

/** The browser's storage only reaches the start page and its font: no launcher data. */
function browserProtocol() {
  electronSession.fromPartition(BROWSER_PARTITION).protocol.handle('nsxl', req => {
    const p = decodeURIComponent(new URL(req.url).pathname);
    if (p.startsWith('/browser/') && !p.includes('..') && p !== '/browser/shell.html') return serveFile(path.join(WWW, p), req);
    if (p === '/fonts/Montserrat.ttf') return serveFile(path.join(WWW, 'fonts', 'Montserrat.ttf'), req);
    return notFound();
  });
}

// ------------------------------------------------------------------ the window

function createWindow() {
  const full = process.argv.includes('--fse') || prefs.get('fullscreen', true);
  win = new BrowserWindow({
    width: 1280, height: 720, minWidth: 800, minHeight: 450, show: false, fullscreen: full, autoHideMenuBar: true,
    backgroundColor: '#0B0B0F', title: 'NSXL', icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: true, autoplayPolicy: 'no-user-gesture-required' },
  });
  win.setMenuBarVisibility(false);
  win.once('ready-to-show', () => win.show());
  win.loadURL('nsxl://app/index.html');

  // keep the launcher on its own page; links open in your browser
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('nsxl://')) { e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); } });
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type === 'keyDown' && input.key === 'F11') { e.preventDefault(); H.setFullscreen(!win.isFullScreen()); }
  });

  // games take the focus; coming back to NSXL is the "back from the game" moment
  win.on('blur', () => { if (session) session.left = true; js('window.onPauseApp&&window.onPauseApp()'); });
  win.on('focus', () => { playFinish(); js('window.onResumeApp&&window.onResumeApp()'); });
  win.on('closed', () => { win = null; app.quit(); });
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  protocol.handle('nsxl', req => handle(req).catch(() => notFound()));
  browserProtocol();
  scan();
  createWindow();
  // where NSXL lives, for the Xbox mode home app to open it
  const exePath = process.env.PORTABLE_EXECUTABLE_FILE || process.execPath;
  if (app.isPackaged) exec(`reg add "HKCU\\Software\\NSXL" /v ExePath /t REG_SZ /d "${exePath}" /f`, { windowsHide: true });
  native.start();
  native.call('vol.get').then(r => { H._vol = r; }).catch(() => {});
  native.call('bright.get').then(r => { H._bright = r; }).catch(() => {});
  pia.watch();
  pia.refresh().then(() => { if (prefs.get('vpnOnStart', false) && pia.state.connection !== 'Connected') pia.connect().catch(() => {}); }).catch(() => {});
  fseCheck();
  const { globalShortcut } = require('electron');
  // first shortcut Windows lets us have
  for (const k of ['Control+Alt+Space', 'Control+Shift+Space', 'Alt+F1']) {
    try { if (globalShortcut.register(k, overlayOpen)) { menuShortcut = k.replace(/Control/g, 'Ctrl').replace(/\+/g, '+'); break; } } catch (e) { }
  }
  powerMonitor.on('resume', () => js('window.onWake&&window.onWake()'));
});

// CI check: `electron . --smoke` opens NSXL, waits for Home, saves a screenshot and reports page errors.
if (process.argv.includes('--smoke')) {
  const errors = [];
  app.on('web-contents-created', (e, wc) => wc.on('console-message', (ev, level, message) => { if (level >= 3) errors.push(message); }));
  app.whenReady().then(() => setTimeout(async () => {
    try {
      const r = await win.webContents.executeJavaScript(`({ pc: document.documentElement.classList.contains('pc'), tiles: document.querySelectorAll('#row .tile').length, dock: document.querySelectorAll('#dock .dk').length, theme: document.documentElement.dataset.theme })`);
      const img = await win.webContents.capturePage();
      fs.writeFileSync(path.join(__dirname, 'smoke.png'), img.toPNG());
      // and NSXL Web: the browser window opens on its start page
      openBrowser('', false);
      await new Promise(res => setTimeout(res, 6000));
      const b = await browserWin.webContents.executeJavaScript(`({ url: document.getElementById('wv').getURL(), title: document.title, tiles: 0 })`);
      fs.writeFileSync(path.join(__dirname, 'smoke-browser.png'), (await browserWin.webContents.capturePage()).toPNG());
      await browserWin.webContents.executeJavaScript(`document.getElementById('wv').loadURL('https://example.com/')`).catch(() => {});
      await new Promise(res => setTimeout(res, 5000));
      const web = await browserWin.webContents.executeJavaScript(`document.getElementById('wv').getURL()`);
      browserWin.close();
      // the Windows helper answers, and the quick menu opens
      const helper = await native.call('ping', {}, 10000).catch(e => 'error: ' + e.message);
      const volume = await native.call('vol.get', {}, 10000).catch(e => 'error: ' + e.message);
      const radio = await native.call('bt.radio', {}, 10000).catch(e => 'error: ' + e.message);
      win.blur(); win.minimize();
      await new Promise(res => setTimeout(res, 800));
      overlayOpen();
      await new Promise(res => setTimeout(res, 4000));
      const menu = overlayWin ? await overlayWin.webContents.executeJavaScript(`({ open: document.body.classList.contains('open'), items: document.querySelectorAll('#dock .dk').length })`).catch(e => 'error: ' + e.message) : 'no window';
      if (overlayWin) fs.writeFileSync(path.join(__dirname, 'smoke-menu.png'), (await overlayWin.webContents.capturePage()).toPNG());
      const fseFiles = fs.existsSync(path.join(RES, 'fse', 'NSXL.HomeApp.msix')) && fs.existsSync(path.join(RES, 'fse', 'NSXL.cer'));
      console.log('SMOKE', JSON.stringify({ ...r, browser: b, web, helper, volume, radio, menu, fseFiles, errors }));
      app.exit(r.pc && r.dock > 0 && /browser\/start\.html/.test(b.url) && /example\.com/.test(web) && helper === 'pong' && menu && menu.open && fseFiles && !errors.length ? 0 : 1);
    } catch (err) { console.log('SMOKE failed', err); app.exit(1); }
  }, 9000));
}
app.on('window-all-closed', () => app.quit());
app.on('will-quit', () => { native.stop(); pia.stop(); try { require('electron').globalShortcut.unregisterAll(); } catch (e) { } });
