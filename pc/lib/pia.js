'use strict';
// Private Internet Access, through its command-line tool piactl (installed with the PIA app).
const { execFile, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

const CANDIDATES = [
  path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Private Internet Access', 'piactl.exe'),
  path.join(process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)', 'Private Internet Access', 'piactl.exe'),
];

class Pia {
  constructor(onChange) {
    this.exe = CANDIDATES.find(p => fs.existsSync(p)) || null;
    this.state = { installed: !!this.exe, connection: 'Unknown', region: 'auto', ip: '', regions: [] };
    this.onChange = onChange;
    this.monitor = null;
  }
  run(args, timeout = 20000) {
    return new Promise((resolve, reject) => {
      if (!this.exe) { reject(new Error('Private Internet Access isn\'t installed')); return; }
      execFile(this.exe, args, { windowsHide: true, timeout }, (err, out, errOut) => {
        if (err) reject(new Error((errOut || err.message || '').trim() || 'piactl failed')); else resolve(String(out).trim());
      });
    });
  }
  async refresh() {
    if (!this.exe) return this.state;
    const get = k => this.run(['get', k]).catch(() => '');
    const [connection, region, ip] = await Promise.all([get('connectionstate'), get('region'), get('vpnip')]);
    Object.assign(this.state, { connection: connection || 'Unknown', region: region || 'auto', ip: ip === 'Unknown' ? '' : ip });
    if (!this.state.regions.length) {
      const list = await get('regions');
      this.state.regions = list ? list.split(/\r?\n/).map(s => s.trim()).filter(Boolean) : [];
    }
    this.onChange && this.onChange(this.state);
    return this.state;
  }
  watch() {
    if (!this.exe || this.monitor) return;
    this.monitor = spawn(this.exe, ['monitor', 'connectionstate'], { windowsHide: true });
    readline.createInterface({ input: this.monitor.stdout }).on('line', line => {
      this.state.connection = line.trim() || this.state.connection;
      if (/^(Connected|Disconnected)$/.test(this.state.connection)) this.refresh().catch(() => {});
      else this.onChange && this.onChange(this.state);
    });
    this.monitor.on('exit', () => { this.monitor = null; });
  }
  async connect() {
    await this.run(['background', 'enable']).catch(() => {});   // works even when the PIA window isn't open
    await this.run(['connect']);
  }
  disconnect() { return this.run(['disconnect']); }
  setRegion(r) { return this.run(['set', 'region', r || 'auto']); }
  stop() { if (this.monitor) { try { this.monitor.kill(); } catch (e) { } } }
}
module.exports = Pia;
