# 2026-06-18 — targetId fix, PROD flag + passthrough, Clear History, XML edit, light fixes

## fix-1 targetId not saved
The /definition DTO (used to load a workflow into the designer) set sourceId but NOT targetId,
so on edit the field came back empty and was saved blank. Added dto.targetId (and dto.production)
to the definition endpoint. End-to-end round-trip confirmed (writer/parser already handled it).

## mod-1 passthrough for anonymize/mask
New: a mask/anonymize step does a verbatim input->output copy (no masking) when its param
passthrough=true OR the workflow runs in PROD. Implemented at the top of runMask/runAnonymize
(maskPassthrough + passthroughRequested). Output path resolved like the normal run
(outFile param or <name>_masked/_anon). Emits ##VAR outputFile. Tested.

## mod-3 Production environment flag
WorkflowDef.production + DTO + parser (reads production="true") + writer (emits it) + definition
DTO + designer switch (default off) with hint. Engine seeds run var __prod from def.production.
3.1: __prod=true forces every anonymize/mask step to passthrough. 3.2: Clear History disabled
in the designer when the workflow is production (applyProdState toggles .js-clearhist).

## mod-2 Clear History
POST /api/admin/clear-history: refuses if any run is active, then deletes every child of the
feeds base dir (recursive) and recreates it empty. Designer button with double irreversible
confirm; disabled for PROD workflows.

## mod-5 Edit generated XML
POST /api/workflows/save-xml: validates raw XML by parsing, writes it, reloads/reschedules.
Designer: "Edit XML directly" opens a textarea (prefilled from the generated XML); "Validate &
save XML" posts it and, on success, reloads the page on the saved feed. Good for cloning.

## mod-4 light theme black areas
Light overrides for .xmlbox, textarea/.file-editor-area/.xml-editor, .dropzone and the
wf-files-box table (were hardcoded dark).

## mod-6 masking dictionaries (diagnosis, not a bug)
The pools are present and inverted (firstnames_it.txt: Giusppee, Giovnnai, Antnioo, Mairo, ...).
pick() returns the ORIGINAL value when a pool is empty. Inverted names appear only for columns
mapped to firstName/lastName/fullName; if none are mapped (e.g. only cid columns), no inverted
names show. Asked the user to check the column mapping / run log.

77 classes compile; designer JS valid; no \n/\r; Thymeleaf-safe. passthrough + multi-pattern +
parser whitelist all tested.
