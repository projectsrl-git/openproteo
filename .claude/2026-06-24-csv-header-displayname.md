# CSV viewer: show the displayschema DisplayName in the header

## Goal
A friendlier CSV table view: each column header shows the human DisplayName from the feed's
displayschema.json (matched to the CSV column = ColumnName), with the technical ColumnName kept
underneath.

## Changes
- ApiController.displayNameMap(feedId): reads <feedDir>/displayschema.json (list, or under a "columns"
  key) and builds ColumnName -> DisplayName (accepts name/ColumnName/COLUMN_NAME for the key and
  DisplayName/displayName/display_name for the label). Empty for shared files / no schema.
- csv/meta now returns `displayNames` (aligned to `columns`) when at least one column matches; matching
  is exact first, then case-insensitive.
- viewer.js (CSV table): the header cell shows the DisplayName (bold) with the ColumnName as a small
  sub-label and a tooltip; the column falls back to ColumnName when there is no DisplayName. The range
  filter dropdown shows "DisplayName — ColumnName" and the active-filter chips use the DisplayName.
  Sorting/resize/paging are unchanged (still by column index).

## Notes
Only the table view is affected; export, aggregation columns and the raw text view keep technical
names. No change for shared CSVs or feeds without a displayschema.

Compiles (103 classes). viewer.js node --check OK; no literal \n/\r.
