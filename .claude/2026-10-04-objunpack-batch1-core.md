# 2026-10-04 — objunpack, batch 1: the core, unwired

Base `5f6c8c3`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md`, §13 for what was built and measured.

## What

The inverse of `objpack` as plain classes: a Transarch object package back to its objects under
their original names and its metadata CSV byte for byte. **Not an executor yet** — no name in the
parser, no dispatch, no panel. No runtime parameter, no behaviour change for any existing step.

## Files

- `objunpack/ObjectUnpack.java`, `objunpack/ObjUnpackException.java` — new.
- `objpack/SubmissionName.java` — `parse` and `Parsed` added. Nothing existing changed.
- `objpack/AuditJson.java` — `read`, `Document`, `FileItem` and a strict JSON reader added.
  `write` and `validate`, which `objpack` uses, are not touched.
- `unarchive/EntryName.java` — `printable` made `public`. One word.
- `.claude/OBJECT_UNPACK_EXECUTOR.md` — Gate 0 answers, six corrections struck through where they
  were wrong, §13. This note. `COMMIT_MSG.txt`.

`CLAUDE.md`, `USAGE.md`, `README.md` not touched: no rule changes, and a user cannot reach the
feature yet.

## Gate 0

G2 answered: names should be unique but that cannot be guaranteed — so the refusal stands and
reports every clash. G1 and G3–G8 were not answered one by one; the code follows the
recommendations. **To be confirmed before batch 2.**

## Wrong predictions, and what the code corrected in the spec

- "No visibility changes": one was needed (`EntryName.printable`).
- "Each member staged under its member name": staged under a number.
- "`fail` refuses at the first inconsistency": it logs all of them, then refuses.
- A size check against the tar header was written and was dead code; a mutation showed it.
- The first "member stored twice" fixture did not contain one: GNU tar had written a hard link.
- Not predicted at all: under an ASCII locale an accented name cannot be created. Now a refusal
  that names the encoding.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.10, both RUN; compiled with the Java 8
`javac`; PowerShell 7.4.6, GNU tar 1.35, bsdtar 3.7.2; root and uid 65534; `LANG=C.UTF-8` and
empty):** the suite in all eight combinations — 638 assertions under UTF-8 (645 as non-root), 179
under ASCII (186 as non-root); 44 packages, 21 straight from `ObjectPack` and from the legacy
script, 23 rebuilt from real members with GNU tar; the round trip package → objunpack →
ObjectPack → objunpack on ten packages of both producers; 56 mutations on copies with every
anchor checked, 55 caught, 1 equivalent, 3 first-run survivors opened and resolved; `git apply
--check` on a second clean clone.

**Verified on Windows:** nothing. The Windows name rules were exercised on Linux by naming the
host (`hostOs`), which proves the logic, not the platform: no file was created on NTFS.

**Not verified on either:** `mvn clean package`; Windows PowerShell 5.1 and its `ConvertTo-Json`
(one hand-written audit file stands in for it); a legacy package made by the real script on
Windows; a package above a few megabytes — timing and memory at 100 000 objects or 20 GB are not
measured; the case-only rename path on NTFS.

## Follow-up

Batch 2: registration and `runObjUnpack`. Still open from batch 0: `ObjectPack.glob` throws on any
pattern with a literal character — not touched here.
