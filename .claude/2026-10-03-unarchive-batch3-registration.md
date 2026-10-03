# unarchive — Batch 3: registration, runUnarchive, USAGE.md

The executor is now reachable from a workflow XML (`exec="unarchive"`). The designer panel and its
three registration places are batch 4. Spec `.claude/UNARCHIVE_EXECUTOR.md` §11 and §19.

## What changed

* `WorkflowXmlParser`: whitelist, the error message listing the valid execs, the `internal` set (no
  `script` attribute required).
* `WorkflowEngine.internalKind()`.
* `InternalSteps`: the dispatch, passing `control` for Stop, and `runUnarchive` — 19 parameters in,
  nine outputs out on every path, exit code set explicitly: 0 done, 2 refused, -997 Stop, 1 I/O.
* `USAGE.md`: "The unarchive step" and its line in the executor list.

## Found while wiring

* **The dispatcher's catch keeps -1.** It does `res.exitCode = res.exitCode == 0 ? 1 : res.exitCode`,
  so an exception that reaches it while the code is still the initial -1 ends the step as -1, not 1.
  `runUnarchive` handles its own exceptions; the suite asserts the code is never -1. The spec said
  "1 via the generic catch" — struck through.
* **The exit-code lint would flag `res.exitCode = code` as a false alarm** (used by `filerename` and
  here). Extended so a variable counts through its own assignments and a call is opaque; still flags
  `runObjPack` on the pre-fix code, exactly as the original did.

## Verification

* Wiring, 78 assertions, on methods lifted verbatim from the sources by a script and the real parser.
* Mechanical: spec table == names read (19/19), defaults equal across spec, adapter and `UnarchiveRun`,
  dispatch passes `control`.
* USAGE.md through `docs.html`'s own `render()` with jsdom, 39 assertions; the pre-patch guide fails
  19; a guide with one default changed fails on exactly that default.
* Lint: 30 `run*` methods, none flagged; positive controls flag what they must.
* Batch 1 and 2 suites re-run green (232 + 194).
* Wiring mutations: 17, all caught — two only after closing real gaps (no test proved each numeric
  parameter reaches its own limit; the configuration cases ran on a hostile feed that ended in exit 2
  anyway).

## Not verified

`mvn clean package` — the first batch that touches `InternalSteps`, `WorkflowEngine` and the parser;
a run inside the container; Windows; Java 8 at runtime.
