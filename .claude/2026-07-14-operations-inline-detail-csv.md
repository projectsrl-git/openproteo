# Operations: always-visible detail, inline filter, CSV export/copy

## Problem / goal
The Operations page showed only the per-source rollup; the per-feed detail was
hidden until you clicked a rollup number. Goals:
- show the detail table directly, with the rollup tiles acting as a filter
  (not a show/hide toggle);
- add an inline text filter (like the home page);
- allow CSV extraction of the **displayed** (filtered) rows and of the
  **selected** (checkbox) rows — two separate exports;
- allow copying the table as CSV to the clipboard (for mail/chat);
- (clear-history in multi-select already existed from the bulk-actions batch).

## Approach (frontend only: templates/overview.html)
- `drill` now defaults to `{bucket:'total', source:null}` so the detail renders
  on load; `clearDrill()` resets the filter to "all" and clears the text box
  instead of hiding the panel; the close button became "Show all".
- The inline filter input and the CSV/Copy buttons live in the **static** panel
  header (not inside the re-rendered `#drillWrap`), so the 20s auto-refresh does
  not steal focus or wipe typed text. `renderDrill()` reads the filter value,
  applies it (feedId/name/source/target/status contains match) on top of the
  bucket/source filter, and records the visible list in `drillDisplayed`.
- CSV built client-side: `feedCsvRows` → `toCsv` (RFC-style quoting via
  `csvCell`, CRLF line ends built with `String.fromCharCode` — no literal
  newline escapes, UBS proxy safe). `exportDisplayed`/`exportSelected` download a
  Blob; `copyDisplayed` uses the async clipboard with a textarea fallback.
- Array-of-arrays literals avoided in the template (`rows.push([...])` not
  `[[...]]`) so Thymeleaf inlining never grabs a `[[`.

## Backward compatibility
Pure UI. No endpoint, model or data change. The rollup, multi-select bulk bar
(Run/Lock/Unlock/Clear history/Delete) and persistent selection set are
untouched.

## Verify
overview.html passes node --check; zero literal \n/\r; zero unsafe `[[`/`[(`.
