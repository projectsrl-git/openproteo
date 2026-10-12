# Runs that stop between two steps: diagnostics, and the end of "RUNNING for ever"

Spec and record of one delivery, 2026-10-12, on `d25a1ce`. Author's request, after the second
stuck run on the UAT host (AIX): "a package that collects the information the next time it hangs,
and have the system administrator copy the new war" - the administrator is in another country, has
little time, and the author has the GUI only.

## 1. What was seen

* **2026-10-09.** A check run: `Look inside the package` (bash) SUCCESS at 06:14:48; the next step,
  a `setvar`, never started (PENDING, no start time); trigger `RESUME`; the first Stop did not end
  it, the second did (`Force-stopped`): the run was RUNNING and no thread reacted.
* **2026-10-12** (server time). Run started 12:08:00; `List packages` started only at 12:15:46 and
  lasted 1 s; the next step 12:15:47; `Look inside` started 12:21:51, six minutes after, and
  lasted **14 min 32 s** - 12 s for the same kind of package on 2026-10-09; at 13:00 the next
  `setvar` had not started 24 minutes after.

What is known from the code (`WorkflowEngine`, at `d25a1ce`): between two steps the engine only
saves the run (`RunStore.save`, `synchronized` on the store: one lock for all feeds), writes audit
lines (`AuditLogger.log`, one lock per feed), evaluates gates and moves an index. Minutes there mean
one of: the thread waits for one of those locks; a file write that does not return; a Java runtime
spending its time in garbage collection; or the thread is gone - an `Error` thrown outside a step
reaches the run pool, which logs `run task ... crashed` and leaves the run RUNNING for ever
(`schedule()`, `catch (Throwable)`; the step-level net of `2c0c19a` does not cover it). The bash
step being 70 times slower points to the host as well (CPU starved by GC, paging, or a file system
that answers late): the JVM alone cannot slow `tar`.

**Not known:** which of these it was. That is what this delivery is for.

## 2. What was built

1. **`diag/StallSnapshot`** (JDK only): a text snapshot - engine state; the `wf-runner` threads at
   the start; the bookkeeping-write timings; a disk probe per folder (create, write 4 KB, sync,
   delete, list, free space) with a 15 s timeout; GC count/time and process CPU over a 10 s
   sample; heap, pools (with use after the last collection); host load and memory as the
   operating-system bean reports them (read by name through its public interfaces: com.sun on
   HotSpot, com.ibm on the IBM runtime); deadlock detection; the full thread dump, `wf-runner`
   first, lock owners named, held monitors marked.
2. **`diag/StallWatchdog`** (pure logic): the rule of §3.
3. **`diag/IoTimings`**: lock-wait and write time of every `RunStore.save` and `AuditLogger.log`;
   counts and maxima per kind; the calls of 2 s or more in a ring of 40.
4. **`diag/DiagnosticsService`** (Spring component): a check every 30 s, snapshots kept in memory
   (12) and written by a thread of its own to `<log dir>/diag/` (50 kept); snapshot on request,
   at most one per 30 s.
5. **`diag/DiagnosticsController`**: `GET /api/diagnostics` (list), `POST /api/diagnostics/snapshot`,
   `GET /api/diagnostics/{id}` (the text, as an attachment).
6. **Platform page**: section "Diagnostic snapshots", button and list.
7. **`WorkflowEngine`**: `catch (Error)` in `loop` and `continueFromTarget` - the run ends FAILED
   with a message naming the error and, for an OutOfMemoryError, the maximum heap; `liveRuns()`.
8. **`RunStore.save`**: the method-level `synchronized` becomes a `synchronized (this)` block, same
   monitor, so that the wait before it can be measured. **`AuditLogger.log`**: two timestamps
   around the existing lock.

## 3. The rule, and why only that

A run is judged when it is RUNNING, is not a test run, and has no step RUNNING. Its fingerprint is
the current node, the last gate's time, and the status, end time and attempts of every step. If the
fingerprint has not changed for **120 s**, a snapshot; while it stays the same, one more every
**15 min**, **3** at most. Checked every **30 s**.

**∩ D1 — "a stuck run" × "a long step".** A step RUNNING for an hour may be a legitimate unpack of
100 000 objects; nothing in the engine says how long a step should take. Decided: only the gap
BETWEEN steps is judged, where the engine needs milliseconds - the two cases of §1 are both gaps,
and a slow step is still visible in the snapshot's GC and disk sections when one is taken by hand.

**∩ D2 — "Default conservativi: every new behaviour is born off" (CLAUDE.md) × a watchdog on by
default.** The watchdog changes no feed output, no run and no file of a feed: it reads, and
writes its own text files under the log directory. A diagnostic that has to be switched on before
the problem happens is no diagnostic for a problem that happens once a week on a server the author
cannot reach. Decided: on. The one behaviour that does change a run - an Error between steps now
ends it FAILED instead of RUNNING for ever - is a defect closed, not a feature added.

**∩ D3 — "Configurabilità integrale da GUI" (CLAUDE.md) × thresholds.** 120 s, 15 min, 3, 30 s,
12, 50 are constants, not settings: no key is added, so nothing becomes file-only debt. Named in
USAGE and here.

**∩ D4 — "the Platform page is public until authentication exists" × thread dumps.** A snapshot
shows feed and run names, probed folders and every thread's stack. It never shows run variables,
system properties other than `java.version`, `java.vendor`, `os.name`, `os.arch`, `user.dir`,
`java.io.tmpdir`, environment variables, or the JVM's command-line arguments (which can carry
passwords). Asserted by the suite: a run with a property, an environment variable and a JVM
argument carrying sentinel values, none of which appears.

**∩ D5 — "a disk that does not answer" × "a snapshot that must be taken".** The probe runs on a
thread of its own with a 15 s timeout; a folder whose probe has not returned is not probed again
(the next snapshot says since when); the file is written by a third thread, and a snapshot taken
while the previous one is still being written is kept in memory only, saying why. The workflow
registry's `synchronized` methods, needed to name a run's current node, get 5 s; past that the
snapshot is taken without the names, saying so.

**∩ D6 — "one lock for all feeds" (`RunStore`) × measuring the wait.** The monitor is unchanged
(`this`, shared with `delete`); only its form changes, from method modifier to block, so that a
timestamp can be taken before it. Same for `AuditLogger`, where the lock already was a block.

## 4. Evidence (Linux; Temurin 1.8.0_432 and OpenJDK 21.0.12)

* The six classes of `diag`, `RunStore`, `AuditLogger`, `AppProperties`, `FeedLayout`,
  `HostFiles`, `PlatformProbe` and the model compiled for real with `javac` 8, `-Xlint:all`, no
  warning in the touched files, against stubs of SLF4J, Spring (annotations, `Environment`,
  `ResponseEntity`, `HttpHeaders`, `MediaType`), the servlet request, Jackson's `ObjectMapper`
  (NOT Jackson: enough to run `save` and `log`), and of `WorkflowEngine` and `WorkflowRegistry`.
* `TDiag`, 57 assertions; 59 with the 2-minute end-to-end case (Temurin 8); 60 with also the
  read-only folder, run as uid 65534 (JDK 21). Repeated twice more on each JDK without it:
  a `wf-runner` thread BLOCKED on a monitor, named with its holder, its frame, the holder marked;
  GC and CPU measured; the slow write and its totals; the disk probe on a real folder, a missing
  one, a folder given twice, a read-only one (as non-root), and a probe that never returns (given
  up after its timeout, not restarted while stuck, restarted once it returns); the ring of 40;
  the watchdog's rule case by case (119/120 s, repeats and maximum, a run that moves, a loop pass,
  a long step, ON_HOLD, a test run, a run RUNNING before its first step, a run that leaves and
  returns); `RunStore.save` waiting for its lock held by another thread (wait measured, run
  saved), a slow write (work measured), a fast one (counted only); `AuditLogger.log` waiting for
  the feed's lock; the service and controller: a snapshot on request (who asked, the node named
  from the registry), the 30 s floor, the file under `logs/diag`, pruning to 50, list, download
  with its header, 404; a registry held by another thread (5 s, said, runs still listed); the
  watchdog end to end, two looks 121 s apart, snapshot taken and logged.
* `WorkflowEngine`: differential compilation of `WorkflowEngine`, `RunStore`, `AuditLogger` and
  the `diag` classes against the REAL engine and registry, before and after: identical error
  sets, all in classes whose libraries are not in the sandbox (POI, JT400); positive control - a
  call to a missing method in `endedByError`, and one in `RunStore.save`, both reported.
* Mutations on copies, 24 in all: 20 caught at the first run; 2 did not compile as written
  (a `get()` without timeout leaves the `TimeoutException` catch unreachable) and were rewritten
  as a one-hour timeout, then caught; 2 survived and were holes in the suite, each closed with
  its own case: the order of the full dump (the blocked run thread came first anyway, by its
  BLOCKED state - a sleeping second `wf-runner` thread now has to come before every other), and
  the lock owner in the full dump (only the early section was checked). One survivor is
  equivalent and kept: removing only the `wf-runner` rule leaves the `wf-` rule.
  A first harness run of the not-root case failed only because uid 65534 could not reach the
  JDK 8 under the session's private temporary folder; it runs on JDK 21.
* Platform page: inline script through `node --check`; no `\n` or `\r` literal in it; no `[[`.

**Not verified:** the engine itself - `endedByError` is compiled and read, never run (it needs an
Error thrown between two steps of a real run); Spring's wiring of the new component and
controller; the JSON of the list endpoint as Jackson writes it; the IBM runtime - in particular
the `com.ibm.lang.management` names and the factor 100 of its `getProcessCpuTime`, taken from its
documentation, not run; AIX; Windows.
