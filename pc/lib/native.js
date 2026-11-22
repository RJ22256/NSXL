'use strict';
// Talks to nsxl-native.exe (pc/native/Helper): one JSON message per line each way.
const { spawn } = require('child_process');
const fs = require('fs');
const readline = require('readline');

class Native {
  constructor(exe) {
    this.exe = exe; this.proc = null; this.n = 0; this.waiting = new Map(); this.handlers = {};
    this.log = () => {}; this.stopping = false; this.restarts = 0; this.lastStart = 0;
  }
  get running() { return !!this.proc; }
  get available() { return fs.existsSync(this.exe); }
  start() {
    if (this.proc || !this.available) return;
    this.lastStart = Date.now();
    this.proc = spawn(this.exe, [], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    this.log('helper started');
    readline.createInterface({ input: this.proc.stderr }).on('line', l => this.log('helper: ' + l));
    this.proc.on('exit', code => {
      this.proc = null;
      this.log('helper stopped (' + code + ')');
      for (const [, w] of this.waiting) w.reject(new Error('Helper stopped'));
      this.waiting.clear();
      // it watches the controllers, so bring it straight back (more slowly if it keeps failing)
      if (!this.stopping) { this.restarts++; setTimeout(() => this.start(), Date.now() - this.lastStart < 10000 ? 5000 : 500); }
    });
    this.proc.on('error', e => { this.log('helper failed to start: ' + e.message); this.proc = null; });
    readline.createInterface({ input: this.proc.stdout }).on('line', line => {
      let m; try { m = JSON.parse(line); } catch (e) { return; }
      if (m.event) { (this.handlers[m.event] || []).forEach(f => { try { f(m); } catch (e) { } }); return; }
      const w = this.waiting.get(m.id); if (!w) return;
      this.waiting.delete(m.id);
      m.ok ? w.resolve(m.data) : w.reject(new Error(m.error || 'Failed'));
    });
  }
  call(cmd, args = {}, timeout = 30000) {
    this.start();
    if (!this.proc) return Promise.reject(new Error('This needs the NSXL Windows helper, which is missing'));
    const id = ++this.n;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.waiting.delete(id); reject(new Error('Timed out')); }, timeout);
      this.waiting.set(id, { resolve: v => { clearTimeout(t); resolve(v); }, reject: e => { clearTimeout(t); reject(e); } });
      this.proc.stdin.write(JSON.stringify({ id, cmd, ...args }) + '\n');
    });
  }
  on(event, f) { (this.handlers[event] = this.handlers[event] || []).push(f); }
  stop() { this.stopping = true; if (this.proc) { try { this.proc.stdin.end(); this.proc.kill(); } catch (e) { } } }
}
module.exports = Native;
