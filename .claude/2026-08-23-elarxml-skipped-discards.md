# elarxml: the `.skipped` discards file, and two policies where there was one

**Requested from the field**: a referenced content file that is not on disk should skip the row and leave
a record of it, not refuse the whole run.

## It refused, and through the wrong switch

Until now a missing content file failed the run, gated by the **same** `onMalformedRow=FAIL` that
governs a row with the wrong field count. Two different problems sharing one switch:

- a **malformed row** means the input itself is broken. Re-running will not help;
- a **missing content file** usually means staging has not finished. The rows that *do* have their
  files are perfectly deliverable, and the ones that do not will be next time.

One switch made the second hostage to the first. `onMissingFile` is now separate, `SKIP` by default —
**a declared exception to the conservative-default rule**, since a family relying on the old refusal
must now set `onMissingFile=FAIL` to keep it.

## The discards file

`<input>.skipped`, beside its input, opening with the input's own header line and carrying each
dropped row **verbatim** — byte for byte, trailing spaces included. Verbatim is the whole point: this
file exists to be re-read, and re-serialising the parsed fields would quietly rewrite quoting and
separators. Correct the staging, rename it so it ends in `.csv`, and the next run delivers the rows
with nothing edited by hand.

The name **appends** rather than replacing the extension, matching `.done`: the original name stays
legible, `a.csv` and `a.txt` cannot collide, and `listInputs` only accepts `.csv` so a discards file
is never mistaken for an input while it still ends in `.skipped`.

**It is published at the same moment its input is renamed to `.done`**, reusing the machinery from the
per-file rename: a temp file until then, removed if the run fails. A discards file left behind by a
run that delivered nothing would read as a complete account of what was dropped, and would be the
opposite of one. So `.skipped` means exactly what `.done` means. It is created lazily, so an input
with nothing to discard leaves no empty artefact to be mistaken for a report.

Rows with an **empty content path** go in it too. Re-running will not rescue them — the source has to
be corrected — but a discards file listing only some of the dropped rows would misrepresent what was
archived, and this is an archive. Each reason keeps its own counter.

`writeSkippedRows=false` turns the file off while leaving the skip and the counters intact. New result
variable `skippedFilesWritten`, so a gate can branch on it without parsing a log.

`onMissingFile` is deliberately **not** in the Variables page's `PARAM_OPTIONS`: `ifscopy` already uses
that parameter name with the opposite default, and that table is keyed by name with no executor
context. Same rule as `inputCharset` — the second time this has come up, so a shared parameter name
should now be treated as the norm rather than the surprise.

## Defect found alongside: `rowsMalformed` counted twice

Pre-existing, visible only under `onMalformedRow=SKIP`. The counter was assigned from the pre-scan and
then incremented again in the write loop, so every malformed row was reported **double** in `run.vars`
and in the cross-feed log report. The pre-scan's count is the authoritative one — taken before any
output exists, over rows the loop may never reach — so the loop no longer counts. Caught by an
assertion that expected 1 and got 2, which is the argument for asserting exact counts rather than
"greater than zero".

## Verified

- **36 assertions**, `--release 8`, running the whole executor against real files. The decisive one:
  **a run that fails after discarding rows publishes no discards file**, leaves no temp file, and
  leaves the input un-renamed so the whole thing is reprocessed.
- Also asserted: the skip, the counters and the `.done` rename still happen; the discards file is
  byte-for-byte the source lines with the input's own header; **the round trip** — rename to `.csv`,
  put the missing file back, re-run, the row is delivered and nothing is left to discard;
  `onMissingFile=FAIL` refuses with nothing written and nothing renamed; the two policies are
  independent in both directions; an input with nothing to discard leaves no file at all; and
  `writeSkippedRows=false` still skips and still counts.
- The value-edge suite (31), the naming suite (15) and the `.done` rename suite (33) re-run unchanged.
  Designer panel suites green at 92 and 67 — the two new fields are covered by the same assertion that
  no panel field writes a parameter the executor does not read. `elar` rule scans and template scans
  clean.
