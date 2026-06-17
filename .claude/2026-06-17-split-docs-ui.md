# 2026-06-17 — SPLIT executor, in-app Docs, collapse-on-open, light inputs

(English-only from now on, per request.)

## SPLIT executor (#6)
New internal executor `split`: split an existing file into parts by row count and/or MB,
reusing the SQL export rotation semantics (header repeated per part, parts named stem_001.ext,
CRLF, optional BOM). Lines pass through verbatim (no re-quoting), so masked/validated content
is preserved. Outputs csvFiles / csvParts / csvFile / rowCount — identical to the SQL split —
so a LOOP can iterate ${csvFiles} regardless of where the split happened. Lets the user run the
loop only over the final steps (after validation/anonymization).
- InternalSteps: runSplit + helpers utf8Len/partName; dispatch branch.
- WorkflowEngine: "split" added to the internal-executor list.
- Designer: executor option, params branch (source, output base, rows/MB, list sep, hasHeader,
  bom), clientValidate requires source.
- Tested (TestSplit): 25 rows / 10 -> 3 parts (10,10,5), header repeated, naming stem_001,
  no-limit -> single file.

## In-app Docs (#1)
- src/main/resources/static/USAGE.md: single comprehensive English guide; README.md is a copy.
- docs.html at /docs: fetches /USAGE.md and renders it with a compact no-CDN markdown renderer
  (headings/lists/code/bold/links/hr) + a navigable TOC (h2/h3). Link in dashboard nav.
- No \n/\r literals in JS (String.fromCharCode); link regex uses char classes to avoid a
  literal [( (Thymeleaf inlining safe).
- Docs and commit messages are English from now on.

## Collapse on open (#2)
Designer load(): seed collapsedNodes for every node so an existing workflow opens fully
collapsed (compact). Add/expand still work.

## Light theme inputs (#3)
Added light-theme overrides so editable inputs/selects/textareas and .ms-box use a light
background (#fff) + dark text; dark theme unchanged.

## Answered (#4)
Step working dirs NN_<stepId>: NN = execution order x 10 (00,10,20,...). Not a version; the
gap lets steps be inserted, and folders sort in run order. stepId stays the unique key.

## Deferred to next batch (#5 / #5.1)
Loop drawn graphically with a back-arrow ENDLOOP -> LOOP; at run time show the jump back,
reset/"switch off" the executed blocks for the new pass, and display the current iteration.
Most involved part (static SVG + run-state animation) — done next as a focused batch.

## Verify on UBS (not runtime-testable in sandbox)
SPLIT in a real run; designer rendering; /docs rendering in the corporate browser; light-theme
look. Split logic + renderer logic + compilation tested here. 76 classes compile.
