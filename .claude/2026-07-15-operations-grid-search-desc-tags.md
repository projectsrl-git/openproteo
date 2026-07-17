# Operations grid: search-all, source/target descriptions, tags (Batch A of the request)

Covers request items 1.1, 1.2, 1.3.

## 1.1 Free search over the whole grid (incl. dates)
The feed filter now matches across every displayed field: feedId, name, source id
+ source description, target id + target description, tags, last status, failed
step, last-run and last-success timestamps (so dates are searchable), and the
output-data labels/values. (Was: only id/name/source/target/status.)

## 1.2 Source / Target show the description too
The SOURCE and TARGET columns now show the id (mono) with the description beneath
it. Both descriptions were already returned by /api/overview/feeds
(sourceDescription / targetDescription); this is a display change only.

## 1.3 Tags in the FEED column (searchable)
/api/overview/feeds now also returns `tags` (from the workflow definition). The
FEED column shows a "tags: …" line, and tags are included in the search (1.1).

CSV export/copy updated to include sourceDescription, targetDescription and tags.

## Verify
Backend: one line added (tags) mirroring the existing targetDescription put.
overview.html passes node --check; no literal \n/\r; no unsafe Thymeleaf. The
search hay-building was exercised in Node: it matches a date, a tag, the source
and target descriptions, and an output-data label/value, and rejects an absent
string. Full render to be confirmed on deploy (Ctrl+F5).
