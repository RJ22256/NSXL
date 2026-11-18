'use strict';
/*
 * Finds your games on Windows: installed Steam games, installed Epic Games titles,
 * and anything you've added yourself (an .exe, a shortcut or an internet shortcut).
 *
 * Each entry: { pkg, label, game, installed, source, ...source-specific fields }.
 * pkg ids look like "steam:440", "epic:Fortnite" or "exe:<id>".
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// ------------------------------------------------------------------ Steam

let steamRootCache;
function steamRoot() {
  if (steamRootCache !== undefined) return steamRootCache;
  steamRootCache = null;
  try {
    const out = execFileSync('reg', ['query', 'HKCU\\Software\\Valve\\Steam', '/v', 'SteamPath'], { encoding: 'utf8', windowsHide: true });
    const m = out.match(/SteamPath\s+REG_SZ\s+(.+)/);
    if (m && fs.existsSync(m[1].trim())) steamRootCache = path.normalize(m[1].trim());
  } catch (e) { /* not installed, or not Windows */ }
  if (!steamRootCache) {
    for (const p of ['C:\\Program Files (x86)\\Steam', 'C:\\Program Files\\Steam']) if (fs.existsSync(p)) { steamRootCache = p; break; }
  }
  return steamRootCache;
}

function vdfValue(text, key) {
  const m = text.match(new RegExp('"' + key + '"\\s+"([^"]*)"', 'i'));
  return m ? m[1].replace(/\\\\/g, '\\') : null;
}

const NOT_GAMES = /redistributable|steamworks common|proton|steam linux runtime|steamvr|dedicated server|\bsdk\b|soundtrack|wallpaper engine/i;

function steamGames() {
  const root = steamRoot();
  if (!root) return [];
  const libs = new Set([path.join(root, 'steamapps')]);
  try {
    const vdf = fs.readFileSync(path.join(root, 'steamapps', 'libraryfolders.vdf'), 'utf8');
    for (const m of vdf.matchAll(/"path"\s+"([^"]+)"/gi)) libs.add(path.join(m[1].replace(/\\\\/g, '\\'), 'steamapps'));
  } catch (e) { /* only the main library */ }
  const out = [];
  for (const lib of libs) {
    let files = [];
    try { files = fs.readdirSync(lib).filter(f => /^appmanifest_\d+\.acf$/i.test(f)); } catch (e) { continue; }
    for (const f of files) {
      try {
        const text = fs.readFileSync(path.join(lib, f), 'utf8');
        const appid = vdfValue(text, 'appid'), name = vdfValue(text, 'name');
        if (!appid || !name || appid === '228980' || NOT_GAMES.test(name)) continue;
        const updated = Number(vdfValue(text, 'LastUpdated') || 0) * 1000;
        const dir = vdfValue(text, 'installdir');
        out.push({ pkg: 'steam:' + appid, label: name, game: true, installed: updated, source: 'steam', appid, dir: dir ? path.join(lib, 'common', dir) : null });
      } catch (e) { /* skip broken manifest */ }
    }
  }
  return out;
}

/** Steam's own artwork for a game, from its local library cache. kind: 'capsule' | 'header' | 'icon'. */
function steamImage(appid, kind) {
  const root = steamRoot();
  if (!root) return null;
  const cache = path.join(root, 'appcache', 'librarycache');
  const want = kind === 'capsule' ? [/library_600x900(_2x)?\.jpg$/i, /library_capsule\.jpg$/i] : kind === 'header' ? [/header\.jpg$/i] : [/^[0-9a-f]{40}\.jpg$/i, /icon\.jpg$/i];
  // older Steam: <appid>_library_600x900.jpg in the cache folder
  const flat = { capsule: appid + '_library_600x900.jpg', header: appid + '_header.jpg', icon: appid + '_icon.jpg' }[kind];
  if (flat && fs.existsSync(path.join(cache, flat))) return path.join(cache, flat);
  // newer Steam: a folder per game, sometimes with a sub-folder
  const dir = path.join(cache, String(appid));
  const found = [];
  const walk = (d, depth) => {
    let list = [];
    try { list = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; }
    for (const e of list) {
      if (e.isDirectory() && depth < 2) walk(path.join(d, e.name), depth + 1);
      else if (e.isFile()) found.push(path.join(d, e.name));
    }
  };
  walk(dir, 0);
  for (const re of want) {
    const hit = found.find(f => re.test(path.basename(f)));
    if (hit) return hit;
  }
  return null;
}

// ------------------------------------------------------------------ Epic Games

function epicGames() {
  const dir = path.join(process.env.ProgramData || 'C:\\ProgramData', 'Epic', 'EpicGamesLauncher', 'Data', 'Manifests');
  let files = [];
  try { files = fs.readdirSync(dir).filter(f => f.toLowerCase().endsWith('.item')); } catch (e) { return []; }
  const out = [];
  for (const f of files) {
    try {
      const m = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      if (m.bIsIncompleteInstall) continue;
      const cats = (m.AppCategories || []).map(c => String(c).toLowerCase());
      if (cats.includes('addons') || (m.MainGameAppName && m.MainGameAppName !== m.AppName)) continue;
      if (cats.length && !cats.includes('games')) continue;
      const exe = m.InstallLocation && m.LaunchExecutable ? path.join(m.InstallLocation, m.LaunchExecutable) : null;
      let installed = 0;
      try { installed = fs.statSync(path.join(dir, f)).mtimeMs; } catch (e) { }
      out.push({
        pkg: 'epic:' + m.AppName, label: m.DisplayName || m.AppName, game: true, installed, source: 'epic',
        appName: m.AppName, ns: m.CatalogNamespace, item: m.CatalogItemId, dir: m.InstallLocation || null, exe,
      });
    } catch (e) { /* skip broken manifest */ }
  }
  return out;
}

function epicLaunchUrl(g) {
  return 'com.epicgames.launcher://apps/' + encodeURIComponent(g.ns + ':' + g.item + ':' + g.appName) + '?action=launch&silent=true';
}

// ------------------------------------------------------------------ your own games

class Custom {
  constructor(file) { this.file = file; }
  read() { try { return JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch (e) { return []; } }
  write(list) { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file, JSON.stringify(list, null, 1)); }
  add(entry) { const l = this.read(); l.push(entry); this.write(l); }
  remove(id) { this.write(this.read().filter(e => e.id !== id)); }
}

/** Turns a picked file into a library entry: .exe, .lnk (Windows shortcut) or .url (internet shortcut, e.g. steam://). */
function entryFromFile(file, shell) {
  const ext = path.extname(file).toLowerCase();
  const base = path.basename(file, ext);
  const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  if (ext === '.lnk') {
    const s = shell.readShortcutLink(file);
    return { id, label: base, target: s.target, args: s.args || '', cwd: s.cwd || path.dirname(s.target), icon: s.icon || s.target };
  }
  if (ext === '.url') {
    const text = fs.readFileSync(file, 'utf8');
    const url = (text.match(/^URL=(.+)$/im) || [])[1];
    const icon = (text.match(/^IconFile=(.+)$/im) || [])[1];
    if (!url) throw new Error('That shortcut has no address');
    return { id, label: base, url: url.trim(), icon: icon ? icon.trim() : null };
  }
  return { id, label: base, target: file, args: '', cwd: path.dirname(file), icon: file };
}

function customGames(custom) {
  return custom.read().map(e => {
    let installed = 0;
    try { installed = fs.statSync(e.target || e.icon || '').mtimeMs; } catch (x) { }
    return { pkg: 'exe:' + e.id, label: e.label, game: true, installed, source: 'custom', entry: e };
  });
}

module.exports = { steamGames, steamImage, steamRoot, epicGames, epicLaunchUrl, Custom, entryFromFile, customGames };
