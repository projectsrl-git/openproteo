# Workflow versioning on structural changes (section 2, batch 2)

Second and main batch of section 2 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`. Batch 1 shipped
`${parentId}`; this adds the trigger, the dialog and the version save. Operations showing a version
next to its parent is still outstanding.

## The trigger

Server-side in `ApiController.save`, before anything is written. All THREE must hold:

1. the feed already exists in the registry;
2. the set of **STEP** node ids changed (added or removed);
3. the feed has at least one run on disk.

Then the save is refused with `409` and `{versionSuggested, nextVersionId, addedSteps, removedSteps,
scheduled}`, and the designer asks. `structuralOverwrite=true` bypasses it and is what the "overwrite
anyway" path sends.

Detection compares `stepIdsOf(toDto(existing))` against `stepIdsOf(dto)` - going through `toDto` on
both sides means the comparison is against exactly what the designer would have loaded, so a
difference can only be a real edit and not a representation artefact.

**Only STEP nodes, as decided.** Gates and LOOP/ENDLOOP added, removed or moved are ordinary edits.
Reordering steps without adding or removing any is also ordinary. Renaming a step id counts as one
removed plus one added, which is exactly what it is as far as the history is concerned.

## Why it is a refusal and not a warning

The run history is audited against a definition. If the steps change under it, a run recorded months
ago no longer matches the workflow it claims to have executed, and nothing on disk records that the
definition moved. That is not a nuisance in a Legal Archive feed, so the save is stopped rather than
annotated.

## Version id allocation

`nextVersionId` keys the family on `parentId`, so editing `tf0003819.v2` allocates `tf0003819.v3` and
NOT `tf0003819.v2.v1`: versions are a flat list under one parent, not a chain. It takes max+1, so
gaps left by a deleted version are never reused - reusing an id would attach a new definition to the
old one's run directory. Candidates are checked against BOTH the registry and the workflows
directory, because a definition that fails to parse is absent from the registry while its file, and
therefore its id, is very much still taken.

## Failing safe

`hasRuns` returns **true** when the run history cannot be read. Suggesting a version that was not
needed costs the operator one checkbox; silently overwriting a definition that a run was audited
against cannot be undone.

## The version save

`saveAsVersion()` on the client reuses the Duplicate-as-new machinery rather than inventing a second
path: `DUP_FROM` makes the server copy the uploaded files once the new feed exists, `EDIT_FEED_ID =
null` turns the save into a create, and the Feed ID field is updated so the operator sees the new id.

The one addition is `wf.cron = ''`. `WorkflowScheduler` skips a workflow whose cron is null, so this
is what makes the version **scheduling-inert**: the original keeps the schedule and remains the one
that runs tonight. Both the dialog and the success banner say which workflow is still scheduled,
because that is the question an operator will actually have.

Everything else - PROD, locked, tags, variables - is inherited unchanged. Only the cron is cleared.
Clearing PROD would weaken the guards on a definition that is a copy of a production one; locking it
would produce an undiagnosable "why won't it run".

## Overwrite is a checkbox, not a second button

The dialog has one OK button and a checkbox reading "Overwrite ... in place instead - its past runs
will no longer match its definition". That keeps the version as the default the spec asked for, works
with the existing `opConfirm` API, and puts the consequence in the label rather than in a tooltip.

## Consistency with the Variables page

The bulk add from the Variables page still does not version - decided in the previous batch, because
one click across forty feeds would produce forty unscheduled workflows and change nothing about what
runs tonight. Its confirmation text now says so explicitly, so the two paths do not look arbitrary.

## Verification

* The trigger and the id allocation were transcribed against stubs, compiled with `-source 8` and run
  against 19 assertions: a step added, removed and renamed all trigger; a gate or LOOP/ENDLOOP added
  or removed does NOT; steps merely reordered do NOT; no runs, a brand-new feed, or
  `structuralOverwrite` each suppress it; first version, second version, max+1 across a gap, a
  version editing its own family, an unparseable file still occupying an id, and near-miss ids in
  other families all allocate correctly.
* `saveAsVersion` was extracted from the template and run in node against stubs, asserting the
  mutations that matter: **cron cleared**, feedId and the visible field updated, `EDIT_FEED_ID`
  cleared, `DUP_FROM` set to the original, the schedule fact carried to the banner, and the re-save
  issued without re-prompting and without the structural bypass.
* `node --check` on all 152k of the designer's inline JS, zero literal newline/CR escapes, only the
  pre-existing commented Thymeleaf marker; brace/paren balance 0/0 on `ApiController`; import checker
  green on every added line.

**Not verified**: `mvn clean package`, and the flow end to end - an actual refusal against a real run
history, the created `.v1` file, the copied uploads and the reschedule. Try it first on a
non-production feed that has at least one run.

## Still open in section 2

Operations does not yet show a version next to its parent, so `tf0003819` and `tf0003819.v1` look
like unrelated feeds in the grid. That is the remaining batch: group or badge by `parentId`, using
the `isVersioned()` companion already added in batch 1.
