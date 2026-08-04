# sqlreport executor — Batch 1 (executor, read-only validation, Markdown report, designer, docs)

Implements section 1 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`, batch 1 only.
Batch 0 (`VarResolver.keyed()` for `${COL@key}`) was already on main; batch 2 (collecting query
results into run variables) is NOT in here.

## Registration — the four locations

The symptom of missing one is an executor that never appears in the designer dropdown, so all four
were done and each verified by grep against the existing `diff` registration:

| # | File | Change |
|---|---|---|
| 1 | `parser/WorkflowXmlParser` | added to the `exec` whitelist **and** to the error message listing the valid values |
| 2 | `parser/WorkflowXmlParser` | added to the `internal` set, so `script` is not required |
| 3 | `engine/WorkflowEngine.internalKind()` | added to the chain |
| 4 | `engine/InternalSteps.run()` | `else if ("sqlreport".equals(kind))` dispatch |

Plus the two designer locations the project's rule also covers: the `<option>` in the executor
`<select>` and the `clientValidate()` branch.

## Storage: `<reportQuery>`, NOT `<query>`

The spec asks for one XML element per query. The obvious tag is taken: the parser reads the single
statement of the `sql` executor with `textOrAttr(el, "query")`, which looks for a `<query>` CHILD
before falling back to the attribute. Reusing it would have made the first report query silently
become `StepDef.query` — invisible until someone read the XML. The element is therefore
`<reportQuery title= keyColumn= collect= maxRows=>SQL text</reportQuery>`, one per query, so a `;`
inside a statement can never corrupt the definition, which was the point of the requirement.

New `model/def/ReportQuery`, `StepDef.reportQueries`, parser + writer round-trip,
`NodeDto.ReportQueryDto` and the `toDto` mapping.

## Read-only enforcement

`engine/SqlReportSupport` is a new class with **no Spring, no JDBC and no project types**, on
purpose: it holds the part of the executor whose behaviour must be provable without a database, and
it compiles and runs standalone (see Verification).

`readOnlyError(sql)` returns null or the reason. It works on the statement with comments stripped
(`stripComments`) and with the content of string literals and quoted identifiers blanked out
(`blankQuoted`), so a `;` or the word DELETE inside a literal is not mistaken for the real thing.
Three checks:

1. not empty (and not only comments);
2. no second statement — a `;` followed by anything but whitespace;
3. the leading keyword must be SELECT or WITH, **and** no DML/DDL keyword anywhere.

**Check 3 is stricter than the spec asked**, deliberately: the spec's two checks pass a
data-modifying CTE (`WITH x AS (...) DELETE FROM t`), which does begin with WITH and is a single
statement. Blanking quoted text first is what makes the keyword scan safe enough to use. If it ever
rejects a legitimate SELECT, the `FORBIDDEN` array is the single place to relax.

Second level: `Connection.setReadOnly(true)` (its real effect is read back with `isReadOnly()` and
reported in the document — some drivers ignore it) plus the step's TIMEOUT SEC as query timeout.
The report states in its own header that this is a guard against mistakes and not a guarantee,
because a SELECT can call a function with side effects; the real guarantee is the database
account's rights. The same wording is in `USAGE.md` and under the designer block.

Every statement is validated BEFORE the connection is opened: if one is rejected, nothing runs at
all, rather than half a report with half the queries executed.

## The report

UTF-8 Markdown, one file, path from the `reportFile` param, default
`${stepDir}/${feedId}_${stepId}.md`. Header: run id, execution timestamp with the JVM zone,
datasource id/type/host/user/database (+ product name), workflow and step, and the read-only
statement above. Per query: the title, its own timestamp and duration, the row count, the statement
**as executed** in a fenced ` ```sql ` block, and the result table with `|` escaped and CR/LF folded
to a space so a multi-line value cannot break the table.

* **Row count is always real.** The `ResultSet` is consumed to the end and counted; only the first
  `maxRows` rows are kept for the table, and the header says "table truncated to the first N" when
  they differ. `Statement.setMaxRows` is deliberately NOT used — it would falsify the count.
* **No password, ever.** Only datasource id, host and user. For a `custom` datasource the host line
  is the JDBC URL passed through `redactJdbcUrl`, which masks `password=`/`passwd=`/`pwd=` values
  and the `user:pw@host` form.
* Deviation from the spec's example: the statement is in a fenced block instead of a 4-space
  indented one, because indentation breaks if the SQL contains a blank line.

Outputs `${reportFile}`, `${queriesExecuted}`, `${rowsTotal}`. `failOnEmpty` (default off) fails the
step when a query returned no rows — **after** writing the report, so the evidence survives the
failure. Each statement is registered in `control.statement` while it runs, so an operator Stop (or
the new bulk Stop) can cancel it.

## Conservative defaults

Nothing existing changes behaviour. A new executor id, a new optional child element, a new model
list that is empty for every existing step, and additive designer code behind
`ex === 'sqlreport'`. No new Maven dependency: plain JDBC through the existing `SqlSupport.open`.

## Not in this batch

`keyColumn` and `collect` are parsed, stored, round-tripped and shown in the designer (labelled
"batch 2"), but unused; the step log says so when a query declares them. Variable collection is
batch 2.
