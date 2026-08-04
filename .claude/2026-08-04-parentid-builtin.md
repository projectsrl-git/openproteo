# `${parentId}` built-in — section 2, batch 1 (foundation for workflow versioning)

First batch of section 2 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`. Only the built-in variable:
the structural-change detection and the "save as a new version" dialog are batch 2.

## Why this first

`${parentId}` is the only part of section 2 that is provable in isolation, changes no behaviour, and
is useful on its own — an author can start writing `${parentId}` into delivered file names *before*
any version exists, because on an unversioned feed it equals `${feedId}`. Shipping it separately also
means the risky part of section 2 arrives on a codebase where the variable is already deployed and
observed.

## What was verified rather than assumed

The spec DECIDED that `tf0003819.v1` is an acceptable feed id so the registry stays untouched. That
is now checked against the code rather than trusted: every place a feed id is validated
(`WorkflowXmlParser:65`, `ApiController:1806` and `:2321`, `designer.html` `clientValidate` twice)
uses `[A-Za-z0-9._-]+`, which already admits a dot. No change was needed anywhere.

## The derivation

`VarResolver.parentId(feedId)`: strip ONE trailing `.v<digits>`.

* Textual and **total** — on a feed that is not a version it returns the feed id unchanged, so
  `${parentId}` is never empty and can be used unconditionally. That is the whole reason it exists as
  a variable rather than as a UI label.
* Only one suffix is stripped: `tf0003819.v1.v2` -> `tf0003819.v1`, which is what the naming says.
* `x.v` (no digits) is not a version. `.v1` would strip down to nothing, so it is returned unchanged
  rather than becoming the empty string.
* `null` and blank give `""`, never `null`.
* Companion `isVersioned(feedId)` for the UI batch.

Published in all six places `${feedId}` is: `WorkflowEngine.buildRun` (run variables), the two
design-time preview maps in `ApiController` (`feedVars` and the csvsql preview), the tag-resolution
map used by Operations, and in the designer both the Builtin-vars cheat sheet and the path
autocomplete list.

## Decision taken here: the Variables page stays OUT of the versioning trigger

Left open at the end of the previous batch. The section 3 bulk add now creates steps in N feeds, and
section 2 wants to intercept exactly that on a feed that has already run. Decision: **the trigger
applies to the designer save only.**

The reason is arithmetic. Section 2 creates a version that is scheduling-inert and leaves the
original scheduled. Applied to a bulk add across 40 feeds, one click would produce 40 new workflow
ids, none of them scheduled, and change nothing about what runs tonight — the operator's intended
change would simply not happen, and there would be 40 workflows to clean up. A version is an
interactive, one-feed-at-a-time decision by the author; a fleet-wide add is a deliberate operation
where the operator has already confirmed PROD inclusion in the dialog. The bulk-add confirmation text
should say plainly that it modifies the feeds in place and creates no versions — a wording change to
fold into batch 2, listed here so it is not lost.

## Batch 2 (not in this patch)

Structural-change detection on the designer save (steps added or removed, and the feed has at least
one run), the version/overwrite dialog, `.v<n>` id allocation with increment, uploaded files copied
like Duplicate-as-new, the new workflow created scheduling-inert, and the dialog stating which
workflow is still scheduled. Then, separately, Operations showing the version next to its parent so
`tf0003819` and `tf0003819.v1` do not look like unrelated feeds.

## Verification

`parentId`/`isVersioned` transcribed verbatim, compiled with `-source 8` and run against 18
assertions: unversioned returns itself, null and blank give `""`, `.v1`/`.v12`/`.v0` strip, only one
suffix strips, whitespace is trimmed, and `x.v`, `x.V1`, `xv1`, `tf.0003819`, `.v1` and `.` are all
correctly NOT treated as versions.

Brace/paren balance 0/0 on the three Java files; the import checker is green on every added line;
`node --check` on the designer's inline JS passes with zero literal newline/CR escapes and only the
pre-existing commented Thymeleaf marker.

**Not verified**: `mvn clean package`, and `${parentId}` resolving inside a real run.
