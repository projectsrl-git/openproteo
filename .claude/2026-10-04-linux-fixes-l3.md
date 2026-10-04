# 2026-10-04 — The fixes of the Linux audit (L3)

Base `3456bd1`. What and why: `.claude/LINUX_AUDIT.md` §4-5.

## What

| Finding | Change |
|---|---|
| F1 | `elarxml`, `json2csv`, `ftpsend`, elar split: a processed input is renamed with `HostFiles.renameNoReplace`. An existing target is refused on every host, as Windows always did |
| F2 | `filecopy`, `safecopy`: on a host that matches case-sensitively the log says so, and says how many files were left behind for case alone |
| F3 | `filecopy`, `safecopy`, `encoding` batch: off Windows the files are taken in name order; on Windows the enumeration is not touched |
| F5 | every step: a parameter that begins with `/` and contains `\` gets a WARNING line off Windows; no value is changed |
| F6 | `RunStore`, `WorkflowPorter`: recursive deletes that do not follow symbolic links |
| F4, F8 | `USAGE.md` only: «Moving a workflow from Windows to Linux» |

`CLAUDE.md` gains two rules, in the sections they belong to: host-dependent file operations go
through `platform/HostFiles` («Vincoli ambientali», 9), and the differential compile («Verifica
prima della consegna»).

## Files

- `platform/HostFiles.java` — new, JDK only.
- `elar/ElarRun.java`, `elar/InputSplitter.java`, `json2csv/Json2CsvRun.java` — one line each.
- `engine/InternalSteps.java` — ftpsend rename; filecopy; safecopy; encoding batch; the F5 check.
- `engine/StepExecutor.java` — the F5 check for external steps.
- `store/RunStore.java`, `port/WorkflowPorter.java` — the delete.
- `static/USAGE.md`, `CLAUDE.md`, `.claude/LINUX_AUDIT.md`, `.claude/LINUX_AND_GUI_CONFIG.md` §9.5,
  this note, `COMMIT_MSG.txt`. `README.md` not touched. No runtime parameter.

## Verification

**Verified on Linux (sandbox; Java 1.8.0_432 and JDK 21.0.12, both run; root and uid 65534; ext4
and tmpfs):**

- `HostSuite`, 41 assertions in all eight combinations, on the real `HostFiles` and on the REAL
  `ElarRun.renameDone` and `Json2CsvRun.renameProcessed`. Three controls inside it: `File.renameTo`
  does replace here; the host's own enumeration is not in name order; the delete that was in
  `RunStore` did empty the directory a link pointed at. 18 mutations, all caught - one was green
  at first and showed that the failure count was asserted only as "more than zero".
- `LiftSuite`, 21 assertions: the bodies of `runFileCopy`, `runSafeCopy` and `runEncodingBatch`
  lifted from `InternalSteps` by position and run, **beside the same bodies lifted from the
  pre-patch file**, on the same directories. The same files are taken, the same bytes copied; the
  order differs, and the log differs by exactly the two new lines. Two things stubbed and named:
  the CSV-list mode (not touched by L3) and the charset conversion (the order it is called in is
  what is under test).
- **Differential compile** of `InternalSteps`, `RunStore`, `WorkflowPorter`, `WorkflowEngine`:
  the errors `javac` reports for missing libraries are identical before and after, line numbers
  removed. **It found a real defect on the first pass**: `LinkedHashMap` is not imported in
  `InternalSteps`. Positive control: a wrong argument put on purpose in an edited line shows up.
- `USAGE.md` through `docs.html`'s `render()`: 21 for the new section (it is followed by the next
  chapter, so it swallowed nothing; its quoted log lines are lifted from the Java), and the
  earlier 122 + 21 still pass.
- `git apply --check` on a second clean clone.

**Verified on Windows: nothing.** What Windows should show, all of it "as before": the `.done`
renames; `filecopy` / `safecopy` logs without the two new lines and `${matchedFiles}` in the
order it always had; no WARNING lines.

**Not verified on either:** `mvn clean package`; the F5 check inside `InternalSteps.run` and the
two rewritten deletes in their classes (differential compile only - the functions they call are
tested); `InputSplitter` and the `ftpsend` rename in their classes (same helper, call site
compiled for the first, differential for the second); that NTFS order equals `NAME_ORDER`, which
is why the sort is not applied on Windows.

## Wrong on the way

Two of the suite's own expectations were wrong and the code right: the comparison with the
pre-patch log included the one line that MUST differ, and the expected path order put `a/9.csv`
before `A.csv` (`.` sorts before `/`). Both were mine, both corrected in the suite, neither in
the product.

## Follow-up

W (Windows process-tree kill, blind, born off). Then the author decides on L4 (§9.5).
