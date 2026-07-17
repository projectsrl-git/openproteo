# ON HOLD engine + resume (Batch F2, req 5.3 core)

A step can be marked ON HOLD. When the run reaches it, the run suspends
(RunStatus.ON_HOLD) without executing the step; a resume (PLAY) executes that step
and continues. Mirrors the existing WAITING_APPROVAL pause/resume infrastructure.

## Model
- StepDef.onHold; XML attribute onHold="true" (round-trips via parser/writer/
  NodeDto/toDto, like skip/overwrite). Designer "Step mode" dropdown is now
  normal / skip (passthrough) / on hold (pause) (setStepMode sets skip & onHold
  mutually exclusive).
- RunStatus gains ON_HOLD (non-terminal, like WAITING_APPROVAL).
- WorkflowRun gains onHoldStepId (paused step) and releasedHold (transient: step
  whose hold was just released so the loop executes it once instead of re-holding).

## Engine
- loop(): before executing a StepDef, if step.onHold and it isn't the just-released
  step, set status ON_HOLD + onHoldStepId, save, remove the feed from runningFeeds
  (frees the engine), audit RUN_ON_HOLD, return (suspend). run.currentIndex already
  tracks the node, so resume continues from there.
- resumeHold(feedId, runId, user): validate ON_HOLD, set releasedHold =
  onHoldStepId, status QUEUED, re-register in runningFeeds/activeRuns, enqueue
  loop(..., currentIndex). Mirrors decide().
- Active-status: ON_HOLD added to the queue snapshot and rank (treated like
  WAITING_APPROVAL); Stop now also aborts an ON_HOLD run.

## Endpoint
POST /api/runs/{feedId}/{runId}/resume -> engine.resumeHold (PLAY / CONTINUE).

## Verify
onHold attribute round-trip verified via DOM. designer.html passes node --check;
no literal \n/\r; no unsafe Thymeleaf. Engine changes mirror the proven
WAITING_APPROVAL/decide pause-resume path. NOTE: this is a substantial engine
change that was NOT compiled in the chat sandbox — please build on deploy. The UI
(run-page ON HOLD blue status + partial N/TOT counts + partial outputs + PLAY
button, and the Operations ON HOLD column + PLAY) is the next batch (F3).
