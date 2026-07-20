# Operations: source & target multi-select filters

The feed grid gains two dedicated filters — Source and Target — each a
checkbox dropdown allowing one, several, or all values.

## Behaviour
- Each control is a button ("Source: all v" / "Target: all v") that opens a panel
  with an "All" row plus one checkbox per distinct value (blank shown as a dash).
- Empty selection = all (no filter). Selecting values filters to them; the button
  shows "N selected". Clicking "All" clears the selection back to all.
- Source and Target combine with AND, and with the existing free-text search and
  the rollup drill (bucket/source). "Show all" also resets both filters.
- Panels close on outside click.

## Implementation (overview.html + app.css, frontend only)
srcSel/tgtSel are plain objects (empty = all). msfDistinct builds the option list
from FEEDS; renderDrill excludes a feed when a non-empty selection doesn't contain
its source / target. .msf/.msf-panel/.msf-item CSS added (theme-aware).

## Verify
overview.html passes node --check; no literal \n/\r; no unsafe Thymeleaf; CSS
braces balanced. The filter logic (distinct, single/multi select, source AND
target, partial clear, blank value) was exercised in Node and matches expectations.
Frontend only — Ctrl+F5.
