#!/usr/bin/env node
// Fetch stele.jar from the GitHub release once, into the package dir.
// Fill RELEASE_URL after publishing; keeps the npm package tiny (the 47 MB jar
// is downloaded on install rather than shipped).
const fs = require('fs');
const path = require('path');
const https = require('https');

const RELEASE_URL = 'https://github.com/USER/stele/releases/download/v0.1.0/stele.jar';
const dest = path.join(__dirname, '..', 'stele.jar');

if (fs.existsSync(dest)) process.exit(0);

function get(url, file, redirects = 0) {
  https.get(url, (res) => {
    if ([301, 302, 307, 308].includes(res.statusCode) && res.headers.location && redirects < 5) {
      return get(res.headers.location, file, redirects + 1);
    }
    if (res.statusCode !== 200) {
      console.error(`download failed: HTTP ${res.statusCode} for ${url}`);
      process.exit(1);
    }
    res.pipe(file);
    file.on('finish', () => file.close());
  }).on('error', (e) => { console.error('download error:', e.message); process.exit(1); });
}

get(RELEASE_URL, fs.createWriteStream(dest));
