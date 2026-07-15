# diff executor — Batch 2 (CSV_KEY) — implementation note

Implements delivery steps 3-4 of `.claude/DIFF_EXECUTOR.md`: the CSV_KEY mode of
the `diff` executor (key-aligned reconciliation with attribute matches and the
multi-occurrence rule). Depends on Batch 1 (apply Batch 1 first).

## What this batch delivers
- `runDiff` now dispatches by `mode`: CSV_POSITIONAL (Batch 1) or **CSV_KEY**.
- **`runDiffKey`** (H2 pipeline, reuses the csvsql CSVREAD loader):
  - Loads both files as all-VARCHAR H2 tables (`CSVREAD`).
  - Builds, per side, a grouped/collapsed view: `GROUP BY <keyExpr>`, and for each
    match `COUNT(DISTINCT <matchExpr>)` + `MAX(<matchExpr>)`. Keys and multi-column
    matches use `CONCAT_WS` **only when 2+ columns** (H2 rejects single-arg
    CONCAT_WS); a single column is used bare. Key separator is `CHAR(1)`.
  - Full-outer-join emulated as `LEFT JOIN ... UNION ALL <anti-join>` (H2 2.1.214
    does not accept FULL OUTER JOIN here).
  - Categories: `value_mismatch`, `missing_in_A`, `missing_in_B`, and
    `inconsistent_key` (a match has >1 distinct value within one side for a key,
    i.e. the multi-occurrence agreement rule). Numeric matches compared with
    BigDecimal (so `0100` == `100`); text by string equality.
  - Same report artifacts as POSITIONAL, KEY-flavoured: `<name>_recon_report.md`
    (config echo incl. keys + matches, summary, totals with % over checked cells,
    per-match table) and `<name>_recon_differences.csv` (key, match, valueA,
    valueB, category). Output vars add `keysCompared`, `inconsistentKeys`.
- Column identifiers validated as `[A-Za-z_][A-Za-z0-9_]*` (SQL-injection safe);
  match separators are SQL-escaped. Match indices are collected by scanning the
  params (gap-tolerant), so the designer's match-remove needs no renumbering.

## Config — still params only (no model/DTO/writer change)
`keysA`, `keysB` (comma-separated columns); matches as `match.<n>.a`, `.b`,
`.sep` (default space), `.type` (text|numeric), `.label`. Designer: mode dropdown
gains CSV_KEY; when selected, Key A / Key B inputs + an **ADD MATCH** repeater
(A columns / B columns / separator / type / label / remove) appear, all bound via
`nodeParam`/`setNodeParam`; `clientValidate` requires keys + at least one complete
match.

## Verify
The **actual** `runDiffKey` source was executed standalone against a real H2
2.1.214 on sample CSVs: value_mismatch, missing_in_A, missing_in_B and
inconsistent_key all correct; identical duplicate rows collapse; numeric
leading-zero equality holds; non-contiguous match indices tolerated. Two real
H2-syntax bugs were found and fixed this way (single-arg CONCAT_WS; FULL OUTER
JOIN). designer.html passes node --check; no literal \n/\r; no unsafe Thymeleaf.
Full Maven/stub compile not run this chat turn (stubs were reset) — confirm on
deploy.

## Deferred (see DIFF_EXECUTOR.md)
SUBSTRING L/R on key columns (reuse the grouping widget); column **dropdowns**
from a header-preview endpoint (this batch uses free-text column names);
cross-workflow {workflow,file} selection + run correlation; TEXT mode.
