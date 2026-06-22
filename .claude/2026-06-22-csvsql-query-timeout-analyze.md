# csvsql: enforce query timeout + ANALYZE (and the OR-join performance trap)

## Context
An aggressive test query — a 3-level self-join of a 1.5M-row table with **OR predicates in the JOIN
conditions**, plus window functions, COUNT(DISTINCT), aggregation and a final ORDER BY — ran for
hours without finishing.

## Why no engine tuning can fix that query
- An **OR in a join condition** (`ON b.PARENT=a.NDG OR b.CODCLI=a.PARENT OR b.NDG=a.PARENT`) cannot be
  served by an index or a hash/merge join; the optimizer falls back to a **nested loop** — for each
  row of `a` it scans all of `b`. With 1.5M rows that is ~2.25e12 comparisons for the first join, and
  the second self-join (`c`) multiplies again. This is not "slow", it is effectively non-terminating,
  in H2 or in any RDBMS.
- The OR-joins also **explode the row count** (fan-out), and the downstream window functions /
  DISTINCT counts / final sort then run over that exploded set.
So this is an algorithmic problem in the SQL, independent of mem-vs-file, indexes or cache.

## What was actually broken in the tool
Internal steps (csvsql/xlsx2csv) **ignored the step timeout entirely** — only external script steps
received it. So a runaway query had nothing to stop it; it just kept running.

## Changes
- **Query timeout**: csvsql now applies `Statement.setQueryTimeout(...)` to the staging, index and
  query statements, using the step's **TIMEOUT SEC** (else `orchestrator.default-step-timeout-sec`,
  1800s). A query that exceeds it is aborted by H2; the step is flagged `timedOut` (the dashboard
  shows "timeout Ns") with a clear console message instead of hanging for hours.
- **ANALYZE**: after staging + indexing, a best-effort `ANALYZE` refreshes optimizer statistics so
  H2 can pick a better join order / use the indexes.
- (Engine = mem and per-input indexes from the previous change still apply for well-formed queries.)

## Guidance documented in USAGE
Avoid OR conditions in JOINs; rewrite each OR-join as a UNION of separate equi-joins so each branch
can use an index/hash join. csvsql is meant for filter/aggregate/equi-join workloads, not multi-hop
graph expansion over millions of rows.

## Verification
- Compiles (96 classes). Designer unchanged this round. Live H2 timing is UBS-side (jar unreachable
  in the sandbox).
