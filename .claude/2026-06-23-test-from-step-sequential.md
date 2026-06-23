# Step-by-step test from a chosen step, with Continue/Stop (#5 follow-up)

## Why
The single-step Test runs one step in isolation, but an intermediate step usually lacks the inputs
produced by earlier steps. This adds a guided sequential test: start at a step and run to the end,
one step at a time, confirming each.

## What
- `WorkflowEngine.testFrom(feedId, startStepId, user)`: builds ONE ephemeral test run (via buildRun,
  full var seeding), runId `<feedId>_test_<ts>`, marks steps before the start SKIPPED, stores a
  TestSession (ordered StepDefs from start..end + cursor), registers it in activeRuns + controls,
  runs the first step on the test executor, then pauses (status WAITING_APPROVAL + run.pausedNextStep).
  Because every step runs against the SAME run object, output vars accumulate; outputs are written to
  the feed's normal step folders, so step N sees step N-1's output.
- `WorkflowEngine.testContinue(feedId, runId, user)`: runs the next step then pauses again, or finishes
  SUCCESS after the last step / FAILED on a step failure.
- Stop: the existing engine.stop() already finalizes WAITING_APPROVAL runs as ABORTED; finish() now
  also drops the TestSession. No worker thread is blocked between steps (each step is a short task on
  the test executor), so it never ties up the pool while waiting for confirmation.
- New field `WorkflowRun.pausedNextStep` distinguishes a step-by-step pause from a manual gate wait.

## API
- POST /api/workflows/{feedId}/test-from/{stepId} -> {ok, runId, url}
- POST /api/runs/{feedId}/{runId}/continue -> {ok}

## UI
- Designer: a **From here** button on each STEP node (next to Test) starts the session and opens the
  run page in a new tab.
- Run page: a step-by-step pause box (separate from the gate approval box) shows the next step with
  **Continue** / **Stop**, driven by run.status == WAITING_APPROVAL && run.pausedNextStep.

## Caveats
- Uses the SAVED workflow. Gates and loops are not evaluated (steps run in document order). A step that
  needs LOOP `${item}` context has none when tested. Refused if the same feed has a normal run active.

## Verification
- Compiles (98 classes). designer.html + run.html JS node --check OK; no literal \n/\r; no unsafe
  [[ / [(. Live behaviour is UBS-side.
