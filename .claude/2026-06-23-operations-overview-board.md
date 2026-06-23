# Operations board: live executions + per-source rollup (#4)

## What
New page **/overview** ("Operations", linked from the dashboard) with two auto-refreshing sections.

### Executions in progress (auto-refresh 3s)
Backend `GET /api/overview/active` returns `engine.queueSnapshot()` (in-memory, cheap): every
queued/running/waiting run across all feeds, enriched with the workflow name, source (id +
description), status, started time, trigger/operator, and progress (done/total steps + the step
currently RUNNING). The table offers an **Open** link to the run page and a **Stop** button
(POST /api/runs/{feedId}/{runId}/stop) — which, combined with the earlier Stop fix, now actually
cancels a running csvsql query.

### By source rollup (refresh 20s + manual)
Backend `GET /api/overview/rollup` reads the latest run of each feed once and buckets every feed by
status per source: **not run / running / success / failed / aborted / other**. Running is detected
via `engine.activeRunId` (in-memory) so active feeds skip the disk read. Returns per-source counts +
global totals. The page shows summary tiles (Feeds / Running / Success / Failed / Not run / Aborted /
Other) and a sortable-by-total table with a per-source stacked mix bar.

## Files
- `web/ApiController`: `overviewActive()` + `overviewRollup()`.
- `web/PageController`: `/overview` route.
- `templates/overview.html`: the page (vanilla JS polling, no framework; UBS-safe — no literal
  `\n`/`\r`, no `[[`/`[(`).
- `templates/dashboard.html`: "▦ Operations" nav link.

## Notes / limits
- The rollup status is per feed's **latest** run (history-based), not a full per-run tally.
- The rollup does up to one small disk read per non-active feed each refresh (~140); fine for an ops
  page. If it ever needs to be cheaper, cache the last-run status and invalidate on run completion.

## Verification
- Compiles (97 classes). overview.html JS `node --check` OK; no literal `\n`/`\r`; no unsafe `[[`/`[(`.
- Live data is UBS-side.
