// Copies the shared interface (the same page the Android app uses) into pc/www.
const fs = require('fs');
const path = require('path');
const src = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'www');
const dest = path.join(__dirname, '..', 'www');
fs.rmSync(dest, { recursive: true, force: true });
fs.cpSync(src, dest, { recursive: true, filter: p => !p.endsWith('cloud.html') });
// PC-only pages
fs.mkdirSync(path.join(dest, 'browser'), { recursive: true });
fs.copyFileSync(path.join(__dirname, '..', 'ui', 'browser-shell.html'), path.join(dest, 'browser', 'shell.html'));
fs.copyFileSync(path.join(__dirname, '..', 'ui', 'second.html'), path.join(dest, 'second.html'));
console.log('UI copied to', dest);
