# Stop fix for SQL extraction + selectable home-list variables

## 1) Stop left the run in RUNNING (SQL extraction)
Root cause: the DB2 `sql` executor (SqlSupport.exportCsv) created its JDBC Statement but never
registered it in the run's RunControl, so engine.stop() had nothing to cancel — a slow extraction kept
running and the run stayed RUNNING until it finished on its own. (csvsql already registered its
statement; external steps were already killed via destroyForcibly.)

Fix: SqlSupport.exportCsv now has an overload that reports the live Statement via a callback (no
ds->engine coupling) and sets a JDBC fetch size; runSql passes `st -> control.statement = st`. On Stop,
engine.stop() cancels the statement, the query/fetch aborts (SQLException), the step returns not-ok and
the loop finalizes the run as ABORTED (the loop's catch also guarantees finalisation). Also re-applied
the G2 CsvWriter write optimisation + setFetchSize that had been lost in an earlier packaging
(byte-identical output re-verified).

Note: purely in-memory, CPU-bound internal steps (e.g. masking a very large file) still complete their
current step before the run aborts; SQL/csvsql/external steps now stop promptly. Cooperative abort
checks for the remaining internal loops can be added later if needed.

## 2) Selectable variables in the home feed list (searchable)
New config `orchestrator.home-list-vars` (comma-separated workflow variable names, e.g.
`recordBusinessDate,businessDate`). The dashboard shows one column per name with each feed's value
(from the workflow variables) and includes those values in the row's general search box, so you can
search feeds by, e.g., Business Record Date. Empty config = no extra columns (unchanged layout).

- AppProperties.homeListVars + homeListVarNames(); PageController injects AppProperties, resolves the
  values per feed into row.varValues (+ row.varSearch appended to data-search); dashboard.html renders
  the dynamic columns and dynamic colspans.

## Verification
- Compiles (99 classes). CsvWriter byte-identity self-test passes. dashboard JS node --check OK; no
  literal \n/\r; no unsafe [[ /[(. Live stop/search behaviour on UBS.
