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
// NSXL keeps watching the controller while a game covers it, so Windows mustn't treat it as hidden
app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion');

let win = null;

// Windows extras ship in resources/ (built by native/build.ps1)
const RES = app.isPackaged ? process.resourcesPath : path.join(__dirname, 'resources');
const native = new Native(path.join(RES, 'native', 'nsxl-native.exe'));
native.log = m => log(m);
/** Splits launch options the way a command line would ("quoted parts" stay together). */
function splitArgs(str) { const out = []; String(str || '').replace(/"([^"]*)"|(\S+)/g, (m, q, w) => { out.push(q != null ? q : w); return ''; }); return out; }

// ------------------------------------------------------------------ small stores

// A small log for troubleshooting (Settings › Controls › Open the log).
const LOG = path.join(DATA, 'nsxl.log');
function log(msg) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > 1024 * 1024) fs.renameSync(LOG, LOG + '.old');
    fs.appendFileSync(LOG, new Date().toISOString() + '  ' + msg + '\n');
  } catch (e) { }
}
function readJson(file, dflt) { try { return JSON.parse(fs.readFileSync(path.join(DATA, file), 'utf8')); } catch (e) { return dflt; } }
function writeJson(file, v) { fs.mkdirSync(DATA, { recursive: true }); fs.writeFileSync(path.join(DATA, file), JSON.stringify(v)); }
const prefs = { get: (k, d) => { const p = readJson('prefs.json', {}); return k in p ? p[k] : d; }, set: (k, v) => { const p = readJson('prefs.json', {}); p[k] = v; writeJson('prefs.json', p); } };

const js = code => { if (win && !win.isDestroyed()) win.webContents.executeJavaScript(code).catch(() => {}); };
const toast = msg => js('window.showToast&&showToast(' + JSON.stringify(msg) + ',2600)');
const done = (id, data) => js('window.onAsync(' + JSON.stringify(id) + ',true,' + JSON.stringify(data) + ')');
const fail = (id, err) => js('window.onAsync(' + JSON.stringify(id) + ',false,' + JSON.stringify(String((err && err.message) || err)) + ')');
const safe = s => String(s).replace(/[^a-z0-9._-]/gi, '_');
const coverFile = pkg => path.join(COVERS, safe(pkg) + '.jpg');
const VIDEO_EXT = ['.mp4', '.webm', '.m4v', '.mov'];
/** A moving tile: the video the user chose for this game, if any. */
const coverVideo = pkg => VIDEO_EXT.map(e => path.join(COVERS, safe(pkg) + '.video' + e)).find(f => fs.existsSync(f)) || null;
const dropCoverVideo = pkg => VIDEO_EXT.forEach(e => fs.rmSync(path.join(COVERS, safe(pkg) + '.video' + e), { force: true }));
// a live tile from SteamGridDB: an animated WebP/PNG/GIF kept as it came, so it keeps moving
const ANIM_EXT = ['.webp', '.png', '.gif'];
const coverAnim = pkg => ANIM_EXT.map(e => path.join(COVERS, safe(pkg) + '.anim' + e)).find(f => fs.existsSync(f)) || null;
const dropCoverAnim = pkg => ANIM_EXT.forEach(e => fs.rmSync(path.join(COVERS, safe(pkg) + '.anim' + e), { force: true }));
const imgExt = (type, url) => /webp/.test(type) || /\.webp(\?|$)/i.test(url) ? '.webp' : /gif/.test(type) || /\.gif(\?|$)/i.test(url) ? '.gif' : /png/.test(type) || /\.png(\?|$)/i.test(url) ? '.png' : '.jpg';
// second screen: your own picture or video per game, or artwork fetched for it
const SECOND = path.join(DATA, 'second');
const SECOND_EXT = ['.mp4', '.webm', '.m4v', '.mov', '.jpg', '.jpeg', '.png', '.webp', '.gif'];
const secondOwn = pkg => SECOND_EXT.map(e => path.join(SECOND, safe(pkg) + '.own' + e)).find(f => fs.existsSync(f)) || null;
const dropSecondOwn = pkg => SECOND_EXT.forEach(e => fs.rmSync(path.join(SECOND, safe(pkg) + '.own' + e), { force: true }));
const heroFile = pkg => path.join(SECOND, safe(pkg) + '.hero.img');
const logoFile = pkg => path.join(SECOND, safe(pkg) + '.logo.img');
const LOGO_EXT = ['.png', '.webp', '.gif', '.jpg', '.jpeg', '.svg'];
const ownLogo = pkg => LOGO_EXT.map(e => path.join(SECOND, safe(pkg) + '.ownlogo' + e)).find(f => fs.existsSync(f)) || null;
const dropOwnLogo = pkg => LOGO_EXT.forEach(e => fs.rmSync(path.join(SECOND, safe(pkg) + '.ownlogo' + e), { force: true }));

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
function playStart(pkg) {
  session = { pkg, start: Date.now(), left: false };
  if (typeof secondNowPlaying === 'function') secondNowPlaying();
}
/** Back on NSXL: the game counts as finished only once its processes have gone, so it can still be closed from NSXL. */
let playWatch = null;
function playCheck() {
  if (!session || !session.left) return;
  const a = byPkg[session.pkg];
  if (!a || !a.dir) return playFinish();
  const still = () => native.call('proc.list', { dir: a.dir }, 5000).then(l => Array.isArray(l) && l.length > 0).catch(() => false);
  const pkg = session.pkg;
  still().then(on => {
    if (!session || session.pkg !== pkg) return;
    if (!on) return playFinish();
    log(a.label + ' is still running');
    clearInterval(playWatch);
    playWatch = setInterval(() => {
      if (!session || session.pkg !== pkg) { clearInterval(playWatch); playWatch = null; return; }
      still().then(on => { if (!on && session && session.pkg === pkg) { clearInterval(playWatch); playWatch = null; session.left = true; log(a.label + ' has closed'); playFinish(); js('window.onGameEnded&&window.onGameEnded()'); } });
    }, 5000);
  });
}
function playFinish() {
  if (!session || !session.left) return;
  const ms = Date.now() - session.start, pkg = session.pkg;
  session = null;
  if (typeof secondNowPlaying === 'function') secondNowPlaying();
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

const isVideo = f => VIDEO_EXT.includes(path.extname(f).toLowerCase());
async function pickMedia(title) {
  const r = await dialog.showOpenDialog(win, { title, properties: ['openFile'], filters: [
    { name: 'Pictures and videos', extensions: ['png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif', 'mp4', 'webm', 'm4v', 'mov'] },
    { name: 'Videos', extensions: ['mp4', 'webm', 'm4v', 'mov'] }, { name: 'Pictures', extensions: ['png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif'] }] });
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

// ------------------------------------------------------------------ Steam, out of sight
// Steam games need Steam running, so NSXL starts it quietly in the background and keeps its pop-ups
// behind NSXL's game intro until the game's own window appears.

const STEAMISH = /^(steam|steamwebhelper|steamservice|steamerrorreporter|gameoverlayui|gameoverlayui64)$/i;
const SHELLISH = /^(explorer|shellexperiencehost|startmenuexperiencehost|searchhost|searchapp|textinputhost|lockapp|applicationframehost|nsxl|electron|nsxl-native)$/i;
function steamExe() { const r = lib.steamRoot(); const f = r && path.join(r, 'steam.exe'); return f && fs.existsSync(f) ? f : null; }
const steamRunning = () => new Promise(res => exec('tasklist /FI "IMAGENAME eq steam.exe" /NH', { windowsHide: true }, (e, out) => res(!e && /steam\.exe/i.test(out || ''))));
async function steamQuietStart() {
  if (!prefs.get('steamQuiet', true) || process.argv.includes('--smoke')) return;
  const exe = steamExe();
  if (!exe || await steamRunning()) return;
  try { const c = spawn(exe, ['-silent'], { detached: true, stdio: 'ignore', cwd: path.dirname(exe) }); c.on('error', () => {}); c.unref(); } catch (e) { }
}

let launchWatch = null;
function launchCoverEnd(shown, hwnd) {
  clearInterval(launchWatch); launchWatch = null;
  if (win && !win.isDestroyed()) win.setAlwaysOnTop(false);
  if (hwnd) native.call('fg.set', { hwnd }, 3000).catch(() => {});
  js('window.onGameShown&&window.onGameShown(' + !!shown + ')');
}
/** Keeps NSXL's intro in front of Steam's "Preparing to launch…" windows until the game itself appears. */
function launchCover() {
  clearInterval(launchWatch);
  const cover = prefs.get('steamCover', true) && !process.argv.includes('--smoke');
  if (cover && win && !win.isDestroyed()) win.setAlwaysOnTop(true, 'floating');
  const t0 = Date.now();
  let steamSince = 0, busy = false;
  launchWatch = setInterval(async () => {
    if (busy) return; busy = true;
    try {
      if (Date.now() - t0 > 60000) return launchCoverEnd(false);   // never showed up: give the screen back
      let f; try { f = await native.call('fg.get', {}, 1500); } catch (e) { return; }
      const name = String(f.name || '');
      if (!name || f.pid === process.pid || SHELLISH.test(name)) { steamSince = 0; return; }
      if (STEAMISH.test(name)) {
        // a Steam window that stays up is asking you something (a cloud save conflict, an update): let it through
        if (!steamSince) steamSince = Date.now();
        else if (Date.now() - steamSince > 7000) launchCoverEnd(false, f.hwnd);
        return;
      }
      launchCoverEnd(true, f.hwnd);   // anything else in front is the game
    } finally { busy = false; }
  }, 250);
}

// ------------------------------------------------------------------ NSXL Companion (phone / tablet remote on your home network)

const { Companion } = require('./lib/companion');
const COMPANION_PORT = 8974;
const REMOTE_PADS = ['up', 'down', 'left', 'right', 'a', 'b', 'x', 'y', 'l', 'r', 'plus', 'minus'];
let companionArt = { key: '', art: null };
async function companionNow() {
  const m = await music(false).catch(() => null);
  if (!m) return null;
  const sp = m.spotify, se = m.session;
  const src = sp && (sp.playing || !(se && se.playing)) ? { title: sp.title, artist: sp.artist, playing: !!sp.playing, art: sp.art, from: 'Spotify' }
    : se && se.title ? { title: se.title, artist: se.artist, playing: !!se.playing, art: null, from: String(se.app || '').split('!')[0].split('.').pop() } : null;
  if (src && !src.art && se && se.title) {   // album art from Windows, fetched once per track
    const k = se.title + '|' + se.artist;
    if (companionArt.key !== k) { companionArt = { key: k, art: null }; native.call('media.get', { art: true }, 8000).then(r => { if (companionArt.key === k) companionArt.art = r && r.art || null; }).catch(() => {}); }
    src.art = companionArt.art;
  }
  return src;
}
const companion = new Companion({
  prefs,
  page: path.join(__dirname, 'www', 'companion.html'),
  files: path.join(__dirname, 'www'),
  async state(live) {
    const s = {
      pc: require('os').hostname(),
      playing: session && byPkg[session.pkg] ? { pkg: session.pkg, label: byPkg[session.pkg].label, since: session.start } : null,
      volume: H._vol || null,
      music: await companionNow(),
      vpn: pia.state && pia.state.installed ? { on: pia.state.connection === 'Connected', state: pia.state.connection, region: pia.state.region } : null,
      second: prefs.get('secondScreen', 'off') === 'art',
    };
    if (live) return s;
    const pt = readJson('playtime.json', {});
    s.games = apps.filter(a => a.game).map(a => ({ pkg: a.pkg, label: a.label, last: (pt[a.pkg] || {}).last || a.installed || 0, ms: (pt[a.pkg] || {}).ms || 0 }))
      .sort((x, y) => y.last - x.last);
    return s;
  },
  art: (pkg) => handle(new Request('nsxl://app/art/' + encodeURIComponent(pkg) + '?s=512')),
  async act(a, args) {
    const [x, y] = args;
    switch (a) {
      case 'pad': if (REMOTE_PADS.includes(x)) js('window.onPad&&window.onPad(' + JSON.stringify(x) + ')'); return;
      case 'home': bringHome(); return;
      case 'menu': overlayOpen(); return;
      case 'launch': if (byPkg[x]) { bringHome(); setTimeout(() => js('window.remoteLaunch&&window.remoteLaunch(' + JSON.stringify(x) + ')'), 250); } return;
      case 'text': {
        const wc = browserWin && !browserWin.isDestroyed() && browserWin.isVisible() ? browserWin.webContents : win.webContents;
        wc.insertText(String(x || '').slice(0, 2000)); return;
      }
      case 'key': {
        const k = { enter: 'Enter', back: 'Backspace', tab: 'Tab' }[x]; if (!k) return;
        const wc = browserWin && !browserWin.isDestroyed() && browserWin.isVisible() ? browserWin.webContents : win.webContents;
        wc.sendInputEvent({ type: 'keyDown', keyCode: k }); wc.sendInputEvent({ type: 'keyUp', keyCode: k }); return;
      }
      case 'volume': H.setVolume(Math.max(0, Math.min(100, Math.round(+x || 0)))); return;
      case 'mute': H.toggleMute(); return;
      case 'media': if (['play', 'next', 'prev'].includes(x)) { if (spotify.connected) await H.spotifyCmd('_', x === 'play' ? 'toggle' : x).catch(() => {}); else H.media(x); } return;
      case 'screenshot': H.screenshot(); return;
      case 'closeGame': H.closeGame(); return;
      case 'sleep': H.power('sleep'); return;
      case 'vpn': if (x) await pia.connect(); else await pia.disconnect(); return;
      case 'second': prefs.set('secondScreen', x ? 'art' : 'off'); secondSync(); secondPush(); return;
      default: throw new Error('Unknown action');
    }
  },
  onPaired: (name) => toast(name + ' is connected to NSXL'),
});
async function companionSync() {
  if (prefs.get('companion', false)) {
    try { await companion.start(COMPANION_PORT); } catch (e) { toast('The companion couldn\'t start: port ' + COMPANION_PORT + ' is in use'); }
  } else companion.stop();
}
let qrLib; try { qrLib = require('qrcode'); } catch (e) { qrLib = null; }

// ------------------------------------------------------------------ second screen: big artwork of the selected game

function sniffImage(f) {
  try { const b = Buffer.alloc(4); const fd = fs.openSync(f, 'r'); fs.readSync(fd, b, 0, 4, 0); fs.closeSync(fd);
    if (b[0] === 0x89 && b[1] === 0x50) return 'image/png'; if (b[0] === 0x52 && b[1] === 0x49) return 'image/webp'; if (b[0] === 0x47 && b[1] === 0x49) return 'image/gif'; } catch (e) { }
  return 'image/jpeg';
}
const mtime = f => { try { return Math.round(fs.statSync(f).mtimeMs); } catch (e) { return 0; } };
let secondWin = null, second = { pkg: null, label: '', key: '' };
const secondTried = new Set();

/** The screen NSXL isn't on, when there is one (or the one you picked). */
function otherDisplay() {
  const { screen } = require('electron');
  const all = screen.getAllDisplays();
  if (all.length < 2) return null;
  const mine = win && !win.isDestroyed() ? screen.getDisplayMatching(win.getBounds()) : screen.getPrimaryDisplay();
  const pick = prefs.get('secondDisplay', null);
  return all.find(d => d.id === pick && d.id !== mine.id) || all.find(d => d.id !== mine.id) || null;
}
/** Opens, moves or closes the artwork window to match your setting and the screens plugged in. */
function secondSync() {
  const d = prefs.get('secondScreen', 'off') === 'art' ? otherDisplay() : null;
  if (!d) { if (secondWin && !secondWin.isDestroyed()) secondWin.destroy(); secondWin = null; return; }
  if (secondWin && !secondWin.isDestroyed()) { secondWin.setFullScreen(false); secondWin.setBounds(d.bounds); secondWin.setFullScreen(true); return; }
  secondWin = new BrowserWindow({
    x: d.bounds.x, y: d.bounds.y, width: d.bounds.width, height: d.bounds.height, show: false, frame: false, focusable: false,
    skipTaskbar: true, fullscreen: true, backgroundColor: '#000000', title: 'NSXL second screen',
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false },
  });
  secondWin.setMenu(null);
  secondWin.loadURL('nsxl://app/second.html');
  secondWin.webContents.once('did-finish-load', () => { if (!secondWin) return; secondWin.showInactive(); secondPush(); });
  secondWin.on('closed', () => { secondWin = null; });
}
/** While a game is running the second screen shows it, whatever is selected on Home. */
function secondNowPlaying() { secondPush(); }
/** Sends the selected (or playing) game's artwork to the second screen. */
function secondPush() {
  const pkg = session && byPkg[session.pkg] ? session.pkg : second.pkg;
  if (!secondWin || secondWin.isDestroyed() || !pkg) return;
  const e = encodeURIComponent(pkg), a = byPkg[pkg];
  const own = secondOwn(pkg), steam = a && a.source === 'steam';
  const hasHero = fs.existsSync(heroFile(pkg)) || !!(steam && lib.steamImage(a.appid, 'hero'));
  const hasLogo = !!ownLogo(pkg) || fs.existsSync(logoFile(pkg)) || !!(steam && lib.steamImage(a.appid, 'logo'));
  const cv = coverVideo(pkg);
  const data = {
    pkg, label: (a && a.label) || second.label || '',
    playing: session && session.pkg === pkg ? { since: session.start } : null,
    media: own ? { url: '/second/' + e + '?v=' + mtime(own), video: isVideo(own) }
      : hasHero ? { url: '/hero/' + e + '?v=' + mtime(heroFile(pkg)), video: false } : null,
    logo: prefs.get('secondLogo', true) && hasLogo ? '/logo/' + e + '?v=' + (mtime(ownLogo(pkg) || '') || mtime(logoFile(pkg))) : null,
    tile: cv ? { url: '/artvideo/' + e + '?v=' + mtime(cv), video: true } : { url: '/art/' + e + '?s=1024&v=' + (mtime(coverAnim(pkg) || '') || mtime(coverFile(pkg))), video: false },
  };
  secondWin.webContents.executeJavaScript('window.show&&show(' + JSON.stringify(data) + ')').catch(() => {});
  if ((!own && !hasHero) || (prefs.get('secondLogo', true) && !hasLogo)) secondFetch(pkg, !own && !hasHero, !hasLogo);
}
/** Downloads a hero banner and logo from SteamGridDB the first time a game is shown (needs your API key). */
async function secondFetch(pkg, needHero, needLogo) {
  const key = second.key, a = byPkg[pkg];
  if (!key || !prefs.get('secondSgdb', true) || secondTried.has(pkg)) return;
  secondTried.add(pkg);
  try {
    let gameId = null;
    if (a && a.source === 'steam') { try { gameId = (await sgdb(key, '/games/steam/' + a.appid)).data.id; } catch (e) { } }
    if (!gameId) { const j = await sgdb(key, '/search/autocomplete/' + encodeURIComponent(String(second.label || (a && a.label) || '').trim())); gameId = j.data && j.data[0] && j.data[0].id; }
    if (!gameId) return;
    const grab = async (kind, file) => {
      const j = await sgdb(key, '/' + kind + '/game/' + gameId + '?types=static');
      const pickOne = (j.data || []).find(x => x.url);
      if (!pickOne) return;
      const r = await net.fetch(pickOne.url);
      if (r.ok) { fs.mkdirSync(SECOND, { recursive: true }); fs.writeFileSync(file, Buffer.from(await r.arrayBuffer())); }
    };
    if (needHero) await grab('heroes', heroFile(pkg)).catch(() => {});
    if (needLogo && prefs.get('secondLogo', true)) await grab('logos', logoFile(pkg)).catch(() => {});
    secondPush();
  } catch (e) { }
}

// ------------------------------------------------------------------ the quick menu (over games)

let overlayWin = null, overlayGame = null, overlayFocused = false, xinputPads = 0, menuShortcut = '';
let overlayOpening = false;
// nothing NSXL waits on while opening the menu may hold it up: a hidden window's page can stall
const within = (p, ms, fallback) => Promise.race([Promise.resolve(p).catch(() => fallback), new Promise(r => setTimeout(() => r(fallback), ms))]);
/** A dimmed picture of the screen to sit behind the menu (Windows wouldn't show a see-through window over games). */
async function overlayBackdrop(d) {
  try {
    const { desktopCapturer } = require('electron');
    const size = { width: Math.round(d.size.width * d.scaleFactor), height: Math.round(d.size.height * d.scaleFactor) };
    const sources = await Promise.race([desktopCapturer.getSources({ types: ['screen'], thumbnailSize: size }), new Promise((_, no) => setTimeout(() => no(new Error('slow')), 1500))]);
    const src = sources.find(x => String(x.display_id) === String(d.id)) || sources[0];
    return src && !src.thumbnail.isEmpty() ? 'data:image/jpeg;base64,' + src.thumbnail.toJPEG(90).toString('base64') : null;
  } catch (e) { return null; }
}
async function overlayOpen() {
  if (overlayOpening && Date.now() - overlayOpenedAt < 4000) return;
  if (overlayWin && !overlayWin.isDestroyed() && overlayWin.isVisible()) { overlayClose(); return; }
  overlayOpening = true; overlayOpenedAt = Date.now();
  log('opening the quick menu');
  try {
    const fg = await within(native.call('fg.get', {}, 1500), 1600, null);
    overlayGame = fg && fg.pid && fg.pid !== process.pid && !/^(NSXL|electron|explorer|ShellHost|ShellExperienceHost)$/i.test(fg.name || '') ? fg : null;
    const { screen } = require('electron');
    // the screen the game (or NSXL) is on, never wherever the mouse was left, which may be the second screen
    let d = null;
    if (fg && fg.w > 0 && fg.pid !== process.pid) {
      const c = screen.screenToDipPoint({ x: fg.x + fg.w / 2, y: fg.y + fg.h / 2 });
      d = screen.getDisplayNearestPoint({ x: Math.round(c.x), y: Math.round(c.y) });
    }
    if (!d && win && !win.isDestroyed()) d = screen.getDisplayMatching(win.getBounds());
    if (!d) d = screen.getPrimaryDisplay();
    log('quick menu on screen ' + (screen.getAllDisplays().findIndex(x => x.id === d.id) + 1) + ' of ' + screen.getAllDisplays().length + (fg ? ' (in front: ' + (fg.name || '?') + ')' : ''));
    const backdrop = await overlayBackdrop(d);
    if (!overlayWin || overlayWin.isDestroyed()) {
      // an ordinary (not see-through) window: those always draw, over games too
      overlayWin = new BrowserWindow({
        ...d.bounds, show: false, frame: false, transparent: false, resizable: false, movable: false, skipTaskbar: true, alwaysOnTop: true, hasShadow: false,
        backgroundColor: '#000000', title: 'NSXL quick menu', roundedCorners: false, thickFrame: false,
        webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false },
      });
      overlayWin.setAlwaysOnTop(true, 'screen-saver');
      overlayWin.on('focus', () => { overlayFocused = true; });
      overlayWin.on('blur', () => {
        if (!overlayFocused || !overlayWin || !overlayWin.isVisible()) return;
        // a game often grabs focus straight back as the menu opens: take it again once instead of closing
        // (for the first moments it never closes: if the game won't let go, the check in grabFocus minimises it)
        if (Date.now() - overlayOpenedAt < 2500) { if (!overlayRegrab) { overlayRegrab = true; setTimeout(() => { if (overlayWin && overlayWin.isVisible()) grabFocus(); }, 80); } return; }
        overlayClose(false);
      });
      native.call('win.flat', { hwnd: hwndOf(overlayWin) }, 2000).catch(() => {});
      await within(overlayWin.loadURL('nsxl://app/overlay.html').catch(e => log('quick menu page failed to load: ' + e.message)), 5000);
    }
    overlayWin.setBounds(d.bounds);
    const ready = await within(overlayWin.webContents.executeJavaScript('window.helperPs=' + psPads + ';window.setBackdrop&&setBackdrop(' + JSON.stringify(backdrop) + ')'), 900, false);
    if (!ready) log('quick menu backdrop was slow; showing anyway');
    await new Promise(r => setTimeout(r, 16));
    overlayOpenedAt = Date.now(); overlayRegrab = false;
    overlayWin.show(); overlayWin.moveTop(); grabFocus();
    overlayWin.webContents.executeJavaScript('window.onOpen&&window.onOpen()').catch(() => {});
    log('quick menu shown' + (backdrop ? '' : ' (no backdrop picture)'));
  } catch (e) { log('quick menu failed: ' + e.message); }
  finally { overlayOpening = false; }
}
function grabFocus() {
  overlayFocused = false;
  overlayWin.focus();
  const h = overlayWin.getNativeWindowHandle();
  const hwnd = h.length >= 8 ? Number(h.readBigUInt64LE(0)) : h.readUInt32LE(0);
  if (!overlayWin.isFocused()) native.call('fg.set', { hwnd }, 3000).catch(() => {});
  // if Windows still won't hand over focus, the menu reads the controller anyway
  setTimeout(() => { if (overlayWin && !overlayWin.isDestroyed()) overlayWin.webContents.executeJavaScript(`window.forcePads=${!overlayWin.isFocused()}`).catch(() => {}); }, 600);
  if (grabFocus.checking) return;
  grabFocus.checking = true;
  setTimeout(() => {
    grabFocus.checking = false;
    if (!overlayWin || overlayWin.isDestroyed() || !overlayWin.isVisible()) return;
    native.call('fg.get', {}, 2000).then(fg => {
      if (!overlayWin || overlayWin.isDestroyed() || !overlayWin.isVisible()) return;
      if (fg && fg.pid === process.pid) { log('quick menu is in front'); return; }
      log('quick menu was kept behind ' + (fg ? fg.name || 'a window' : 'something') + ': minimising the game so it shows');
      const shell = x => x && /^(explorer|ShellHost|ShellExperienceHost|StartMenuExperienceHost|SearchHost|XboxPcApp|GameBar)$/i.test(x.name || '');
      const g = overlayGame && overlayGame.hwnd && !shell(overlayGame) ? overlayGame.hwnd : fg && !shell(fg) ? fg.hwnd : 0;
      if (g) { overlayGame = overlayGame || fg; overlayGame.minimised = true; native.call('win.min', { hwnd: g }, 2000).catch(() => {}); }
      setTimeout(() => { if (overlayWin && !overlayWin.isDestroyed() && overlayWin.isVisible()) { overlayWin.setAlwaysOnTop(true, 'screen-saver'); overlayWin.show(); overlayWin.focus(); native.call('fg.set', { hwnd: hwndOf(overlayWin) }, 2000).catch(() => {}); } }, 350);
    }).catch(() => {});
  }, 700);
}
let overlayOpenedAt = 0, overlayRegrab = false;
const hwndOf = w => { const h = w.getNativeWindowHandle(); return h.length >= 8 ? Number(h.readBigUInt64LE(0)) : h.readUInt32LE(0); };
/** Hides the quick menu and hands focus back to whatever was in front (the game, or NSXL), so the controller keeps working there. */
let overlayClosing = false;
function overlayClose(refocus = true, animate = true) {
  overlayFocused = false;
  if (!overlayWin || overlayWin.isDestroyed() || !overlayWin.isVisible() || overlayClosing) return;
  if (animate) {
    overlayClosing = true;
    within(overlayWin.webContents.executeJavaScript('window.animateOut?animateOut():0'), 300, 0).then(ms => {
      setTimeout(() => { overlayClosing = false; overlayClose(refocus, false); }, Math.min(Number(ms) || 0, 400));
    });
    return;
  }
  overlayWin.hide();
  if (!refocus) return;
  if (overlayGame && overlayGame.hwnd) native.call('fg.set', { hwnd: overlayGame.hwnd }, 3000).catch(() => {});
  else focusHome();
}
/** Puts NSXL in front with real keyboard and controller focus, even when Windows is reluctant. */
function focusHome() {
  if (!win || win.isDestroyed()) return;
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
  setTimeout(() => { if (win && !win.isDestroyed() && !win.isFocused()) native.call('fg.set', { hwnd: hwndOf(win) }, 3000).catch(() => {}); }, 120);
}
// Windows sometimes leaves no window in front (after a menu or cover hides, or a game quits). NSXL then
// looks focused but isn't, and stops hearing the controller. Notice that and take focus back.
let padsAnyway = false, focusTries = 0, focusPause = 0;
setInterval(() => {
  if (!win || win.isDestroyed() || !native.running) return;
  if (win.isFocused() || !win.isVisible() || win.isMinimized() || (overlayWin && !overlayWin.isDestroyed() && overlayWin.isVisible())) { setPadsAnyway(false); fgOurs = win.isFocused(); return; }
  native.call('fg.get', {}, 2000).then(fg => {
    if (!fg || !win || win.isDestroyed()) return;
    fgOurs = fg.pid === process.pid;
    if (win.isFocused()) return;
    const nobody = !fg.hwnd || (fg.pid === process.pid && fg.hwnd !== hwndOf(win));   // empty foreground, or one of NSXL's hidden windows
    const desktop = /^explorer$/i.test(fg.name || '') && (!fg.title || fg.title === 'Program Manager');
    const gameOn = !!launchWatch || (session && Date.now() - session.start < 90000);   // a game is starting: leave the foreground alone
    if (nobody && !gameOn && Date.now() > focusPause) {
      if (++focusTries > 3) { focusTries = 0; focusPause = Date.now() + 60000; log("couldn't take focus back, leaving it for a minute"); }
      else { log('focus was lost to ' + (fg.hwnd ? 'a hidden NSXL window' : 'nothing') + ': taking it back'); focusHome(); }
    } else if (!nobody) focusTries = 0;
    setPadsAnyway(!gameOn && (nobody || desktop));
  }).catch(() => {});
}, 1500);
let fgOurs = true;
// PlayStation controllers as the helper reads them. Once a game or Steam switches one to its full reports,
// Windows stops passing it to NSXL's page, so the helper's copy drives NSXL's menus whenever NSXL is in front.
const psState = {};
native.on('psstate', m => {
  if (!m || !m.pad) return;
  if (m.s) psState[m.pad] = m.s; else delete psState[m.pad];
  let s = 0; for (const k in psState) s |= psState[k];
  const ov = overlayWin && !overlayWin.isDestroyed() && overlayWin.isVisible();
  const front = !ov && win && !win.isDestroyed() && !win.isMinimized() && (win.isFocused() || fgOurs || padsAnyway);
  if (ov) overlayWin.webContents.executeJavaScript('window.helperPs=' + psPads + ';window.onHelperPad&&onHelperPad(' + s + ')').catch(() => {});
  js('window.helperPs=' + psPads + ';window.onHelperPad&&onHelperPad(' + (front ? s : 0) + ')');
});
function setPadsAnyway(v) { if (v === padsAnyway) return; padsAnyway = v; js('window.padsAnyway=' + v); }
function bringHome() {
  overlayClose(false);
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show(); win.setAlwaysOnTop(true); win.focus(); win.setAlwaysOnTop(false);
}
let lastCombo = 0, lastComboAt = 0;
/** A quick-menu button press, from the helper (Xbox and PlayStation pads) or from NSXL's own controller reading. */
function padCombo(combo, from) {
  const want = prefs.get('menuButton', 'viewmenu');
  lastCombo = combo; lastComboAt = Date.now();
  const hit = combo === want || (want === 'both' && (combo === 'guide' || combo === 'viewmenu'));
  log('button ' + combo + ' from ' + from + (hit ? ' → quick menu' : ' (quick menu is set to ' + want + ')'));
  if (!hit) return;
  if (Date.now() - (padCombo.at || 0) < 700) return;   // the same press seen twice
  padCombo.at = Date.now();
  overlayOpen();
}
native.on('pad', m => padCombo(m.combo, m.ps ? 'PlayStation controller' : 'Xbox controller'));
let otherMusic = false;   // another app is playing (Spotify, a browser…): the page pauses its ambient music
native.on('media.playing', m => { otherMusic = !!(m && m.playing); js('window.onOtherMusic&&onOtherMusic(' + otherMusic + ')'); });
native.on('pads', m => { const n = (m && m.count) || 0; if (n !== xinputPads) log('Xbox-style controllers: ' + n); xinputPads = n; js('window.updateStatus&&updateStatus()'); });
let psPads = 0;   // PlayStation controllers NSXL reads directly (no Steam or DS4Windows needed)
native.on('pspads', m => {
  const n = (m && m.count) || 0; if (n !== psPads) log('PlayStation controllers read directly: ' + n); psPads = n;
  js('window.helperPs=' + n + ';window.updateStatus&&updateStatus()');
  if (overlayWin && !overlayWin.isDestroyed()) overlayWin.webContents.executeJavaScript('window.helperPs=' + n).catch(() => {});
});

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
let fseLaunched = process.argv.includes('--fse');   // Windows opened NSXL as its Xbox mode home app
const xboxModeNow = () => fseLaunched;

// Apps that open alongside NSXL in Xbox mode: iCUE (found automatically) and any you add
const ICUE_PATHS = [
  'C:\\Program Files\\Corsair\\Corsair iCUE5 Software\\iCUE.exe',
  'C:\\Program Files\\Corsair\\CORSAIR iCUE 5 Software\\iCUE.exe',
  'C:\\Program Files\\Corsair\\CORSAIR iCUE 4 Software\\iCUE.exe',
  'C:\\Program Files (x86)\\Corsair\\CORSAIR iCUE Software\\iCUE.exe',
];
function icuePath() {
  for (const f of ICUE_PATHS) if (fs.existsSync(f)) return f;
  for (const root of ['C:\\Program Files\\Corsair', 'C:\\Program Files (x86)\\Corsair']) {
    let dirs = []; try { dirs = fs.readdirSync(root); } catch (e) { continue; }
    for (const d of dirs) { const f = path.join(root, d, 'iCUE.exe'); if (fs.existsSync(f)) return f; }
  }
  return null;
}
function fseAppList() {
  const list = [], icue = icuePath();
  if (icue && prefs.get('fseIcue', true)) list.push({ exe: icue, args: ['--autorun'] });   // --autorun: starts in the tray
  for (const f of prefs.get('fseApps', []) || []) if (fs.existsSync(f)) list.push({ exe: f, args: [] });
  return list;
}
async function fseApps() {
  for (const a of fseAppList()) {
    const name = path.basename(a.exe);
    const running = await new Promise(r => exec('tasklist /FI "IMAGENAME eq ' + name + '" /NH', { windowsHide: true }, (e, out) => r(!e && String(out).toLowerCase().includes(name.toLowerCase()))));
    if (running) { log(path.basename(a.exe) + ' is already open'); continue; }
    const go = args => new Promise(r => exec('start "" /d "' + path.dirname(a.exe) + '" "' + a.exe + '"' + (args.length ? ' ' + args.join(' ') : ''), { windowsHide: true }, e => r(e)));
    const isUp = () => new Promise(r => exec('tasklist /FI "IMAGENAME eq ' + name + '" /NH', { windowsHide: true }, (e, out) => r(!e && String(out).toLowerCase().includes(name.toLowerCase()))));
    let err = await go(a.args);
    await new Promise(r => setTimeout(r, 8000));
    if (!(await isUp()) && a.args.length) { log('Xbox mode: ' + name + " didn't start with " + a.args.join(' ') + ', trying again plainly'); err = await go([]); await new Promise(r => setTimeout(r, 8000)); }
    log('Xbox mode: ' + name + ((await isUp()) ? ' is running' : " didn't start" + (err ? ' (' + err.message.trim() + ')' : '')));
  }
}
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
  getApps: () => { if (!apps.length) scan(); return JSON.stringify(apps.map(a => ({ pkg: a.pkg, label: a.label, game: a.game, installed: a.installed, source: a.source, cover: fs.existsSync(coverFile(a.pkg)) || !!coverVideo(a.pkg) || !!coverAnim(a.pkg), coverVideo: !!coverVideo(a.pkg) }))); },
  getStatus: () => JSON.stringify({ net: net.isOnline() ? 'wifi' : 'none', bars: 3, time24: false, battery: battery.level, charging: battery.charging, controllers: [] }),
  reportBattery: (level, charging) => { battery = { level, charging: !!charging }; },

  launch: (pkg) => {
    const a = byPkg[pkg];
    if (!a) { toast("Can't find that game any more"); return; }
    try {
      const direct = (a.source === 'steam' || a.source === 'epic') && (prefs.get('directExe', {}) || {})[pkg];
      if (direct && fs.existsSync(direct)) {
        // straight to the game's .exe, past its own launcher; a Steam game is told its id so it uses the (quietly running) Steam without restarting through it
        const env = a.source === 'steam' ? Object.assign({}, process.env, { SteamAppId: String(a.appid), SteamGameId: String(a.appid) }) : process.env;
        const child = spawn(direct, splitArgs((prefs.get('directArgs', {}) || {})[pkg] || ''), { cwd: path.dirname(direct), detached: true, stdio: 'ignore', env });
        log('launching ' + a.label + ' directly: ' + direct);
        child.on('error', () => toast("Couldn't start " + a.label));
        child.unref();
      } else if (a.source === 'steam') shell.openExternal('steam://rungameid/' + a.appid);
      else if (a.source === 'epic') shell.openExternal(lib.epicLaunchUrl(a));
      else if (a.entry.url) shell.openExternal(a.entry.url);
      else {
        const child = spawn(a.entry.target, a.entry.args ? [a.entry.args] : [], { cwd: a.entry.cwd || path.dirname(a.entry.target), detached: true, stdio: 'ignore', windowsVerbatimArguments: true });
        child.on('error', () => toast("Couldn't start " + a.label));
        child.unref();
      }
      playStart(pkg);
      launchCover();
    } catch (e) { toast("Couldn't start " + a.label); }
  },
  launchCancel: () => { if (launchWatch) launchCoverEnd(false); },

  // Steam options
  steamOpts: () => JSON.stringify({ found: !!steamExe(), quiet: prefs.get('steamQuiet', true), cover: prefs.get('steamCover', true) }),
  setSteamOpt: (k, v) => { if (k === 'quiet' || k === 'cover') prefs.set(k === 'quiet' ? 'steamQuiet' : 'steamCover', !!v); if (k === 'quiet' && v) steamQuietStart(); },
  directGet: (pkg) => { const f = (prefs.get('directExe', {}) || {})[pkg]; return f && fs.existsSync(f) ? f : ''; },
  directArgs: (pkg) => (prefs.get('directArgs', {}) || {})[pkg] || '',
  setDirectArgs: (pkg, v) => { const all = prefs.get('directArgs', {}) || {}; if (String(v || '').trim()) all[pkg] = String(v).trim(); else delete all[pkg]; prefs.set('directArgs', all); },
  /** The game's real .exe, guessed: the biggest program in its folder that isn't a launcher, installer or helper. */
  directSuggest: (pkg) => {
    const a = byPkg[pkg]; if (!a || !a.dir || !fs.existsSync(a.dir)) return '[]';
    const skip = /launch|setup|install|unins|redist|vc_?redist|dxsetup|crash|report|helper|update|patch|config|settings|anticheat|easyanticheat|battleye|eac|ue4prereq|prereq|cefprocess|webhelper|uplay|ubisoft|origin|eadesktop|socialclub|rockstar|bink|benchmark/i;
    const found = [];
    const walk = (dir, depth) => {
      let list; try { list = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
      for (const d of list) {
        const f = path.join(dir, d.name);
        if (d.isDirectory()) { if (depth < 4 && !/^(_commonredist|redist|directx|vcredist|support|tools|engine\\extras)$/i.test(d.name)) walk(f, depth + 1); }
        else if (/\.exe$/i.test(d.name)) { let size = 0; try { size = fs.statSync(f).size; } catch (e) { } found.push({ path: f, name: d.name, rel: path.relative(a.dir, f), size, launcher: skip.test(d.name) }); }
      }
    };
    walk(a.dir, 0);
    found.sort((x, y) => (x.launcher - y.launcher) || (y.size - x.size));
    return JSON.stringify(found.slice(0, 8));
  },
  directSet: (pkg, f) => { if (!f || !fs.existsSync(f)) return false; const all = prefs.get('directExe', {}) || {}; all[pkg] = f; prefs.set('directExe', all); return true; },
  directPick: async (pkg) => {
    const a = byPkg[pkg]; if (!a) return;
    const r = await dialog.showOpenDialog(win, { title: 'Choose ' + a.label + "'s .exe", defaultPath: a.dir || undefined, properties: ['openFile'], filters: [{ name: 'Programs', extensions: ['exe'] }] });
    if (r.canceled) return;
    const all = prefs.get('directExe', {}) || {}; all[pkg] = r.filePaths[0]; prefs.set('directExe', all);
    js('window.onDirectSet&&window.onDirectSet(' + JSON.stringify(pkg) + ',true)');
  },
  directClear: (pkg) => { const all = prefs.get('directExe', {}) || {}; delete all[pkg]; prefs.set('directExe', all); },

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
    const f = await pickMedia('Choose a picture or video for this tile');
    if (!f) return;
    const say = (ok, msg) => js('window.onImagePicked(' + JSON.stringify(isVideo(f) ? 'coverVideo' : 'cover') + ',' + JSON.stringify(pkg) + ',' + ok + (msg ? ',' + JSON.stringify(msg) : '') + ')');
    try {
      fs.mkdirSync(COVERS, { recursive: true });
      if (isVideo(f)) {
        if (fs.statSync(f).size > 500 * 1024 * 1024) return say(false, 'That video is over 500 MB. Pick a shorter clip.');
        dropCoverVideo(pkg);
        fs.copyFileSync(f, path.join(COVERS, safe(pkg) + '.video' + path.extname(f).toLowerCase()));
      } else { fs.writeFileSync(coverFile(pkg), squareJpeg(f, 720)); dropCoverVideo(pkg); dropCoverAnim(pkg); }
      say(true);
    } catch (e) { say(false); }
  },
  removeCover: (pkg) => { fs.rmSync(coverFile(pkg), { force: true }); dropCoverVideo(pkg); dropCoverAnim(pkg); },

  // NSXL Companion
  companionState: () => JSON.stringify({ on: prefs.get('companion', false), running: companion.running, url: companion.running ? companion.url() : '', code: companion.running ? companion.pairCode() : '', devices: Object.keys(companion.devices()).length, qr: !!qrLib }),
  setCompanion: (on) => { prefs.set('companion', !!on); companionSync().then(() => js('window.refreshOverlays&&refreshOverlays()')); },
  companionForget: () => companion.forgetAll(),
  companionQR: async (id) => {
    try {
      if (!qrLib) throw new Error('QR codes aren\'t available in this build');
      done(id, await qrLib.toDataURL(companion.url() + '/?code=' + companion.pairCode(), { margin: 1, width: 480, color: { dark: '#000000', light: '#ffffff' } }));
    } catch (e) { fail(id, e); }
  },

  // second screen
  secondState: () => {
    const { screen } = require('electron');
    return JSON.stringify({ screens: screen.getAllDisplays().length, mode: prefs.get('secondScreen', 'off'), logo: prefs.get('secondLogo', true), sgdb: prefs.get('secondSgdb', true), open: !!(secondWin && !secondWin.isDestroyed()) });
  },
  setSecond: (k, v) => {
    if (!['secondScreen', 'secondLogo', 'secondSgdb'].includes(k)) return;
    prefs.set(k, v); if (k === 'secondLogo' && v) secondTried.clear();
    secondSync(); secondPush();
  },
  secondShow: (pkg, label, key) => { second = { pkg, label: label || '', key: key || second.key }; secondPush(); },
  secondHas: (pkg) => !!secondOwn(pkg),
  secondPick: async (pkg) => {
    const f = await pickMedia('Choose a picture or video for the second screen');
    if (!f) return;
    try {
      if (fs.statSync(f).size > 1024 * 1024 * 1024) { js('window.onImagePicked("second",' + JSON.stringify(pkg) + ',false,"That file is over 1 GB.")'); return; }
      fs.mkdirSync(SECOND, { recursive: true }); dropSecondOwn(pkg);
      fs.copyFileSync(f, path.join(SECOND, safe(pkg) + '.own' + path.extname(f).toLowerCase()));
      js('window.onImagePicked("second",' + JSON.stringify(pkg) + ',true)'); secondPush();
    } catch (e) { js('window.onImagePicked("second",' + JSON.stringify(pkg) + ',false)'); }
  },
  secondRemove: (pkg) => { dropSecondOwn(pkg); fs.rmSync(heroFile(pkg), { force: true }); secondTried.delete(pkg); secondPush(); },
  sgdbHeroes: async (id, key, gameId) => {
    try {
      const live = await sgdbList(key, '/heroes/game/' + gameId + '?types=animated', true);
      const still = await sgdbList(key, '/heroes/game/' + gameId + '?types=static');
      done(id, live.concat(still).slice(0, 60));
    } catch (e) { fail(id, e); }
  },
  sgdbLogos: async (id, key, gameId) => {
    try {
      const live = await sgdbList(key, '/logos/game/' + gameId + '?types=animated', true);
      const still = await sgdbList(key, '/logos/game/' + gameId + '?types=static');
      done(id, live.concat(still).slice(0, 60));
    } catch (e) { fail(id, e); }
  },
  secondLogoApply: async (id, pkg, url) => {
    try {
      const r = await net.fetch(url);
      if (!r.ok) throw new Error('Download failed (' + r.status + ')');
      fs.mkdirSync(SECOND, { recursive: true }); dropOwnLogo(pkg);
      fs.writeFileSync(path.join(SECOND, safe(pkg) + '.ownlogo' + imgExt(r.headers.get('content-type') || '', url)), Buffer.from(await r.arrayBuffer()));
      done(id, true); secondPush();
    } catch (e) { fail(id, e); }
  },
  secondLogoPick: async (pkg) => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose a logo (a PNG with a see-through background looks best)', properties: ['openFile'], filters: [{ name: 'Pictures', extensions: ['png', 'webp', 'gif', 'jpg', 'jpeg', 'svg'] }] });
    if (r.canceled) return;
    try {
      fs.mkdirSync(SECOND, { recursive: true }); dropOwnLogo(pkg);
      fs.copyFileSync(r.filePaths[0], path.join(SECOND, safe(pkg) + '.ownlogo' + path.extname(r.filePaths[0]).toLowerCase()));
      js('window.onImagePicked("secondLogo",' + JSON.stringify(pkg) + ',true)'); secondPush();
    } catch (e) { js('window.onImagePicked("secondLogo",' + JSON.stringify(pkg) + ',false)'); }
  },
  secondLogoRemove: (pkg) => { dropOwnLogo(pkg); fs.rmSync(logoFile(pkg), { force: true }); secondTried.delete(pkg); secondPush(); },
  secondLogoHas: (pkg) => !!ownLogo(pkg),
  secondApply: async (id, pkg, url) => {
    try {
      const r = await net.fetch(url);
      if (!r.ok) throw new Error('Download failed (' + r.status + ')');
      const type = r.headers.get('content-type') || '', ext = /webm/.test(type) || /\.webm(\?|$)/i.test(url) ? '.webm' : imgExt(type, url);
      fs.mkdirSync(SECOND, { recursive: true }); dropSecondOwn(pkg);
      fs.writeFileSync(path.join(SECOND, safe(pkg) + '.own' + ext), Buffer.from(await r.arrayBuffer()));
      done(id, true); secondPush();
    } catch (e) { fail(id, e); }
  },
  // the phone asks for notification access to see other apps' music; Windows needs nothing
  mediaAccess: () => true,
  openMediaAccess: () => {},
  otherMusic: () => otherMusic,
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
      // live (animated) tiles first, then square pictures, then any other shape
      const live = await sgdbList(key, '/grids/game/' + gameId + '?types=animated', true);
      let still = await sgdbList(key, '/grids/game/' + gameId + '?dimensions=512x512,1024x1024&types=static');
      if (!still.length) still = await sgdbList(key, '/grids/game/' + gameId + '?types=static');
      done(id, live.concat(still).slice(0, 60));
    } catch (e) { fail(id, e); }
  },
  sgdbApply: async (id, pkg, url, animated) => {
    try {
      const r = await net.fetch(url);
      if (!r.ok) throw new Error('Download failed (' + r.status + ')');
      const buf = Buffer.from(await r.arrayBuffer());
      fs.mkdirSync(COVERS, { recursive: true });
      dropCoverVideo(pkg); dropCoverAnim(pkg);
      if (animated) {
        const ext = imgExt(r.headers.get('content-type') || '', url);
        if (ext === '.jpg') throw new Error("That one isn't animated");
        fs.writeFileSync(path.join(COVERS, safe(pkg) + '.anim' + ext), buf);   // kept as it came, so it keeps moving
      } else fs.writeFileSync(coverFile(pkg), squareJpeg(nativeImage.createFromBuffer(buf), 720));
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
    try { fs.mkdirSync(COVERS, { recursive: true }); fs.writeFileSync(coverFile(pkg), squareJpeg(shots[shot], 720)); dropCoverVideo(pkg); dropCoverAnim(pkg); done(id, true); }
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
    overlayClose(false);
    const a = session && byPkg[session.pkg];
    log('closing ' + (a ? a.label : overlayGame ? overlayGame.name : 'the game'));
    // the game's own folder catches every process it started; the window in front is the fallback
    const byDir = a && a.dir ? native.call('proc.killdir', { dir: a.dir }, 8000).catch(() => 0) : Promise.resolve(0);
    byDir.then(n => n ? n : (overlayGame && overlayGame.pid ? native.call('proc.kill', { pid: overlayGame.pid }).then(() => 1) : 0))
      .then(n => { if (n) { overlayGame = null; if (session) session.left = true; playFinish(); bringHome(); toast((a ? a.label : 'The game') + ' closed'); } else toast("Couldn't find the game to close"); })
      .catch(e => toast("Couldn't close it: " + e.message));
  },
  runningGame: () => (session && byPkg[session.pkg] ? session.pkg : ''),
  padCombo: (c) => { if (c === 'guide' || c === 'viewmenu') padCombo(c, 'NSXL'); },
  takeFocus: () => {
    // not while the quick menu is opening or open: that press was for the menu
    if (overlayOpening || Date.now() - overlayOpenedAt < 3000 || (overlayWin && !overlayWin.isDestroyed() && overlayWin.isVisible())) return;
    log('controller pressed while NSXL was behind: bringing it forward'); focusHome();
  },
  menuStatus: () => JSON.stringify({ helper: native.running, restarts: native.restarts, lastCombo, ago: lastComboAt ? Math.round((Date.now() - lastComboAt) / 1000) : -1 }),
  openLog: () => { if (fs.existsSync(LOG)) shell.openPath(LOG); else toast('Nothing logged yet'); },
  gameName: () => (overlayGame ? (overlayGame.title || overlayGame.name || '') : ''),
  openPanel: (kind) => { overlayClose(false); H.openSettings(kind === 'volume' ? 'sound' : kind); },
  openControllersHome: () => { bringHome(); js('window.openControllers&&openControllers()'); },
  openMusicHome: () => { bringHome(); js('window.openMusic&&openMusic()'); },
  sleep: () => H.power('sleep'),
  hideHandle: () => {}, stopOverlay: () => {}, requestWriteSettings: () => {},
  menuButton: () => prefs.get('menuButton', 'viewmenu'),
  setMenuButton: (v) => prefs.set('menuButton', v),
  menuInfo: () => JSON.stringify({ shortcut: menuShortcut, xinput: xinputPads, ps: psPads }),

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
  inXboxMode: () => xboxModeNow(),
  xboxMode: () => {
    const entering = !xboxModeNow();
    log(entering ? 'switching to Xbox mode' : 'switching to the Windows desktop');
    overlayClose(false);
    // Windows then reopens its home app (NSXL, if it's set up), which also starts the Xbox mode apps
    native.call('keys.xboxmode', {}, 3000).then(() => { fseLaunched = entering; }).catch(e => toast("Couldn't switch: " + e.message));
  },
  fseAppsState: () => JSON.stringify({ icue: !!icuePath(), icueOn: prefs.get('fseIcue', true), apps: (prefs.get('fseApps', []) || []).map(f => ({ f, name: path.basename(f, '.exe') })) }),
  setFseIcue: (v) => prefs.set('fseIcue', !!v),
  fseAppAdd: async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose an app to open with Xbox mode', properties: ['openFile'], filters: [{ name: 'Programs', extensions: ['exe'] }] });
    if (r.canceled) return;
    const l = prefs.get('fseApps', []) || []; if (!l.includes(r.filePaths[0])) l.push(r.filePaths[0]); prefs.set('fseApps', l);
    js('window.onFseApps&&onFseApps()');
  },
  fseAppRemove: (f) => prefs.set('fseApps', (prefs.get('fseApps', []) || []).filter(x => x !== f)),

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

/** One page of SteamGridDB results as picker items; `live` marks animated ones. */
async function sgdbList(key, p, live) {
  let j;
  try { j = await sgdb(key, p); } catch (e) { if (live) return []; throw e; }   // no animated ones is fine
  return (j.data || []).filter(g => g.url).slice(0, 40).map(g => ({ url: g.url, thumb: live ? g.url : (g.thumb || g.url), width: g.width, height: g.height, style: g.style || '', animated: !!live || /animated/i.test(g.style || ''), video: /\.(webm|mp4)(\?|$)/i.test(g.url) }));
}
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
    const anim = coverAnim(pkg);
    if (anim) return serveFile(anim, req);
    if (fs.existsSync(coverFile(pkg))) return serveFile(coverFile(pkg), req);
    const a = byPkg[pkg];
    if (a && a.source === 'steam') { const f = lib.steamImage(a.appid, 'capsule') || lib.steamImage(a.appid, 'header'); if (f) return serveFile(f, req); }
    return notFound();
  }
  if (p.startsWith('/artvideo/')) { const f = coverVideo(p.slice(10)); return f ? serveFile(f, req) : notFound(); }
  if (p.startsWith('/second/')) { const f = secondOwn(p.slice(8)); return f ? serveFile(f, req) : notFound(); }
  if (p.startsWith('/hero/') || p.startsWith('/logo/')) {
    const hero = p.startsWith('/hero/'), pkg = p.slice(6), f = hero ? heroFile(pkg) : logoFile(pkg);
    const mine = !hero && ownLogo(pkg);
    if (mine) return serveFile(mine, req);
    if (fs.existsSync(f)) return bytes(fs.readFileSync(f), sniffImage(f));
    const a = byPkg[pkg], s = a && a.source === 'steam' && lib.steamImage(a.appid, hero ? 'hero' : 'logo');
    return s ? serveFile(s, req) : notFound();
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
  win.on('blur', () => { fgOurs = false; });   // until the focus check sees NSXL in front again
  win.loadURL('nsxl://app/index.html');

  // keep the launcher on its own page; links open in your browser
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('nsxl://')) { e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); } });
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type === 'keyDown' && input.key === 'F11') { e.preventDefault(); H.setFullscreen(!win.isFullScreen()); }
  });

  // games take the focus; coming back to NSXL is the "back from the game" moment
  win.on('blur', () => { if (session) session.left = true; js('window.onPauseApp&&window.onPauseApp()'); });
  win.on('focus', () => { playCheck(); js('window.onResumeApp&&window.onResumeApp()'); });
  win.on('closed', () => { win = null; app.quit(); });
}

app.on('second-instance', (e, argv) => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } if ((argv || []).includes('--fse')) { fseLaunched = true; fseApps(); } });
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
  if (process.argv.includes('--fse')) setTimeout(fseApps, 1500);
  setTimeout(steamQuietStart, 2500);
  companionSync();
  { const { screen } = require('electron'); ['display-added', 'display-removed', 'display-metrics-changed'].forEach(ev => screen.on(ev, () => setTimeout(secondSync, 400))); }
  setTimeout(secondSync, 1500);
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
