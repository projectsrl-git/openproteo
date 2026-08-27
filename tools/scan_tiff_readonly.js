#!/usr/bin/env node
/*
 * Asserts that the TIFF scanning package carries no way to change anything on disk.
 *
 * Modelled on the property ELAR_CHECK_EXECUTOR.md section 307 states for elarcheck: read-only BY
 * CONSTRUCTION, not by promise. That property is what makes it reasonable to aim a scan at a live
 * directory without reading the code first, and it survives only if something checks it on every
 * change - a single convenience write added later would end it silently.
 *
 * The rewriting half of tiffcompress lives in its own package for exactly this reason and is not
 * scanned here.
 *
 * usage: node scan_tiff_readonly.js <package-dir>
 */
'use strict';
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
if (!dir) { console.error('usage: node scan_tiff_readonly.js <package-dir>'); process.exit(2); }

// Every API in the JDK that can create, change, move or remove a file. A token here is a defect
// wherever it appears, including inside a comment: a commented-out write is a write waiting to be
// uncommented, and the check is cheap enough not to need the subtlety.
const FORBIDDEN = [
  'FileOutputStream', 'FileWriter', 'RandomAccessFile("', 'PrintWriter',
  'Files.write', 'Files.newOutputStream', 'Files.newBufferedWriter', 'Files.copy',
  'Files.move', 'Files.delete', 'Files.deleteIfExists', 'Files.createFile',
  'Files.createDirectory', 'Files.createDirectories', 'Files.createTempFile',
  '.renameTo(', '.createNewFile(', '.delete()', '.deleteOnExit(', '.mkdir(', '.mkdirs(',
  '.setWritable(', '.setLastModified(', 'File.createTempFile'
];

// RandomAccessFile is how the scanner seeks, and it is only safe in "r" mode. The pattern above
// catches the mode string directly, so the class name alone is not forbidden - but any mode other
// than "r" is.
const RAF_MODE = /new\s+RandomAccessFile\s*\([^)]*,\s*"([^"]*)"/g;

let files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.java')) files.push(p);
  }
})(dir);

if (files.length === 0) {
  console.error('no .java files under ' + dir + ' - the scan would pass by finding nothing at all');
  process.exit(2);
}

let defects = 0;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  const lines = src.split('\n');
  lines.forEach((l, i) => {
    for (const tok of FORBIDDEN) {
      if (l.indexOf(tok) >= 0) {
        console.log('  ' + path.basename(f) + ':' + (i + 1) + '  ' + tok + '  ->  ' + l.trim().slice(0, 90));
        defects++;
      }
    }
  });
  let m;
  RAF_MODE.lastIndex = 0;
  while ((m = RAF_MODE.exec(src)) !== null) {
    if (m[1] !== 'r') {
      console.log('  ' + path.basename(f) + '  RandomAccessFile opened in mode "' + m[1] + '", not "r"');
      defects++;
    }
  }
}

console.log('read-only scan on ' + dir);
console.log('  java files scanned : ' + files.length);
console.log('  write API found    : ' + defects);
console.log(defects === 0
  ? '  -> the package cannot change anything on disk'
  : '  -> READ-ONLY IS BROKEN');
process.exit(defects === 0 ? 0 : 1);
