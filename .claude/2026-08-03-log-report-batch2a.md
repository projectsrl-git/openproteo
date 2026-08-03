# Log report — Batch 2a: rolling window, run + output-data indexing, facets

Decisions taken (spec §10): rolling window **90 days**, refresh **10s**. Feed/log directories are
already configurable and were not touched.

## Design change vs the spec
`.claude/LOG_REPORT.md` §2.2 excluded `_runs/{runId}.json` as a search source. That has to change: the
audit line only carries `exitCode`/`attempts`/`reason`, so the **output data** shown in Operations and
in the run history is NOT in the audit trail at all — it lives in `run.vars`, matched against the
`outputData` declarations on the workflow. Indexing runs is therefore required to deliver what was
asked, and the spec section is superseded by this note.

## What ships
- **Rolling window** — `openproteo.logreport.window-days` (default 90). Audit entries and runs older
  than the cutoff are not loaded; for audit files the seq pointer still advances past them, so old
  lines are read once and never re-examined. Setting it to 0 disables the window (load everything).
- **`run_entry`** (feed_id, run_id, status, trigger, triggered_by, start/end, message, steps
  total/ok/failed) and **`run_output`** (feed_id, run_id, var_name, label, value, ts) — the declared
  output variables with their descriptions and the values from that run.
- Run files are rewritten while a run progresses, so they cannot be tailed like the append-only audit:
  the indexer keeps a `size:mtime` stamp per file, re-reads only what changed, and replaces that run's
  rows (delete + insert) so a run that moves from RUNNING to SUCCESS is never duplicated.
- **`GET /api/logs/runs`** — filters feed/source/target/status/user/time plus free text, paginated,
  each row carrying its `outputData` list. The free text also searches inside the output values,
  labels and variable names (`EXISTS` subquery), so "find the run that produced 21292 rows" works.
  Outputs for the whole page are fetched in one extra query, not one per run.
- **`GET /api/logs/facets`** — feeds/sources/targets from the registry, events/severities/statuses/
  output variable names/users from the index.
- `/api/logs/status` now also reports `windowDays`, indexed `runs` and `outputs`.

## Not yet
The `/logs` page itself (Batch 2b), timeseries, metrics, export, RBAC gating.

## Verify
Brace balance checked with a string/comment-aware counter. Every new statement (run DDL, status IN,
time range, the free-text EXISTS over run_output, the batched per-page output fetch, and the four facet
queries) was executed against a real SQL engine with the expected results — notably a search for
`21292` returns exactly the run whose OUTPUT DATA contains it. H2 itself still cannot be exercised in
the chat sandbox (Maven Central unreachable), so the JDBC path runs first on your build; check
`GET /api/logs/status` for `runs`/`outputs` counts right after deploy.
