# 2026-10-04 — bash runner and process-tree kill, batch 2: the code

Base `5d7593d`. Spec: `.claude/LINUX_AND_GUI_CONFIG.md` §8 (decisions taken while building: §8.15).

## What

- **`bash` runner.** `exec="bash"` or a `.sh` script. Always run through the interpreter. Parameters
  arrive as `OP_<name>` environment variables, built by an all-ASCII `bash -c` bootstrap that ends
  in `exec`. Refused before launch, with the reason in the step log: a name collision, a nameless
  parameter, more than 120 KiB, a NUL, a script with CRLF endings.
- **Process-tree kill** on timeout and Stop, for every external runner on a non-Windows host:
  launched under `setsid`, killed by session and descent, stopped first. Degraded modes are written
  to the step log and shown on the Platform page.
- **Stop on a fan-out** stops every item (every platform).
- **PowerShell**: default `pwsh` outside Windows; there, `-OutputFormat Text` and `TERM=dumb`.
- **CMD / JAR outside Windows**: a parameter the JVM cannot encode is refused instead of sent as `?`.
- **Platform page**: bash row, "Stopping a step", file-name encoding verdict, no-wrap column.
- **Windows measurement kit** in `tools/windows-proctree-kit/`.

## Files

- `engine/ProcessTree.java` — new.
- `engine/StepExecutor.java`, `engine/RunControl.java`, `engine/WorkflowEngine.java` (two places:
  the `StepExecutor` constructor call and `stop()`).
- `config/AppProperties.java`, `application.properties` (the `powershell-exe` line is commented out).
- `parser/WorkflowXmlParser.java` — `bash` in the allowed `exec` values and in the message.
- `web/PlatformController.java`, `templates/platform.html`, `tools/scan_platform_whitelist.js`.
- `templates/designer.html`, `static/js/filespanel.js`.
- `static/USAGE.md`.
- `tools/windows-proctree-kit/ProcTreeKit.java`, `run-kit.cmd`.
- `CLAUDE.md` (debt list, entry), `.claude/LINUX_AND_GUI_CONFIG.md` (§8 delivered, §8.15), this
  note, `COMMIT_MSG.txt`. `README.md` not touched.

## New runtime parameter — incomplete by checklist 10

`orchestrator.bash-exe`, default `/bin/bash`. Applies at the next run of a step. **File-only**:
batch 4 does not exist. Added to the debt list in `CLAUDE.md` «Configurazione esterna».
`orchestrator.powershell-exe` is not new; its bundled line is now a comment so that an unset key
can follow the host.

## Verification

**Verified on Linux (sandbox; Java 1.8.0_432 runtime and JDK 21.0.12, both run; bash 5.2.21;
pwsh 7.4.6; as root and as uid 65534; `LANG` unset and `C.UTF-8`):**

- `ExecSuite`, 126 assertions, green in four combinations of the above, on the real classes:
  kinds; the three existing commands against the pre-patch class; bash values (quotes, `$`,
  backticks, backslashes, newline, non-ASCII, empty); `$0`, no positional arguments, working
  directory, nothing on any command line (with a positive control); every refusal and that nothing
  ran; a non-bash interpreter; a missing one; no `x` bit; a `noexec` tmpfs; CMD/JAR encodability by
  locale; timeout with six kinds of child (none survive), the declared escapee (survives), a fork
  storm (none), no `setsid` (exactly the two orphans), Windows rules (children survive, as today);
  Stop on three concurrent items, and a late Stop; pid by reflection; `/proc` parsing; pid-reuse
  rule; PowerShell default by host; a failing `.ps1` under real `pwsh` logged as plain text, and a
  `pwsh` step's child killed on timeout.
- `ControllerSuite` 22 (Spring stubbed, real `AppProperties` / `GlobalVarsStore` / `ProcessTree`).
- Designer in jsdom against the real template, 19; its XML parsed by the real `WorkflowXmlParser`
  and resolved to `BASH`, 5. Platform page in jsdom, 87. Whitelist scan clean, 21 controls.
- `USAGE.md` through `docs.html`'s own `render()`, 122; wrapped paragraphs elsewhere 86 before and
  after.
- Mutations on copies: executor 40 (39 caught, 1 equivalent - argued in §8.15.7); page 28 (27 + the
  known equivalent); designer 7; guide 12 (3 green at first, each a loose assertion, tightened).
- `git apply --check` on a second clean clone.

**Verified on Windows: nothing.** For the author, in this order:

1. `mvn clean package`, then an existing PowerShell feed end to end: same log, same output.
2. A `.cmd` and a `.jar` step: unchanged.
3. Stop on a fan-out step with concurrency > 1: every item stops (it did not before).
4. A step that times out: the log ends with `!!! process tree: interpreter only - Windows: ...`.
5. `/platform`: "Stopping a step" says `interpreter only`; the bash row says `not found` (expected);
   `orchestrator.powershell-exe` still shows `powershell.exe`; the Setting column does not wrap.
6. `tools\windows-proctree-kit\run-kit.cmd`, and send back `proctree-report.txt`.
7. With `./feeds` present, the case probe: `case-insensitive` (still never run - batch 1).

**Not verified on either:** `mvn clean package`; Spring wiring; a browser; `WorkflowEngine` as a
whole (its two edited places were read, not run - the Stop loop is exercised through `RunControl`
and `ProcessTree` directly); bash older than 5.2; the kit on Windows.

## Follow-up

The kit's report, then the Windows tree-kill design. Batch 3: authentication.
