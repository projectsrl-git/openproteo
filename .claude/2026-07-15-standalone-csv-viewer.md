# Standalone CSV viewer (csv-viewer.html at repo root)

Self-contained HTML at the project root, runnable via file:// (no server/CDN), for
testers. Two tabs (Data / Aggregate). Mirrors the internal viewer: CsvService-style
parsing (BOM, delimiter auto-detect, quote-aware split; manual override) and
displayschema.json titles (List or {columns:[...]}; name/ColumnName/COLUMN_NAME ->
DisplayName variants; DisplayName over ColumnName). Data tab: virtualised grid,
all-columns filter, click-sort, per-column auto-width + drag-resize. Aggregate tab:
group-by + DISTINCT COUNT + SUM (multi each) + optional pivot + substring COL=L4/R2 +
TOTAL row + CSV export. Dates formatted only on visible cells (filter/sort/agg on RAW),
schema-driven (format/dateFormat/... or type=date; auto yyyyMMdd/ISO/dd-MM-yyyy/Excel
serial). JS uses String.fromCharCode (no literal \n/\r).
