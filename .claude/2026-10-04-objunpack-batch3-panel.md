# 2026-10-04 — objunpack, batch 3: the designer panel

Base `1203b25`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §15. No Java in this batch.

## What

`objunpack` can be chosen and configured in the designer. Three sections — Package, Output,
Checks and limits — eleven fields, one per parameter the executor reads. Choosing the executor
writes the nine defaults into the step. The Variables page offers the two new option lists.

## Files

- `templates/designer.html` — the seed on choosing the executor, the `<option>`, the panel,
  `clientValidate`.
- `templates/variables.html` — `PARAM_OPTIONS`: `md5Check`, `onInconsistency`.
- `static/USAGE.md` — the sentence saying the step had no panel now describes the panel.
- `.claude/OBJECT_UNPACK_EXECUTOR.md` — §15, ∩ U11. This note. `COMMIT_MSG.txt`.

No runtime parameter is introduced: the eleven exist since batch 2 and are now all settable from
the GUI, each applying at the next run of the step — the panel says so (checklist 10).

## Wrong on the way

The first version of the panel test read the conditional notes from `document.body`. The body's
text contains the page's own script, so the notes' wording was always "present". Three negative
assertions failed and exposed it. A test that finds something must be shown able to find nothing.

## Verification

**Verified on Linux (sandbox; node 22 with jsdom; the Java source read, not run, in this
batch):** the real `designer.html` driven through its own controls, 128 assertions; parameters
written by the panel against parameters read by `runObjUnpack`, eleven and eleven, mechanically,
with a positive control; seeded defaults against the initialisers of `ObjectUnpack`; 28 mutations
on copies, 26 caught, 2 equivalent; `node --check` of the inline scripts of both templates with a
broken-script control; scans of the 71 added template lines for literal `\n` `\r` `\t` and for
`[[` `[(`, zero, with a control line; `USAGE.md` through the real `render()`; `git apply --check`
on a second clean clone and the panel suite re-run from it.

**Verified on Windows:** nothing.

**Not verified on either:** a real browser, the UBS browser in particular; Thymeleaf rendering;
the Variables page; `mvn clean package`.

## To look at in a browser

Add a step, choose objunpack: nine parameters appear in the XML preview. Set the checksum to off
and the non-conforming package to warn: an amber note under each. Type an output directory: the
two paths below it follow, and the second-run note appears. Save, reopen: the step is objunpack.
