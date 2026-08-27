# ELAR log document index

Reading the elarxml step-log history of a set of workflows and producing one CSV pairing each
delivered INDX file with the original content file it carries.

Tool: `tools/Get-ElarDocumentIndex.ps1`. Read-only, no deploy, no build.

## 1. Why this exists

ELAR validates an INDX in full and rejects it in full. The first question after a rejection is always
which documents were in that file, and answering it used to mean reopening and parsing the INDX -
assuming it is still on the share. Since `7fa7298` (2026-08-24) the executor answers it in its own
step log, one line per document. This is the reader for those lines across the whole history.

## 2. Why a PowerShell tool and not an internal executor

Four reasons, in order of weight.

1. **It is a cross-feed analysis of history, not a step in a pipeline.** An internal executor belongs
   to one feed and runs inside one workflow; this reads the runs of several feeds. The precedents in
   this repository are the right shape already: `ElarEquivalence` is a `main` run against real
   directories, `scan_tiff_readonly.js` and `scan_panel_redraw.js` are tools.
2. **Time to answer.** The tool is: copy one file, run it. An executor is patch, `mvn clean package`,
   WAR, Tomcat restart, configure a step, start a run. The question this answers is raised BY a
   rejection, and it has to be answerable the same afternoon.
3. **Indexing step logs was already considered and rejected for the product.** The log report indexes
   the audit JSONL and the run JSONs and deliberately does NOT index step logs - they are one to
   three orders of magnitude larger (`.claude/2026-08-04-step-log-lazy-peek.md`). Putting a step-log
   reader into the product now would contradict that decision without revisiting it.
4. An executor would create a new run, and therefore a new log, inside the very history it reads.

The cost is stated rather than hidden: the sandbox can run this on PowerShell 7 for Linux, which is
not Windows PowerShell 5.1. See §7.

## 3. What the logs actually carry

Three lines matter, all written by `ElarRun` through the step log consumer in `InternalSteps`, which
prefixes every line with `O`, a tab, a millisecond timestamp and another tab.

| line | written | gated by |
|---|---|---|
| `elarxml: <INDX> <- id=<ID> file=<NAME>` | before the document is written | `logDocuments` |
| `elarxml: <INDX> delivered with <N> document(s), paired with <PULL>` | in `Batch.close`, after the final rename | `logDocuments` |
| `elarxml: wrote <INDX> (<N> document(s))` | at the end of the run | nothing |

**The trace line precedes the write, and the final name arrives only at close.** A batch that is
aborted - an exception, the disk guard, an oversize rollover - therefore leaves trace lines naming an
INDX that was never delivered. Harvesting the trace lines on their own would produce a CSV asserting
deliveries that did not happen, which in an archive is the worst kind of wrong: confidently wrong,
and about what was archived.

**So a pair is emitted only when the same log also shows that INDX reaching its final name.** Either
delivery line is accepted, because they have different blind spots: `delivered with` is absent when
`logDocuments` is off, `wrote` is absent when the run failed before its summary. A trace with no
delivery is counted and reported, and written to a second file on request - never emitted into the
main CSV, never silently dropped.

**The traced count is reconciled against the declared one.** The two lines come from the same
executor and must agree; a disagreement is reported as something to investigate, in the same spirit
as `filesRead = rowsWritten`.

## 4. What the logs do NOT carry, and it is stated rather than discovered

* **Runs before 2026-08-24 have no per-document trace at all**, nor do runs whose step set
  `logDocuments=false`. They carry the delivery lines, so the tool knows an INDX was delivered and
  how many documents it held, and it says so per run instead of leaving the file looking as if it
  carried nothing. For those INDX files the mapping is not recoverable from the logs.
* **`file=` is the bare file name, never the path.** `LocalContentStore.fileName` and
  `IfsContentStore.fileName` both take the last segment, so even under `contentSource=IFS`, where the
  CSV column carries a full path, only the name reaches the log. If the full path is ever needed it
  is a change to the executor and it applies from that day forward; no tool can recover it from
  history.
* The document id is in the log and is available under `-Detailed`, but the two columns asked for are
  the two columns written by default.

## 5. Decisions

* **Test runs are INCLUDED by default.** A `_test_` run that wrote a real INDX delivered it, so for
  the question "what is out there" a missing row is worse than an extra one. They are counted
  separately in the summary and `-ExcludeTestRuns` drops them.
* **No de-duplication.** The same content file delivered in two different INDX files is two true
  rows - the shape a re-run without `output.start_time` produces, which the naming code already warns
  about. Both rows are written AND the count is reported, because in an archive a duplicate delivery
  is a finding, not noise. The check holds one entry per distinct file name and switches itself off
  above `-MaxTrackedFiles`, saying so, rather than growing without bound.
* **Every `.log` under `_logs/runs` is read**, not only the step ids that are `elarxml` in the CURRENT
  definition. A step can be renamed and a workflow deleted while its history stays on disk. A log
  with no `elarxml: ` line costs one pass and is not counted.
* **Two passes per log, not one.** The delivery line comes after the traces it validates. Buffering
  them instead would hold a whole batch in memory, which is the accumulation this subsystem exists to
  avoid. Same trade elarcheck makes: the cost is I/O.
* **Logs are opened with `FileShare.ReadWrite`.** The tool is meant to be pointed at a live feed
  directory; a reader that locks a log the engine is appending to would turn an analysis into an
  outage.
* **UTF-8 with BOM, CRLF, RFC-4180 quoting.** The BOM is what lets Excel read accented file names out
  of a semicolon-separated file. A value containing `;` cannot in fact reach the log through a
  `;`-separated input, but the writer is correct about it anyway.

## 6. Open

* Whether the legacy history matters - deliveries made by `elar-file-maker.jar` through a `jar` step,
  before the executor existed. Their logs have a different shape and are not read. Nothing has been
  written for them because nothing has been asked for them.
* Whether the full IFS path is wanted in future logs (§4).

## 7. Verification

Everything below was run, not reasoned about.

* **The fixture logs are written by the REAL `ElarRun`.** The `elar` package compiles standalone with
  `javac --release 8`, so a harness runs the actual executor over actual files and captures its log
  lines in the exact `O<TAB>timestamp<TAB>message` format `InternalSteps` uses. The parser is
  therefore tested against the emitter, not against a transcription of it. Five runs are produced: a
  clean one, one whose third batch is traced and then aborted by an unencodable metadata value, one
  with `logDocuments` off, a `_test_` run of a second feed, and one carrying two values chosen to
  break a careless parser - a document id that itself contains `" file="`, and a file name with a
  double quote in it.
* **61 assertions, all green. Eleven mutations of the real script, all caught**: emitting traces
  regardless of delivery (11 assertions), the first `" file="` instead of the last (2), both
  `wrote`-line guards removed (the tool throws), no count reconciliation (3), a non-sharing reader
  (1), no CSV quoting (2), the count read one character too narrow (2), test runs dropped silently
  (5), the no-trace case reported as delivered-and-empty (1), the duplicate check disabled (1), and
  the output path resolved against the process directory (1).
* **A green mutation was opened rather than filed**, and it was a bad mutation: removing the
  `EndsWith(" document(s))")` guard changes nothing because `LastIndexOf(" (")` returns -1 on a PULL
  name and the branch exits there. The two guards defend the same thing. Reading that line to answer
  the question found a real if benign off-by-one beside it - the count substring was one character
  too wide, and it only parsed because `int.TryParse` ignores the trailing space. Fixed, and pinned
  by an assertion that fails in the direction that would produce a wrong number.
* **An assertion of mine could not fail and was replaced.** A relative `-Out` resolving against the
  caller cannot be distinguished in this sandbox: on Linux .NET's current directory follows the
  process, so the two agree whatever the tool does. The real `Resolve-OutPath` is now lifted out of
  the script at test time and exercised with `[System.IO.Directory]::SetCurrentDirectory` pointed
  somewhere else, which reproduces the Windows divergence by hand. It fails when the fix is removed.
* **NOT verified**: Windows PowerShell 5.1. The sandbox has PowerShell 7.4.6 for Linux only. The
  script is written to the 5.1 dialect and six scans assert it - no `??`, no ternary, no `&&`/`||`,
  no `-Parallel`, no three-argument `Join-Path`, no literal backslash-n - each with a positive
  control proving the scan can fire. That is a syntax argument, not a run.
* **NOT verified**: any real log from the field. The first run on the real history is also the
  measurement of how much of it predates the trace.
