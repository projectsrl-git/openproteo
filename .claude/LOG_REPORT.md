# Cross-Feed Log Report (audit search, timeline, metrics) — Design & Handoff Spec

## 0. Before you start
Read `CLAUDE.md` and `.claude/claude-code-prompt.md` first — this spec assumes
and does not repeat the hard constraints and workflow rules defined there
(Java 8, no CDN/new deps without Nexus confirmation, no literal `\n`/`\r` in
JS, no unsafe Thymeleaf inline, PII never logged, spec-first + `COMMIT_MSG.txt`
discipline, four-location rule for new internal executors — not relevant here,
no new executor is added).

This feature does **not** add any new logging anywhere in the engine. It adds
a **read-only aggregation/search layer** over log data OpenProteo already
writes. That is the central design decision and it drives everything below.

## 1. Goal
One page, `/logs`, that lets an operator search **every audit event of every
feed** in one grid, filter by source / target / workflow (feed) / step /
event / user / time range, see a clickable activity timeline on top (Grafana
style: brush-select a range, click a bucket to zoom), see dynamic statistics
that are themselves clickable filters, and export the current result set to
CSV. Today `/audit/{feedId}` only shows one feed at a time with no filtering
beyond the URL, and `/api/audit/{feedId}/entries` caps at the last 5000 lines
in memory per call. Neither supports cross-feed search. `/audit/{feedId}`
stays exactly as-is (hash-chain verification view, per-feed) — this is a new,
additional page, not a replacement.

## 2. What "the logs" actually are (no new instrumentation needed)
Three things exist on disk per feed under `{baseDir}/{feedId}/_logs/`:

1. **`audit_{feedId}.jsonl`** — append-only, one JSON object per line, SHA-256
   hash chain (`AuditLogger`). Every field we need is already here: `seq`,
   `ts` (ISO-8601 with offset), `feedId`, `runId`, `node` (= stepId), `event`,
   `user`, `details` (free-form key/value map). `WorkflowEngine.auditFeed(...)`
   is already called for **every** step/run/gate/loop lifecycle transition —
   `RUN_QUEUED`, `RUN_RUNNING`, `STEP_STARTED`, `STEP_COMPLETED`, `STEP_FAILED`,
   `STEP_SKIPPED`, `STEP_RETRY`, `GATE_WAITING`, `GATE_DECISION`, `LOOP_START`/
   `LOOP_END`, `RUN_ON_HOLD`, `RUN_RESUMED`, `DELETE_ON_SUCCESS*`, etc. — plus
   feed/config-level events (`FEED_LOCKED`, `WORKFLOW_SAVED`, `FILE_UPLOADED`…).
   **This file is the primary and only search source for this feature.** It is
   already structured, already curated (no raw PII dumps — the constraint is
   enforced today by what callers choose to put in `details`), and already
   has exactly the four requested filter dimensions once joined with the
   feed's `WorkflowDef` (below).
2. **`_runs/{runId}.json`** (`WorkflowRun`) — full state snapshot per run.
   Redundant with the audit trail for our purposes (same lifecycle, just
   shaped as an object instead of a stream of events). **Not used** as a
   search source — would mean parsing two things instead of one for no new
   information.
3. **`_logs/runs/{runId}/{stepId}.log`** — raw PowerShell stdout/stderr, free
   text, unbounded size, no guaranteed structure. **Explicitly out of scope**
   for the grid/search/index: unstructured, potentially large, and the one
   place a script could leak something we don't want indexed and exported in
   bulk. It stays reachable only via the existing per-step drill-down
   (`GET /api/.../stepLog`) linked from a grid row — one click, one file, same
   access control as today. Nothing from it is ingested or cached.

`sourceId` / `targetId` / `sourceDescription` / `targetDescription` /
`production` / `name` are **not** on the audit line — they live on
`WorkflowDef`, keyed by `feedId`. Join at query time from
`registry.all()` (144 items, trivially cheap to hold as an in-memory map) —
do not denormalize them into the index, so a rename in the workflow XML is
reflected immediately without a reindex.

## 3. Indexing layer — why, and what technology
Reading and re-parsing 144 JSONL files (which only grow, forever, and must
never be modified — the hash chain is audit-proof, we must stay strictly
read-only against them) on every filtered request does not scale to "search
all history." We need a queryable cache, rebuildable at any time from the
files, that never persists as a second source of truth.

**Recommendation: an in-memory H2 instance** (`jdbc:h2:mem:logidx;DB_CLOSE_DELAY=-1`),
not a file-mode database. This is the same technology already vetted and
committed for `csvsql` and the `diff` CSV_KEY mode (already on the Spring Boot
2.7 BOM, Java 8-safe, runtime scope, no new dependency) — reused here purely
as a disposable SQL engine over transient data, not as a persistence layer.
`CLAUDE.md`'s "niente database server / lo stato vive su file" holds: the
JSONL files remain the only durable state; H2-in-memory dies with the JVM and
is rebuilt on next startup. This gets us filter/sort/paginate/group-by "for
free" instead of hand-rolling it in Java, and keeps the query code in one
place shared by the grid, the timeseries chart and the metrics cards.

Table shape (illustrative):
```sql
CREATE TABLE log_entry (
  feed_id     VARCHAR NOT NULL,
  seq         BIGINT NOT NULL,       -- per-feed chain sequence, NOT globally unique
  ts          TIMESTAMP NOT NULL,
  run_id      VARCHAR,
  node        VARCHAR,               -- stepId
  event       VARCHAR NOT NULL,
  severity    VARCHAR NOT NULL,      -- OK|FAIL|WAIT|RUN|SKIP|INFO, precomputed (see 6)
  user_name   VARCHAR,
  details     VARCHAR,               -- JSON blob, for the grid's "details" column + free-text search
  PRIMARY KEY (feed_id, seq)
);
CREATE INDEX ix_log_ts ON log_entry(ts);
CREATE INDEX ix_log_event ON log_entry(event);
```
`sourceId`/`targetId` are **not** columns — joined in Java from the
`WorkflowDef` map after the H2 query returns feed_ids, or exposed via a small
in-memory `feedId -> {sourceId, targetId, name, production}` lookup used both
to enrich rows and to build the `WHERE feed_id IN (...)` predicate for
source/target filters (H2 doesn't need to know about them at all).

**Indexing strategy — incremental, resumable, bounded:**
- Per feed, track the last indexed `seq` (kept in a small in-memory map,
  rebuilt by `SELECT feed_id, MAX(seq) ... GROUP BY feed_id` after any load).
- On startup: for each feed, tail its `audit_{feedId}.jsonl` from the
  beginning **only up to a configurable rolling window** (e.g. last N days —
  see open decision in §11), insert into H2.
- Every T seconds (or on-demand, lazily, before serving a request that needs
  fresher data than the last refresh — reuse the existing 10s-TTL pattern
  already used for `feedsCache` in `ApiController`), re-open each feed's file
  and append only lines with `seq >` the last indexed one. Cheap: this is a
  file tail, not a rescan, because JSONL is append-only and line order is
  chain order.
- A query whose time range falls (partly or fully) outside the loaded window
  falls back to an **on-demand cold scan restricted to the specific feed(s)
  and date range in the filter** — never a full 144-file scan. This is what
  makes "search all history" honest without loading years of data into heap
  by default. Once cold-scanned, those rows can optionally be merged into the
  in-memory table (cache warm-up) if reused.
- A manual `POST /api/logs/reindex` (PROTEO_MASTER only) forces a full
  rebuild — for recovery after an unexpected shutdown mid-refresh, or after
  changing the rolling-window setting.
- This whole indexer is a new, small, isolated component
  (`com.legalarchive.orchestrator.logreport.LogIndexer` or similar) — do not
  bolt it onto `AuditLogger`, which is a writer/verifier and should stay that
  way.

## 4. Query API (new controller, not `ApiController`)
`ApiController` is already ~170KB / one file for the whole app. Put this
feature in its own `LogReportController` + a small `LogQueryService` that
wraps the H2 access — keeps the new, sizeable feature reviewable and doesn't
make the existing file harder to navigate.

- `GET /api/logs/search` — params: `feedId[]`, `sourceId[]`, `targetId[]`,
  `step` (node, exact or prefix), `event[]`, `severity[]`, `user`, `from`,
  `to`, `q` (free text over `details`/`event`/`node`), `sort`, `page`, `size`.
  Returns paginated rows, each already joined with `sourceId`/`targetId`/
  `workflowName`/`production` and a link to the feed's `/run/{feedId}/{runId}`
  and (if `node` present) the raw step log.
- `GET /api/logs/timeseries` — same filters minus paging, plus `bucket`
  (`auto|minute|hour|day|week`; `auto` picks bucket width from the selected
  range so the chart never renders more than ~200 bars). Returns
  `[{bucketStart, severity, count}]` — one aggregate query, not one row per
  event.
- `GET /api/logs/metrics` — same filters. Returns the numbers for the stat
  cards (§6): total events, distinct runs, distinct feeds touched,
  success/fail counts (from `STEP_COMPLETED`/`STEP_FAILED`/`RUN_*` events),
  top N feeds by failure count, top N steps by failure count, average step
  duration (paired `STEP_STARTED`→`STEP_COMPLETED|STEP_FAILED` timestamps for
  the same `feed_id,run_id,node` — computable inside the index, no extra file
  reads).
- `GET /api/logs/facets` — distinct `sourceId`/`targetId`/`feedId`/`event`
  values for filter dropdowns/typeahead. Source/target/feed list comes from
  `registry.all()` (static, cheap); `event` list from a `SELECT DISTINCT
  event FROM log_entry` (small, bounded cardinality — the event vocabulary is
  the fixed set of literals used in `WorkflowEngine`).
- `GET /api/logs/export` — identical filters to `/search`, no paging,
  streamed CSV (`StreamingResponseBody`), same column set as the grid.
- `POST /api/logs/reindex` — admin-only, full rebuild (§3).

## 5. Frontend — `/logs` page (`logs.html`, new `PageController` route)
Same visual language as the rest of the app (no CDN, same `app.css` tokens —
`--ok`/`--ok-bg`, `--fail`/`--fail-bg`, `--wait`/`--wait-bg`, `--run`/
`--run-bg`, `--skip`/`--skip-bg` map directly onto the `severity` values in
§6, so grid badges, chart bars and metric cards all use one shared palette).
Add a `▤ Log Report` link next to the existing `▦ Operations` link in the
topbar (`dashboard.html`, and anywhere else the nav is duplicated).

Layout, top to bottom:
1. **Filter bar** — source/target/feed/step multi-select (mirror the
   existing multi-select checkbox filter widget already built for Operations'
   source/target filters — reuse the component, don't rebuild it), event
   multi-select, user, free-text `q`, date-range (with quick presets: last
   1h/24h/7d/30d/custom).
2. **Timeline chart** — custom SVG (same approach as `bpmn.js`: hand-built
   SVG, no charting library, consistent with "no CDN"). Stacked bars per time
   bucket, colored by `severity`. Click-drag draws a selection rectangle →
   sets `from`/`to` to the selection and re-fetches chart+grid+metrics
   (zoom-in, Grafana-style); a small "reset zoom" control restores the
   filter-bar range. Single click on a bar narrows to that bucket's exact
   `[bucketStart, bucketStart+bucketWidth)`.
3. **Metric cards** — total / success / failed / running / top failing
   feed / top failing step, etc. Each card is clickable and **toggles a
   filter** (e.g. click "Failed: 42" → adds `severity=FAIL` to the active
   filter state and re-renders chart+grid+metrics). This is the exact
   interaction already implemented for the Operations rollup —
   `setDrill(bucket, source)` → `renderRollup()` + `renderDrill()` in
   `overview.html`. Reuse that pattern (a single `filterState` object,
   one `apply()` that re-fetches the three endpoints and re-renders three
   views) instead of inventing a new one.
4. **Grid** — virtualized rendering for smooth scrolling of large result
   pages (same virtualization technique as `csv-viewer.html`/`viewer.js`),
   but data itself is **server-paginated** through `/api/logs/search` — the
   grid never tries to hold "all logs" client-side, only the current page.
   Columns: ts, feed (+ source/target), run, step, event (badge, colored by
   severity), user, details (truncated, expandable), links (→ run detail,
   → raw step log if `node` present).
5. **Export button** — calls `/api/logs/export` with the current
   `filterState` serialized as query params; browser download, same UX as
   existing CSV exports elsewhere in the app.

## 6. Severity classification — centralize it
`audit.html` today infers color from the event string inline in a Thymeleaf
expression (`FAILED`/`ERROR`/`REJECTED` → fail, `COMPLETED`/`SUCCESS` → ok,
`WAITING`/`DECISION` → wait). Formalize this once as a small pure function —
e.g. `LogSeverity.of(String event)` returning `OK|FAIL|WAIT|RUN|SKIP|INFO` —
used by: the indexer (precomputed `severity` column, so queries/aggregates
never re-parse strings), the grid badge, the chart bar color, and the metric
cards. Optional but recommended: once it exists, `audit.html` can call the
same function instead of duplicating the string-matching logic — nice
cleanup, not required for this feature to ship.

## 7. RBAC integration
Memory shows Phase 1 auth (PROTEO_MASTER / DEVELOPER / PROD_RUNNER /
PROD_VIEWER) as designed; it was **not present on `main`** as cloned for this
spec — confirm its merge status before starting §8/§4. Whatever the state:
- **Row-level scoping is mandatory, server-side, never client-side.** The
  `feedId IN (...)` (or `production = false`) predicate is injected into
  every one of the four endpoints in §4 based on the authenticated user's
  role/assigned feeds — a `DEVELOPER` must not be able to page past a filter
  and see a PROD row, and a `PROD_VIEWER`/`PROD_RUNNER` only sees their
  assigned feeds. If the auth layer isn't merged yet, ship the endpoints
  behind the same "no security config present → security disabled" fallback
  already established for JSON-absent, and leave the scoping hook as an
  explicit, obviously-named no-op to wire in later — do not block this
  feature on Phase 1, but do not silently skip the check either.
- **Audit the auditor.** A cross-feed export (or a search that spans PROD
  feeds) is itself a sensitive action worth recording. Reuse the existing
  `_shared` mechanism already used for cross-feed file events
  (`auditUpload(...)` writes to `assets.sharedDir().resolve("_audit_shared.jsonl")`
  when there's no single feed context) — write a `LOG_REPORT_EXPORTED` event
  there (feed count, filter summary, row count, user) on every `/export`
  call. Don't audit plain searches — too noisy — only exports and (optional,
  discuss) explicit full-history/`reindex` actions.

## 8. Non-goals / explicitly out of scope for this feature
- Ingesting or indexing raw step `.log` files (§2.3).
- Touching `_runs/{runId}.json` as a search source (§2.2).
- Any change to `AuditLogger`'s write path, hash chain, or `/audit/{feedId}`
  and `/api/audit/{feedId}/verify` (untouched, still the authoritative
  per-feed chain-integrity view — `/logs` links out to it, doesn't replace
  it).
- A persistent database file. The H2 instance is memory-only and disposable.

## 9. Verification (mirrors the project's usual checklist)
- `mvn -o clean package -DskipTests` → BUILD SUCCESS.
- `logs.html` and any new/edited JS: `node --check`, zero literal `\n`/`\r`,
  zero unsafe `[[`/`[(` outside the inlining comment.
- Manual: index rebuild after a fresh checkout with a handful of feeds with
  real audit files; confirm search/timeseries/metrics/export all agree on
  row counts for the same filter; confirm a `DEVELOPER`-scoped call (once
  RBAC is wired) never returns a PROD `feedId`.
- Confirm zero writes ever occur against any `audit_{feedId}.jsonl` from this
  feature's code path (read-only, by construction — worth a quick grep for
  `Files.write`/`newBufferedWriter` in the new package to be sure nothing
  slipped in).

## 10. Open items / decisions (resolve before or during Batch 1)
1. Real production volumes: events/day/feed and years of retention, to size
   the default rolling window (§3) and estimate heap use — ask Fabiano rather
   than guess.
2. Rolling window default (e.g. 90 days?) and whether cold-scanned
   out-of-window results get merged into the warm cache or discarded after
   the request.
3. RBAC Phase 1 merge status on `main` at the time work starts (§7).
4. Exact reindex refresh interval (T in §3) — balance freshness vs file I/O;
   the existing `feedsCache` TTL (10s) is a reasonable starting point but
   this indexer touches 144 files instead of one aggregate computation.
5. Whether `/logs` should default to "last 24h, all feeds" or require an
   explicit range/feed selection before the first query (avoid an
   accidental full-history, all-144-feeds query as the very first page load).

## 11. Suggested delivery order
Smallest end-to-end slice first, confirmation gate between batches (project
convention):
1. **Batch 1 — skeleton + single-feed correctness.** `LogIndexer` (in-memory
   H2, full-history load, no rolling window yet), `LogQueryService`,
   `/api/logs/search` only, no UI — verify via curl/Postman against one real
   feed's audit file that filters and pagination are correct and match
   `/api/audit/{feedId}/entries` for that feed.
2. **Batch 2 — cross-feed + facets + `/logs` grid UI.** Multi-feed indexing,
   `WorkflowDef` join for source/target, `/api/logs/facets`, the filter bar
   and virtualized grid (no chart/metrics yet).
3. **Batch 3 — timeline chart.** `/api/logs/timeseries`, custom SVG chart,
   brush-select/zoom, bucket-by-severity coloring.
4. **Batch 4 — metrics cards + click-to-filter wiring**, reusing the
   `setDrill`-style pattern end to end across chart+grid+cards.
5. **Batch 5 — export + admin reindex + RBAC scoping** (or earlier if Phase 1
   auth is already merged by then — don't ship export ungated by role scoping
   if RBAC is available).
6. **Batch 6 — incremental refresh + rolling window + cold-scan fallback**,
   once real volumes (§10.1) are known and the fixed full-load from Batch 1
   is confirmed too slow/heavy to keep as-is.
