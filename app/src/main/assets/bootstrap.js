// bootstrap.js — runs INSTEAD of server.js directly.
// Responsibilities:
//   1. Print runtime diagnostics (versions, ICU, TLS self-test)
//   2. Patch config.yaml (enableCorsProxy) every launch
//   3. Set production env + server arguments
//   4. Trap fatal errors so crashes get logged
//
// stdout/stderr are captured by the Android service (ProcessBuilder
// redirects them into the log file), so plain console.log just works.

const fs = require('fs');
const path = require('path');

const ROOT = process.env.WT_ROOT;
const ST = path.join(ROOT, 'SillyTavern');

// ---- 0. Crash visibility, without touching console ----
// Weyland's server-main.js does `console.log = function(){}` right after
// printing START — that's an intentional privacy choice (routine internal
// logging, including things like per-message usage tracking, shouldn't be
// sitting in a log a support ticket might paste). Mobile respects that the
// same way desktop's launcher does: nothing here un-silences console.
// Real crash visibility comes entirely from process.stderr.write below,
// which was never routed through console and can't be silenced by it.

// Silent process.exit() calls are the worst kind of crash. Make every exit
// announce itself with a stack trace first. (process.stderr.write, not
// console — belt and braces.)
const realExit = process.exit.bind(process);
process.exit = function (code) {
  try {
    process.stderr.write('\n[bootstrap] process.exit(' + code + ') called from:\n'
      + new Error().stack + '\n');
  } catch (e) {}
  return realExit(code);
};

// ---- 1. Runtime diagnostics ----
console.log('\n===== Tavern boot ' + new Date().toISOString() + ' =====');
console.log('[bootstrap] Node ' + process.version
  + ' | V8 ' + process.versions.v8
  + ' | ICU ' + (process.versions.icu || 'MISSING')
  + ' | Unicode ' + (process.versions.unicode || 'MISSING'));
try {
  // The exact regex class that killed nodejs-mobile. If this line survives,
  // the ICU war is over.
  new RegExp('^\\p{Cc}|\\p{Cf}|\\p{Co}|\\p{Cs}$', 'u');
  console.log('[bootstrap] Unicode property escapes: OK');
} catch (e) {
  console.log('[bootstrap] Unicode property escapes: FAILED — ' + e.message);
}
// TLS self-test (async, non-blocking — result appears in the log)
try {
  const https = require('https');
  https.get('https://api.github.com/', { headers: { 'User-Agent': 'WeylandTavernMobile' }, timeout: 15000 }, (res) => {
    console.log('[bootstrap] TLS self-test: OK (HTTP ' + res.statusCode + ')');
    res.resume();
  }).on('error', (e) => {
    console.log('[bootstrap] TLS self-test: FAILED — ' + e.message);
  });
} catch (e) {
  console.log('[bootstrap] TLS self-test: FAILED (sync) — ' + e.message);
}

// ---- 2. Patch config.yaml ----
try {
  const cf = path.join(ST, 'config.yaml');
  if (fs.existsSync(cf)) {
    let text = fs.readFileSync(cf, 'utf8');
    if (/^\s*enableCorsProxy:/m.test(text)) {
      text = text.replace(/^\s*enableCorsProxy:.*$/m, 'enableCorsProxy: true');
    } else {
      text += '\nenableCorsProxy: true\n';
    }
    fs.writeFileSync(cf, text);
    console.log('[bootstrap] config.yaml patched (enableCorsProxy: true)');
  } else {
    console.log('[bootstrap] config.yaml not present yet — ST will generate it');
  }
} catch (e) {
  console.log('[bootstrap] config patch failed: ' + e);
}

// ---- 3. Environment + args ----
process.env.NODE_ENV = 'production';
process.chdir(ST);

process.argv = [
  process.argv[0],
  path.join(ST, 'server.js'),
  '--listen', 'true',
  '--listen-host', '127.0.0.1',
  '--listen-port', '8000',
  // No xdg-open on Android — BapOS opens Chrome itself. Without this the
  // post-boot browser autolaunch throws spawn ENOENT and kills the server.
  '--browserLaunchEnabled', 'false',
];

// ---- 4. Crash trapping ----
// process.stderr.write directly — immune to any console tampering.
process.on('uncaughtException', (err) => {
  process.stderr.write('\n[FATAL] Uncaught exception:\n' + (err && err.stack ? err.stack : err) + '\n');
  realExit(1);
});
process.on('unhandledRejection', (reason) => {
  process.stderr.write('\n[FATAL] Unhandled rejection:\n'
    + (reason && reason.stack ? reason.stack : reason) + '\n');
});

console.log('[bootstrap] starting Weyland Tavern server...');
console.log('[bootstrap] ROOT=' + ROOT);
console.log('[bootstrap] ST=' + ST);
console.log('[bootstrap] server.js exists=' + fs.existsSync(path.join(ST, 'server.js')));
import(path.join(ST, 'server.js')).catch(err => {
  // stderr.write, not console.error — if the import got far enough for
  // server-main.js's own console nerf to have already run, console.error
  // would silently vanish here right when it matters most.
  process.stderr.write('\n[FATAL] Failed to import server.js:\n' + (err && err.stack ? err.stack : err) + '\n');
  realExit(1);
});
