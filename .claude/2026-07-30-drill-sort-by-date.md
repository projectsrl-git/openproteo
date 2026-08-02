# Operations drill grid: sort by the two date columns, newest run first by default

The feed list under the rollup came out in backend order, so the most recently executed feeds were
not necessarily on top.

- New state `drillSort` (default `{ key:'lastRunTs', dir:'desc' }`), so the grid now opens sorted by
  LAST RUN descending.
- `Last run` and `Last success` are clickable headers (`th.sortable`, styled in app.css) with an
  indicator: the active column shows an accent-coloured up/down triangle, the other a faint sort glyph.
  Clicking the active column toggles asc/desc; clicking the other switches to it starting from desc
  (newest first, which is what you almost always want).
- `drillCmp` compares the timestamps as text, which is chronological for the yyyy-MM-dd HH:mm:ss format
  the backend sends, falls back to feedId for equal values (stable, predictable order) and always sends
  missing values LAST in both directions -- a feed that never ran, or never succeeded, should not push
  real data off the top when sorting ascending.
- The sort is applied to the filtered list right before `drillDisplayed` is set, so "CSV (displayed)"
  and Copy export in exactly the order shown on screen.

Only the drill grid is affected; the live-executions table is untouched.

Verify: the real drillSort/drillCmp/drillSetSort were extracted from the template and executed in Node
over the feeds from a production screenshot plus a never-succeeded and a never-run feed -- default gives
newest run first, the toggle reverses it, last-success sorting puts the never-succeeded feed last, the
never-run feed stays last in every mode, and each click triggers exactly one re-render. overview.html
passes node --check with no literal \n/\r and no unsafe Thymeleaf.
