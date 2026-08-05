# DESIGN — `audit_report.md`: a per-run evidence report for every successful run

Status: **CONFIRMED and implemented.**
Scope: new report generator, hooked to run completion + an on-demand backfill for past runs.

## 1. Request

1. For every **successful** run, including ones that have **already happened**, produce an
   `audit_report.md` containing the output results of each step and all the output variables
   (OUTPUT VAR) shown in the Operations run summary ("output data" column).
2. The report is divided into one **paragraph per step**, with the start and end timestamps of each
   step.

## 2. Findings (verified against `main` @ 34e21cf)

### F1 — per-step attribution of output variables is already in the run JSON

This is the finding that makes the whole thing feasible for past runs. `run.vars` is a flat map, so
at first sight there is no record of which step produced which variable. But `WorkflowEngine:947-951`
writes **both** forms on every step output:

```
run.vars.put(ov.getKey(), ov.getValue());
run.vars.put(step.id + "." + ov.getKey(), ov.getValue());   // namespaced: ${stepId.var}
```

So for step `S` the variables it produced are exactly the `run.vars` keys starting with `S.`, and
they are already persisted in `{feedId}/_runs/{runId}.json`. **No audit-trail replay is needed** and
nothing has to be reconstructed from the step logs.

Caveat to handle: the namespaced form only exists for runs executed by an engine that already wrote
it. A run whose `vars` contains no `S.` key for any step id is from before that, and the report must
say the per-step breakdown is unavailable for it rather than print an empty section that looks like
"this step produced nothing".

### F2 — "output data" in Operations is a DECLARED list, not simply the run's variables

`ApiController:1576-1603` builds the column from the **definition**: every step param named
`outputData.<varName>` plus the workflow-level `def.outputData`, with the label being the
description. The value then comes from `run.vars.get(varName)`.

Two consequences:

* the declared list belongs to the **current** definition, so for an old run the workflow may now
  declare different variables than the run actually produced. The value lookup still works (it reads
  that run's own vars) but a variable added since will simply be empty, and one removed since will be
  absent from the report even though the run produced it;
* the declared list and the raw per-step outputs are **different sets**. The request names both -
  "output results of each step" and "all the output variables shown in the summary" - so the report
  needs both, not one standing in for the other.

### F3 — everything else the report needs is already in `StepExec`

`stepId`, `name`, `status`, `startTs`, `endTs`, `exitCode`, `attempts`, `message`, `logFile`
(relative to `_logs`), and `checks` for validate steps. Gates are in `GateExec` with their condition,
result, who decided and when. Requirement 2 is satisfiable directly from the run JSON.

### F4 — run files are all retained and enumerable

`RunStore.list(layout, max)` with `max <= 0` parses every `{runId}.json` in `_runs`. Backfill can
enumerate the whole history per feed. Test runs are identifiable by `_test_` in the runId, as
elsewhere.

## 3. Decisions

**D1 — the report has three sections, in this order.**
1. *Run summary*: feed, name, run id, trigger, who started it, start/end/duration, status; and, when
   the feed is versioned, `parentId`.
2. *Output data (as shown in Operations)*: the declared OUTPUT DATA list of F2, label -> value, which
   is the section the request explicitly points at.
3. *Steps*: one paragraph per step, in execution order.

**D2 — one paragraph per step contains**: heading `N. <stepId> — <name>`; status, exit code and
attempts; **start and end timestamp plus the computed duration**; the message when there is one; the
validate checks when present; the variables that step produced (from F1); and a reference to its log
file. Gates are rendered as their own short paragraph in sequence, because a report that silently
drops the approval step is not evidence.

**D3 — the step's standard output is INLINED** (confirmed): the same lines the run page shows when
you click *open* on a step, so for a `sql` step the executed query is in the report. Shortened when
long, but as **head 100 + tail 400** rather than the pure tail the run page uses: the query, the
datasource and the parameters are printed at the START of a step log, and a tail would cut exactly
what the report is for. The omission is marked in the text. A ``` inside a log is neutralised to
`'''` so it cannot close the fence and turn the rest of the log into Markdown.

**D4 — only `SUCCESS` runs, and never `_test_` runs.** As asked. A failed run's evidence is its log
and its audit trail; generating a "report" for it would invite it being read as a delivery record.

**D5 — the file is written to `{feedDir}/_logs/runs/{runId}/audit_report.md`** (confirmed).
That directory already exists per run and already holds the step logs, so the report lands next to
the material it cites and is removed together with the run when history is cleared.

**D6 — on request only, in two places** (confirmed, and narrower than this spec first proposed).
There is NO hook on run completion and no mass backfill over the whole history:

* `POST /api/runs/{feedId}/{runId}/audit-report` — one specific run, from the button next to
  *open* in Run history, shown only on a SUCCESS run;
* `POST /api/workflows/{feedId}/audit-report/last` — the LAST run of one feed, driven by the
  Operations multi-select bar, which loops it over the selected feeds.

Re-running simply overwrites, so both are safe to repeat. Nothing walks 144 feeds' entire history on a
deploy.

**D7 — a failure to write the report never fails the run.** It is generated after the run reaches
SUCCESS; an IO error is logged and audited, and that is all. The report is a derived artefact and
must not be able to turn a good run red.

**D8 — no new dependency.** Plain string building and `Files.write`, like the `diff` and `sqlreport`
reports already do.

## 4. Known limitation, stated in the report itself

The declared output-data list is the one in the workflow **as it is now**. For an old run, a variable
added since will show empty and one removed since will not appear at all, even though the run
produced it. This cannot be fixed for history - the definition of the day is not kept alongside the
run - and it is precisely what workflow versions exist to avoid going forward. Both the docs and the
report say so rather than presenting a partial list as complete.

## 5. Verification plan

### Verification actually performed

`RunAuditReport` compiled standalone with `-source 8` against the real run model and exercised by 34
assertions, all passing: the header and the computed run duration; the Output data section including
a declared variable the run never produced showing empty rather than being dropped; steps numbered in
order with a gate placed chronologically **between** them; per-step start/end timestamps and
duration; per-step variables, with a grandchild key (`SEND.sentFile.checksum`) correctly NOT listed
as a step variable and the plain non-namespaced `feedId` correctly not attributed to any step; the
executed SQL query present in the inlined standard output; a step with no log saying so; a run
predating the namespaced vars getting the explanatory note and NO misleading empty variables table
while its Output data still resolves; the `Version of` row appearing only for a versioned feed; and
the escaping helpers - pipe, folded newline, a ``` inside a log neutralised while a mid-line backtick
run is left alone, negative/unparseable/missing durations returning null, and the ms/s/h formatting.

`declaredOutputVars` was extracted from the feeds endpoint rather than duplicated, so the report and
the Operations column are the same code path by construction. Brace/paren balance 0/0 on both Java
files; import checker green; `node --check` on the inline JS of both `overview.html` and `runs.html`,
zero literal newline/CR escapes, and the only Thymeleaf inlining marker is the pre-existing commented
one in `runs.html`.

**Not verified**: `mvn clean package`, and a report generated from a real run on disk.
