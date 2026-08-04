# Operations: version badge and family filter (section 2, batch 3 — closes section 2)

Last piece of section 2 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`. Batch 1 added `${parentId}`,
batch 2 the trigger and the version save; without this, `tf0003819` and `tf0003819.v1` sat in the
Operations grid looking like two unrelated feeds.

## What it does

`/api/overview/feeds` publishes two more fields per feed: `parentId` and `version` (the digits after
`.v`, empty when the feed is not a version). Both are derived server-side from
`VarResolver.parentId`, the same function that produces the runtime `${parentId}`, so the grid can
never disagree with the engine about what a feed descends from. Deriving them again in JavaScript
would have been zero backend change and a second definition of "version" waiting to drift.

The Feed cell then carries a badge:

* on a version: `v1 of tf0003819`;
* on an original that has versions: `2 versions`;
* on everything else: nothing.

Clicking either puts the family id into the existing search box and re-renders. The substring filter
already matched a parent id against its versions, so this adds no filtering logic - it just makes it
one click instead of typing.

## What it deliberately does NOT do

No grouping, no merging, no change to the sort. Versions are separate feeds with separate runs,
separate output data and separate audit trails, and that separation is the whole point of versioning
- a grid that visually merged them would suggest a relationship the system does not have. The badge
is a signpost.

## The orphan case

A version whose original has since been deleted shows only `v1`, with a tooltip saying the original
no longer exists. Printing `v1 of tf0003819` when `tf0003819` is gone would send the operator looking
for a workflow that is not there, and the family filter would return a single row with no
explanation.

## Verification

* The `version` extraction was compiled with `-source 8` and run against the same corpus as
  `parentId`: `.v1`, `.v12`, `.v0`, an unversioned id, a version of a version (reports only the last),
  and the four shapes that are NOT versions (`x.v`, `x.V1`, `tf.0003819`, `.v1`) - all consistent with
  `parentId`, which is what keeps the two fields from contradicting each other.
* `buildFamilies`, `drillFamily` and `versionBadge` were extracted from the template and run in node
  against synthetic feeds: the family map counts only versions, a parent reports the right count with
  singular/plural, a feed with no versions gets no badge at all, a version names itself and its
  parent, both badges filter on the PARENT id, the orphan shows `v1` without claiming a parent and
  explains on hover, clicking sets the search box and re-renders, and an id containing a quote is
  escaped in the attribute rather than interpolated raw.
* `node --check` on the page's inline JS, zero literal newline/CR escapes, no Thymeleaf inlining
  markers.

**Not verified**: `mvn clean package`, and the grid in a browser with a real version present.

## Section 2 is complete

`${parentId}`, the structural-change trigger with the version/overwrite dialog, and the Operations
signposting. What remains of the original spec document is only the optional batch 3 of section 1 -
a helper to compare two collected `sqlreport` lists - which was marked "if wanted" and has not been
asked for.
