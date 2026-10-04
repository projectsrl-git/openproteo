# 2026-10-04 — bash runner and process-tree kill, batch 2: the spec

Base `12cfc3a`. Spec: `.claude/LINUX_AND_GUI_CONFIG.md` §8. **Spec only - no code.**

## What

- §8 of the programme spec: the `bash` runner (kind, detection, the all-ASCII bootstrap, parameters
  as `OP_<name>` environment variables, name mapping, limits), CRLF scripts, the process-tree kill
  on timeout and abort with its degraded modes and risks, the fan-out Stop defect, PowerShell's
  default and error stream on non-Windows, the Platform page additions, six intersections decided
  by name, and eight Gate 2 questions.
- §7.13: what the author's Windows screenshot of `/platform` verifies, and what it does not.

## How it was prepared

Measurements first (§8.2, M1-M15). A Java 8 runtime (Temurin 1.8.0_432) and PowerShell 7.4.6 for
Linux were fetched from GitHub releases into the sandbox. `StepExecutor` and `RunControl` were
compiled from the repository with the Java 8 `javac` and driven by small harnesses: a `.ps1` through
the real bootstrap; three kill strategies against a script with six kinds of child, counting
survivors with `pgrep`; non-ASCII values as argument, environment variable and ASCII bootstrap
under three locales; three concurrent `execute` calls on one `RunControl` with the engine's own
Stop lines; a noexec tmpfs; a CRLF script; a non-bash shell.

Two things the harnesses did to ME, kept because they are the same trap the spec is about: `javac`
refused a source file with a non-ASCII comment (the sandbox has no locale), and a non-ASCII value
passed on the harness's own command line arrived as U+FFFD before `StepExecutor` ever saw it.

## Files

- `.claude/LINUX_AND_GUI_CONFIG.md` — §7.13 addition, §8.
- `CLAUDE.md` — entry at the end.
- `.claude/2026-10-04-bash-runner-batch2-spec.md` — this note.
- `COMMIT_MSG.txt`.

No Java, template, JS, `pom.xml`, `README.md`. `USAGE.md` goes with the code. No new runtime
parameter in THIS commit; the code batch adds `orchestrator.bash-exe`, file-only until batch 4, and
will declare itself incomplete on that point (checklist 10, spec §8.11.6).

## Verification

Verified on Linux (sandbox; Java 1.8.0_432 runtime and JDK 21.0.12; bash 5.2.21; pwsh 7.4.6):
every row of §8.2, by running it; every file:line the spec cites, read at `12cfc3a`;
`git ls-remote origin main` = `12cfc3a` before generating; the patch touches only `CLAUDE.md` and
two `.claude` files; `git apply --check` on a second clean clone.

Verified on Windows: nothing by me. By the author: what §7.13 records from the screenshot.

Not verified on either: anything about Windows process trees (§8.7 says so and proposes a kit);
whether a failing `.ps1` logs CLIXML under Windows PowerShell 5.1 (Gate question 6); the measured
kill timings under load; a `noexec` mount other than tmpfs.

## Follow-up

Answers to §8.14, then the code.
