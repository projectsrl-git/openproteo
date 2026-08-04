# Variables page: creating a partial step in the feeds that lack it (section 3, batch 2)

Completes section 3 of `.claude/SQLREPORT_VERSIONING_VARIABLES.md`. Batch 1 made the difference
visible; this makes it actionable. This is the most invasive change of the series - it writes a new
node into N workflow XML files - so the guards below are the substance of it, not paperwork.

## Shape of the request

The client sends `{feedId, addSteps:[{stepId, fromFeedId, afterStepId}], steps:[{stepId, fields, params}]}`
per target feed. It **never sends a step definition**. The server copies the node out of a fresh
`toDto(sourceDef)`, so what gets inserted is always a real, already-parsed step from a workflow that
exists, and the endpoint's attack surface does not grow by a whole node structure.

`applyStepAdditions` runs BEFORE `applyEditsToDto`, which is what makes the whole thing cheap: the
field edits then land on the freshly inserted node through the existing code path, and the existing
all-or-nothing staging (regenerate -> `xmlParser.parse` -> stage -> write only if every feed passed)
covers the structural change for free.

## The two things that must not be guessed

**Insertion position.** A dropdown of the steps common to the whole selection, plus "at the very
beginning". The default is offered only when every feed that already has the step puts it after the
SAME common step; otherwise the select sits on "(choose where to insert it)" and the add is refused.
Appending at the end, or copying the position from the first feed, is how a step ends up running
after the send.

**A field the source feeds disagree on.** It is blanked and marked required, not copied from
whichever feed came first. A `query` taken from feed A and written into three others is wrong in
three places and looks right in the XML.

## Guards

* **A feed with a live run is refused.** This is a structural edit and the engine walks `def.nodes`
  by index, so inserting a node under a running workflow shifts what it executes next. The check uses
  `engine.activeRunsByFeed()` and not `activeRunId()`, because a run paused on a manual gate or ON
  HOLD has released the engine slot and would otherwise look inactive - the same trap that was fixed
  in `deleteRun` earlier.
* Duplicate id in the target: refused. This creates, it never overwrites.
* Unknown source feed, or a source feed that does not have the step: refused.
* Anchor absent from the TARGET: refused. The UI only offers anchors common to the selection, but the
  server does not trust the UI.
* An executor conflict keeps the step read-only with no button at all.
* PRODUCTION feeds in the target set require the Clear-History-style required checkbox.

## Two subtle ones worth naming

**`validateChecks` is deep-copied.** `toDto` assigns `nd.validateChecks = st.validateChecks` - the
same List instance the registry's StepDef holds. Inserting that node and then editing it would have
mutated the SOURCE workflow in memory. The test asserts this directly by editing the inserted copy
and checking the original list.

**The add form uses class `avval`, not `vval`.** `saveVariables()` collects
`.vval[data-dirty="1"]`, so an add-form input could otherwise have ridden along with an ordinary
Save and silently written a value into every selected feed. Isolation by class is provable in one
assertion, which is why it was preferred to relying on the scope dispatch.

## Verification

* The insertion core was transcribed against stub DTOs, compiled with `-source 8` and run: order
  after an anchor, after the last node, at the beginning (empty and null anchor), two chained adds,
  and every guard above rejecting while leaving the node list untouched - plus the `validateChecks`
  aliasing test.
* The real `renderCommon` was run against a fake DOM: the anchor defaults to the shared position when
  the sources agree and to "(choose)" when they do not; a field the sources agree on is copied and
  not required, one they disagree on is blanked and required; a conflicting step gets no anchor
  select and no button and its section is `vlocked` with disabled inputs; and every add input carries
  `data-add`, has no `data-scope`, and does not carry the `vval` class.
* `node --check` on the page's inline JS, zero literal newline/CR escapes, no Thymeleaf inlining
  markers, every CSS variable used checked against `app.css`, brace/paren balance 0/0 on
  `ApiController` and the import checker green on every added line.

**Not verified**: `mvn clean package`, and the whole thing end to end - an actual insertion into real
workflow files, the resulting XML, and the page in a browser. Try it first on two non-production
feeds and read the generated XML before using it on anything that ships.
