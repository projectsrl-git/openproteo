# Fix: csvsql fails with 'Parameter "fileName" is not set [90012]'

## Symptom
A `csvsql` step fails immediately (exit -1) with:
```
ERROR: Parameter "fileName" is not set; SQL statement:
CREATE TABLE anagrafe_generale AS SELECT * FROM CSVREAD(?, NULL, ?) [90012-214]
```
even though the `<input csv="...">` path is correct and the file exists.

## Cause
The staging step ran `CREATE TABLE <t> AS SELECT * FROM CSVREAD(?, NULL, ?)` with the file name and
options bound as JDBC parameters (`ps.setString(1, csv)`). For `CREATE TABLE ... AS SELECT`, H2 has
to determine the result columns to define the table, and since `CSVREAD` is given `NULL` for the
column list, H2 must open the file and read its header **at statement-prepare time** — before the
bound parameters are applied. So H2 sees the first `CSVREAD` argument (internally named `fileName`)
as unset and aborts with 90012. This is a known H2 limitation, not a path problem.

## Fix
Inline the file name and the options as **escaped SQL string literals** (single quotes doubled)
instead of bound parameters, so they are available when H2 builds the table metadata. The table name
is already validated against `[A-Za-z_][A-Za-z0-9_]*`, and paths/options are escaped, so this is not
an injection vector.

- `engine/InternalSteps.runCsvSql`: staging now uses a `Statement` with
  `CSVREAD('<path>', NULL, '<opts>')` built via a new `sqlLit(...)` helper.
- `web/ApiController` (csvsql preview): same change (plus its own `sqlLit`).
- `h2/H2Probe.java`: had the identical bound-parameter bug — it would have failed the link test on a
  perfectly good H2. Switched to the inlined-literal form so the probe matches the executor.

## Verification
- Compiles (96 classes). Live H2 still not runnable in the sandbox (the H2 jar is unreachable here —
  Maven Central / GitHub assets blocked), so confirm on UBS: re-run the `csvsql` step (it should now
  stage the table and produce `output.csv`), and `h2/H2Probe` should print `H2 OK: CSVREAD rows = 2`.
