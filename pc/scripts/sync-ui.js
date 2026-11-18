// Copies the shared interface (the same page the Android app uses) into pc/www.
const fs = require('fs');
const path = require('path');
const src = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'www');
const dest = path.join(__dirname, '..', 'www');
fs.rmSync(dest, { recursive: true, force: true });
fs.cpSync(src, dest, { recursive: true, filter: p => !p.includes(path.sep + 'browser') && !p.endsWith('overlay.html') && !p.endsWith('cloud.html') });
console.log('UI copied to', dest);
