# diff executor — Batch 1 (CSV_POSITIONAL) — implementation note

Implements delivery step 1-2 of `.claude/DIFF_EXECUTOR.md`: the `diff` internal
executor end-to-end, CSV_POSITIONAL mode only, with the two report artifacts.

## What this batch delivers
- New internal step kind **`diff`**, registered in the four locations:
  parser exec whitelist, parser `internal` set, `WorkflowEngine.internalKind()`,
  `InternalSteps` dispatch (parser error message updated too).
- **`runDiff` (CSV_POSITIONAL)**: streaming, constant memory. Compares the
  columns present in **both** headers (matched by name), row-by-row by position;
  surplus rows reported as `missing_in_A` / `missing_in_B`. A minimal RFC-style
  CSV line parser (`parseCsvLine`, quotes + `""` escaping; assumes no embedded
  newlines) is added since the codebase had no CSV reader.
- **Reports** written to the step dir: `<name>_recon_report.md` (headline
  PERFECT MATCH / DIFFERENCES, config echo, summary, totals with % over checked
  cells, per-attribute table) and `<name>_recon_differences.csv` (one row per
  difference: rowIndex, attribute, valueA, valueB, category).
- **Output vars**: `diffResult` (PERFECT_MATCH|DIFFERENCES), `diffCount`,
  `rowsCompared`, `attributesCompared`, `valueMismatches`, `missingInA`,
  `missingInB`, `reportFile`, `differencesFile`.
- Optional `failOnDifferences` param → non-zero exit when differences are found
  (default: succeed and expose the vars, so a gate can branch on
  `${stepId.diffResult}`).

## Config — all via step params (no model/DTO/writer change)
`mode` (default CSV_POSITIONAL), `fileA`, `fileB` (var-resolved paths),
`delimiter` (default `;`), `reportName` (default step id), `failOnDifferences`.
Designer: `diff` added to the exec dropdown, a dedicated editor branch (mode,
fileA, fileB, delimiter, report name, fail-on-diff) bound via
`nodeParam`/`setNodeParam`, and a `clientValidate` rule (fileA/fileB required).

## Not in this batch (see DIFF_EXECUTOR.md §12)
TEXT mode; CSV_KEY (keys + matches + multi-occurrence H2 pipeline); the ADD MATCH
repeater; cross-workflow {workflow,file} selection + run correlation. Config is
params-only for now; a richer model can come with CSV_KEY.

## Verify
Diff algorithm executed standalone on sample CSVs (2 mismatches + 1 surplus row →
correct report/metrics/differences.csv). designer.html passes node --check; no
literal \n/\r; no unsafe Thymeleaf. NOTE: full Maven/stub compile not run in this
chat turn (sandbox stubs were reset) — real build to be confirmed on deploy.
