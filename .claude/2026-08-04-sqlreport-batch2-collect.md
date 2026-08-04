# sqlreport executor — Batch 2 (collecting query results into run variables)

Completes section 1.5 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`. Batch 0 (`VarResolver.keyed()`)
and batch 1 (executor, read-only validation, Markdown report, designer, docs) were already on main.
No new file: the executor, `SqlReportSupport` and the designer branch are extended.

## What is published

Per query, driven by the two fields that batch 1 parsed and kept unused:

| case | published |
|---|---|
| `collect=COL`, one row | `${COL}` = the value (the scalar case of the spec) |
| `collect=COL`, several rows | `${COL}` = `;`-separated list in row order, so `${COL[N]}` works |
| `collect=A,B` + `keyColumn=K` | `${A}`, `${A.keys}`, `${B}`, `${B.keys}`, `${K}` |
| no `collect`, result has exactly ONE column | that column, implicitly, under its own label |
| no rows | the empty string, so nothing downstream reads a stale value |

`${COL.keys}` is the companion list `VarResolver.keyed()` requires; `${K}` is the same key list under
the key column's own name, as the spec's first formulation asked. Names are the RESULT's column
labels, matched case-insensitively against what the author declared, so `collect=amount` on a column
labelled `AMOUNT` publishes `${AMOUNT}`.

## Decisions

**The separator is `;`, always — `step.delimiter` is deliberately NOT honoured.** Both
`VarResolver.keyed()` and the positional `${list[N]}` split on a hardcoded `;`. Letting the step
choose another separator would produce lists that look right and silently fail every lookup.

**A collected value containing `;` or a line break is sanitised to a space, and counted.** One such
value would shift every later position and misalign the companion keys list. The count goes in the
report and the step log, following the `newlinesSanitized` precedent of the `sql` executor.

**Explicit intent fails hard, implicit convenience degrades quietly.** A `collect` list naming a
column that is not in the result, a `keyColumn` that is not in the result, a `keyColumn` with no
`collect`, a label that is not a usable variable name, or a name that would overwrite a built-in run
variable — all fail the step. The implicit single-column case just skips publication and says so in
the log: the author never asked for it, so it must not break their report. `RESERVED_VARS` is the
list that must never be overwritten (`feedId`, `runId`, `stepDir`, …) — collecting into `${feedId}`
would silently redirect every later path.

**Overflow publishes NOTHING, and fails.** Collection is not capped by `maxRows` (which caps only the
rendered table) but by `collectMaxRows`, default 5000, `0` = no cap. Above it the query's variables
are not published at all and the step fails. A partially collected list is the worst outcome: every
missing key would resolve to the empty string, which is indistinguishable from a legitimate absence.

**Duplicate keys warn, they do not fail.** `${COL@key}` returns the first match; the count is written
into the report and the step log so it is visible in the evidence, but a non-unique key can be a
legitimate positional list.

**Collected values are never printed to the step log.** The `##VAR` echo prints the value only for the
step's own three outputs; a collected variable is logged by name with `(collected, value not logged)`.
Note the limit of this: the ENGINE still audits every out var with its value
(`STEP_OUTPUT_VAR`), and OUTPUT DATA displays it — that is shared behaviour for all executors and was
not changed here. The docs therefore say plainly: collect counts, sums, statuses and keys, not
personal data.

## UI and docs

Designer: the two per-query fields lose their "(batch 2)" label and gain real tooltips, a
**Collect max rows** field sits next to Max rows and Fail on empty, and `clientValidate` refuses a key
column without a collect and a collected name that is not a plain identifier. A paragraph under the
block explains the scalar/list/keyed forms and the PII warning. `USAGE.md` gains a
**sqlreport notes: collecting variables** paragraph next to the csvsql and xlsx2csv notes.

## Verification

* `SqlReportSupport` compiled standalone with `-source 8`; 43 new assertions plus the 42 from batch 1
  re-run as a regression, all passing.
* The end-to-end assertions publish variables through a copy of the executor's publication block and
  then read them back through a **re-implementation of `VarResolver.keyed()` and `${list[N]}` copied
  from the real resolver** — so what is proved is the contract, not just the join:
  `${AMOUNT@CID2}` returns `0200` with its leading zero, an absent key returns the empty string
  rather than a neighbouring row, `${AMOUNT[2]}` still resolves positionally, and a value containing
  `;` is found under its key after sanitising.
* `node --check` on the designer's inline JS **caught a real defect**: the first placement of the new
  validation split an `if`/`else` pair and would have broken the whole page. Fixed and re-checked.
* Brace/paren balance 0/0 on both Java files; the import checker passes on every added line.

**Not verified**: `mvn clean package`, and everything needing a live database — the JDBC path, the
collection over a real result set, and the audit/OUTPUT DATA rendering of a collected list.
