# unarchive — Batch R: nested archives (opt-in)

Gate 0 R1–R6, recommended answers. Spec §22.4–22.5.

## What it does

`nested=extract`: an archive found inside an archive — by NAME, with the step's pattern, never by
content (a .docx is a zip) — is extracted in place (`inner.zip` -> `inner/`), then removed; down to
`nestedDepth` (3), deeper ones kept with a warning. Everything inside the outer archive's staging
folder: any failure fails the outer archive, nothing partial committed. One budget for the tree.
On Linux, a nested archive's links stay inside its own folder. Manifest entries `inner.zip!x.csv`.
`${nestedExtracted}`. Designer, PARAM_OPTIONS, USAGE.md.

## Found while building

* **I55**: with cumulative limits, a gzip stream's ratio must count only its own bytes — a 14 KB
  nested gzip after 12.6 MB of legitimate data would have read as ~850:1 and been refused.
* **I56**: a nested archive without an archive extension would extract into a folder with its own
  name; refused with a message saying so.

## Verification

Nested suite 30; all earlier suites unchanged and green; wiring 101, panel 130, guide 52, mech 20.
Mutations 22, all caught. Two gaps closed before running them (nested link chain; I55 fixture).

## A broken test of mine

Panel checks on `document.body.textContent` read the designer's inline script source too, which
contains every hint string: they could not fail. Found because a mutation that removed a hint survived.
Fixed to the body without scripts; an L1 check had the same flaw and now passes for the right reason.
