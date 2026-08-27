# 2026-08-27 - elarxml log document index

## What was asked

Given a list of workflows containing at least one `elarxml` step, read the whole log history and
deduce one `;`-separated CSV pairing the INDX file name with the original file name. PowerShell or a
Java executor, my choice.

## What was delivered

* `tools/Get-ElarDocumentIndex.ps1` - read-only analyser over the elarxml step logs.
* `.claude/ELAR_LOG_DOCUMENT_INDEX.md` - the decisions, what the logs do and do not carry, and the
  verification.

Nothing under `src/` is touched, so this cannot change any feed. No build, no deploy, no restart.

## The choice

PowerShell. It is a cross-feed analysis of history rather than a step in a pipeline; the question is
raised by an ELAR rejection and has to be answerable the same day; and indexing step logs was already
considered and rejected for the product when the log report was built. Reasoning in §2 of the spec.

## The finding that shaped the code

The trace line is written BEFORE the document, while the INDX only gets its deliverable name at
`Batch.close`. An aborted batch therefore leaves trace lines naming a file that was never delivered,
and the obvious implementation - collect every `<- id=` line - would produce a CSV asserting
deliveries that did not happen. A pair is emitted only when the log also shows that INDX reaching its
final name, and the traced count is reconciled against the declared one.

## Files

| file | note |
|---|---|
| `tools/Get-ElarDocumentIndex.ps1` | new, 535 lines, read-only |
| `.claude/ELAR_LOG_DOCUMENT_INDEX.md` | new, the spec |
| `CLAUDE.md` | one section appended |

## Verified

61 assertions green against log files written by the real `ElarRun` compiled standalone with
`--release 8`; eleven mutations of the script all caught; one green mutation opened and found to be a
bad mutation, which turned up an off-by-one beside it; one assertion of mine that could not fail,
replaced with one that does. Details in §7 of the spec.

## Not verified

Windows PowerShell 5.1 (the sandbox has PowerShell 7 for Linux; the dialect is asserted by scan, not
by running), and any real log from the field.

## Follow-up

Two questions were asked in chat and are open: whether the legacy `elar-file-maker.jar` history is in
scope, and whether the workflows will be named by feed id or by `<workflow name>` - both are accepted
today, with wildcards, so neither blocks a first run.
