# Variables page: steps present in only SOME selected feeds (spec section 3, batch 1)

Implements the read-only half of section 3 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`, and
answers its two open questions. `[Add to all selected]` is batch 2 and is NOT in here.

## Why split it

Section 3 is two different changes wearing one name. Making the difference **visible** is pure UI,
touches one template, and cannot damage anything. **Creating a step in the feeds that lack it** is a
structural write to N workflow XML files, needs an insertion position, and interacts with the
versioning trigger of section 2 (adding a step to a feed that already ran is exactly what section 2
wants to intercept). Shipping them together would put a zero-risk improvement behind a change that
deserves its own review.

## Batch 1 (this one)

A new **Steps missing from some feeds (N)** section under **Common steps**, listing every id where
`0 < count < selection`. Per id:

* the id and, when the feeds agree on it, the step name;
* a badge **in N of M feeds**;
* the executor, or a **conflict** badge when it differs across feeds;
* **Missing from:** the feed ids that lack it, truncated at 6 with the full list in the `title`;
* a read-only preview of the fields and params common to the feeds that DO have it.

## The two open questions, answered

**Different executor under the same id -> conflict, shown, never mass-anything.** The spec proposed
this and it costs nothing to honour now: `conflict: sql / csvsql` sits in the header. The reason is
not tidiness - `query` on a `sql` step and `query` on a `csvsql` step are different things, so a mass
edit would write a value that is wrong in half the feeds. Batch 2 must refuse to add these.

**Adding a step to a PRODUCTION feed requires explicit confirmation, like Clear History.** Agreed,
and deferred to batch 2 where the action exists. It will follow the established pattern: `opConfirm`
with `danger:true` and a REQUIRED checkbox naming the number of PROD feeds included.

## Why read-only is not a limitation but the point

The section could have rendered editable inputs bound to the feeds that have the step. That was
rejected: an edit made in a section titled "missing from some feeds" would apply to a subset, and the
operator would have no way to tell from the result. The preview inputs are therefore `disabled` and
carry **no `data-scope`**. That is the actual safety mechanism: `saveVariables()` collects
`.vval[data-dirty="1"]` and dispatches on `data-scope`, so an input with neither can never enter the
payload. The test asserts the invariant directly rather than trusting the two properties separately.

## Batch 2 (not in this patch)

1. `[Add to all selected]` per partial step, disabled on a conflict.
2. Insertion position: a dropdown of the common steps, defaulted to the position the step occupies in
   the feeds that already have it **when that position is consistent**, and with no default when it
   is not - guessing a position in a pipeline is how a step ends up running after the send.
3. Server-side creation: the save endpoint currently applies edits to EXISTING steps
   (`applyEditsToDto`); creating one needs a new operation that builds the StepDef from the template
   feeds, inserts it at the chosen index, and goes through `xmlWriter.toXml` + `xmlParser` validation
   before ANY write, all-or-nothing as today.
4. PROD confirmation as above.
5. Open for batch 2: what to prefill when the source feeds disagree on a field. Proposal: leave it
   blank rather than pick one feed's value, consistent with how the common editor already shows
   "(differs across feeds)".

## Verification

The real `renderCommon` was extracted from the template and run against a fake DOM with five
synthetic feeds (a common step, a step missing from one feed, one missing from three, one present in
a single feed, and an id whose executor differs). Asserted: the common section still counts only the
truly common id, the partial section counts three, each badge reads the right N of M, the executor
conflict is detected and names both executors, the missing-feed lists are right, the hover title
carries the full list, and - the invariant that matters - every disabled input has no `data-scope`
while every enabled one has one, and no disabled input is dirty.

`node --check` on the page's inline JS passes; zero literal newline/CR escape sequences (the hover
title builds its line breaks with `String.fromCharCode(10)`); no Thymeleaf inlining markers. Every
CSS variable used by the page's `<style>` block was checked to exist in `app.css` - `--bg-raise`,
`--ink-faint`, `--fail` and the rest are all defined, none invented.

**Not verified**: `mvn clean package` (no change to Java in this patch, but the build is still the
only final proof) and the page as rendered by a real browser.
