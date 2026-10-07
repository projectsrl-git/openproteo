# 2026-10-07 — objpack no longer keeps the rows; a step that ends with an Error is ended

Base `d577da6`. Specs: `.claude/OBJECT_PACKAGE_EXECUTOR.md` §18,
`.claude/OBJECT_UNPACK_EXECUTOR.md` §21.

## What happened

On AIX, the corrective chain of the pre-check template reached `objpack` with 100 000 rows
(52.5 MB). The console stopped at `read 63271 rows, paired 63270 objects so far`; an hour and a
half later the step was still RUNNING. The author has the GUI only, no server log.

Before that, the pre-check step had not found its script: USAGE told him to upload it "to the
scripts folder", and an upload does not go there.

## Cause

Reproduced on Java 8: `objpack` kept every row as a map of its cells for the whole run - 100 000
rows of 44 columns do not fit in 512 MB, and the JVM spends minutes collecting before it gives
up. And an `Error` in a step is caught by nothing: the run pool logs it, the step stays RUNNING.

## What

- `objpack/ObjectPack.java` — a row's other columns are read again from the CSV where they are
  written (`replay`), instead of being kept. The file is checked to be the one read the first
  time. `glob` fixed: every `include`/`exclude` pattern with a literal character threw.
- `objpack/AuditJson.java` — `write` streams instead of building the text first.
- `engine/InternalSteps.java` — `run` catches `Error`: exit 1 and a message naming the heap.
  `describeError`.
- `engine/WorkflowEngine.java` — the same net in `executeStep`.
- `platform/PlatformProbe.java`, `templates/platform.html` — the heap on the Platform page.
- `USAGE.md` — where an uploaded script is and is not; the Script field resolves no variable;
  objpack's memory and its passes; `include`/`exclude`; the heap row.
- `workflows/_TEMPLATE-objunpack-precheck-resend.xml` — the same correction in its description.
- The two specs.

## Behaviour that changes

- `objpack` on input that worked: nothing. 625 cases, old against new, identical outcome.
- `objpack` when the source CSV changes while it runs: refused (it could not happen before; the
  rows were in memory).
- `include` / `exclude` with a literal character: match, instead of throwing.
- Any built-in step ending with an `Error`: FAILED with a message, instead of RUNNING for ever.
- No parameter added.

## Measured (Temurin 1.8.0_432; 100 000 rows, 72 MB CSV, 135 MB of objects)

| | heap | result |
|---|---|---|
| before | 512 MB | does not finish (collecting; stopped by hand after a minute) |
| before | 1 GB | ok, 37 s |
| after | 128 MB | out of memory, in the audit file's read-back |
| after | 144 MB | ok |
| after | 256 MB | ok, 23 s; outcome identical to *before, 1 GB* |

## Not done, and why

- **The Script field does not resolve `${alias}`.** Left as it is by the author's decision; the
  documentation now says what the code does.
- **The audit file is still read whole**, by `objpack` to validate it and by `objunpack`: it is
  what keeps both from running in 128 MB. Not needed for the case at hand.
- **Next intervention, on the HEAD after this one:** the Delimiter field of csvsql, dequote and
  validate resolved as a variable; a check in the pre-check script that every object the audit
  lists is in the archive.
- Seen and left: `.claude/OBJECT_PACKAGE_EXECUTOR.md` §5 declared a default `exclude` the code
  never had (corrected in the table, struck through). With `objectsDir` = `outputDir` and
  pairing by `order`, the step's own outputs are in the listing; whether to add that default is
  a decision, not a fix.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.12; LANG=C.UTF-8; root unless
said):**

- `ObjectPack`, old (`d577da6`) against new, the whole outcome - variables, warnings, every line
  said, every file by hash, every tar member by name, size and hash: 625 cases, identical, on
  both JDKs. Old against old first, identical. 114 of them as uid 65534, and with `LANG` empty.
- What is new in `ObjectPack`: 53 assertions, both JDKs, and as uid 65534. Not valid with `LANG`
  empty: the fixture has an accented file name, which that locale cannot find at all.
- The real `InternalSteps.run`, lifted by position from before and after, its dispatch targets
  replaced by stubs: 268 assertions, both JDKs, with an `OutOfMemoryError` the JVM really threw.
- `PlatformProbe.system()` executed on both JDKs with three heap sizes; the Platform row in
  jsdom, 11 assertions; `tools/scan_platform_whitelist.js` clean.
- Differential compile of `InternalSteps`, `WorkflowEngine`, `WorkflowXmlParser`,
  `PlatformController`: 501 error lines before and after; an error put in each changed line
  shows. `node --check` on `platform.html`; added template lines scanned for `\n`-style escapes
  and `[[`, with a control; brace balance of the five Java files, with a control.
- USAGE rendered through the page's own `render()`: the new sections have no raw markdown left.
- Mutations on copies, anchors checked: `ObjectPack`/`AuditJson` 27, 27 caught after three holes
  in the suite were closed (spec §18); `InternalSteps.run` 7, 7 caught.

**Verified on Windows:** nothing.

**Verified on AIX:** nothing by me. The author's run there is what found both defects, and is
the first evidence that the pre-check script runs on AIX.

**Not verified on any:** the IBM JVM - in particular how long it collects garbage before it
throws; `WorkflowEngine.executeStep`'s new catch, which cannot be executed here; Jackson
serialising the two new numbers of `/api/platform`; a browser; `mvn clean package`.

## Seen on the way, not touched

The Docs page does not render `*single-star*` emphasis: it shows the asterisks. USAGE has about
fifty of them, some mine from earlier deliveries. The lines of this delivery, and the ones of
the pre-check section, use `**bold**` instead; the rest and the renderer are as they were.
