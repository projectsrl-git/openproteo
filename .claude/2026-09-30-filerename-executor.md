# 2026-09-30 — filerename: the executor form of Rename-FilesFromCsvMap.ps1

Base `f9e40ea`. Spec: `.claude/FILE_RENAME_EXECUTOR.md`.

## What

A new internal executor, `filerename`, that renames the files of a directory according to a CSV
mapping: the name on disk is built from a template and an id, the new name is a column. It does
what `Rename-FilesFromCsvMap.ps1` does, with its 16 parameters plus `-WhatIf`, the same defaults,
checks, log lines and exit codes. Requested with "every parameter explicit in the viewer": all 17
are panel fields, and choosing the executor writes the defaults into the step, so the saved XML
states every value the run uses.

## Files

- `rename/CsvRenameMap.java` — the script's logic, Spring-free, JDK only.
- `rename/PsCsvReader.java` — port of PowerShell 7.4.6 `ImportCsvHelper` (MIT).
- `rename/DotNet.java` — `Trim`, `IsNullOrWhiteSpace`, `int.TryParse`, `PadLeft`, Windows invalid
  file-name characters, with .NET semantics.
- `rename/CaseInsensitive.java` — the `OrdinalIgnoreCase` key used by every set.
- `engine/InternalSteps.java` — `runFileRename` and its dispatch line.
- `engine/WorkflowEngine.java`, `parser/WorkflowXmlParser.java` — registration (whitelist,
  message, `internal` set, `internalKind()`).
- `templates/designer.html` — dropdown option, panel, `clientValidate`, default seeding in
  `updNodeR`, helpers `frSeed` / `frPreview` / `frYes`.
- `templates/variables.html` — `PARAM_OPTIONS` for `force`, `paddingBasis`, `reverse`,
  `summaryOnly`, `whatIf` (new names, no clash with other executors: checked).
- `static/USAGE.md` — executor bullet and `## The filerename step`.

## How it was verified

The oracle was the real script, not a reading of it: PowerShell 7.4.6 for Linux downloaded from
the PowerShell GitHub releases, one process running many scenarios through a driver that redirects
`Console.Out` per scenario.

- **Executor differential**: 656 scenarios (26 hand-written, 630 random) run by the script and by
  `CsvRenameMap` on copies of the same directory; compared final directory (names and contents),
  exit code, every counter, and the ordered REN / SKIP / FAIL / What-if events. **0 unexplained
  differences**. 25 expected ones asserted as such in both directions: 24 of deviation 1 (script
  errors, executor proceeds) and 1 host deviation (pwsh on Linux renames a name holding CR/LF).
- **Parser differential**: 623 CSV texts (23 hand-written, 600 fuzzed over quotes, delimiters,
  blanks, CR, LF, `#`) parsed by `Import-Csv` and by `PsCsvReader`, cell by cell: **0 differences**.
- **Wiring**: `runFileRename` lifted VERBATIM from `InternalSteps.java`, compiled with
  `--release 8` against the real `StepExecutor`, `VarResolver`, `RunControl`: 32 assertions (exit
  code on every path including success, outputs, rebasing on `${feedDir}`, tab, Stop, log file).
- **Panel**: jsdom on the real template, controls driven through their own handler attributes:
  133 assertions. The pre-patch template fails the suite.
- **USAGE.md** through `docs.html`'s own `render()`: 30 assertions; the pre-patch file fails 24.
  Mechanically: 17/17 parameters, 11/11 outputs, 9/9 defaults agree between guide, executor,
  field initialisers and designer seeding.
- **Mutations**: core 23/24 caught, parser 8/8, wiring 10/10, panel 11/11.

## What the mutations found

- Core #19 (a stray quote in an unquoted field eating trailing blanks) survived the executor
  differential. **Equivalent on the executor's outcome**: every value from that branch contains
  `"`, which Windows forbids in a file name, so any name built from it is refused anyway. The
  parser differential catches it (19 files differ), which is why that suite exists.
- Wiring: "delimiter trimmed" survived. **Real gap**: no test passed a LITERAL tab (what `&#9;`
  in the XML delivers), which `pv()` would trim into the default `;`. Test added; caught.
- Panel: a stray `</div>` survived. **Real gap**: with one card, an unbalanced tag shifts
  everything one level and the card still ends with its body. Now two cards must stay siblings
  and every field must hold exactly one label; caught.
- Panel: removing the redraw of the padding-basis select survived because **nothing the panel
  shows depends on it**. The redraw was superfluous and was removed rather than defended.
- A mutation that returns an empty last field made the parser loop forever (the blank-line skip
  never reaches EOF): the harness now times out each run and counts a hang as caught.

## Harness traps worth remembering

- The PowerShell host caches the FIRST `Console.Out` for its What-if output: a later scenario in
  the same process wrote to a closed writer and showed as a FAIL in the script. WhatIf scenarios
  now run one process each. Two first-run "mismatches" were this, not the code.
- The `.brand` link has only `th:href` in the template; `CTX` reads `href`. The jsdom harness sets
  it as Thymeleaf would.
- My own check of a default read `';'` as empty: the regex used `;` as its terminator. Read the
  deciding line instead.

## Findings about the script

- `-Encoding windows-1252`, its default, is not in `Import-Csv`'s `ValidateSet` in the 5.1-era
  source: on Windows PowerShell 5.1 the script cannot run with its defaults. From the source, not
  run on 5.1.
- Its counters were the sizes of lists capped at `MaxReport`.
- Chains and swaps pass its pre-check and fail at run time, leaving the directory half renamed.
  Same in the executor, by choice (Gate 0 Q3).

## Not verified

- `mvn clean package` (the full `InternalSteps` needs the Spring tree).
- Windows: the case-only rename path (`ATOMIC_MOVE` + `toRealPath()` read-back) is argued from
  jdk8u `WindowsFileCopy.move`, which returns early for the same file; never run on Windows.
- Windows PowerShell 5.1 as an oracle: the oracle was pwsh 7.4.6 on Linux.
- The panel in a real browser.

## Follow-up

Gate 0 in the spec, §7: the name, exit 2 as failure, chain detection as an option off by default.
