# PROD badge, JSON/XML auto-prettify, CSV range search + column sort

## 1) PROD flag visible on Home and Operations
Feeds flagged production now show a red PROD badge: on the dashboard (next to the feed id, beside the
lock badge) and on Operations (drill-down feed list and in-progress executions). /api/overview/feeds
now returns `production`.

## 2) JSON / XML auto-prettified in the file viewer (no edit needed)
The viewer now pretty-prints .json and .xml files on open, read-only, using the existing formatter
(prettyFormat). Previously formatting was only available inside Edit. Falls back to raw text with a note
if the content does not parse. Edit (and its Format button) are unchanged.

## 3) CSV viewer: FROM/TO range search on selectable columns + column sort
- Range search: pick a column from the dropdown, type FROM and/or TO, "+ Add range". Multiple ranges
  combine (AND); each shows as a removable chip. Comparison is numeric when both value and bound parse
  as numbers, otherwise case-insensitive lexicographic (so ISO dates and codes work too).
- Column sort (3.1): click a column header to sort asc, again for desc, again to clear; an arrow shows
  the active column/direction.
- Both are applied server-side in CsvService.page (streaming filter; sort buffers the matching rows,
  capped at 300k with a "sorted first 300k" notice) and combine with the existing all-columns text
  filter and the virtualised paging. New repeated query params fc/ff/ft (column,from,to) plus
  sortCol/sortDir; the previous behaviour is unchanged when none are set.

## Verification
- Compiles (103 classes). CsvService self-test: numeric range, numeric vs lexicographic sort, date
  range + secondary sort all correct. viewer.js + overview.html node --check OK; no literal \n/\r; no
  unsafe [[ /[(.
