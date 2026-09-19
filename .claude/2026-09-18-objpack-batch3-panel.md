# objpack — Batch 3: the designer panel

The configuration panel for `objpack`, so the executor stops needing hand-written `+ param` entries.
Four sections — Submission, Source metadata and objects, Column mapping, Package and checks — plus
one helper, `objpackBase`, that previews the submission base name live.

## Why a preview

Every artifact of a submission shares one base name, and §2 of the specification makes it the thing
that must be unique and must not change on a retry. A wrong feed id or a mistyped date is therefore
not a small error: it is a submission that either collides with an earlier one or is rejected on
arrival. The preview puts `tf0002448.20260918.S001.V001` under the four fields that produce it, and
says what is wrong with them when something is, so the mistake is visible while it is still cheap.

It mirrors `SubmissionName` deliberately: three-digit padding, lowercased feed id. A `${variable}`
in the date is passed through and **not** flagged — the value is resolved at run time and the panel
has no business calling it malformed.

## What the panel deliberately does not offer

* **`compression`.** While Gate 0 9.4 is open its only valid value is `none`, and the executor
  refuses anything else. A control whose only correct setting is the default is a way to get it
  wrong — the same reason `oidPadding` was dropped as a parameter in batch 2.
* **`orderBy`** appears only when "find each object by" is set to row order, because it means
  nothing otherwise.

The `onMissingObject`, `failOnOversize` and `failOnStaleBusinessDate` controls exist precisely
because those three are the Gate 0 questions still open: the conservative default is preselected and
the alternative is one click away, rather than being a parameter nobody knows about.

## Verification

* **The panel's parameter names were compared against what the executor reads**, by extracting every
  `setNodeParam(…)` key from the panel branch and every `pv(params, vars, …)` key from
  `runObjPack`. 32 written, 33 read, **nothing written that is never read** and nothing validated
  that cannot be set. The single read-but-absent key is `compression`, which is the deliberate
  omission above. This check matters because a panel writing `map.objectID` while the executor reads
  `map.objectId` is silently broken and no compiler would ever say so.
* **`node --check`** on the extracted inline JavaScript, with the usual positive control confirming
  the check can fail.
* **11 assertions on `objpackBase`** run under node: valid input, lowercasing, three-digit padding,
  the empty step showing placeholders rather than a wrong name, a short feed id and a dashed date
  each called out, a `${variable}` left alone, and the clamping of a zero sequence.
* **6 mutations of the preview logic, all caught** — after the harness was fixed. One "survivor" in
  the first run was nothing of the sort: the `sed` anchor did not match, so the unmutated test ran
  and passed. The harness now compares the file before and after and reports **ANCHOR MISSED**
  rather than counting it as green, which is the same failure as a check that reads the wrong exit
  status: a test that cannot fail is worse than no test.
* Markup emitted by the panel is tag-balanced: 55 `<div>`, 5 `<select>`, 33 `<label>`, each matched.
* The brace-balance tool reports the same counts on designer.html **before and after** this change,
  so its non-zero figure is the surrounding HTML being counted as code, not something introduced
  here. For this file `node --check` is the authority.

## Not verified

The panel has not been opened in a browser: no rendering, no click-through, no check that the
collapsible sections behave. `mvn clean package` was not run. Nothing here changes Java, so the
batch-2 suites were not re-run.
