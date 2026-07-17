# diff: make it cancellable + query timeout (Stop now works on diff)

## Problem
A running `diff` step could not be stopped. `runDiff*` was invoked without the
`RunControl`, so it never checked the abort flag and — critically for CSV_KEY —
never registered its H2 `Statement`, so an operator Stop had nothing to cancel.
Combined with the pre-fix CSV_KEY query, a diff could hang for hours with Stop
ineffective.

## Fix (mirrors the csvsql cancellation pattern)
- `run()` now passes `control` to `runDiff`, which threads it to
  `runDiffKey` / `runDiffText` / `runDiffTextSet` and the positional path.
- CSV_KEY (`runDiffKey`): right after creating the H2 `Statement` it sets
  `setQueryTimeout(qto)` (qto = the step timeout via `stepTimeoutSec`, same source
  as csvsql) and registers `control.statement = st`; the finally clears it. So an
  operator Stop calls `statement.cancel()` on the live diff query (WorkflowEngine
  already does this for csvsql), and a step timeout bounds the query even without
  Stop. An `aborted` check runs before the heavy query.
- Streaming paths: POSITIONAL checks `control.aborted` periodically inside the
  row loop (returns cleanly via the existing try/finally); TEXT and TEXT_SET check
  `aborted` before starting.

## Verify
The real `runDiffKey` was run with a mock RunControl: a normal run (control live,
query timeout 30 s) gives the correct diffCount and clears `control.statement` in
the finally; an aborted run returns exit -997. The cancellation mechanism is the
same one csvsql uses (WorkflowEngine cancels `control.statement`), which is proven;
the mid-query cancel itself needs a concurrent canceller and was not live-tested in
this chat sandbox. Full Maven build to be confirmed on deploy.

## Operator note
Setting a step timeout (or the app default step timeout) now also caps the diff
query, so a runaway CSV_KEY diff fails on its own instead of hanging.
