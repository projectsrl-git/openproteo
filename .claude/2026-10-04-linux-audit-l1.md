# 2026-10-04 — Linux audit of the internal executors (L1)

Base `34ad944`. Report: `.claude/LINUX_AUDIT.md`. **No product code changed.**

## What

A scan of all 35 575 lines of Java for the constructs through which a platform leaks into
behaviour, every hit read, and each suspected difference measured on a real Java 8 runtime.

**Clean:** no read or write of file content with the default charset; every output file has a fixed
line ending; no AWT, fonts, file locks, or process started outside the launcher; temp files are
created beside their target; the audit hash does not depend on the line ending.

**Eight findings**, in `LINUX_AUDIT.md` §4. The three that change what a feed does on Linux:

- **F1** `File.renameTo` replaces an existing `<name>.done` on Linux and refuses on Windows - the
  older evidence file is lost without a word (elarxml, json2csv, ftpsend, elar split).
- **F2** `*.csv` matches `DATA.CSV` on Windows and not on Linux, exit 0 (filecopy, safecopy).
- **F3** `${matchedFiles}` comes in name order on NTFS and in hash order on Linux, and a `forEach`
  iterates it (filecopy, safecopy, encoding batch).

## The decisions taken without the author

He cannot answer before L3. Each is in `LINUX_AUDIT.md` §4-5 and all share one rule: nothing changes
what an existing Windows feed produces. F1: rename without replace, same refusal on both hosts.
F2: matching stays the host's, and the log says which rule applied and when case alone prevented a
match. F3: sorted on non-Windows hosts only, because the claim about NTFS order cannot be verified
here. F5: a warning line, no value changed. F6: recursive deletes that do not follow links.

## What I got wrong on the way, and how it showed

The first version of the measurement printed "case-insensitive order (what NTFS returns)". It is
not: `String.CASE_INSENSITIVE_ORDER` decides `a_b` vs `ab` on the LOWER-cased characters and puts
`a_b` first; NTFS compares upper-cased and puts `ab` first. The underscore sits between `Z` and `a`,
and file names are full of underscores. The sort in F3 is therefore by upper-cased name - and, not
being verifiable here, is not applied on Windows at all.

## Files

- `.claude/LINUX_AUDIT.md` — new, the report.
- `tools/scan_platform_assumptions.py` — new, the scanner used; finds candidates, does not judge.
- `.claude/LINUX_AND_GUI_CONFIG.md` — §9.3.
- `.claude/2026-10-04-linux-audit-l1.md` — this note.
- `COMMIT_MSG.txt`.

No Java, template, JS, `pom.xml`, `USAGE.md`, `README.md`. No runtime parameter. `CLAUDE.md` not
touched: no rule changed.

## Verification

Verified on Linux (sandbox; Java 1.8.0_432): the scanner over the whole tree; E1-E3 of the report
run on ext4 and tmpfs; eleven packages compiled with the Java 8 `javac`; every file:line the report
cites read at `34ad944`; `git apply --check` on a second clean clone.

Verified on Windows: nothing. Three statements about Windows in the report are marked as read, not
run: `renameTo` onto an existing file fails, the glob matcher is case-insensitive, NTFS enumerates
in name order.

Not verified on either: the executors hosted in `InternalSteps` were read, not run (their
dependencies cannot be fetched); the scanner finds what its patterns describe and nothing else.

## Follow-up

L2 (FTPS trust on Linux), then L3 (`LINUX_AUDIT.md` §5), then W.
