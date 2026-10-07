# 2026-10-07 — every package of a folder in one workflow: check, quarantine, correct

Base `2c0c19a`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §22 (and §14 ∩ U10, corrected).
No Java in this delivery: scripts, one workflow template, documentation.

**This delivery replaces two earlier zips generated on the same base and never committed**,
`openproteo-objunpack-every-package-2c0c19a.zip` and
`openproteo-objunpack-check-and-correct-2c0c19a.zip`. It contains their scripts and their
correction of the two existing templates; their own templates are replaced by the one below.

## Asked

One template per feed: (1) the check of every package of a folder, sending the valid ones;
(2) if nothing is in quarantine, end with success; (3) otherwise, in the same workflow, unpack
and repack what the check put on the list.

## What

- `workflows/_TEMPLATE-objunpack-check-and-correct.xml` — new. Phase 1: list, and for each
  package pre-check, validate, send as it is or quarantine. Phase 2, if anything is to be
  corrected: for each such package unpack, csvsql, dequote, validate, objpack, send, record.
- `scripts/list-packages.sh`, `scripts/quarantine-list.sh` — new.
- `scripts/objunpack-precheck.sh` — `onRefusal=report` (default `fail`, as before);
  `packageReadable`, `problem`; every variable published every time.
- `workflows/_TEMPLATE-objunpack-precheck-resend.xml`, `workflows/_TEMPLATE-objunpack-resend.xml`
  — **corrected**: `${stepDir}/${runId}` for the steps that must not find an earlier result.
- `USAGE.md`, the objunpack spec.

## Three things decided by me, to be confirmed or changed

1. **A run that leaves `manual` packages in the list ends REJECTED**, not SUCCESS. The request
   named success for "nothing in quarantine" and correction for the rest; packages that cannot
   be corrected are a third case, and a green run that left documents unsent seemed the worse
   mistake. One gate, two literal targets.
2. **`resumeCorrection`**, a variable. Running the one workflow again after a failure in phase 2
   would send the valid packages twice and forget what was already corrected; with this set to
   `true` the run goes straight to phase 2 on the list as it is.
3. **The list is in the feed's folder** (`${feedDir}/objunpack-quarantine.txt`), one per feed,
   visible in the Workflow files panel, kept when the feed's history is cleared.

## The defect found, and it is mine

USAGE and the spec (§14) said a step's directory is new at every run. It is not: it belongs to
the feed. So the two templates delivered before today work **once** per feed: the second run
stops at its first step, or at the Unpack step; and with only the Unpack step's folder cleared,
the resend template's `*.tar` send would deliver the previous run's package along with the new
one. Reproduced by walking the committed templates twice in one feed; kept as controls.

**A workflow already created from either template has the defect and is not changed by this
patch.**

## Default behaviour

No executor changed. `objunpack-precheck.sh` without `onRefusal` fails and exits as before; it
publishes two variables more.

## Still open

The Delimiter field as a variable (a wrong literal sends every package to phase 2); a listing
check in the pre-check script; `orchestrator.max-transitions`, settable from a file only, limits
a run without holds to about 30 packages that all need correcting.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.12; bash 5.2.21; GNU tar 1.35;
LANG=C.UTF-8; root and uid 65534):** the three scripts on real folders and packages - 39, 47
and 379 assertions; the new template parsed by the real parser and walked with the real
resolver, gate evaluation, bash runner, send plan and the lifted bodies of setvar, validate,
objunpack, dequote and objpack on seven real files, 23 assertions; the two existing templates
walked twice in one feed, 8 assertions, three of them controls on the templates as they were;
102 mutations, 99 caught, 3 equivalent here, after five holes in the suite were closed
(spec §22); USAGE through the Docs page's own renderer.

**Verified on Windows:** nothing. The template needs bash.

**Verified on AIX:** nothing.

**Not verified on any:** the engine running these loops and gates (the walk's loop is mine,
written from the engine's); `csvsql` (emulated in the walk); on hold inside a loop;
`deleteOnSuccess`; the order of the list under a locale other than C; `mvn clean package`
(nothing it compiles has changed).

## Seen on the way, not touched

The Docs page renders neither `*single-star*` emphasis nor Markdown tables: both show as typed.
The new USAGE sections use neither.
