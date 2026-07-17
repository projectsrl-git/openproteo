# Clear history: PROD (checkbox-gated) + keep-last (Batch D, req 2.1/2.2)

## 2.1 Allow clearing PROD history behind a confirmation checkbox
- Backend: /api/workflows/{feedId}/clear-history gains `confirmProduction`. A
  PRODUCTION feed is no longer hard-refused; it's cleared only when
  confirmProduction=true, otherwise a clear message (feed skipped).
- opConfirm extended: `opts.checks = [{id,label,required,checked}]` renders
  checkboxes; the OK button stays disabled until every `required` box is ticked;
  onYes receives the checked-state map.
- Operations (drillBulkClear, now opConfirm not native confirm): counts the PROD
  feeds in the selection and, if any, shows a REQUIRED checkbox "I understand N
  production feed(s) are included". Dashboard (bulkClearHistory) shows an optional
  "Also clear PRODUCTION feeds (otherwise skipped)" gate. Both pass
  confirmProduction to the endpoint.

## 2.2 Keep the most recent run
- Backend: clear-history gains `keepLast`. When true it deletes every run except
  the most recent (store.delete per run) instead of the full clearOneFeed wipe,
  preserving the latest run's state, logs and working output. Audited as
  FEED_HISTORY_CLEARED_KEEP_LAST.
- Both confirm dialogs offer a "Keep the most recent run of each feed" checkbox.

## Verify
modal.js, dashboard.html, overview.html pass node --check (no literal \n/\r, no
unsafe Thymeleaf). The opConfirm required-checkbox gating was exercised in Node
(OK disabled until the required PROD box is ticked; enabled when no PROD present).
Backend mirrors existing store.list/store.delete/badRequest/audit patterns; full
Maven build not run this turn.
