# Standalone viewer: per-column filters (search options)

The viewer had only the free-text (all-columns) search. Added per-column range
filters, mirroring the internal viewer:
- pick a column, enter "from" (min/exact) and/or "to" (max), click "+ Add column
  filter" -> a removable chip "Column: from .. to";
- multiple filters combine with AND, and with the free-text search;
- comparison uses cmpValue: numeric when both operands look numeric, otherwise
  case-insensitive text (same rule as CsvService); the range is inclusive; the same
  value in both fields = exact match. Filters run on RAW values (fast).

csv-viewer.html only (delivered raw in the zip; the deploy script extracts it into the
repo root). JS uses String.fromCharCode (no literal \n/\r); logic verified in Node.
