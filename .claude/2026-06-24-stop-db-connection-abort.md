# Stop now aborts AS400/DB2 SQL extractions (close-connection aborter)

## Symptom
After the previous fix (register the JDBC Statement so Stop calls cancel()), Stop still left a SQL
extraction RUNNING — observed on a TEST run of step SQL_EXTRACTION against the AS400 (jt400) source.

## Root cause
The control wiring was correct (runOnce passes the same RunControl that stop() uses, for normal AND
test runs). The problem is the driver: AS400 **jt400 Statement.cancel() does not reliably interrupt a
running query/fetch**, so cancel() returned and the call stayed blocked.

## Fix
On Stop, in addition to Statement.cancel() (which works for csvsql/H2), forcibly **close the statement
and the connection** of the running extraction — closing the socket makes the blocked
executeQuery/next() throw, which is the reliable cross-driver way to abort.
- RunControl gains `aborter` (a Runnable). SqlSupport.exportCsv registers an aborter that does
  cancel + close(statement) + close(connection); runSql wires `control.aborter = r`.
- engine.stop() runs the aborter on a short-lived daemon thread (so a slow close never blocks the Stop
  response), alongside the existing statement.cancel() and process.destroyForcibly().
- The thrown SQLException is caught in InternalSteps.run (exitCode set) -> executeStep returns not-ok
  -> the run finalises ABORTED (loop for normal runs; testStep/runSessionStep now also map aborted ->
  ABORTED rather than FAILED).

## Scope
Fixes the reported case (AS400/DB2 `sql` extraction) and any JDBC extraction. csvsql (H2) keeps using
cancel(). Purely in-memory CPU-bound internal steps still finish the current step before aborting.

Compiles (101 classes).
