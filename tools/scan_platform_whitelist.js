#!/usr/bin/env node
/*
 * Asserts that the platform diagnostics code can only disclose what its whitelist names.
 *
 * GET /api/platform is public until authentication exists (.claude/LINUX_AND_GUI_CONFIG.md
 * section 7). What keeps it from turning into a dump of the JVM is that the code reads system
 * properties and environment variables by LITERAL NAME only, and that the controller passes on
 * a fixed list of configured values. That holds by construction today and survives only if
 * something checks it on every change: one convenient System.getProperties() added later would
 * end it silently, on an endpoint nobody has to log in to read.
 *
 * Also asserts the one write the page is allowed: a single createFile and a single delete, in
 * the case probe, and no API that could create a directory.
 *
 * usage: node scan_platform_whitelist.js <src/main/java root>
 * exit:  0 clean, 1 violation, 2 nothing to scan
 */
'use strict';
const fs = require('fs');
const path = require('path');

const SYSTEM_PROPERTIES = ['os.name', 'os.arch', 'java.version', 'java.vendor', 'file.encoding',
  'sun.jnu.encoding', 'user.dir', 'java.home', 'java.io.tmpdir'];
const ENV_VARIABLES = ['PATH', 'SystemRoot'];
const SPRING_PROPERTIES = ['logging.file.name', 'logging.file.path'];
const CONFIG_GETTERS = ['getWorkflowsDir', 'getScriptsDir', 'getSharedDir', 'getDefaultBaseDir',
  'getDatasourcesFile', 'getFtpTargetsFile', 'getMaskPoolsDir', 'getPowershellExe', 'getCmdExe', 'getJavaExe',
  'getBashExe', 'isWindowsTreeKill'];
// Never, in either file: the whole property set, the whole environment, and anything that
// creates a directory or writes content.
const FORBIDDEN = ['getProperties(', 'getenv()', 'getMaskingSecret', 'getGlobalVars(',
  'createDirectories', 'createDirectory(', '.mkdir(', '.mkdirs(', 'Files.write', 'FileOutputStream',
  'FileWriter', 'Files.newOutputStream', 'Files.newBufferedWriter', 'Files.copy', 'Files.move',
  'createTempFile', 'ProcessBuilder', 'Runtime.getRuntime'];

/* Comments are stripped first: the javadoc NAMES what the code must not call. */
function code(src) {
  let out = '', i = 0;
  while (i < src.length) {
    const c = src[i], d = src[i + 1];
    if (c === '"') {
      let j = i + 1;
      while (j < src.length && src[j] !== '"') j += (src[j] === '\\' ? 2 : 1);
      out += src.slice(i, j + 1); i = j + 1;
    } else if (c === '/' && d === '/') {
      while (i < src.length && src[i] !== '\n') i++;
    } else if (c === '/' && d === '*') {
      const j = src.indexOf('*/', i + 2);
      const cut = src.slice(i, j < 0 ? src.length : j + 2);
      out += cut.replace(/[^\n]/g, ''); i = j < 0 ? src.length : j + 2;
    } else { out += c; i++; }
  }
  return out;
}

function literals(src, re) {
  const found = []; let m;
  while ((m = re.exec(src)) !== null) found.push(m[1]);
  return found;
}

function scan(probeSrc, controllerSrc) {
  const problems = [];
  const p = code(probeSrc), c = code(controllerSrc);
  for (const [name, src] of [['PlatformProbe', p], ['PlatformController', c]]) {
    for (const tok of FORBIDDEN) if (src.indexOf(tok) >= 0) problems.push(name + ': forbidden ' + tok);
    for (const k of literals(src, /System\s*\.\s*getProperty\s*\(\s*"([^"]*)"/g)) {
      if (SYSTEM_PROPERTIES.indexOf(k) < 0) problems.push(name + ': system property "' + k + '" is not on the whitelist');
    }
    // A property read whose name is not a literal cannot be checked, so it is not allowed.
    const all = (src.match(/System\s*\.\s*getProperty\s*\(/g) || []).length;
    const lit = literals(src, /System\s*\.\s*getProperty\s*\(\s*"([^"]*)"/g).length;
    if (all !== lit) problems.push(name + ': System.getProperty called with a non-literal name');
  }
  if (c.indexOf('System.getProperty') >= 0 || c.indexOf('System.getenv') >= 0) {
    problems.push('PlatformController: reads the JVM directly; that belongs to PlatformProbe');
  }
  // Environment: one delegating call, and the lookups by literal name only.
  if ((p.match(/System\s*\.\s*getenv\s*\(/g) || []).length !== 1) problems.push('PlatformProbe: exactly one System.getenv call expected (the delegate)');
  const envAll = (p.match(/env\s*\.\s*apply\s*\(/g) || []).length;
  const envLit = literals(p, /env\s*\.\s*apply\s*\(\s*"([^"]*)"/g);
  if (envAll !== envLit.length) problems.push('PlatformProbe: environment read with a non-literal name');
  for (const k of envLit) if (ENV_VARIABLES.indexOf(k) < 0) problems.push('PlatformProbe: environment variable "' + k + '" is not on the whitelist');
  // The one permitted write.
  if ((p.match(/Files\s*\.\s*createFile\s*\(/g) || []).length !== 1) problems.push('PlatformProbe: exactly one Files.createFile expected (the case probe)');
  if ((p.match(/Files\s*\.\s*delete(IfExists)?\s*\(/g) || []).length !== 1) problems.push('PlatformProbe: exactly one Files.delete expected (the case probe)');
  // Controller: fixed configuration getters, fixed Spring properties.
  for (const g of literals(c, /props\s*\.\s*(\w+)\s*\(/g)) {
    if (CONFIG_GETTERS.indexOf(g) < 0) problems.push('PlatformController: props.' + g + '() is not on the whitelist');
  }
  const spAll = (c.match(/environment\s*\.\s*getProperty\s*\(/g) || []).length;
  const spLit = literals(c, /environment\s*\.\s*getProperty\s*\(\s*"([^"]*)"/g);
  if (spAll !== spLit.length) problems.push('PlatformController: Spring property read with a non-literal name');
  for (const k of spLit) if (SPRING_PROPERTIES.indexOf(k) < 0) problems.push('PlatformController: Spring property "' + k + '" is not on the whitelist');
  // A scan that finds nothing must be checked for whether it can find anything.
  const seen = literals(p, /System\s*\.\s*getProperty\s*\(\s*"([^"]*)"/g).length + envLit.length
    + literals(c, /props\s*\.\s*(\w+)\s*\(/g).length + spLit.length;
  if (seen < 10) problems.push('scan matched only ' + seen + ' reads in total - the patterns no longer fit the code');
  return problems;
}

module.exports = { scan: scan, code: code };

if (require.main === module) {
  const root = process.argv[2];
  if (!root) { console.error('usage: node scan_platform_whitelist.js <src/main/java root>'); process.exit(2); }
  const base = path.join(root, 'com', 'legalarchive', 'orchestrator');
  const PROBE = path.join(base, 'platform', 'PlatformProbe.java');
  const CONTROLLER = path.join(base, 'web', 'PlatformController.java');
  for (const f of [PROBE, CONTROLLER]) {
    if (!fs.existsSync(f)) { console.error('not found: ' + f + ' - the scan would pass by finding nothing at all'); process.exit(2); }
  }
  const problems = scan(fs.readFileSync(PROBE, 'utf8'), fs.readFileSync(CONTROLLER, 'utf8'));
  if (problems.length) {
    console.log('platform whitelist: ' + problems.length + ' violation(s)');
    problems.forEach((x) => console.log('  ' + x));
    process.exit(1);
  }
  console.log('platform whitelist: clean');
}
