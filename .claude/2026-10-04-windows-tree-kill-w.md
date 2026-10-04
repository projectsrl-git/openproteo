# 2026-10-04 — Windows process-tree kill (W): written blind, born off

Base `b8e1e46`. Spec: `.claude/LINUX_AND_GUI_CONFIG.md` §9.6; review list §9.7.

## What

On Windows a timeout or Stop kills the interpreter and nothing it started (batch 2 solved this for
non-Windows hosts only). This adds a Windows tree kill **that is off unless
`orchestrator.windows-tree-kill=true`**, because nobody has run it on Windows.

- `engine/WindowsTreeKill` — finds the step among the JVM's children (Java 8 has no pid) and runs
  `taskkill /T`. Exactly one candidate or nothing; never the JVM, never a system pid.
- `engine/ProcessTree` — a `TASKKILL` mode, live only on Windows with the switch on; the launch
  records its time window and the step's distinctive argument.
- `AppProperties.windowsTreeKill`, commented line in `application.properties`.
- `WorkflowEngine` (two lines) and `PlatformController` (one) hand the switch to `ProcessTree`.
- Platform page: `TASKKILL` is shown as `partial`. `USAGE.md`: the option, with what it is.

## New runtime parameter — incomplete by checklist 10

`orchestrator.windows-tree-kill`, default `false`, applies from the next step. File-only; added to
the debt list in `CLAUDE.md`.

## Files

`engine/WindowsTreeKill.java` (new), `engine/ProcessTree.java`, `engine/WorkflowEngine.java`,
`config/AppProperties.java`, `application.properties`, `web/PlatformController.java`,
`templates/platform.html`, `tools/scan_platform_whitelist.js`, `static/USAGE.md`, `CLAUDE.md`
(debt list), `.claude/LINUX_AND_GUI_CONFIG.md` §9.6-9.7, this note, `COMMIT_MSG.txt`.
`README.md` not touched.

## Verification

**Verified on Linux (sandbox; Java 1.8.0_432 and JDK 21.0.12, both run; PowerShell 7.4.6):**

- `WinSuite`, 45 assertions: parsing, choice, every refusal, no `taskkill` when in doubt, both pid
  paths, the wiring under an injected `os.name` with the switch off and on, a Linux host ignoring
  the switch, and the query's PowerShell expression executed by a real PowerShell in a non-UTC
  time zone. 20 mutations, all caught - one was green at first ("switch on by default"): the suite
  set the switch before ever reading it; it now reads the default first.
- The batch 2 `ExecSuite` (126) and the controller suite (22) re-run on the changed classes: green.
- Platform page 90, with `TASKKILL`; guide suites 123 + 21 + 21.
- Differential compile of `WorkflowEngine` and the other classes that cannot be built here:
  identical to baseline. Whitelist scan clean. `git apply --check` on a second clean clone.

**Verified on Windows: nothing.** With the switch OFF, check that nothing changed: a timed-out
step still logs `!!! process tree: interpreter only - Windows: ... orchestrator.windows-tree-kill
is off`. To try the switch, on a TEST instance: a step that starts a long-running program, then
Stop; the log line says what was killed or why not; `tasklist` shows whether the program is gone.
Run `tools\windows-proctree-kit\run-kit.cmd` first: it measures the same two commands.

**Not verified on either:** `Get-CimInstance`, `taskkill`, Windows PowerShell 5.1, and the command
line Windows records for a Java-started process; `mvn clean package`; Spring binding of the new
boolean property.

## Follow-up

L1, L2, L3 and W are delivered. Decisions for the author: §9.7 (review), §9.5 (L4, JDBC drivers on
the standalone). Then batch 3.
