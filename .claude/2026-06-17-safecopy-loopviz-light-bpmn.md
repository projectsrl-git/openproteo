# 2026-06-17 — safecopy, loop back-edge, light BPMN, bigger toggle

## #4 safecopy executor
Copies wildcard-matched files from an input dir to an output dir, writing each as
<name>.on_fly_ and renaming (atomic move when possible) to the final name only after the
copy completes — so a landing-zone watcher never sees a partial file. Params: source, dest,
pattern, tmpSuffix (default .on_fly_). InternalSteps.runSafeCopy; engine internal list;
designer option + branch + validation. Tested: copies *.csv, skips others, no temp leftover.

## #1 light BPMN
The run-view BPMN blocks had hardcoded dark fills. Added light-theme overrides for node
shapes/edges/arrowheads and per-status fills.

## #2 loop visualization
bpmn.js now matches LOOP<->ENDLOOP by nesting and draws a dashed accent back-edge arched
over the top from ENDLOOP to its LOOP (bpLoopArrow marker + "loop" label). LOOP/ENDLOOP
nodes get a bp-loopmarker accent stroke.

## #2.1 pairing visible
Designer shows the computed pairing: LOOP -> "paired ENDLOOP: <id>", ENDLOOP -> "Closes
LOOP: <id>" (loopPartner by nesting), warning if unmatched. The diagram arrow shows it too.

## #3 toggle button
.nc-toggle enlarged and boxed (30x26, border + background, accent hover) so it is clearly
distinct from the move/duplicate/delete buttons.

## Verify on UBS / pending
- Verify the diagram arch, light theme, safecopy run, and designer pairing in the browser.
- Still pending (was #5.1): run-time loop animation — visually reset executed blocks on each
  pass and show the current iteration number live.
76 classes compile; bpmn.js + designer JS valid; no \n/\r; Thymeleaf-safe.
