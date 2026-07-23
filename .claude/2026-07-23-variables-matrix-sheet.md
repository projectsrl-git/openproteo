# Variables matrix: spreadsheet editor (feeds = rows, variables = columns)

New page /matrix (template matrix.html) to insert/edit everything in one screen, like a
spreadsheet. Complements the existing Variables page (which is per-field, mass-edit oriented).

## Layout
- Rows = feeds (sticky first column: feedId + name; PROD feeds get a red left border).
- Columns = the union of every workflow variable name across all feeds (sorted), plus two
  optional meta columns: `tags` (text) and `PROD` (checkbox).
- Header row and feed column are sticky; the sheet scrolls both ways.

## Editing
- Type in any cell; only cells you touch are sent. Edits are held in an EDITS map keyed by
  feedId+kind+name, so filtering/sorting never loses them; dirty cells are highlighted.
- An empty cell means the variable is not defined for that feed (placeholder "not set");
  typing a value CREATES it on save (applyEditsToDto adds unknown variable names).
- "+ Add column" adds a new variable name as a column across all feeds.
- The ▾ button in a column header fills that column down to every visible feed (mass set).
- Arrow Up/Down and Enter move between cells; pasting a TSV block from Excel fills cells to
  the right and below (clipboard split on String.fromCharCode(9/10/13)).
- Filters: feed text filter, variable/column text filter, "only columns that differ" (shows
  just the variables whose value is not identical across the visible feeds).
- Discard asks for confirmation; leaving with unsaved edits warns via beforeunload.

## Backend
No new save endpoint: the grid posts the existing POST /api/variables/save with
{edits:[{feedId, vars:[{name,value}], tags?, production?}]}. var-catalog now also returns
`production` per workflow (it did not before, so the Variables page PROD hint was always
"non-production" too).

Verify: matrix.html passes node --check, zero literal \n/\r, no unsafe Thymeleaf; the
save-payload grouping was executed in Node from the real source (vars/tags/production grouped
per feed, new variable included). Java not compiled here -> Maven build on deploy.
