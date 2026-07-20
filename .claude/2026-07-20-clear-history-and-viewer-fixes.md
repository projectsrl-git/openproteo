# Fixes: Operations clear-history, designer clear-history, viewer column widths

## 1. Operations "Clear history" threw opConfirm is not defined
overview.html never loaded modal.js, so opConfirm/opAlert were undefined and
drillBulkClear failed before showing the dialog. Fix: overview.html now includes
modal.js (and theme.js, so the environment badge / theme toggle also work there).
The dialog (PROD confirm checkbox when production feeds are selected + "keep the most
recent run") was already wired; it just needed the modal script.

## 2. Designer (EDIT) "Clear History" was the old dialog
designer.html clearHistory() still used the pre-Batch-D flow: it hard-blocked
production and passed no confirmProduction/keepLast. Rewritten to match dashboard /
Operations: a required PROD checkbox when the workflow is Production (instead of a
block) and a "Keep the most recent run" option, threaded to
clear-history?confirmProduction=&keepLast=.

## 3. Standalone viewer: all columns had the same width (illegible)
Columns were a fixed 160px. Now each column auto-sizes to its content: max of the
DisplayName, the ColumnName and a 300-row sample of values, ~7.2px/char + padding,
clamped 54..420px. Date columns get a small minimum. Added horizontal scrolling with a
sticky header (header now scrolls horizontally with the body and sticks on vertical
scroll) and drag-to-resize on each column's right edge.

Verify: overview.html, designer.html and csv-viewer.html pass node --check; no literal
\n/\r; no unsafe Thymeleaf. Auto-width exercised in Node. Backend unchanged.
