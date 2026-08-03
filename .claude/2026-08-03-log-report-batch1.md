# Log report — Batch 1: indexer + query service + /api/logs/search (no UI)

Implements the first slice of `.claude/LOG_REPORT.md` (spec committed alongside). Nothing in the
engine changed: this is a read-only aggregation layer over the audit files that already exist.

## What ships
- `logreport/LogSeverity` — `of(event)` -> OK|FAIL|WAIT|RUN|SKIP|INFO, the single classifier used by
  the indexer now and by the grid/chart/cards later. Failures win over completions when an event names
  both.
- `logreport/LogIndexer` — in-memory H2 (`jdbc:h2:mem:logidx;DB_CLOSE_DELAY=-1`) used purely as a SQL
  engine, exactly like `csvsql` (runtime-only, `Class.forName("org.h2.Driver")`, no compile dependency,
  no new artifact). Table + indexes as per the spec. Refresh is a true tail: the byte offset consumed
  per feed is remembered, only appended bytes are read, and a trailing partial line is left for the
  next pass. A shrunken file (replaced/truncated) is detected and that feed reloaded. A malformed line
  or an unreadable feed is skipped, never fatal. `reindex()` truncates and reloads. TTL 10s, matching
  the existing feedsCache pattern.
- `logreport/LogQueryService` — filters (feedId/sourceId/targetId/step/event/severity/user/from/to/q),
  paging, whitelisted sort. Source/target/name/production are joined live from `registry.all()` and are
  NOT columns, so a rename in the XML shows immediately without reindexing. All values are bound
  parameters; `sort` is whitelisted, never interpolated. Page size capped at 500.
- `web/LogReportController` — `GET /api/logs/search`, plus `GET /api/logs/status` (rows loaded, last
  refresh, per-feed seq/offset) and `POST /api/logs/reindex`. Its own controller, as the spec requires.

## Not in this batch
No `/logs` page, no timeseries/metrics/facets/export, no rolling window or cold scan (Batch 1 loads
full history on purpose, to be measured against real volumes), no RBAC scoping — `reindex` and the
search are currently ungated, which is why this batch is TEST-only until Phase 1 auth lands.

## Verify
`LogSeverity.of` and `LogIndexer.parseTs` were compiled and executed over the real event vocabulary and
four timestamp shapes (offset, Z, local, space-separated, plus empty/garbage/null -> null). The
byte-offset tail was compiled and exercised on a file appended to three times, including a partial
final line: the partial line is not consumed and is correctly reassembled on the following pass. The
SQL the service emits (DDL, IN-lists, time range, node prefix LIKE, free text, user, paging, all four
sort options) was executed against a real SQL engine and returned the expected rows. H2 itself could
not be exercised here: Maven Central is unreachable from the chat sandbox, so the JDBC path runs for
the first time on your build. Brace balance checked with a string/comment-aware counter.

## Open items from the spec still to answer (§10)
Real volumes per feed/day and retention; rolling-window default; refresh interval T; whether `/logs`
should default to "last 24h" or force an explicit range; RBAC merge status. Batch 1 is deliberately
independent of all five.
