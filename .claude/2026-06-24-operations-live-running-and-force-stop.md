# Operations live RUNNING + force-stop safety valve

## Symptom
After stopping a run, the Operations board (tiles + by-source rollup) kept showing it as RUNNING; and
stopping a stuck AS400 SQL extraction took "more than one try".

## Fixes
1) **Operations RUNNING is now live.** In GET /api/overview/feeds the `running` flag (and the derived
   `bucket`) were cached together with the heavy last-status disk reads (10s TTL), so a feed stayed
   RUNNING for up to a cache cycle after its run ended. Now the heavy fields stay cached but `running`
   and `bucket` are recomputed on **every** call from in-memory engine state (engine.activeRunId),
   cheap, so a feed leaves RUNNING the instant its run is removed from activeRuns (i.e. as soon as it
   finishes/aborts).

2) **Force-stop on repeat Stop.** If the operator presses Stop again while the run is still active
   (a worker genuinely stuck on a blocked native/IO call that ignores cancel+connection-close), stop()
   now finalises the run ABORTED immediately and abandons the worker thread, so the UI/Operations never
   stay stuck on RUNNING. Audited RUN_FORCE_STOPPED. The first Stop still does the graceful
   cancel + DB-connection-close (from the previous change); the second is the escape hatch.

3) **finish() is idempotent + thread-safe.** A synchronized check-and-claim means the first
   finalisation wins, so the abandoned worker re-calling finish() after a force-stop can't flip the
   status or double-audit.

4) **UI.** The run page Stop confirm text is now generic (queries are cancelled and the DB connection
   closed), and after the first Stop the button relabels to "Force stop" so a second press is clearly
   the hard kill.

Compiles (101 classes). run.html JS node --check OK; no literal \n/\r; no unsafe [[ /[(.
Note: this complements the connection-close aborter (sql/DB2) shipped previously — deploy that too.
