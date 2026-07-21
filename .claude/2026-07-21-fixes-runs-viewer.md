# Fixes: deleted-run rows, light-theme zebra, range filter in standalone aggregate

## #2 Deleted run still showed in run history
The run history page (runs.html) is built from the audit log grouped by runId; deleting
a run removes its JSON + step logs and logs RUN_DELETED, but the audit events remained so
the run's node stayed. Fix: runs.html now marks a run as deleted when it has a RUN_DELETED
event and hides it from the list (the audit trail itself is kept in the log for
compliance). The run counter reflects only visible runs.

## #3 Light theme: csv-viewer zebra rows hard to read
Added explicit light-theme rows to csv-viewer.html: white base, #f2f5f9 odd rows, soft
amber hover -- a gentle zebra instead of the too-weak translucent overlay.

## #4 (standalone) Range filter not applied to the aggregate
csv-viewer rowsForAgg() only applied the free-text filter. Now, when "respect Data
filter" is on, it also applies the per-column range filters (RANGES via rangeOk), so the
Aggregate tab reflects the same rows as the Data grid.

(The internal OpenProteo viewer's aggregation ignoring range filters, and copying uploaded
files when duplicating a workflow, are backend changes delivered separately.)

Verify: runs.html and csv-viewer.html pass node --check; no literal \n/\r; no unsafe
Thymeleaf. runs.html is a template -> rebuild the WAR; csv-viewer.html is the raw file.
