# Log report — Batch 2b: the /logs page (one grid, source selector)

Single page, single grid, with a source switch instead of two tabs (your call): **Events** reads
`/api/logs/search`, **Runs & output data** reads `/api/logs/runs`. Filters, paging and the grid are
shared; only the columns and the two source-specific filters change.

- Filter bar built from `/api/logs/facets`: source id, target id, feed (id + name), event, severity,
  run status, plus free text, from/to and user. Event/severity show for Events, run status for Runs;
  everything else applies to both, so switching source keeps the current selection.
- Events columns: when, feed (+PROD flag and name), source, target, run, step, event, severity badge,
  user, details. Runs columns: started, ended, feed, source, target, run, status badge (+message),
  trigger and who, steps ok/total with failures highlighted, and **output data** rendered as
  `description = value` lines — the same information Operations shows.
- Feed links to the workflow page and the run id to `/run/{feedId}/{runId}`, so the grid is a jumping
  point into the existing pages rather than a dead end.
- Server-side paging (100/page) with prev/next and an `x-y of N` counter; the footer also reports what
  the index currently holds (events, runs, outputs, window days) so an empty result is easy to tell
  apart from an empty index.

Route added in PageController, link on the dashboard. The page uses the new cache-busting includes.

Verify: logs.html passes node --check with zero literal \n/\r and no unsafe Thymeleaf; all three static
includes carry `(v=${buildId})`. Rendering could not be exercised in the sandbox (no running app): after
deploying, open /logs — the footer counter tells you immediately whether the index is populated.
