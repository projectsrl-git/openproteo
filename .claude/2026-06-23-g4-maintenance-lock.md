# G4 — per-feed maintenance lock

## Goal
Let any feed be locked to block execution while it is under maintenance.

## Behaviour
- A locked feed REFUSES manual runs and scheduled (cron) runs. Step-by-step TEST runs remain
  allowed, so you can still configure/verify a feed during maintenance.
- The flag is persisted in the workflow XML (root attribute locked="true").

## Changes
- Model: WorkflowDef.locked. Parser reads root @locked; writer emits it (mirrors production).
- DTO: WorkflowDto.locked; toDto maps it; designer save round-trips it.
- Engine.start(): refuses when def.locked (audit RUN_REJECTED, reason "feed is locked for
  maintenance") — this single point also covers the cron scheduler.
- API: POST /api/workflows/{feedId}/run returns 409 {locked:true} with a clear message when locked;
  POST /api/workflows/{feedId}/lock?locked=true|false toggles the flag (rewrites the workflow XML via
  toDto+writer, reloads the registry, audits FEED_LOCKED / FEED_UNLOCKED).
- /api/overview/feeds and /api/overview/active now include `locked`.

## UI
- Designer: a "Maintenance lock" switch next to Production; saved with the workflow.
- Dashboard: a lock badge on the feed id, the Run button disabled on locked feeds, and a quick
  Lock/Unlock button per row.
- Workflow page: LOCKED badge in the header and Run now disabled when locked.
- Operations: a lock glyph next to locked feeds in the drill-down and the executions list.

## Verification
- Round-trip test: dto.locked=true -> XML locked="true" -> parsed def.locked=true; false -> no attr ->
  false. Compiles (99 classes). designer/dashboard/workflow/overview JS node --check OK; no literal
  \n/\r; no unsafe [[ /[(. Live behaviour on UBS.
