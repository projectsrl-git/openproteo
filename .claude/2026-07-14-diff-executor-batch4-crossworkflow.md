# diff executor — Batch 4 (cross-workflow file picker + run correlation)

Implements the practical core of `.claude/DIFF_EXECUTOR.md` §7 for this codebase.
Applies on top of Batch 1-3 (all on main).

## Layout reality that shapes the design
A feed's produced files live in **stable directories** under its feedDir
(`NN_<stepId>`, `99_landing_out`), overwritten each run; only run *state*
(`_runs/{runId}.json`) and logs are per-run. So a stable absolute path already
resolves to the latest run's output — there is no per-run file history to walk.
Cross-workflow referencing therefore reduces to: pick another workflow's file →
store its absolute path; the report stamps the file's produced time.

## What this batch delivers
- **Backend**: `GET /api/workflows/catalog` → `{ok, workflows:[{feedId,name}]}`
  (from `registry.all()`), for the workflow dropdown. The file dropdown reuses the
  existing `GET /api/workflows/{feedId}/files` (returns `files[].path` + `dir`).
- **Designer**: under File A and File B, a "…or from a workflow" row with a
  workflow `<select>` (lazy-loaded from the catalog on focus) and a file
  `<select>` (loaded from that workflow's files on choice). Picking a file writes
  the **absolute path** (`dir` + relative, backslashes normalised) into the File
  field and the `fileA`/`fileB` param. Free-text paths still work; this is a
  convenience builder.
- **Run correlation**: every mode's report now stamps
  `Sources produced: A @ <mtime>, B @ <mtime>` in the config echo — the produced
  instant of each compared file. (Given stable overwritten paths, mtime is the
  faithful "which produced instance" stamp; full per-run file history isn't kept.)

## Verify
Executor stamp verified by running the actual updated runDiffKey standalone (the
report shows the Sources line; exit code correct). designer.html passes
node --check; no literal \n/\r; no unsafe Thymeleaf. The catalog endpoint mirrors
the existing `/active` and `/queue` GETs. NOTE: the designer's async picker flow
(catalog fetch → workflow → files → set path) could not be live-tested in this
chat sandbox (no running app/endpoints); it mirrors the existing xlsx-sheets
async-select pattern. Full Maven build to be confirmed on deploy.

## Remaining (see DIFF_EXECUTOR.md) — optional UX refinements
SUBSTRING L/R on CSV_KEY key columns (reuse the grouping widget); column
**dropdowns** in the CSV_KEY panel from a header-preview endpoint (still free-text
column names). Core reconciliation across all three modes + cross-workflow
picking + run-correlation stamping are now in place.
