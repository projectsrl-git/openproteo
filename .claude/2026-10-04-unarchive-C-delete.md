# unarchive — Batch C: opt-in deletion after extraction (and batch R specified)

Gate 0 of 2026-10-04: flat stays excluded (F1, read as "leave it as it is"); FIFOs/devices unchanged
(D1); zip/tar/gz suffice; non-UTF-8 tar names stay refused; C1 deletion opt-in with the proposed
rules; R1–R6 nested archives with the recommended answers. Spec §22.

## Batch C, as built

* `afterExtract=delete`: the archive is deleted only after its own commit; never when its extraction
  failed or was stopped, never when it was skipped (`onExisting=skip`). A failed deletion fails the
  step (`COMMIT_FAILED`, "extracted and committed, but deleting the archive failed"); the extraction
  stays. Logged per archive.
* Designer option with a "no undo" warning, validation, `PARAM_OPTIONS`; USAGE.md updated (the sentence
  "no deletion of the archive" removed — it became false).

## Verification

Batch 2 suite 199 (+5), non-root program 7 (+1: a deletion that fails — only reproducible as a
non-root user), Windows 232, Linux 140, links 77, wiring 92, panel 117, guide 47. Three existing
expectations inverted by the decision (not deleted). Mutations 10, all caught.

A mistake of mine: a test lambda captured a reassigned variable, the suite did not compile, and the
non-root program ran its PREVIOUS build (6 checks instead of 7) — caught because the count did not
grow; everything recompiled and re-run.

## Batch R (next)

Specified in §22.4: `nested=extract`, selection by name only (Office files are zips), `nestedDepth`
3, extraction in place into the parent's staging, nested archive removed after its own extraction,
cumulative limits, all-or-nothing per outer archive, links confined to the nested folder, manifest
entries as `inner.zip!entry`.
