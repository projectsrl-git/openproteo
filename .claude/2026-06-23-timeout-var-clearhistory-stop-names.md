# stepTimeoutMins variable, per-feed Clear History, working Stop, source/target names

## 1) Standard variable stepTimeoutMins (default 5 minutes), specialisable per step
- Every run now seeds `stepTimeoutMins=5` at the lowest precedence (overridable globally, per-workflow
  via def variables, or per-step via `stepTimeoutMins.<stepId>`).
- `InternalSteps.stepTimeoutSec(stepId, stepTimeoutSecField, vars, default)` resolves the effective
  timeout: explicit per-step **TIMEOUT SEC** field (if > 0) wins, else `stepTimeoutMins.<stepId>`
  (minutes), else `stepTimeoutMins` (minutes), else the app default.
- The engine uses it for every step (external waitFor + internal csvsql query timeout). Cheat sheet
  and path autocomplete list `${stepTimeoutMins}` / `${stepTimeoutMins.<stepId>}`.

## 2) Clear History — two data-loss bugs fixed
- **2.1 scope**: the Clear History button called the ADMIN endpoint that wiped *every* feed under the
  base directory. New per-feed endpoint `POST /api/workflows/{feedId}/clear-history`; the button now
  targets the current workflow only (the admin "wipe all" endpoint still exists but is not wired to the
  button).
- **2.2 uploads preserved**: per-feed clear deletes `_runs`, `_logs/runs` (step logs), the step working
  dirs, `99_landing_out` and `_h2`, but PRESERVES the feed-root uploads (dataschema/displayschema/
  scripts + `_assets.json`), the declared input `00_landing_in`, and the audit trail
  (`_logs/audit_*.jsonl`). Refuses on active run or PRODUCTION.
- **2.3 (deferred)**: bulk "schema only" mode to recreate/refresh dataschema+displayschema of existing
  feeds from the bulk CSV — see "Next" below.

## 3) Show source/target NAMES next to ids
Dashboard feed list (Source/Target columns), the workflow page header and the run page header now show
the `sourceDescription`/`targetDescription` next to `sourceId`/`targetId`. (The designer already has
editable description fields.) The dashboard per-source summary panel is part of #4 below.

## 6) Stop now actually stops a running step
Internal steps ignored Stop because they have no OS process and the long work (an H2 query) does not
poll the abort flag. `RunControl` now carries the active JDBC `statement`; `runCsvSql` registers it
around staging and the query, and `engine.stop()` calls `statement.cancel()` (in addition to killing
any external process). A running/queued csvsql query is now cancelled promptly; the run finishes
ABORTED.

## Not done in this batch (need a dedicated pass)
- **2.3** bulk schema-only updater.
- **4** a live "all executions in progress" board + a per-source rollup (not-run / running / success /
  failed). The data exists (run store + sourceStats); this is a new page + endpoint.
- **5** step-by-step execution while another run is in progress. The runner is a SINGLE-THREADED
  executor (`wf-runner`), so a test run currently queues behind the active one. Supporting concurrent
  ad-hoc/test runs needs a separate execution path (isolated control + a dedicated thread) and care
  around shared step directories — worth its own change.

## Verification
- Compiles (96 classes). Designer JS `node --check` OK; no literal `\n`/`\r`; no unsafe `[[`/`[(` in
  edited templates. Live behaviour (stop cancelling H2, clear-history paths) is UBS-side.
