# PROD flag mass-editable (Batch E, req 4)

The production (PROD) flag is now one of the fields editable via the Variables
page — including the multi-feed (mass) editor.

## Backend
- VarSaveReq.FeedEdit gains `Boolean production` (null = unchanged).
- saveVariables applies it before applyEditsToDto:
  `if (fe.production != null) dto.production = fe.production.booleanValue();`
  (same rule the import path already uses).

## Frontend (variables.html)
- New `valSelect` helper (a dirty-tracked <select>, mirroring valLine).
- A "production flag" tri-state dropdown — leave unchanged / set PRODUCTION /
  clear PRODUCTION — in both the single-feed editor and the multi-feed common
  (mass) editor. The multi-feed hint shows whether the selected feeds are all
  PRODUCTION, all non-production, or mixed.
- collect() reads the `production` scope; only a touched (dirty) control is
  collected, so leaving it on "leave unchanged" sends nothing. The value is
  threaded into each edit as a Boolean (or omitted when unchanged).

## Verify
variables.html passes node --check; no literal \n/\r; no unsafe Thymeleaf. The
'' / 'true' / 'false' -> undefined/true/false mapping was checked in Node. Backend
mirrors the existing import-path production handling; full Maven build not run this
turn.
