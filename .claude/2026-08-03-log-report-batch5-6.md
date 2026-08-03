# Log report — Batch 5 (export, reindex) and Batch 6 (cold scan outside the window)

## Batch 5 — CSV export and reindex from the UI
`GET /api/logs/export` streams the whole filtered set with `StreamingResponseBody`, paging through the
index instead of materialising it, so a large export costs bounded memory; a UTF-8 BOM is emitted so
Excel opens it correctly, and the hard stop is one million rows. Columns match the grid, with the run
export flattening the output data as `name=value | name=value`.

RBAC Phase 1 is NOT on main, so this endpoint is **ungated** — anyone who can reach the app can export
the whole audit trail. That is a deliberate, temporary state: `openproteo.logreport.export-enabled=false`
turns the endpoint off (403) until role scoping lands, and it should be gated the moment auth is merged.

The page gets a CSV button and a Reindex button (confirmation dialog, then it reports the rebuild time
and refreshes the grid).

## Batch 6 — cold scan
The window (90 days) and the incremental byte-offset tail already shipped in Batch 2a. What was missing
is the fallback: a query whose `from` predates the window cannot be answered from memory. `LogIndexer
.coldScan` now reads the audit files directly, **restricted to the feeds in the filter** and capped at
20k rows, without writing anything into the index. `LogQueryService.search` routes to it automatically
and applies the same predicates in Java (event, severity, user, step exact/prefix, free text), sorts and
pages the result.

If such a query names no feed, source or target, it is refused with an explanatory message instead of
scanning all 144 files — the one thing the spec explicitly forbids. The response carries `coldScan` and
`truncated`, and the page shows "read from the files (outside the in-memory window)" so the operator
always knows which path answered.

## Verify
The CSV escaper was compiled and executed: delimiter and quote force quoting, inner quotes are doubled,
CR/LF collapse to a space so a row can never break the file, null and empty give an empty field. Brace
balance checked with the string/comment-aware counter; logs.html passes node --check with zero literal
\n/\r and no unsafe Thymeleaf. The cold-scan path could not be exercised end to end here (no H2, no real
audit files) — after deploying, query a range older than 90 days for one feed and check that the footer
reports the file path.
