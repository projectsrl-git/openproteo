# Variables page: edit step core fields (e.g. SQL query), incl. across feeds

## Goal
On the variables manager, besides workflow variables and step params, edit step CORE fields — above
all the SQL query — and do it across multiple selected feeds for steps that share the SAME step id.

## Changes
- /api/var-catalog: each step now also returns `fields` (the non-null core fields among query, source,
  dest, datasource, ifsPath, csvFile, delimiter).
- /api/variables/save: VarSaveReq.StepEdit gained `fields`; applyStepField(nd, name, value) sets the
  matching NodeDto core field. Step params editing is unchanged. As before, every modified XML is
  regenerated and validated with the runtime parser before anything is written (all-or-nothing).
- variables.html:
  - Single-feed mode now shows each step's core fields (query as a multi-line box) plus its params.
  - Multi-feed mode gained a "Common steps" section: step ids present in EVERY selected feed; for each,
    the fields/params common to all are editable. A value shared by all feeds is pre-filled; if it
    differs a "(differs)" hint is shown and typing sets the same value for every selected feed.
  - Save now distinguishes var / field / param scopes and, in multi mode, applies the field/param edits
    to every selected feed (only to the matching step id). Inputs use textarea for the query.

## Verification
- Compiles (103 classes). Round-trip test: editing a step's query via DTO -> XML -> parser persists.
  variables.html node --check OK; no literal \n/\r; no unsafe [[ /[(.
