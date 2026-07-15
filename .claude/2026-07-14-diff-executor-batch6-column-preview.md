# diff executor — Batch 6 (CSV_KEY column preview / autocomplete)

Implements the last item of `.claude/DIFF_EXECUTOR.md` §9-10 (column dropdowns from
a header-preview endpoint). Applies on top of Batch 1-5 (all on main). With this,
the whole design is implemented.

## What this batch delivers
- **Backend**: `GET /api/workflows/{feedId}/diff/columns?path=…&delimiter=…` →
  `{ok, columns:[…], path}`. Mirrors the xlsx/sheets endpoint's path resolution
  (feedVars → VarResolver → rebaseRel), then reads the file's first line, strips a
  BOM, and splits it quote-aware (same rules as the executor's CSV parser) into
  column names. Absolute paths (e.g. from the cross-workflow picker) resolve as-is.
- **Designer (CSV_KEY panel)**: a "⟳ Load columns from File A / File B" button
  fetches both headers, shows them as a reference line, and fills two datalists
  (`diffcolsA_<i>`, `diffcolsB_<i>`). The Key A / Key B inputs and each match's
  A-columns / B-columns inputs are wired to those datalists via `list=`, so column
  names autocomplete (and the reference line shows the exact names for the
  comma-separated multi-column cases). Fields stay free-text; this is assistance,
  not a hard constraint.

## Verify
The endpoint's header-split was exercised standalone (BOM stripped; quoted
`"full name"` and embedded-delimiter `"c,d"` handled). The endpoint mirrors the
existing xlsx/sheets resolution + badRequest handling. designer.html passes
node --check; no literal \n/\r; no unsafe Thymeleaf. NOTE: the designer's async
load flow could not be live-tested in the chat sandbox (no running app); it
mirrors the existing xlsx-sheets / cross-workflow async patterns. Full Maven build
to be confirmed on deploy.

## Design status
`.claude/DIFF_EXECUTOR.md` is now fully implemented: three modes (POSITIONAL,
CSV_KEY, TEXT); multi-attribute matches with text/numeric and the multi-occurrence
rule; key substring L/R; cross-workflow file picking with run-correlation stamping;
and column autocomplete. Nothing outstanding.
