'use strict';
// Talks to nsxl-native.exe (pc/native/Helper): one JSON message per line each way.
const { spawn } = require('child_process');
const fs = require('fs');
const readline = require('readline');

class Native {
  constructor(exe) {
    this.exe = exe; this.proc = null; this.n = 0; this.waiting = new Map(); this.handlers = {};
  }
  get available() { return fs.existsSync(this.exe); }
  start() {
    if (this.proc || !this.available) return;
    this.proc = spawn(this.exe, [], { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
    this.proc.on('exit', () => {
      this.proc = null;
      for (const [, w] of this.waiting) w.reject(new Error('Helper stopped'));
      this.waiting.clear();
    });
    this.proc.on('error', () => { this.proc = null; });
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
  stop() { if (this.proc) { try { this.proc.stdin.end(); this.proc.kill(); } catch (e) { } } }
}
module.exports = Native;
