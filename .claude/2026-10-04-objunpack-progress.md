# 2026-10-04 — objunpack: a long run no longer looks hung

Base `8aded1a`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §17.

## What happened

First run on a real instance (Windows, a 293 MB package): after "checksum verified" the live
console showed nothing more for minutes. The step logged nothing between the checksum and the end
of the extraction.

## What

`objunpack/ObjectUnpack.java`: a line at the start of every phase, and a heartbeat every 5 s while
the archive is read (members, megabytes and percentage of the archive file) and while the objects
are named (files done). Nothing else changes: same output, same variables, same exit codes, no
parameter. `USAGE.md`: «While it runs», which also says why a package of many small objects is
slow by their number.

## Why it was missed

`objpack` had the same defect and the same fix a week earlier; the lesson was not carried into
the new executor, and no test asserted that a long phase says anything. It does now.

## Files

- `objunpack/ObjectUnpack.java`
- `static/USAGE.md`
- `.claude/OBJECT_UNPACK_EXECUTOR.md` §17, this note, `COMMIT_MSG.txt`

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.10 both run; root and uid 65534;
`LANG=C.UTF-8` and empty):** the core suite in the eight combinations, 656 assertions under UTF-8,
17 of them new; 9 mutations of the new code on copies, all caught; the executor suite (the lifted
`runObjUnpack`) and the panel suite re-run; a 301 MB, 20 000-object package built by the real
`ObjectPack` unpacked in 3.5–4.7 s, phase by phase; `git apply --check` on a second clean clone.

**Verified on Windows:** nothing by me. The author's run on Windows is what found the defect: it
shows the step starts, resolves the archive, applies Windows name rules and verifies the checksum
there. It does not yet show that it completes.

**Not verified on either:** how long the author's package takes on his host; `mvn clean package`.
