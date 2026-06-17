# 2026-06-17 — {{columns}} field, mask sub-collapse, step id in header, light sub-steps

## #1 {{columns}} discoverability (answer + fix)
At run time `{{columns}}` in a SQL query is replaced by the column names read from the
dataschema JSON referenced by the step param `columnsSchema` (optionally quoted via
`columnQuote=double`). The designer SQL step had no field for `columnsSchema` (only the
validate step exposed a dataschema field), so the feature was not discoverable. Added a
"Column list from dataschema" field + "Quote columns" select + a hint to the SQL step.

## #2 mask partial collapse
mask step subsections (Column mapping, Pools & strategies, Pool files) are now collapsible:
subHead() builds a clickable sub-label; toggleSub() toggles 'collapsed' on the parent
.subsection; CSS hides every child except the header. State survives field edits (setNodeParam
only refreshes the preview, not the cards).

## #3 step id in header
The node header now shows the step id as a small mono pill next to the name, so it stays
visible when the step is collapsed (useful for referencing it in later phases).

## #4 light sub-step badges
Added light-theme overrides for the verification/anonymization sub-step nodes
(.bp-subnode/.bp-subdot/.bp-check PENDING/SKIP fills + strokes) which were hardcoded dark.

## Verify on UBS
SQL {{columns}} expansion with the new field; collapsing mask subsections; step id visible
when collapsed; sub-step badges in light theme. 76 classes compile; designer JS OK; no \n/\r;
Thymeleaf-safe.
