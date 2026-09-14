// A control whose value changes what the panel SHOWS must go through a handler that redraws it.
// One that only stores the value leaves the page contradicting itself: the new choice selected, and
// the previous choice's fields - or labels - still underneath it. That is how an elarxml step became
// impossible to configure for IFS: the source said IFS, the fields below stayed LOCAL.
//
// DIAGNOSTIC, NOT A GATE. Deliberately. The first version of this flagged thirteen controls of which
// twelve were fine, and a check that cries wolf twelve times out of thirteen gets switched off within a
// week - at which point it protects nothing. It stays advisory until it has been calibrated against a
// few panels written after it. Exit code is always 0; read the output.
//
// Usage: node tools/scan_panel_redraw.js src/main/resources/templates/designer.html

const fs = require('fs');
const file = process.argv[2];
if (!file) { console.log('usage: node scan_panel_redraw.js <template>'); process.exit(0); }
const src = fs.readFileSync(file, 'utf8');

// Parameter names sit inside escaped quotes in the emitted strings - setNodeParam(' + i + ',\'x\',...)
// - so a scan matching plain quotes finds almost nothing and reports a reassuring, false, all-clear.
// That happened on the first run of this: 3 parameters found instead of 118.
const ESC = "\\\\?'";

function all(re) {
  const out = new Set();
  let m;
  while ((m = re.exec(src)) !== null) out.add(m[1]);
  return out;
}

const writtenByAnyControl = all(new RegExp('setNodeParam\\([^,]+,\\s*' + ESC + '([A-Za-z0-9_.]+)' + ESC, 'g'));

// A text input cannot redraw: doing it on every keystroke would take the focus away mid-word, so the
// redraw would be the defect. Only controls that commit a whole value at once are candidates.
const textInputs = all(new RegExp(
  '<input(?![^>]*type=\\\\?"(?:checkbox|radio|number)\\\\?")[^>]*oninput="setNodeParam\\([^,]+,\\s*' + ESC + '([A-Za-z0-9_.]+)' + ESC, 'g'));

// Handlers that already redraw, in the two forms the panels use: a named helper, and the redraw
// written straight into the attribute. Only the first was recognised until a control of the second
// kind went unseen entirely - flagged neither as a defect nor as safe, simply absent from the count.
// The inline form is bounded to its own attribute: without that, the match runs forward past the
// handler it started in and credits an unrelated control with the next redraw it finds.
const redraws = new Set();
for (const p of all(new RegExp(
  'function\\s+\\w+\\s*\\([^)]*\\)\\s*\\{[^}]*setNodeParam\\([^,]+,\\s*' + ESC + '([A-Za-z0-9_.]+)' + ESC + '[^}]*renderNodes\\(\\)', 'g'))) redraws.add(p);
for (const p of all(new RegExp(
  'setNodeParam\\([^,]+,\\s*' + ESC + '([A-Za-z0-9_.]+)' + ESC + '[^;"\']*\\);\\s*renderNodes\\(\\)', 'g'))) redraws.add(p);

// A parameter DECIDES THE SHAPE of the panel when the rendering branches on it in a statement - an
// `if`, or a variable the emission later tests - rather than merely interpolating it into one
// attribute. The second kind only ever affects the control's own `checked` or `selected`, which is
// already correct at the moment the value is written.
const shape = new Set();
let m;
const ifBranch = /if\s*\([^)]*nodeParam\(\s*n\s*,\s*'([A-Za-z0-9_.]+)'/g;
while ((m = ifBranch.exec(src)) !== null) shape.add(m[1]);
// Two ways a panel binds a parameter to a variable it later branches on. The second was added after
// the scan reported a clean run on a panel whose control it could not see at all: the mutation that
// removed that control's redraw was caught by the panel's own jsdom suite and NOT by this scan.
// A scan that cannot fail on the code it is pointed at is worth nothing, which is exactly the
// property this file exists to check for in the panels.
const viaVarPatterns = [
  //  var x = nodeParam(n, 'p') === 'A' ? ... : ...
  /var\s+(\w+)\s*=\s*nodeParam\(\s*n\s*,\s*'([A-Za-z0-9_.]+)'\s*\)\s*===?/g,
  //  var x = nodeParam(n, 'p') || 'DEFAULT'   - the same thing with a default folded in
  /var\s+(\w+)\s*=\s*nodeParam\(\s*n\s*,\s*'([A-Za-z0-9_.]+)'\s*\)\s*\|\|/g
];
for (const viaVar of viaVarPatterns) {
  viaVar.lastIndex = 0;
  while ((m = viaVar.exec(src)) !== null) {
    // only if that variable is later used to choose text or fields, not just the selected attribute
    const uses = new RegExp('\\b' + m[1] + '\\b\\s*===?\\s*\'[^\']*\'\\s*[?)]', 'g');
    let n = 0, mm;
    while ((mm = uses.exec(src)) !== null) n++;
    if (n > 0) shape.add(m[2]);
  }
}

const flagged = [];
for (const p of shape) {
  if (!writtenByAnyControl.has(p)) continue;   // read-only branch, nothing to redraw from
  if (redraws.has(p)) continue;                // already goes through a redrawing handler
  if (textInputs.has(p)) continue;             // a redraw here would steal the focus mid-word
  flagged.push(p);
}

// A SECOND check, keyed on the HELPER rather than on the parameter name. The first one cannot see a
// broken helper whose parameter is ALSO redrawn correctly somewhere else: `listSource` is written by
// ifscopy's helper and by filecopy/safecopy's, so removing renderNodes() from one of them leaves the
// name in `redraws` and the scan reports a clean run. Found by a positive control, not by review -
// the mutation was caught by the panel's jsdom suite and by nothing here.
// Narrow on purpose: only NAMED helpers that write a shape-deciding parameter are considered, which
// is a handful of functions, so this cannot become the thirteen-false-alarms-out-of-thirteen that
// gets a diagnostic switched off within a week.
const brokenHelpers = [];
// Bodies with no nested braces, which is what every one of these helpers is: a single line that
// stores the value and redraws. Written that way deliberately - requiring a newline before the
// closing brace matched none of them at all, and a greedy body would swallow past the function.
// A helper complex enough to contain a block is not considered here; stated rather than silent.
const helperRe = /function\s+(\w+)\s*\([^)]*\)\s*\{([^{}]*)\}/g;
let hm;
while ((hm = helperRe.exec(src)) !== null) {
  const body = hm[2];
  const writes = [...body.matchAll(/setNodeParam\([^,]+,\s*'([A-Za-z0-9_.]+)'/g)].map(x => x[1]);
  const shapeWrites = writes.filter(w => shape.has(w));
  if (shapeWrites.length && body.indexOf('renderNodes()') < 0) {
    brokenHelpers.push(hm[1] + ' writes ' + [...new Set(shapeWrites)].join(', ') + ' and never redraws');
  }
}

console.log('panel redraw scan on ' + file);
console.log('  parameters written by a control : ' + writtenByAnyControl.size);
console.log('  parameters deciding panel shape : ' + shape.size);
console.log('  excluded as text inputs         : ' + textInputs.size);
console.log('  already redrawing               : ' + redraws.size);
console.log('  named helpers writing a shape param, without a redraw : ' + brokenHelpers.length);
for (const h of brokenHelpers) console.log('     ' + h);
if (!flagged.length && !brokenHelpers.length) {
  console.log('  -> no control changes the panel without redrawing');
} else {
  console.log('  -> REVIEW, not necessarily a defect:');
  for (const p of flagged) {
    console.log('     ' + p + ' - the panel branches on it; check whether the branch changes fields or'
      + ' wording, and if so route the control through a handler that calls renderNodes()');
  }
}
process.exit(0);   // advisory
