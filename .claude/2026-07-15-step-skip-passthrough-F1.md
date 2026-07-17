# Step SKIP passthrough (Batch F1, req 5.2)

A step can be marked SKIP. A SKIP step is not executed; instead it passes its
input through to its output unchanged, and the run continues.

## Semantics chosen (please confirm)
"Copy input to output as-is" is implemented as: copy every file (recursively)
from the PREVIOUS step's output directory into this step's output directory; if
the SKIP step is the first step, the source is 00_landing_in. This matches the
linear-chain model (step N's input is step N-1's output dir). The step is recorded
as SKIPPED with a message "SKIP passthrough: copied N file(s) from <src>". If your
intended input source is different (e.g. a specific declared input rather than the
immediately-preceding step), tell me and I'll adjust the source resolution.

## Wiring
- Model: StepDef.skip. XML attribute `skip="true"` (mirrors `overwrite`):
  parser reads it, writer writes it, WorkflowDto.NodeDto.skip + toDto map it.
- Designer: a "Step mode" dropdown (normal / skip (passthrough)) in the common
  step fields; serialised as skip="true".
- Engine (executeStep): before running, if step.skip -> copyDirContents(prev, dst)
  -> StepStatus.SKIPPED -> audit STEP_SKIPPED -> continue. On copy failure the step
  FAILS (so a broken passthrough doesn't silently drop data).

## Verify
copyDirContents was run standalone (copies nested files, overwrites existing,
creates subdirs; 3/3 copied). The skip attribute round-trip was verified via DOM
(parse skip="true"). designer.html passes node --check; no literal \n/\r; no unsafe
Thymeleaf. The round-trip mirrors the proven `overwrite` attribute. Full Maven
build not run this turn (executeStep change) — confirm on deploy.

## Next (ON HOLD, F2/F3)
onHold flag + RunStatus.ON_HOLD + engine suspension + resume endpoint + run-page
partial counts/outputs + Operations ON HOLD column + PLAY/CONTINUE.
