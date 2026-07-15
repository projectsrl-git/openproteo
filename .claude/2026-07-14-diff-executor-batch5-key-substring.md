# diff executor — Batch 5 (CSV_KEY key substring L/R)

Implements `.claude/DIFF_EXECUTOR.md` §5.1 (Substring L/R on key columns). Applies
on top of Batch 1-4 (all on main).

## Design note
The design said to "reuse the grouping widget's Substring L/R control" — that
control does not exist in this codebase. Rather than build a new widget, the
substring is expressed as a compact **per-column suffix inside the existing keysA
/ keysB fields**, so it is fully backward compatible (plain column names are
unchanged) and needs no structural/model change:
- `NDG` — whole column.
- `NDG:L8` — first 8 characters → `LEFT(NDG, 8)`.
- `CODCLI:R4` — last 4 characters → `RIGHT(CODCLI, 4)`.

This lets two feeds whose keys differ only by padding/prefix (e.g. A stores
`AB1234`, B stores `1234`) reconcile via `code:R4` ↔ `code`.

## What this batch delivers
- `runDiffKey`: each key column token is parsed by `keyColSql` into an SQL
  expression (bare column, or `LEFT(col,n)` / `RIGHT(col,n)`); these expressions
  build the key `CONCAT_WS`. Invalid tokens are rejected with a clear message. The
  raw tokens (e.g. `NDG:L8`) are still echoed verbatim in the report's Key A / Key
  B config lines (audit-readable). H2's `LEFT`/`RIGHT` handle short strings
  gracefully (`RIGHT('AB',4)` → `AB`).
- Designer: a hint under the key inputs documenting the `:L<n>` / `:R<n>` syntax.
  Keys stay free-text (no new widget).

## Verify
The actual updated `runDiffKey` was run standalone against real H2 2.1.214 with
`keysA=code:R4`, `keysB=code`: keys aligned (AB1234→1234, XY5678→5678), the value
mismatch on 5678 was reported, keysCompared=2. `LEFT`/`RIGHT` availability and
short-string behaviour were probed on H2 first. designer.html passes node --check;
no literal \n/\r; no unsafe Thymeleaf. Full Maven build to be confirmed on deploy.

## Remaining (optional QoL, see DIFF_EXECUTOR.md)
Only column **dropdowns** in the CSV_KEY panel from a header-preview endpoint
remain (keys/match columns are still typed free-text with the syntax above). All
functional reconciliation capabilities of the design are now implemented.
