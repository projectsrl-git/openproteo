# diff executor — Batch 3 (TEXT mode) — implementation note

Implements delivery step 6 of `.claude/DIFF_EXECUTOR.md`: the TEXT mode, the third
and final comparison mode of the `diff` executor. Applies on top of Batch 1+2
(both already on main).

## What this batch delivers
- `runDiff` dispatches TEXT → **`runDiffText`** (line-oriented comparison):
  - Small files (both <= `textMaxLines`, default 2000): a real **LCS** diff
    (bottom-up DP + forward walk) yields `only_in_A` / `only_in_B` differing lines
    with their line numbers, unified-diff style.
  - Large files (either > cap): a **streaming positional fallback** (line i of A
    vs line i of B → `line_changed`; surplus → only_in_A / only_in_B), with the
    degradation noted in the report. TEXT is meant for small control files; the
    scalable paths remain CSV_POSITIONAL / CSV_KEY. (Resolves open item §11.2: cap
    + fallback rather than refusing.)
- Reports (same pair as the other modes): `<name>_recon_report.md` (headline,
  config with fallback note, summary lines A/B/common, totals with % over the
  larger file) and `<name>_recon_differences.csv` (line, label, valueA, valueB,
  category). Output vars: diffResult, diffCount, linesA, linesB, commonLines,
  onlyInA, onlyInB, (+ changedLines in fallback), reportFile, differencesFile.
- Config via params: fileA, fileB, reportName, failOnDifferences (shared) +
  `textMaxLines`. Designer: TEXT added to the mode dropdown; when selected, a
  Max-lines field + a note that the delimiter is ignored. No model/DTO/writer
  change.

## Verify
The **actual** `runDiffText` source was executed standalone on sample files:
LCS path (common 4, only_in_A 1, only_in_B 2, 33.33% over the larger file) and
the positional fallback (textMaxLines=3 → common 3, changed 2, only_in_B 1, 50%),
with failOnDifferences flipping the exit code. designer.html passes node --check;
no literal \n/\r; no unsafe Thymeleaf. Full Maven/stub compile not run this chat
turn (stubs reset) — confirm on deploy.

## Remaining (see DIFF_EXECUTOR.md)
All three modes now exist. Still open (UX/plumbing, not core): SUBSTRING L/R on
CSV_KEY key columns; column dropdowns from a header-preview endpoint; and
cross-workflow {workflow,file} selection + run correlation (§7) with its endpoints.
