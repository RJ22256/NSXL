// NSXL Companion: a small website NSXL serves on your home network, so a phone or tablet
// browser can see your library, start games and work as a remote. Off unless you turn it on.
//
// Safety: it only answers devices on your local network (private addresses), and every request
// apart from the page itself needs a token that a device only gets by entering the pairing code
// shown on the PC. Tokens are stored hashed; "Forget all devices" wipes them.

const http = require('http');
const crypto = require('crypto');
const os = require('os');
const fs = require('fs');
const path = require('path');

const sha = s => crypto.createHash('sha256').update(String(s)).digest('hex');
const PRIVATE = [/^10\./, /^192\.168\./, /^172\.(1[6-9]|2\d|3[01])\./, /^127\./, /^169\.254\./, /^::1$/, /^f[cd][0-9a-f]{2}:/i, /^fe80:/i];
const isLocal = a => { a = String(a || '').replace(/^::ffff:/, ''); return PRIVATE.some(re => re.test(a)); };
const TYPES = { '.html': 'text/html; charset=utf-8', '.ttf': 'font/ttf', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json' };

class Companion {
  /**
   * o.prefs        settings store (get/set)
   * o.page         path of the companion web page
   * o.files        folder the page's fonts are served from
   * o.state()      async: what the phone shows
   * o.art(pkg)     async: a fetch Response with the game's tile art
   * o.act(a, args) async: do something on the PC
   * o.onPaired(n)  a device just paired
   */
  constructor(o) {
    this.o = o; this.server = null; this.port = 0;
    this.code = ''; this.codeAt = 0; this.tries = new Map(); this.streams = new Set();
    this.timer = null; this.last = '';
  }
  get running() { return !!this.server; }
  addresses() {
    return Object.values(os.networkInterfaces()).flat()
      .filter(i => i && i.family === 'IPv4' && !i.internal && isLocal(i.address)).map(i => i.address);
  }
  url() { const a = this.addresses()[0]; return a ? 'http://' + a + ':' + this.port : ''; }
  /** The current six-digit pairing code; a fresh one every 10 minutes and after each pairing. */
  pairCode() {
    if (!this.code || Date.now() - this.codeAt > 10 * 60e3) { this.code = String(crypto.randomInt(0, 1e6)).padStart(6, '0'); this.codeAt = Date.now(); }
    return this.code;
  }
  devices() { return this.o.prefs.get('companionDevices', {}) || {}; }
  forgetAll() { this.o.prefs.set('companionDevices', {}); this.streams.forEach(s => s.end()); this.streams.clear(); }

  start(port) {
    if (this.server) return Promise.resolve(this.port);
    this.port = port || 8974;
    return new Promise((resolve, reject) => {
      const srv = http.createServer((req, res) => this.route(req, res).catch(e => this.json(res, 500, { error: String(e && e.message || e) })));
      srv.on('error', e => { this.server = null; reject(e); });
      srv.listen(this.port, '0.0.0.0', () => { this.server = srv; this.timer = setInterval(() => this.tick(), 2000); resolve(this.port); });
    });
  }
  stop() {
    clearInterval(this.timer); this.timer = null;
    this.streams.forEach(s => s.end()); this.streams.clear();
    if (this.server) { this.server.close(); this.server = null; }
  }

  json(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(body));
  }
  body(req) {
    return new Promise((resolve, reject) => {
      let n = 0; const parts = [];
      req.on('data', c => { n += c.length; if (n > 65536) { reject(new Error('Too big')); req.destroy(); } else parts.push(c); });
      req.on('end', () => { try { resolve(parts.length ? JSON.parse(Buffer.concat(parts).toString('utf8')) : {}); } catch (e) { reject(new Error('Bad request')); } });
    });
  }
  who(req, url) {
    const t = (req.headers.authorization || '').replace(/^Bearer\s+/i, '') || url.searchParams.get('t') || '';
    if (!t) return null;
    const d = this.devices()[sha(t)];
    return d ? Object.assign({ id: sha(t) }, d) : null;
  }

  async route(req, res) {
    if (!isLocal(req.socket.remoteAddress)) { res.writeHead(403); res.end('NSXL Companion only works on your home network.'); return; }
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');

    // the page itself, and its fonts
    if (req.method === 'GET' && (p === '/' || p === '/index.html')) return this.file(res, this.o.page);
    if (req.method === 'GET' && p.startsWith('/fonts/') && /^[\w.-]+$/.test(p.slice(7))) return this.file(res, path.join(this.o.files, 'fonts', p.slice(7)));
    if (req.method === 'GET' && p === '/manifest.json') {
      return this.json(res, 200, { name: 'NSXL Companion', short_name: 'NSXL', display: 'standalone', background_color: '#000000', theme_color: '#000000', start_url: '/' });
    }

    if (p === '/api/hello') return this.json(res, 200, { pc: os.hostname(), paired: !!this.who(req, url) });
    if (p === '/api/pair' && req.method === 'POST') {
      const ip = String(req.socket.remoteAddress);
      const t = this.tries.get(ip) || { n: 0, at: Date.now() };
      if (Date.now() - t.at > 60e3) { t.n = 0; t.at = Date.now(); }
      if (t.n >= 6) return this.json(res, 429, { error: 'Too many tries. Wait a minute.' });
      const b = await this.body(req);
      if (String(b.code || '').trim() !== this.pairCode()) { t.n++; this.tries.set(ip, t); return this.json(res, 401, { error: "That code isn't right. Check NSXL › Settings › Companion." }); }
      const token = crypto.randomBytes(24).toString('base64url');
      const name = String(b.device || 'Phone').slice(0, 40);
      const all = this.devices(); all[sha(token)] = { name, at: Date.now() }; this.o.prefs.set('companionDevices', all);
      this.code = ''; this.pairCode();   // codes are single use
      this.o.onPaired && this.o.onPaired(name);
      return this.json(res, 200, { token });
    }

    const me = this.who(req, url);
    if (!me) return this.json(res, 401, { error: 'Not paired' });

    if (p === '/api/state') return this.json(res, 200, await this.o.state());
    if (p.startsWith('/api/art/')) {
      const r = await this.o.art(decodeURIComponent(p.slice(9)));
      if (!r || !r.ok) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': r.headers.get('content-type') || 'image/jpeg', 'Cache-Control': 'private, max-age=300' });
      res.end(Buffer.from(await r.arrayBuffer()));
      return;
    }
    if (p === '/api/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
      res.write('retry: 3000\n\n');
      this.streams.add(res);
      req.on('close', () => this.streams.delete(res));
      this.last = ''; this.tick();
      return;
    }
    if (p === '/api/do' && req.method === 'POST') {
      const b = await this.body(req);
      const out = await this.o.act(String(b.action || ''), Array.isArray(b.args) ? b.args : []);
      return this.json(res, 200, { ok: true, result: out === undefined ? null : out });
    }
    if (p === '/api/forget' && req.method === 'POST') {
      const all = this.devices(); delete all[me.id]; this.o.prefs.set('companionDevices', all);
      return this.json(res, 200, { ok: true });
    }
    this.json(res, 404, { error: 'Not found' });
  }

  file(res, f) {
    fs.readFile(f, (e, buf) => {
      if (e) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(f).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
      res.end(buf);
    });
  }

  /** Pushes live state (now playing, volume, the running game) to connected devices when it changes. */
  async tick() {
    if (!this.streams.size) return;
    let s;
    try { s = await this.o.state(true); } catch (e) { return; }
    const msg = JSON.stringify(s);
    if (msg === this.last) return;
    this.last = msg;
    this.streams.forEach(r => { try { r.write('data: ' + msg + '\n\n'); } catch (e) { } });
  }
}

module.exports = { Companion };
