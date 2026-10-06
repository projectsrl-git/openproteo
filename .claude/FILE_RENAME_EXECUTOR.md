# filerename — rename the files of a directory from a CSV mapping

Status: delivered in one batch, 2026-09-30, on `f9e40ea`. Reference contract: the PowerShell
script `Rename-FilesFromCsvMap.ps1` (ELAR toolkit, 423 lines), which this executor replaces.

## 1. Why spec and code arrive together

`CLAUDE.md` asks for a spec before a non-trivial feature and a gate between batches. Here the
behaviour was already fully specified by an external contract, the script, and the request was
"exactly the same thing". So the spec records what the script does on every case where two of
its rules meet, and the few places this executor does something else. The decisions that were
genuinely open are listed in §7 as Gate 0 questions with the conservative answer taken; each can
be reversed without touching the rest.

## 2. What the script does, and the executor with it

For each CSV row: build the name on disk from `SourceTemplate` (`{PREFIX}`, `{ID}` padded, `{EXT}`,
then any `{Column}`), read the new name from `NameColumn`, and plan the rename (reversed with
`Reverse`). Check the whole plan, then rename. Parameters, defaults, check order, log lines and
exit codes (0 / 2 / 1) are the script's.

Parameters: `csvPath`, `directory` (required); `nameColumn` = original_object_name, `idColumn` =
object_id, `extColumn` = mime_type, `prefix` = empty, `sourceTemplate` = `{PREFIX}.OID{ID}{EXT}`,
`idPadding` = 5, `paddingBasis` = MaxId, `reverse` = false, `force` = false, `csvDelimiter` = `;`,
`csvCharset` = windows-1252, `logFile` = none, `summaryOnly` = false, `maxReport` = 30,
`whatIf` = false. Seventeen: the script's sixteen plus `-WhatIf`. The prose above and the table
in USAGE.md state the same defaults; they were compared mechanically with the field initialisers
of `CsvRenameMap` and with the designer's seeding.

## 3. Rules that meet, and which one wins

Each pair below is written here once, and the same outcome is stated in USAGE.md next to both rules.

1. **"A blank parameter means its default" vs "`extColumn` is required when the template uses
   `{EXT}`".** Blank `extColumn` means `mime_type`; if the template has `{EXT}` and the CSV has no
   `mime_type` column, the step stops naming `mime_type`. Example: template default, CSV
   `object_id;original_object_name` -> `Column 'mime_type' not found`.
2. **"Column parameters match regardless of case" vs "template tokens are literal".** The column
   parameters win only for the parameters; a `{docid}` token does not match a header `docID`, is left
   as literal text, and the row then looks for a name containing braces (source not found). The
   script does the same: its `String.Replace` is ordinal.
3. **"Duplicate targets are left out" vs "force skips colliding rows and renames the rest".**
   A duplicate target is left out whether or not force is set (counted in `duplicateTargets`); force
   only decides whether the step stops. A row that is both a duplicate and blocked on disk is
   counted as a duplicate: that check comes first.
4. **"Everything is checked before the first rename" vs "a target being renamed away counts as
   free".** The second wins, and the first is therefore NOT a guarantee for chains and swaps:
   A -> B then B -> C fails the first rename at run time and leaves the directory half renamed.
   Measured identical in script and executor. See §7 Q3.
5. **"Exit 2 = some rows not applied" vs "exit 1 = a rename failed / Stop".** 1 wins: a run with
   both skipped rows and a failed rename ends 1.
6. **"whatIf renames nothing" vs "collisions stop the step".** Collisions still stop a dry run
   with exit 1, as the script's `-WhatIf` did: the dry run is where they should be found.
7. **"`csvCharset` is the charset" vs "a BOM wins".** The BOM wins (`StreamReader` behaviour,
   measured on pwsh). A UTF-8 file with BOM read with the default windows-1252 is read as UTF-8.
8. **"A number is zero-padded" vs "a negative number".** Padded before the sign, `-3` -> `000-3`;
   `+5` -> `00005`. Measured on pwsh, kept.
9. **"MaxId: digits of the largest id" vs "no id is numeric".** Then the width is the length of
   the longest id as written. With mixed ids only the numeric ones count for the largest.
10. **"summaryOnly drops the per-file lines" vs "FAIL lines".** FAIL lines are always written.
11. **"logFile receives every line" vs "What if lines".** What if lines go to the step log only:
    in the script they were host output, not `Log` calls.
12. **"Not a bare name" rule: which invalid-character set.** Windows' set on every host (deviation
    §5.5). On Linux .NET accepts CR/LF in a name; measured, the executor refuses it.

## 4. The CSV is read as `Import-Csv` reads it

`PsCsvReader` is a line-for-line port of `ImportCsvHelper` from PowerShell 7.4.6
(`CsvCommands.cs`, MIT). Compared with the real `Import-Csv` on 623 files (23 hand-written, 600
fuzzed over an alphabet of quotes, delimiters, blanks, CR, LF, `#`): 0 differences. Differences
from Windows PowerShell 5.1, from the 6.0.0-alpha.9 source: 5.1 assumes CR is always followed by
LF, and skips only the first `#` line.

**2026-10-06:** `PsCsvReader` now reads through a one-character look-ahead over a `Reader`, so
that `objunpack` can take a large file record by record (`OBJECT_UNPACK_EXECUTOR.md` §19).
`filerename` calls it as before. The parser of `9d98f95` and the new one gave identical output on
300 000 fuzzed inputs under two delimiters; `Import-Csv` itself was not run again.

## 5. Deviations from the script

1. The extension column is required only when the template contains `{EXT}` (the script required
   it always, and discarded the value). When the column exists it is read as before.
2. Counters are real counts; the script reported the size of lists capped at `MaxReport`.
3. The dry run adds one summary line and `${wouldRename}`.
4. Stop is honoured between two renames (exit 1, a line with the count).
5. The Windows set of invalid file-name characters on every host.
6. Relative paths resolve against `${feedDir}`; a blank parameter means its default; values are
   trimmed like every other step parameter, except `csvDelimiter`, which is read untrimmed and
   accepts the word `tab`.

None changes which file gets which name on Windows for an input the script accepted.

## 6. Two traps found on the way

- **JDK 8 `Files.move` on Windows does nothing for a case-only rename** (`a.pdf` -> `A.PDF`): it
  checks "same file" first and returns (`WindowsFileCopy.move`, jdk8u). .NET renames. The executor
  uses `ATOMIC_MOVE` for that case, which goes straight to `MoveFileEx`, and reads the name back
  with `toRealPath()` so a rename that did not take is a FAIL, not a REN. **Argued from the source,
  not measured on Windows.**
- **The script's default `-Encoding windows-1252` fails parameter validation on Windows
  PowerShell 5.1** (`ValidateSet` of eight names on `Import-Csv -Encoding`, in the 5.1-era source).
  With its defaults the script runs only on PowerShell 7. The executor has no such limit.

## 7. Gate 0 — answered conservatively, open to reversal

- Q1 name: **`filerename`**, beside `filecopy`. Rename is cheap until a workflow uses it.
- Q2 exit 2: **kept as in the script**. OpenProteo fails a step on any non-zero exit, which is what
  happened to the script run as a `powershell` step. Alternative if wanted: a parameter making 2 a
  success, off by default.
- Q3 chains and swaps: **faithful, not detected**. Alternative: an option, off by default, that
  refuses in the pre-check a plan whose target is the source of a LATER row. It would not change any
  run that does not set it.
