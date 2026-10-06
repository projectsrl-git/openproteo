# 2026-10-06 — objunpack: a 100 000-object package no longer exhausts the heap

Base `9d98f95`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §19.

## What happened

On AIX, a real package of 100 000 objects (291 MB; metadata 52.4 MB) was read in 57 s and then the
step stayed at "reading …metadata.csv" for hours.

## Cause

Reproduced on Java 8 with a package of the same shape: with a 512 MB heap the step ran out of
memory at exactly that line. It held the metadata as one string plus every cell. And an
`OutOfMemoryError` is caught by nothing in the engine's step path: the run pool logs
`run task … crashed` and the step stays RUNNING.

## What

- `rename/PsCsvReader.java` — one parser over a `Reader`, with a record-by-record entry point.
  The string entry point runs through the same code; `filerename` calls it as before.
- `objunpack/ObjectUnpack.java` — the metadata is read record by record; per object only its name,
  mime type and row number are kept. Heartbeat and Stop while reading it and while matching. A
  metadata file with more rows than `maxObjects` is refused. The 512 MB cap on the file is gone.
- `engine/InternalSteps.java` — `runObjUnpack` ends the step (exit 1, a message naming the heap)
  on `OutOfMemoryError`.
- `static/USAGE.md` — «While it runs»: the new lines, and what the step and the next ones need.
- `.claude/OBJECT_UNPACK_EXECUTOR.md` §19, `.claude/FILE_RENAME_EXECUTOR.md` §4, this note,
  `COMMIT_MSG.txt`.

No parameter added, none removed. For a package that worked before, the outcome is the same —
shown on 217 runs, old classes against new.

## Measured (Temurin 1.8.0_432, 100 000 objects, metadata 69.6 MB)

| | 256 MB | 512 MB | 768 MB / 1 GB |
|---|---|---|---|
| objunpack before | out of memory | out of memory | completes (768 MB) |
| objunpack now | completes, 12.7 s | completes | completes |
| objpack, next in the chain | — | **out of memory** | completes (1 GB) |

## The suites were lost

The suites of the earlier batches were kept in the sandbox, not in the repository (Gate 0 G7).
The sandbox was recycled; they are gone. This delivery was verified with checks rebuilt for it.
**Proposed:** commit the suite sources and the fixture generator under `tools/` (not the legacy
script, which stays out for the reason G7 gave). Otherwise every session starts from none.

## Proposed, NOT done

1. `InternalSteps.run`: catch `Throwable`, so an `Error` in any executor fails the step.
2. `objpack`: do not keep every metadata row in memory.
3. Platform page: show the JVM's maximum heap.
4. A streaming reader for the audit file.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.12 both run, Java 8 `javac`; root and
uid 65534; `LANG=C.UTF-8`):** the old `PsCsvReader` against the new on 300 000 fuzzed inputs under
two delimiters and on a 69.6 MB metadata file, identical; stream against string on 400 000 parses,
identical; old `ObjectUnpack` against new on 31 packages × 7 configurations, 217 outcomes
identical; 47 assertions on the new behaviour, with the round trip on eight packages of both
producers; 27 mutations on copies, all caught; the real body of `runObjUnpack`, lifted, on six
runs including one under a 100 MB heap; differential compile identical, with its control;
`USAGE.md` through `render()`; `git apply --check` on a second clean clone and the checks re-run
from it.

**Verified on Windows:** nothing.

**Verified on AIX:** nothing by me. The author's run there is what found the defect.

**Not verified on any:** IBM's JVM; `LANG` empty; fixtures made with bsdtar; the panel suite and
the full executor suite of the earlier batches (lost; their code is not touched here);
`mvn clean package`.
