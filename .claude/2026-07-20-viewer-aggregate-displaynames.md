# Standalone viewer: displayschema names in the Aggregate dropdowns

The Aggregate tab dropdowns (Group by / Distinct count of / Sum of multi-selects and
the Pivot by select) showed only the technical column names. They now use the
displayschema:
- dropdown options show "DisplayName — ColumnName" (colLabel); the option search
  matches either the DisplayName or the ColumnName;
- selected chips and the result-table headers (group columns, DISTINCT_/SUM_ labels,
  pivot caption) show the DisplayName when present, else the ColumnName (colShort).
Column indices still drive the aggregation, and substring specs still use the
ColumnName — only the labels changed. csv-viewer.html only (raw in the zip).
