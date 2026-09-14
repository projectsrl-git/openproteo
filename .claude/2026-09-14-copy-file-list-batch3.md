# Copy file list — batch 3: the designer panels

Base commit: `8da7a93`. Spec: `.claude/COPY_FILE_LIST.md`. This makes the option reachable: batch 2
delivered the engine, and until now no one could configure it through the interface at all.

## What is in this batch

A **Files to copy** dropdown on `filecopy` and `safecopy`, the same switch `ifscopy` has, wired through
`copySetListSource` — which sets the parameter **and redraws**. The two panels show different fields in
the two shapes, so a select that only stores the value would leave the page contradicting itself: that
is the defect already recorded twice, for `contentSource` and for `batchBy`.

**One builder for the file-list fields, not one per executor.** `copyListFields(i, n)` serves both. Two
copies would drift, which is the same argument that made the reader itself shared in batch 1.

In the list shape the **source field is relabelled, not hidden** — "base for names that are not complete
paths" — because that fallback IS the "directory + file name from the CSV" combination and it should be
visible where it operates. The pattern is hidden. `clientValidate` stops requiring `source` there,
requires `dest` plus both CSV fields, and reports **both** missing ones rather than the first.

**The `Mode` field stays visible on `filecopy` under a list**, with the panel saying in red that only
`copy` is allowed and the step refuses the other two. Hiding it would leave a stored `move` unreachable
and uncorrectable; the executor refuses rather than ignores, so the field has to be there to be fixed.
`clientValidate` refuses it at save as well, so it never reaches a run.

`buildXml`, the parser, the writer, the DTO and `PARAM_OPTIONS` needed **no change** — every field is a
`<param>`, and `variables.html` already carries `listSource`, `hasHeader`, `onMissingFile` and
`onNameCollision` with these very defaults. Checked, not assumed: `buildXml` carrying all four params is
asserted in the suite.

## The panel is executed, not inspected

`panel.js` builds the DOM **from the real template with its scripts stripped**, so every element the
page bootstraps against is present by construction, then runs the real script and drives it. **88
assertions.** The same suite scores **36 passed, 48 failed** against the pre-patch template.

**Controls are driven through the handler the page wires to them.** jsdom does not run inline handler
attributes, so dispatching an event would do nothing; the attribute is read and evaluated with `this`
bound to the element, as a browser does. The suite also asserts the handler is *not* a bare
`setNodeParam`, and that switching back to the pattern shape writes **no param at all**, so an untouched
step stays byte-identical.

**Two steps are rendered side by side**, one of each executor: their branches are adjacent in the same
chain and a collision between them would be invisible in a single-step test.

**Structure is asserted by NESTING, not by presence.** An unbalanced tag in a string-built panel does not
throw — the parser silently re-parents whatever follows — so a subsection that has escaped its card is
still findable from the document and any `innerHTML` string test would pass. The suite requires each
file-list subsection to be inside a `.node-card` **and** inside its `.nc-body`. That assertion exists
because the mutation that inserts a stray `</div>` came back **green** against the first version.

## Three things the run found, and none of them was review

**A mutation runner piped into `head` left the tree mutated.** `python3 mutate3.py | head -12` closed
the pipe, the runner died on SIGPIPE **before its restore**, and the working copy silently kept the
last mutation. It cost a confused round: the suite then failed one assertion for a reason that had
nothing to do with the code. Worse, my check that the tree was clean — a `grep -c` for the condition —
**matched a different line** and reported all-clear. The runner now restores through `atexit` and
flushes each line, and the lesson is the general one: a check that finds a match is not a check that
found the right match.

**A mutation anchor occurred twice again.** The Delimiter field line is identical in `ifscopy`'s branch,
so the stray-`</div>` mutation would have been applied there. The runner refuses a non-unique anchor
outright now, rather than silently taking the first — the same failure as batch 2's collision guard,
caught this time by the guard added after it.

**A mutation was genuinely green and it was a bad mutation, not a gap.** Dropping ` selected` from the
`fail` option changes nothing: without it the browser selects the **first** option, and `fail` is first.
Replaced by one that bites — reordering the options so `skip` comes first — which is caught.

**Thirteen mutations, all caught.** Twelve by the panel suite, one (a literal `\n` introduced into the
JS) by the build checks.

## The advisory redraw scan could not see this defect, and now can

`tools/scan_panel_redraw.js` reported a clean run with `copySetListSource`'s redraw **removed**. The
reason is structural rather than a bug in the regex: the scan is keyed on the **parameter name**, and
`listSource` is also written by `ifsSetListSource`, which still redraws correctly. One broken helper
hides behind another executor's correct one.

A second check was added, keyed on the **helper**: a named helper that writes a shape-deciding parameter
and never calls `renderNodes()`. Narrow on purpose — a handful of functions — so it cannot become the
twelve-false-alarms-out-of-thirteen that gets a diagnostic switched off within a week. Still advisory,
still `exit 0`.

**Its first version matched nothing at all**: it required a newline before the closing brace, and every
one of these helpers is a single line. It reported a clean run on a tree where the defect was present —
which is exactly the property this file exists to check for in the panels, occurring in the file itself.
**Proved to bite**: each of the five redraw helpers stripped of its `renderNodes()` in turn, each one
named by the scan, and the real tree clean.

## Verified, and not

* `node --check` equivalent on both inline script blocks; **zero literal `\n` / `\r`** with a positive
  control proving the scan can fire; no uncommented `[[` / `[(`; duplicate top-level `function`/`var`
  scan, brace-depth aware, with a control proving it sees the new helper.
* 88 jsdom assertions against the real template, 48 failing against the pre-patch one; 13 mutations all
  caught; the redraw scan clean and proved able to fire on all five helpers.
* **NOT rendered in the UBS browser.** Flex with wrap throughout and no new CSS variable — every class
  used (`field`, `form-row`, `subsection`, `sub-label`, `dim small`) already exists — but that is an
  argument, not a rendering.
* **NOT run: `mvn clean package`**, and the panel's `fetch` calls. Rendering is exercised, the network
  is not.
* **NOT changed and deliberately so**: `PARAM_OPTIONS` in `variables.html`. The four enums are already
  there with these defaults. The label wording differs slightly between executors — `ifscopy` says "fail
  the step", the local panels "fail before copying anything" — but the recorded trap is a shared name
  with a different DEFAULT, and the defaults agree.

## Next

Batch 4, the last one: `USAGE.md`. The existing «ifscopy: copying the files listed in a CSV» section
becomes one section covering all three executors, rendered through `docs.html`'s own `render()`.
