# Variables page: mass-edit "Step mode" (skip / on hold) with the editor's dropdown

The Variables (mass-edit) page could edit step fields/params/output data/on-success delete
across selected feeds, but not the step mode. Added a "step mode" dropdown per step (same
options as the designer step editor: leave unchanged / normal / skip (passthrough) / on hold
(pause)) so you can massively set skip or pause on the common steps of many feeds.

- variables.html: valSelect "step mode" in both the single-feed step view and the
  common-steps (multi-feed) view; hint shows the current mode ("currently: ..." or
  "differ across feeds"). Collect adds scope 'stepMode' -> step edit o.stepMode.
- ApiController: StepEdit gains `stepMode`; applyEditsToDto maps it to the step DTO exactly
  like the designer's setStepMode: skip -> skip=true/onHold=null, onHold ->
  onHold=true/skip=null, normal -> both cleared. var-catalog now also returns skip/onHold
  per step so the "currently" hint is accurate.

This uses the same dropdown as edit; other step dropdowns can be added the same way.

Verify: variables.html passes node --check (no literal \n/\r, no unsafe); ApiController
brace/paren balanced; mapping mirrors setStepMode. Java not compiled here -> Maven build on
deploy.
