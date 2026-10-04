# 2026-10-04 — objunpack, batch 2: registered, and nothing is ever modified

Base `538d1bb`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §14.

## What

`exec="objunpack"` is now a step: accepted by the parser, treated as internal by the engine,
dispatched to `InternalSteps.runObjUnpack`. Written in the XML for now — the designer panel is
batch 3, the workflow template and the measured chain are batch 4.

One decision of the author changed the core: `onExisting=replace` is gone. The step never
replaces, renames, moves or deletes anything it did not create — not the archive, not its `.md5`,
not an existing output.

## Files

- `parser/WorkflowXmlParser.java` — the accepted `exec` values, the error text, the `internal` set.
- `engine/WorkflowEngine.java` — `internalKind()`.
- `engine/InternalSteps.java` — the dispatch line and `runObjUnpack`.
- `objunpack/ObjectUnpack.java` — `onExisting` removed; the class comment says what it never touches.
- `static/USAGE.md` — a bullet under Executors and «The objunpack step».
- `.claude/OBJECT_UNPACK_EXECUTOR.md` — answers, `onExisting` struck through, ∩ U10, §14. This
  note. `COMMIT_MSG.txt`.

`CLAUDE.md` and `README.md` not touched. No file-only key: every parameter is a step `<param>`,
applied at the next run (checklist 10).

## Runtime parameters introduced

Eleven step parameters: `archive`, `outputDir`, `md5Check`, `onInconsistency`,
`metadataDelimiter`, `maxObjects`, `maxObjectMb`, `maxArchiveMb`, `maxRatio`, `maxPathLength`,
`preserveMtime`. **Until batch 3 they can be set only by editing the workflow XML** (the XML
editor of the designer included), not from a panel.

## Known until batch 3

A workflow containing the step opens in the designer with the Executor field showing the first
entry of the list. Left alone it is saved correctly; changed, the step becomes another executor.
`USAGE.md` says so.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.10 both RUN, Java 8 `javac`; root and
uid 65534; `LANG=C.UTF-8` and empty):** the real body of `runObjUnpack` lifted by position and run
on the fixtures in the eight combinations, 85 assertions (87 non-root; 71 / 73 under ASCII), the
exit code never −1 over 31 runs; 22 mutations of it on copies, all caught; the core suite in the
eight combinations, 639 (644; 179 / 184); all 56 core mutations re-run, 55 caught, 1 equivalent;
differential compile of the three classes that do not build here, 459 error lines before and the
same 459 after, with a positive control; `USAGE.md` against the method, 11 parameters and 18
variables, and through the real `render()` of `docs.html`; `git apply --check` on a second clean
clone, and both suites re-run from that patched clone.

**Verified on Windows:** nothing.

**Not verified on either:** that a workflow with `exec="objunpack"` parses, is scheduled as an
internal step and reaches the method — read on the code and differentially compiled, never run;
`mvn clean package`; the `/docs` page in a browser; Windows name rules on NTFS; size and timing on
a large package.

## To run on a real instance

Build; a workflow with one `objunpack` step on a real package; check the step is green, the
eighteen variables in OUTPUT DATA, `objects/` and `package/` in the step directory, and that the
archive folder is unchanged. Then run it a second time with `outputDir` fixed: it must be refused.
