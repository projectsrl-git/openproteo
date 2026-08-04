# Log report next to Operations, and the fix for "0 outputs"

## 1. Navigation
"Log report" now sits next to Operations in the topbar of all 16 pages, same filled style; the
duplicate small button on the dashboard toolbar was removed.

## 2. Why the index reported 0 outputs
Your screenshot showed `index: 106289 events · 137 runs · 0 outputs` and an empty
"Runs & output data" grid. The cause is in the indexer, not in the data: output variables are declared
in TWO places, and it only read one.

Operations reads both:
- `outputData.<var>` **parameters on each step** (this is what the designer's OUTPUT DATA box writes,
  e.g. `rowsWritten = Rows written`), and
- the workflow-level `<outputData>` block.

`upsertRun` only used the workflow-level map, which is empty for every feed that declares its outputs on
the step - i.e. effectively all of them. It now collects from the steps first and lets the workflow-level
block override, exactly matching what Operations displays.

The index rebuilds itself from the files, so after deploying, press **Reindex** (or restart) and
`/api/logs/status` should report a non-zero `outputs`.

## 3. Still open: step-log detail in the grid
The DETAILS column shows the audit payload (`{"exitCode":"0","attempt":"1"}`, `{"var":"...","value":
"..."}`) because that is what the audit line carries. The step LOG - the PowerShell/CLIXML output you see
in "Step log (inspect any step)" - lives in separate per-step log files under the run directory and is
NOT in the audit trail, so it cannot appear in this grid without indexing a third source. Options, in
increasing cost: a link from each row to that step's log on the run page; a lazy "peek" that fetches the
tail of the step log on demand; or indexing the step logs too. Not implemented here - it needs a
decision on which one you want.

## Verify
Brace balance checked with the string/comment-aware counter; all 16 templates carry both buttons exactly
once. The indexing fix could not be exercised here (no H2, no real feeds): the check is the `outputs`
count after a reindex.

## Note on the first attempt
The first version of this change failed to compile: the new code used `LinkedHashMap` while the file only
imported `HashMap`. Brace/paren balance checks cannot catch a missing import - only a compiler can, and
the full project cannot be compiled in the chat sandbox. Cheap mitigation now applied to every logreport
file: cross-check the java.util/java.io/java.nio identifiers used bare against the import list.
