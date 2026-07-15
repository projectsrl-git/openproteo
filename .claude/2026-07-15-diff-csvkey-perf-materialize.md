# diff CSV_KEY performance fix — materialise ag/bg instead of CTEs

## Problem
CSV_KEY reconciliation was extremely slow at scale. Root cause: `runDiffKey` ran
the whole comparison as one `WITH ag AS (...), bg AS (...) SELECT ... UNION ALL
...` statement that references `ag` and `bg` **twice each** (LEFT JOIN + the
anti-join). H2 does not materialise non-recursive CTEs — it re-evaluates each
reference — so the per-side `GROUP BY ... COUNT(DISTINCT)` ran ~4 times and the
two grouped subqueries were joined **without an index**. Cost grew super-linearly.
Measured on 20k×20k rows: **~44 s**.

## Fix
Materialise the two grouped sides into indexed temporary tables, then join:
- `CREATE LOCAL TEMPORARY TABLE ag AS <agSelect>` and `... bg AS <bgSelect>`
  (each GROUP BY runs once),
- `CREATE INDEX ix_ag ON ag(k)` / `ix_bg ON bg(k)` (the key join becomes an index
  lookup),
- the final query drops the `WITH` and selects from the `ag`/`bg` tables.
Temp tables are session-scoped on the per-run in-memory H2 connection, so they
need no cleanup and cannot collide.

## Result
Same output (verified: the 5-key sample still reports DIFFERENCES(4) with the
identical categories). 20k×20k rows now runs in **~2 s** through the real
runDiffKey (CSVREAD load + materialise + index + join + emit) — ~20-30x faster,
and roughly linear instead of super-linear.

## Verify
The actual patched runDiffKey was run against real H2 2.1.214: correctness sample
unchanged; 20k timing 44 s → ~2 s. No stub/Maven build in this chat turn.
