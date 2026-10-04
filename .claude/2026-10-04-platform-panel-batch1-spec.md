# 2026-10-04 — Platform diagnostics panel, batch 1: the spec

Base `aaaf68f`. Spec: `.claude/LINUX_AND_GUI_CONFIG.md` §7. **Spec only - no code.**

## What

- §7 of the programme spec: the read-only `/platform` page and `GET /api/platform`. Whitelist with
  a reason per field, path rows, interpreters resolved without execution, the case probe, the
  disclosure argument, seven intersections decided by name, the verification plan split Linux /
  Windows, and five Gate 1 questions.
- `CLAUDE.md` «Cos'è OpenProteo»: "senza embedded server" corrected (struck through); the Spring
  Boot line of the stack list said the same thing and is aligned.
- Spec §4.7: `X-User` stays for batch 3, confirmed by the author.

## What was read to write it

`ApiController` (`/api/env`, the file-list endpoints that already return absolute directories, the
two `lineSeparator` sites), `AppProperties`, every call site of the path and interpreter getters,
`StepExecutor` (command construction, per-step working directory), `GlobalVarsStore`,
`WorkflowPorter`, objpack's existing case probe, `unarchive.HostRules`, `theme.js`, the dashboard
nav, and jdk8u's `WindowsFileSystemProvider.checkAccess`.

## Files

- `CLAUDE.md` — two corrections at the top, one entry at the end.
- `.claude/LINUX_AND_GUI_CONFIG.md` — §7 added; §4.7 and §6 updated.
- `.claude/2026-10-04-platform-panel-batch1-spec.md` — this note.
- `COMMIT_MSG.txt`.

No Java, template, JS, `pom.xml`, `README.md`. `USAGE.md`: not in a spec-only commit; the section
goes with the code, when there is a page to describe. No new runtime parameter.

## Verification

Verified on Linux (sandbox; nothing compiled, no JDK involved): `git ls-remote origin main` =
`aaaf68f` before generating, fresh clone; every file:line the spec cites read at that commit; every
edit anchor matched exactly once; the patch touches only `CLAUDE.md` and two `.claude` files;
`git apply --check` on a second clean clone; no CR in `CLAUDE.md`.

Verified on Windows: nothing.

Not verified on either: the Windows executable search order in §7.5 (written from the documented
behaviour of `CreateProcess`); `Files.isWritable` on Windows (read from source). Both are stated
as such in the spec and are on the author's Windows list for the code batch.

## Follow-up

Answers to §7.12, then the code: `PlatformProbe`, `PlatformController`, `platform.html`, the
dashboard link, `USAGE.md`.
