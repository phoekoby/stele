#!/usr/bin/env node
// Thin launcher: run the bundled/downloaded stele.jar on the user's Java.
const { spawnSync } = require('child_process');
const { existsSync } = require('fs');
const path = require('path');

const jar = path.join(__dirname, '..', 'stele.jar');
if (!existsSync(jar)) {
  console.error('stele.jar missing — postinstall download failed. Re-run `npm rebuild stele-cli`.');
  process.exit(1);
}

const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
const res = spawnSync(java, ['-jar', jar, ...process.argv.slice(2)], { stdio: 'inherit' });
if (res.error) {
  console.error('Could not run Java. Install a JDK 17+ (https://adoptium.net) and ensure `java` is on PATH.');
  process.exit(1);
}
process.exit(res.status == null ? 1 : res.status);
