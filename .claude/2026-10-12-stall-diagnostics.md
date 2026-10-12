# 2026-10-12 — diagnostic snapshots for runs stuck between two steps

Base: `d25a1ce`. Request: "a package that collects the information the next time it hangs, and
have the system administrator copy the new war". Spec and evidence: `.claude/STALL_DIAGNOSTICS.md`.

## What
- New `diag/IoTimings`, `diag/StallSnapshot`, `diag/StallWatchdog`, `diag/DiagnosticsService`,
  `diag/DiagnosticsController`.
- `WorkflowEngine`: `catch (Error)` in `loop` and `continueFromTarget` (run ends FAILED, message
  names the error and the max heap); `liveRuns()`.
- `RunStore.save` (same monitor, now a block) and `AuditLogger.log`: lock wait and write time.
- `templates/platform.html`: section "Diagnostic snapshots".
- USAGE, "Platform diagnostics": the snapshots; two sentences corrected (the page writes in two
  cases, not one).

## Why
Two runs (2026-10-09, 2026-10-12) RUNNING with no step running; the cause - lock, disk, GC, lost
thread - cannot be told from the GUI, and the server is reachable only through a busy
administrator.

## Verification
Verified on Linux (JDK): Temurin 1.8.0_432 and OpenJDK 21.0.12 - real classes against stubs, 57/59/60
assertions (quick / with the 2-minute case / as uid 65534 with a read-only folder); 24 mutations
(2 holes closed, 1 equivalent); differential compile of the engine with a positive control;
Platform page script with `node --check` and 9 rendering checks in node; platform whitelist scan clean.
Verified on Windows: nothing.
Not verified: Spring wiring, Jackson's JSON, `endedByError` at run time, the IBM runtime's OS bean,
AIX.

## Follow-up
- When a snapshot arrives from the UAT host: decide the real fix (heap, disk, or a lock).
