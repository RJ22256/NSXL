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

const WWW = path.join(__dirname, 'www');
const DATA = app.getPath('userData');
const COVERS = path.join(DATA, 'covers');
const custom = new lib.Custom(path.join(DATA, 'library.json'));

protocol.registerSchemesAsPrivileged([{ scheme: 'nsxl', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } }]);

if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null;

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
  const home = app.getPath('home'), out = [];
  const add = (dir, tag) => {
    let files = [];
    try { files = fs.readdirSync(dir); } catch (e) { return; }
    for (const f of files) {
      if (!/\.(png|jpe?g|webp)$/i.test(f)) continue;
      const p = path.join(dir, f);
      try { const st = fs.statSync(p); out.push({ p, name: f, date: st.mtimeMs, tag }); } catch (e) { }
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

const H = {
  platform: () => 'pc',
  version: () => app.getVersion(),
  sdk: () => 99,
  region: () => (app.getLocaleCountryCode() || 'GB'),
  firstLoad: () => { const f = !H._loaded; H._loaded = true; return f; },
  isDefaultHome: () => true,
  pageReady: () => {},
  getApps: () => { if (!apps.length) scan(); return JSON.stringify(apps.map(a => ({ pkg: a.pkg, label: a.label, game: a.game, installed: a.installed, cover: fs.existsSync(coverFile(a.pkg)) }))); },
  getStatus: () => JSON.stringify({ net: net.isOnline() ? 'wifi' : 'none', bars: 3, time24: false }),

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
  overlayState: () => '{}', overlayConfig: () => {}, overlayEnable: () => {}, overlayTest: () => {},
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
  contents.on('did-fail-load', (ev, code, desc, url, main) => { if (main) console.log('NSXL Web: failed to load', url, code, desc); });
  contents.on('did-finish-load', () => console.log('NSXL Web: loaded', contents.getURL()));
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
  const full = prefs.get('fullscreen', true);
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
  win.on('closed', () => { win = null; });
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  protocol.handle('nsxl', req => handle(req).catch(() => notFound()));
  browserProtocol();
  scan();
  createWindow();
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
      console.log('SMOKE', JSON.stringify({ ...r, browser: b, errors }));
      app.exit(r.pc && r.dock > 0 && /browser\/start\.html/.test(b.url) && !errors.length ? 0 : 1);
    } catch (err) { console.log('SMOKE failed', err); app.exit(1); }
  }, 9000));
}
app.on('window-all-closed', () => app.quit());
