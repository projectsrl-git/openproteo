# 2026-06-17 — Live loop animation (iteration label + xN badges)

## What
At run time the diagram now animates the loop:
* "iteration N / total" label near the LOOP node.
* a "xN" badge on every body block (the steps inside that loop).
* the ENDLOOP->LOOP back-arrow pulses and the body blocks flash when the pass advances,
  so it is visually clear that execution jumps back and the blocks re-run.

## How
* bpmn.js: a loops registry is built while drawing the back-edges (edge element + body node
  ids, with each body node assigned to its innermost enclosing loop). New public method
  setLoopState(loopId, iter, count) updates the label, the per-block badges, and pulses the
  edge / flashes the bodies on advance. addCls/rmCls toggle classes without clobbering the
  status classes set by polling.
* run.html: LOOP_IDS captured from the definition; refresh() reads __loop.<id>.i and .n from
  run.vars (already returned by the status endpoint) and calls setLoopState (iter = i + 1,
  count = n), clearing when the keys are gone. Internal __ vars are hidden from the vars dump.

## No backend change
The engine already persisted __loop.<id>.i/.n in run.vars; the UI drives the animation from
that. bpmn.js + run.html validated; no \n/\r; Thymeleaf-safe.

## Verify on UBS
Run a loop workflow with a few files and watch the iteration label, badges, and the pulse on
each pass. (Fast steps may advance between 2s polls; longer steps show each pass clearly.)
