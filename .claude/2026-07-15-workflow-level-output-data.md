# Workflow-level OUTPUT DATA (Batch C, req 1.5)

Output data could only be defined per STEP (step params outputData.*). Now it can
also be defined at the WORKFLOW level, with the same "variable = description"
textbox on the Variables page.

## Model / round-trip
- WorkflowDef gains `Map<String,String> outputData` (var -> description).
- XML: a `<outputData><var name=".." desc=".."/></outputData>` section (mirrors
  `<variables>`). Parser reads it; writer writes it (from WorkflowDto.outputData,
  populated in toDto). DOM round-trip verified standalone.

## Save + display
- Variables page: a "workflow output data" textarea (one `var = description` per
  line) at the workflow level, both in the single-feed and the multi-feed common
  editor (differ-across-feeds aware, like tags). var-catalog exposes it as text;
  VarSaveReq.FeedEdit gains `outputData`; applyEditsToDto parses the text into
  dto.outputData (full replace), exactly like the step-level output-data parsing.
- Operations (/api/overview/feeds): the output-data variable set now includes the
  workflow-level definitions in addition to the step-level ones, so both feed the
  last-run and all-runs output-data display (Batches A/B).

## Verify
DOM round-trip of the `<outputData>` element verified standalone (parse -> map ->
write). variables.html and overview.html pass node --check; no literal \n/\r; no
unsafe Thymeleaf. The var=description parsing mirrors the proven step-level path.
Backend mirrors the existing tags/variables round-trip. Full Maven/stub build not
run this turn — confirm on deploy.
