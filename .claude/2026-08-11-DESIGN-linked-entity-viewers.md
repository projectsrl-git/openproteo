# DESIGN — Linked entities: multi-file JSON/CSV viewers with a relationship graph

Status: **Batches 1 (standalone JSON) and 2 (standalone CSV) delivered.** Batch 3 (in-app) pending.
Scope: `json_viewer.html`, `csv-viewer.html`, and the in-app `viewer.js` equivalents.

## 1. Request

1. Ask on open whether the document contains **self-references**; if yes, let the operator declare
   PARENT and CHILD fields from dropdowns.
2. A declared field becomes a **clickable link**; clicking it opens an interactive **SVG box diagram**
   in the style of `EOR_viewer.html`, navigable from either end and with a way back to the data.
3. Load **two or more files** into concurrent tabs, with a **memory cap** that refuses the last file
   rather than the whole set, and links declarable **across files**.
4. All of it for the **CSV viewer** as well.

## 2. Findings

### F1 — the model costs ~18× the file size, and that decides the cap

Measured, not estimated. A document shaped like the screenshot (5 000 customers with the same
nesting) was built, serialised and walked with the current `jsonBuildModel`:

| | |
|---|---|
| file bytes | 2.10 MB |
| model records | 168 329 |
| model heap | **37 MB** |
| per record | 228 bytes |

The uploaded file is **8.27 MB and 235 325 nodes**, so its model alone is on the order of **50-60 MB**,
on top of the parsed JSON and the DOM. A cap expressed in *file* megabytes is therefore the wrong
unit by an order of magnitude — see D4.

### F2 — the reference diagram is a FOCUS/PARENT/CHILD fan, not a graph

`EOR_viewer.html:961` (`renderUmlDiagram`) draws one centred FOCUS box, parents down the left, children
down the right, bezier links with arrow heads, and each badge is a `<g class="uml-node" data-ndg=…>`
that re-centres the diagram on click (`goToNode`/`goToParent`/`goToChild`). It is a **one-hop view of
one entity**, not a whole-graph layout. That is the shape to copy, and it is also the shape that
scales: 5 000 customers cannot be drawn at once, one customer and its neighbours always can.

### F3 — the request's step 1 cannot happen when it says

"On opening the page, ask whether there are self-references" — but at page open there is no file, so
there are no fields to put in the dropdowns. The question is only answerable **after** a file is
parsed. See D1.

### F4 — CSV has no nesting, so the same feature is simpler there, not harder

`csv-viewer.html` already holds rows and columns. A relationship is a column-to-column reference and
the same diagram applies unchanged, with a row as the entity. Nothing new is needed except the
declaration UI and the diagram, both shared with JSON.

## 3. Decisions

**D1 — the question is asked when a file is LOADED, not when the page opens.**
Per file: *"Does this file contain references between its own entities?"* → **No** goes straight to the
table; **Yes** opens the declaration panel. From the second file on, the same panel also offers
*"…or references to a file already loaded"*. Asking before a file exists would be a dialog with empty
dropdowns.

**D2 — a link is declared as `entity list · CHILD field → PARENT file · entity list · PARENT field`.**
For JSON the "entity list" dropdown is populated with every array-of-objects found in the document,
by path (`records`, `records[].relationships`), because that is what a table row is here. The field
dropdowns then hold that list's key union — which the table view already computes. For CSV the entity
list is the file itself and the fields are its columns. Several links can be declared; each is
named and can be removed.

**D3 — clicking a link opens the diagram for THAT value, from either side.**
Both ends are clickable, as asked: clicking a CHILD value focuses the parent it points at, clicking a
PARENT value focuses that entity and fans out everything pointing to it. The diagram is a **view, not
a page**: it replaces the table area with a Back control and the tab bar still visible, so the way
back is never in doubt. Badges re-focus the diagram, exactly as the reference does.

**D4 — the cap is on MODEL RECORDS, not on file bytes, and it is checked BEFORE committing.**
From F1 a byte cap would be off by ~18×. The load sequence is: read → parse → count nodes → **if the
running total would exceed the cap, discard this file and keep the others**, saying by how much it
overflowed. Default **400 000 records** across all open tabs (roughly 90 MB of model, about 1.7 of the
uploaded file), configurable in a field on the page so a workstation with room can raise it. The
count is exact because it is the same walk the model does.

**D5 — one tab per file, and links live at the WORKSPACE level.**
Tabs across the top, each with its own table, search and expand state. A declared link belongs to the
workspace, not to a tab, so a cross-file link is the same object as a self-link with a different
target file — which is what makes 2.3 fall out of 2.2 rather than being a second feature.

**D6 — resolution is by an index built per declared link, once.**
`Map<value, entity[]>` over the parent field. Built when the link is declared and kept; without it a
click would scan every row. Values are matched exactly, trimmed, case-sensitive — the same rule as
`${COL@key}`, and for the same reason: a near-miss must be a visible miss, not a silent wrong row.

**D7 — the CSV viewer gets the identical declaration panel and diagram**, sharing the code. The
difference is only where the entities come from.

## 4. Batches

1. **Standalone JSON**: tabs, the record cap, the declaration panel, clickable links, the diagram.
2. **Standalone CSV**: the same, on rows and columns.
3. **In-app viewer**: the same two, minus the file loading, since the file comes from the server.

## 5. Answers received

1. Cap default **400 000 records**, editable on the page.
2. A badge shows the **first three scalar fields** of the entity.
3. A CHILD value that resolves to nothing gets a **NOT FOUND** badge, drawn dashed and in red, with a
   dashed arrow; it is not clickable, because there is nothing to go to.

## 6. Batch 1, as built

`json_viewer.html`: one tab per file, the record budget with a live bar and an editable limit, the
per-file question, the declaration panel with six dependent dropdowns, clickable links on both sides
and the focus diagram. Files are read **one at a time** so the budget is checked against what has
already been accepted — reading them in parallel would let two files each fit on their own and
overflow together. Closing a file drops the links that mention it, because a link to a closed file
would resolve against nothing.

### Defects the jsdom run caught before delivery

* **The page did not start at all.** `WS` is a `var`, so its assignment is not hoisted, and `paintCap()`
  at the end of the page block ran before the workspace block was evaluated. Fixed by ordering the
  blocks; the browser would have shown an empty page with a console error.
* **A declared link matched no row.** An entity's own path carries the element suffix
  (`$.records[]`) while a link is declared against the list (`$.records`). Added `wsListPath`, which
  strips one trailing `[]`.
* **A link on a top-level array never matched.** With an empty root path a top-level array's elements
  had path `[]`, which cannot be reduced back to the list's path. The root is now named `$`.

## 6. Verification plan

* The record counter and the cap: exact counts, a file accepted, one refused with the others intact.
* Link resolution: hit, miss, several matches, trimmed value, wrong case.
* The diagram: focus/parent/child geometry, both directions clickable, re-focus, Back.
* Tabs: independent state, close, memory released.
* jsdom on both standalone pages driven through their real file inputs.
* **Not verifiable here**: rendering on screen, and behaviour at the cap on a real workstation.

## 7. Batch 2, as built — the standalone CSV

`csv-viewer.html` gains tabs, the memory budget, declared relationships and the diagram, on the
**same shared core** the JSON viewer runs. Nothing about links, indexes, the swapped-sides check, the
cache or the diagram was rewritten: a file only answers `lists`, `iterField` and `entityOf`, and for a
CSV those are trivial — the list is the file, an entity is a row, a field is a column. The **ref is a
row number**, so indexing a hundred-thousand-row file costs an array of integers, and the row object
is built only for the entities a diagram actually shows.

The existing grid, filters, ranges, sorting, aggregation and displayschema handling are untouched.
`activate()` reuses every rendering path unchanged; only `drawRows` learned to render a declared
column as a link, and the link map is computed **once per frame** rather than per cell, because
`drawRows` runs on every scroll frame.

**The budget is in CELLS**, not records: 50 000 rows by 20 columns measured 50 MB, about 52 bytes a
cell and 4.6x the file on disk. JSON's ratio is eighteen. Two viewers, two units, because one number
would have been wrong for one of them.

A CSV row has no parent, so the diagram's "lives inside" line is correctly absent rather than empty.

### Three defects caught by the tests, two of them older than this batch

* **`linksPersist` only ever ADDED.** Removing a relationship left it on disk, so it returned on the
  next load and the only cure was Forget, which cleared everything. Now a saved relationship whose two
  files are **both open** is replaced by what is declared, while one naming a file that is not open is
  left alone — it is waiting, not removed. Fixed in **both** viewers.
* **The JSON viewer never persisted on removal at all**: its remove handler updated the list and the
  table but never wrote. Removing appeared to work and then quietly did not.
* **Two `var WS` in one scope.** The CSV page declared its own beside the core's; both parse, the
  later assignment wins, and the page ran with the JSON viewer's record budget instead of its cell
  budget. The duplicate-*function* scan could not see it — the checks now scan **top-level `var`s**
  too. This is the second time a silent duplicate declaration has cost a debugging round.

**Verified** by 59 assertions driving the real page: the existing features still working, tabs and
grid switching, a cross-file declaration with both directions reported, the swapped-sides warning
firing on genuinely asymmetric data and staying silent when the signals disagree, both ends clickable,
the diagram with incoming rows on the left and each naming its file, a dangling reference drawn as NOT
FOUND, the budget refusing a file while keeping the others, a limit below the floor refused without
taking effect, the declaration surviving a reload, removal really removing, and closing a file
dropping the live link while the saved one waits.
