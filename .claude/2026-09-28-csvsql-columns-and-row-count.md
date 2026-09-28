# csvsql — `{{columns}}`, the case of H2's column names, and a row count that was always 0

A `csvsql` step feeding the Transarch metadata CSV failed with
`Syntax error in SQL statement "SELECT\000a{{columns}[*]}\000aFROM SOURCE" [42000-214]`, and the
same log said the input had been staged with `0 rows` — on a file with twelve.

## Two separate things, neither in the data

**`{{columns}}` was never a csvsql feature.** It is expanded in `runSql`, from a dataschema passed in
`columnsSchema`. `runCsvSql` did not touch it, so the placeholder reached H2 verbatim. The designer
only advertises it in the `sql` branch, so nothing promised it; the idiom had been carried over.

**The `0 rows` was a logging defect, present since csvsql existed.** Staging runs
`CREATE TABLE … AS SELECT * FROM CSVREAD(…)` through `executeUpdate` and logged the return value.
For a DDL statement that is an update count, not a row count. Measured on H2 2.1.214 — the version
the error code identifies: `executeUpdate` returned **0**, `SELECT COUNT(*)` on the same table
returned **3**. Every csvsql run has logged `0 rows`, and it has never meant anything.

## What made `{{columns}}` worth adding rather than refusing

A plain `SELECT *` over a staged CSV **writes the header uppercased**, because H2 renames CSV headers
on the way in. For a Transarch metadata file that is a schema change the archive forbids without
re-onboarding. The team had been working round it by hand — a query in an earlier screenshot aliases
every column, `com_ID as "com_ID"` — which is exactly what a dataschema-driven expansion can do for
them.

And it is safe to add: every csvsql query that contains `{{columns}}` today fails outright, so no
working configuration can change behaviour.

## H2's naming rule, measured and then deliberately not reimplemented

On 2.1.214, `CSVREAD` stores:

* headers that are valid SQL identifiers **uppercased** — `trn_CSVAXRef` → `TRN_CSVAXREF`;
* headers that are not **verbatim** — `Transaction Num`, `weird-name` unchanged;
* headers differing only in case **renamed** — `dup`, `Dup` → `DUP`, `DUP1`.

So a bare `trn_CSVAXRef` resolves, a quoted `"trn_CSVAXRef"` does not, and a bare `Transaction Num`
is a syntax error. Reimplementing that rule would break on the first header nobody tested. So
`ColumnsExpansion` does not guess: after staging, the executor reads the table's real column names
from H2, matches each dataschema name against them case-insensitively, and emits
`"<stored>" AS "<dataschema name>"`. The reference always resolves and the output header carries
the dataschema's exact casing, in the dataschema's order.

Refused rather than guessed: a dataschema column the table lacks (the message lists what it does
have); a name matching two stored columns that differ only in case; and two dataschema names that
would read the same column, which would silently duplicate one and lose the other.

There is no *Quote columns* option for csvsql, unlike `sql`. Here the quoting is not a preference:
H2 decides the stored names, and a toggle would be a way to break the expansion.

## Verification

**Against the real H2 2.1.214**, downloaded for this, rather than a mock: 23 assertions in
`CsvSqlSuite`, which stages CSVs exactly as `runCsvSql` does and reads the stored names exactly as
the new code does. It confirms the DDL count really is 0 and `COUNT(*)` is right; that on the
production shape `{{columns}}` yields the dataschema's casing while `SELECT *` yields H2's; that
output follows the dataschema's order and drops undeclared columns; and that a space-named and a
hyphen-named header both resolve. **8 mutations of `ColumnsExpansion`, all caught.**

`InternalSteps` cannot be compiled here. In its place: brace balance; the method signature
(`throws Exception`, so `readSchemaColumnNames` is callable); the type of `ins`; and a check that
`query`, now reassigned, is not captured by any lambda in the method — the one lambda there does not
reference it. Panel and executor parameter names compared: `columnsSchema`, `columnsTable`, no
mismatch. `node --check` on the designer.

## Not verified

`mvn clean package`. A run on the real feed. The extra `SELECT COUNT(*)` per input has not been timed
on a large file-engine input; on H2's MVStore a table count is maintained, so it should not scan.
