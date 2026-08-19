# ELAR XML executor (`elarxml`) — specification

Status: **Batch 0 — specification only. No implementation.**
Replaces the standalone `elar-file-maker.jar` (`com.csg.it.elar.xmlmaker`) with a streaming internal
executor. Self-contained: everything needed to implement is here.

---

## 1. What it does

Reads the **flat** source CSV (one row per document), and for each row emits one `<ELAR:Doc>` block
into an INDX XML file, embedding the document's content file as Base64 together with its SHA-256.
Documents are grouped into batches; each batch produces one INDX and one PULL sharing a filename
counter. The vertical intermediate CSV of the legacy tool is not produced at all.

Nothing accumulates across documents: peak heap is independent of batch size and of embedded file
size.

## 2. Verified against the sources

The legacy behaviour below was read from the code, not inferred. It is repeated here only where the
new design has to reproduce or deliberately break it.

- The filename segment `C152100` is a **synthetic clock**, starting at `<family>.output.start_time`
  or wall-clock time and advancing exactly 60 seconds per batch. `D26229` is
  `String.format("%02d%03d", year % 100, dayOfYear)`.
- The digest in `ELAR:HashValue` is SHA-256 of the **raw file bytes** (`FileHashSHA256`). The
  Base64-text variant (`IndxBuilder.computeSHA256`) is dead code. Every INDX ELAR has accepted
  carries the raw-bytes convention; reproduce it.
- The PULL is a small manifest referencing the INDX by name, its filename derived by
  `replace("INDX", "PULL")`, so the pair always shares the counter by construction.
- `ELAR:HashValue` precedes `ELAR:Content` in the template, so the digest must be known before the
  content is streamed.
- Template constants not in `tagNameMapping` (`ELAR:RecordOwnerLE`, `ELAR:RecordOwnerCountryCode`,
  `ELAR:ChannelICTO`, `ELAR:DeliveryICTO`) must survive verbatim; `ELAR:BU` is both a template
  constant and mapped, and the CSV value wins when present.
- `ELAR:ContentName` is in the template and mapped by nothing; it stays empty.

## 3. Answers to the open questions

### 3.1 Q1, separator inside a value — ANSWERED: no

Confirmed by the feed owner: a value never contains the field separator. `quoteChar` therefore stays
disabled by default and the parse is exactly the legacy split.

The answer is an assertion about the data rather than a measurement, and the design does not need it
to be right. With `onMalformedRow=FAIL` and the pre-scan of §3.1b, a value that does contain the
separator **stops the run instead of losing the document** — so if the assertion is ever wrong, the
failure mode is a refusal rather than a short delivery. That is the whole reason the pre-scan is
worth its second read.

The commands below are kept because the second one answers a different question, and one that stays
open: **how many documents past deliveries are missing.** `CsvParser` discarded every `out_*.csv` row
whose field count was not exactly three, silently. If the count is not zero, that is an archiving
finding, not a software one, and it is worth running once against the intermediates still on disk.

### 3.1-legacy Commands (retained for the historical check)

This is the one question that gates batch 1, and it needs data from `G:`, which the development
sandbox cannot reach. **Three commands, in order.** Run them and record the counts in this file
before batch 1 starts.

```powershell
# 1. Does it happen in the SOURCE data? Any row whose field count differs from the header's.
$src = "G:\Phoenix\openproteo\feeds\<feed>\<source>.csv"
$hdr = (Get-Content -Path $src -TotalCount 1 -Encoding Default)
$n   = ($hdr.ToCharArray() | Where-Object { $_ -eq ';' }).Count
$bad = 0; $ln = 0
Get-Content -Path $src -Encoding Default | ForEach-Object {
  $ln++; if ($ln -eq 1) { return }
  $c = ($_.ToCharArray() | Where-Object { $_ -eq ';' }).Count
  if ($c -ne $n) { $bad++; if ($bad -le 5) { Write-Host "line $ln : $c separators, expected $n" } }
}
"rows with a different field count: $bad"

# 2. Did it cause LOSS in past deliveries? The legacy intermediates are the historical record:
#    CsvParser dropped every out_*.csv row whose field count was not exactly 3, with no message.
Get-ChildItem "G:\ELAR\OUT\CMOD\S210967_CLICT" -Filter "out_*.csv" | ForEach-Object {
  $f = $_.FullName; $bad = 0
  Get-Content $f -Encoding Default | ForEach-Object {
    if ((($_.ToCharArray() | Where-Object { $_ -eq ';' }).Count) -ne 2) { $bad++ }
  }
  "{0} : {1} row(s) silently discarded" -f $_.Name, $bad
}

# 3. If values DO contain the separator, are they quoted?
Select-String -Path $src -Pattern '"' -Encoding Default | Select-Object -First 5
```

What each outcome means:

- **All zero.** The exposure is theoretical. `quoteChar` stays disabled, the default parse is exactly
  the legacy split, and the malformed-row counter of 5.4 exists as an alarm that should never ring.
- **Non-zero in the source, quotes present.** Set `quoteChar="` for that feed and the parse becomes
  correct. Past deliveries are still missing those documents — check 2 says how many.
- **Non-zero in the source, no quotes.** The rows are genuinely ambiguous and no parser can recover
  them. The fix belongs upstream in the AS/400 extraction. The executor counts and reports them; it
  must not guess. This is also the case where **past deliveries are incomplete**, and that is an
  archiving finding, not a software one.

### 3.1b Decided: a malformed row fails the run — and therefore the check runs FIRST

`onMalformedRow` defaults to **`FAIL`**. A row whose field count differs from the header stops the
run; `SKIP` (count, log, report, carry on) remains available per feed.

Stopping mid-file would leave a mess that nothing can tidy: by then some batches have already been
closed and renamed to their final deliverable names, so the output directory holds a partial set with
no marker saying so, and the failing input has no `.done` while its neighbours do. Re-running would
then re-deliver what already went out.

So the check does not run mid-file. **Every input file in `inputDir` is scanned for field-count
mismatches before any output is written at all.** Only if the whole set is clean does processing
begin. The consequences are worth having:

- a bad row anywhere means **nothing is written and nothing is renamed** — the run is refused, not
  half-done, and re-running after the source is corrected processes the complete set;
- the scan reports **every** offending line across **every** file in one message, so the correction
  is done once rather than discovered one failure at a time;
- it costs a second read of the CSVs only. A 3 MB source scanned twice is nothing beside the hundreds
  of megabytes of Base64 the run is about to write, and the scan touches no content file.

**The scan also verifies that every referenced content file exists** (decided), and a missing file
stops the run on the same terms as a malformed row. This is the second deliberate exception to
conservative defaults in this executor and is called out as such: today a missing content file is
counted, logged and skipped, so the feed is **delivered short**; after this change it stops before
anything is written. The counter `documentsSkippedFileMissing` therefore stays in the result but can
only ever be non-zero under `onMalformedRow=SKIP`.

Two practical notes. The scan needs the content path per row, so the tag mapping and
`<family>.documentPath` are resolved during the scan exactly as during the real pass — the same
resolution, not a second copy of it. And the cost is one `exists()` per document: on a UNC or mapped
drive that is a round trip each, so a large feed pauses briefly before writing anything. It is worth
it, because the run is about to read every one of those files in full anyway, and knowing at the
start that 300 documents of 5000 are missing is a different thing from discovering it twenty minutes
in with INDX files already delivered.

The scan must use the **same charset and the same parse** as the real pass — including `quoteChar`
when enabled, where the field count is computed by the quote-aware reader and not by counting
separators. A pre-scan that disagreed with the reader that follows it would be worse than none.

**This is a deliberate exception to conservative defaults, confirmed.** Section 1 of the development
prompt requires such exceptions to be called out: today a mismatched row is dropped in silence and
the feed is delivered short; after this change the same feed stops instead. That is the point — but
it means a feed carrying such rows *today* will fail on its first run under the new executor, and
§3.1 exists precisely to find out whether any does before that happens. If one does, the choice per
feed is between correcting the source and setting `onMalformedRow=SKIP`, which restores today's
behaviour with the difference that the loss is now counted and reported instead of invisible.

Until §3.1 is answered, this is safe either way: with no mismatched rows the check never fires, and
with mismatched rows it refuses to deliver an incomplete set — which is the outcome the legacy tool
should have had.

### 3.2 Q4, characters in the windows-1252 0x80–0x9F range — ANSWERED: none

Confirmed: no source data contains bytes in that range. The `REPORT` policy of §7 therefore cannot
fail on day one, and `outputCharset=ISO-8859-1` is free — it matches what the template declares and
what any consumer reading the declaration expects.

Two things follow, and both belong in `USAGE.md` rather than in anyone's memory.

This is a **measurement of today's data, not a property of the feed**. A curly quote or a euro sign
arriving in a metadata value next year will fail that document loudly, naming the tag and the code
point. That is the intended behaviour — the alternative is a silent `?` inside a legally archived
document — but it will look like a regression to whoever meets it first, so the message must be
self-explanatory and the escape hatch documented: `outputCharset=windows-1252` for that feed while
the source is corrected.

And the mismatch this closes was **latent, not harmless**. Delivered INDX files declare `ISO-8859-1`
while being written with the JVM platform default, which is windows-1252 on the target server. It has
never mattered precisely because the answer to this question is "none" — the two charsets agree on
every byte below 0x80 and on all accented Latin letters, and the Base64 payload is pure ASCII. The
fix is structural rather than a default value: the declaration is generated from `outputCharset`, so
the two cannot disagree again regardless of platform or locale.

### 3.3 Q2 and Q3 — recorded, not blocking

- `ELAR:ContentName` stays empty, reproducing current behaviour. Raise with the archiving team; the
  answer changes nothing in the code until it comes back.
- Same-day reruns: the executor **reports** how many INDX/PULL pairs already exist for the current
  Julian day in `outputDir` and puts the count in the result. It does not refuse. Refusing would
  break a legitimate re-run after a partial failure, which is exactly when a re-run is needed.

## 4. Three design contradictions found while specifying — all three CONFIRMED

**Decided:** (4.1) a content file changing between the passes fails the **batch**; (4.2) `WRITE_ALONE`
closes the current batch first; (4.3) the writer is **hand-rolled** (`WrappingXmlOut`), not
`XMLStreamWriter`, so the break-position rule is enforceable. The reasoning is kept below because it
is the justification the implementation has to preserve.

## 4. Three design contradictions found while specifying

These are consequences of the requirements meeting each other. Each needs a decision, and each has a
recommendation.

### 4.1 A content file that changes between the digest pass and the encode pass cannot fail *the document*

The digest must be written before the content (§2), and the content is streamed straight to the
output. By the time a size or mtime change is detected after the encode pass, `ELAR:HashValue` and
most of `ELAR:Content` are **already in the output stream**. There is nothing to retract.

**Recommendation: it fails the BATCH, not the document.** The temp file is closed and deleted, never
renamed, and the run stops with a message naming the file. This is the only outcome consistent with
atomic temp-then-rename, and an INDX whose digest does not match its own payload is precisely the
thing that must never be delivered. Detect it during the encode pass by counting bytes and comparing
against the size captured before the digest pass, so the failure is immediate rather than at the end.

### 4.2 `WRITE_ALONE` requires closing the current batch first (under `batchBy=BYTES` only)

"Roll over before writing" and "emit the oversize document alone" combine to: if the pending
document's estimate exceeds the whole budget **and** the current batch is non-empty, close the
current batch, then open a new one containing only that document, then close it too. Otherwise the
document is not alone. Stated so it is not discovered during implementation.

### 4.3 Line wrapping cannot be a dumb Writer under `XMLStreamWriter`

A `Writer` that counts characters and inserts a break every N cannot know whether it is inside markup
or inside a text node — which is exactly the legacy defect, restated. And a break inside a text node
**changes the value**: XML does not ignore whitespace inside element content.

The legacy tool broke blindly and got away with it for a reason worth recording: at 25 000 characters
per line and payloads of megabytes, essentially every break lands inside the Base64 of
`ELAR:Content`, where whitespace is ignored by any Base64 decoder. A metadata value straddling a
25 000-character boundary **would have been silently corrupted**. With `max_index_docs=100` and short
metadata the odds are low but not zero, and nothing would have flagged it.

**Recommendation: the wrapper is driven by the emitter, not by a stream underneath it.** A small
`WrappingXmlOut` owns the `BufferedWriter`, tracks the current column, and exposes exactly the
operations this document needs — `startElement`, `attribute`, `text`, `base64Chunk`, `endElement` —
so it knows at every moment whether a break is legal:

- between elements: always legal;
- inside a Base64 payload: legal at any multiple of 4;
- inside any other text node: **never**; if a value plus its tags cannot fit on one line, fail naming
  the tag rather than emit an over-length line or a corrupted value.

This also removes the need for `XMLStreamWriter` entirely. The document shape is fixed by the
template, the escaping rules are small and testable (`&`, `<`, `>` in text; plus `"` in attributes),
and hand-emission is what makes the break-position rule enforceable. **If you would rather keep
`XMLStreamWriter`, the wrapping requirement has to be relaxed to "breaks only between elements",
which makes a single long Base64 payload one enormous line** — that is the trade, and it is worth
naming before choosing.

## 5. Architecture

**Single pass, nothing accumulates.** Read a flat row → build its tag map → emit its block → discard.
The vertical intermediate is removed unconditionally; there is no parameter to restore it.
`skipPrefix` is kept at `out_` purely so leftover legacy intermediates in an input directory are
still ignored, and is documented as a compatibility measure.

**Template model — discovered per family, never assumed.** Confirmed by the feed owner: every family
has **its own INDX template with its own tags**. Only `CLICT@DT.INDX.xml` has been read, so nothing
about tag names, tag counts or nesting may be hardcoded, and nothing about the other families' shape
may be inferred from this one.

The model is therefore derived at step start, from the template itself:

1. Find the document container by **namespace and local name** — `DocumentDescriptors` in the IDMS
   namespace, itself read from the properties (`idms.namespace`), never a literal.
2. Require **exactly one element child**. That child is the per-document block, whatever it is
   called; `ELAR:Doc` is what this family happens to name it.
3. Everything before it is the prologue, everything after is the epilogue, both emitted verbatim.

If the container is absent, or holds zero or more than one element child, **fail at step start**
naming the template path, the container searched for, and what was found instead. Failing before any
output exists is cheap; discovering it mid-run is not.

Because the per-document block is emitted from the parsed template rather than from a hand-written
element list, per-family constants come through automatically — `ELAR:BU`, `ELAR:RecordOwnerLE`,
`ELAR:RecordOwnerCountryCode`, `ELAR:ChannelICTO`, `ELAR:DeliveryICTO` in this family, and whatever
the others carry. Only two tags are treated specially by name, and both come from configuration
rather than from a constant: the tag mapped to the content path, and the tag receiving the file
extension. **No family's tag name appears anywhere in the code.**

The PULL template is imported whole and `[INDEX_NAME]` substituted in every attribute value, as
legacy does.

**Per document.** Resolve the content path through `<family>.documentPath` (built from `familyType`,
never a literal). Digest pass → write the block, emitting template constants from the parsed model
and mapped values from the row → encode pass writing Base64 in chunks that are multiples of 3 bytes.
`ELAR:DSAK` receives the uppercase file extension.

**Batching — one trigger, selected, not two racing.** `batchBy` chooses the rule:

- `batchBy=DOCUMENTS` (**default**) — roll after `max_index_docs` documents, exactly as legacy.
  `maxBytesPerBatch` and `oversizeDocumentPolicy` are not read at all.
- `batchBy=BYTES` — roll when the next document would take the batch past `maxBytesPerBatch`,
  checked **before** the document is written using `ceil(size/3)*4` plus the wrapper's line
  separators plus tag overhead. `max_index_docs` is not read at all.

Julian day still rolls after `files_per_julian_date` pairs under either rule; that is a separate
axis and is unaffected.

**The ignored value must be announced.** Under `BYTES`, `max_index_docs` sits in every family's
properties file, visible to whoever opens it, and silently stops mattering. The step logs at start
which rule is active and which value is being ignored, by name and value:
`batching by BYTES at 200 MB; max_index_docs=100 from the properties file is NOT in effect`. A
setting that can be read but has no effect is worse than one that is absent.

Because only one trigger can fire, the per-batch trigger attribution of the earlier draft is gone:
`batchesRolledByDocCount` and `batchesRolledByByteBudget` collapse into `batchesWritten` plus the
`batchBy` value in the log. That instrumentation existed only to disambiguate two racing triggers.

`oversizeDocumentPolicy` applies **only** under `BYTES`, since under `DOCUMENTS` there is no budget
to exceed. Setting it under `DOCUMENTS` is accepted and ignored, and said so in the log.

**Atomicity.** Every INDX and PULL is written to a temp name and renamed only after a clean close.
A final name that already exists fails unless `overwriteExisting`. The `.done` rename of the input
happens only after every batch that input produced has reached its final name; a rename failure is
logged and non-fatal.

## 6. The defects, and what makes each impossible

- **Validator never ran** (three checks skipped because the vertical CSV has 3 columns and the code
  expected 4) → the checks are re-implemented against the flat row, behind `validate` (default
  `false`, see §8).
- **The reference-tag check could never pass** (`doc_id_reference` holds a CSV *column* name while
  the map is keyed by *ELAR tag* name) → the reference is resolved through `tagNameMapping` before
  lookup. `not_duplicated_tags_list` is already in ELAR names; the asymmetry is handled explicitly
  and stated in `USAGE.md`, not silently normalised.
- **OutOfMemoryError from three accumulations** → no document map, no DOM for output, no
  `ByteArrayOutputStream`, no `StringWriter`.
- **Every file read twice** → still two passes, but by necessity (digest before content) rather than
  by accident, and neither materialises the file.
- **Silent row loss on field-count mismatch** → detected by a pre-scan of every input file before
  any output exists, and the run is refused (`onMalformedRow=FAIL`, §3.1b). Under `SKIP` the rows are
  counted, logged with their line numbers and reported, but never dropped in silence.
- **Three inconsistent input encodings** → one `inputCharset`, explicit, with `onMalformedInput`.
- **NPE on a mismatched `familyType`** → one property resolver that throws naming the key and the
  properties file path.
- **Two hardcoded family identifiers** → a build check asserts no family literal appears in the new
  code, alongside the existing duplicate-function and literal-escape scans.
- **Two non-prefixed properties** → prefixed form takes precedence, bare key still works.
- **PII in logs** → line numbers and counters only. No record content, no customer-identifying path.
- **Skips invisible** → counted by category and reported (§9).
- **Truncated output indistinguishable from valid** → temp-then-rename.
- **Filename collisions across reruns** → same-day pair count reported (§3.3).
- **Lost trailing empty fields** → `split(sep, -1)`.
- **Unchecked single-shot `read()`** → the streaming encoder loops on the returned count; the build
  check for `String.getBytes()` without an argument covers the neighbouring mistake.
- **Charset contradiction** → §7.

## 7. Output charset

- `outputCharset` defaults to `ISO-8859-1`.
- **The XML declaration is generated from `outputCharset`**, never copied from the template. This is
  what closes the defect: the declaration and the bytes cannot diverge regardless of the setting, the
  platform, or the locale.
- The `CharsetEncoder` uses `CodingErrorAction.REPORT` on both malformed input and unmappable
  characters. A value that cannot be represented fails the document naming the tag and the code
  point. Silent `?` substitution must be impossible: it would place a corrupted value inside a
  legally archived document with nothing downstream to flag it.
- No `FileWriter`, no charset-less `OutputStreamWriter`, no argument-less `String.getBytes()`
  anywhere in the new code — added to the build checks.
- Input and output charsets are independent and separately configured. The source is windows-1252;
  the INDX is ISO-8859-1.

The failure surface of `REPORT` is smaller than it looks: the Base64 payload is pure ASCII, so only
metadata values can fail. §3.2 measures whether any currently do.

## 8. Parameters

`inputDir`, `outputDir`, `propertiesPath`, `familyType`, `indexTemplatePath`, `pullTemplatePath` are
required. Optional, with defaults: `inputCharset=UTF-8`, `outputCharset=ISO-8859-1`,
`onMalformedInput=FAIL`, `separator=;`, `listSeparator=,`, `skipPrefix=out_`,
`maxLineLength` from `max.line.length` else **`25000`** (see §8b), `batchBy=DOCUMENTS`,
`maxBytesPerBatch=200MB` (read only under `batchBy=BYTES`),
`oversizeDocumentPolicy=WRITE_ALONE` (read only under `batchBy=BYTES`),
`quoteChar` empty (disabled), `onMalformedRow=FAIL` (or `SKIP`), `validate=false`,
`renameProcessed=true`, `overwriteExisting=false`, `maxErrors=0` (no limit).

Batching parameters stay in the properties file, read through the resolver. The byte budget is the
only new one and is deliberately a step parameter so it is tunable from the designer.

Two notes carried from the codebase rather than the prompt:

- Step parameters are resolved **before** the step runs, so anything the step needs inside its own
  parameters must be seeded with `stepId`/`stepName`/`stepDir` — the same ordering defect already
  fixed for `${dataSource}`.
- The Julian segment is computed with `getDayOfYear()` and `String.format`, never a
  `DateTimeFormatter` pattern: `DD` is day-of-year in `java.time`, which is the trap already hit in
  the validate executor.

## 8b. The `maxLineLength` fallback — decided: 25000

**Decided: the default is 25000**, the figure in the development prompt, not the legacy fallback of
20000.

`CLICT@DT` sets `max.line.length=25000` explicitly, so that family is unaffected either way — its
properties file has always overridden the fallback. The decision only reaches families that do
**not** set the key, and for those it is a **deliberate exception to conservative defaults, the third
in this executor**: their INDX line breaks will fall at different offsets on the first run after
deploy.

The exception is defensible on the ground already established: `maxLineLength` is a **maximum, not an
exact width** (confirmed), and ELAR imposes no maximum INDX size, so longer lines are within what the
receiving system tolerates. Combined with the break-position rule of §6.4 — breaks only between
elements or at Base64 quad boundaries — no line break can land inside a value, which is a stronger
guarantee than the legacy blind chop offered at any width.

One command remains worth running, no longer to decide anything but to know who is affected:

```powershell
Select-String -Path "G:\...\config_*.properties" -Pattern "max\.line\.length"
```

Every properties file that appears is explicit and cannot change. Any file that does **not** appear
is a family whose line breaks move on deploy. If the list is complete, nothing changes anywhere and
this exception is theoretical.

## 9. Result surface

`filesProcessed`, `filesFailed`, `documentsWritten`, `documentsSkippedNoPath`,
`documentsSkippedFileMissing`, `rowsMalformed`, `tagsWritten`, `batchesWritten`,
`documentsOversize`, `bytesEmbedded`, `sameDayPairsFound`, and the list of output filenames.
`batchBy` is logged rather than counted, since with one trigger there is nothing to attribute.

## 10. Validation, when enabled

Three checks against the flat row: duplicate `doc_id`; duplicate value for each tag in
`not_duplicated_tags_list`; and **the document id present and non-empty**.

That third check is not the one this spec originally listed. The reference check — `doc_id` equal to
the value of the reference tag resolved through `tagNameMapping` — becomes a **tautology** on a flat
row: the document id *is* the value of the column that maps to that tag, so the two sides are the
same value by construction and the check can never fail. **A green check that cannot go red is worse
than no check**, because it reports confidence it does not have. It is replaced by the invariant that
survives the translation and can still fail on real data. The first two need whole-file visibility but only over small keys — a `Set` of ids
and a `Set` of `tag=value` — bounded by document count and not by content. **State the bound in
`USAGE.md`**: at a few thousand documents it is nothing; at tens of millions it is not, and that is
the point at which this needs revisiting.

Off by default, because these checks have never executed on any delivered feed and enabling them may
reject data that is already archived. Turning the default on is a separate decision, after a dry run.

## 11. Batch plan

1. Flat CSV reading: charset, mapping resolved once per file, property resolver, malformed-row
   counting. Testable without XML.
2. `WrappingXmlOut` and the template model: prologue/block/epilogue, escaping, break positions,
   temp-then-rename.
3. Streaming Base64 and SHA-256, skip accounting.
4. Batching, size estimation, oversize policy, Julian/clock, filename patterns, INDX/PULL pairing.
5. Validation behind `validate`.
6. Registration in the four locations (parser whitelist **and** its error message, parser `internal`
   set, `WorkflowEngine.internalKind()`, `InternalSteps` dispatch), plus designer `buildXml` if
   exposed — a new child element needs **five** places, not four.
7. `USAGE.md` and the equivalence script.

## 12. Definition of done

As listed in the prompt, plus two conditions that follow from §4:

- A content file modified between the digest and encode passes **aborts the batch**, leaves no file
  under a final name, and names the file.
- A field-count mismatch **or a missing content file** in any input file leaves the output directory
  untouched and no input renamed to `.done`, and the message names every offending file and line —
  not just the first.
- A template whose document container is missing, empty, or holds more than one element child fails
  at step start naming the template and what was found, before any output exists.
- The pre-scan and the reading pass agree on field counts for the same file under the same
  `separator`, `quoteChar` and `inputCharset`; a file that passes the scan cannot fail the read.
- The equivalence run uses `batchBy=DOCUMENTS`, which is the default and the only rule legacy had.
  Under `BYTES` the batch-distribution comparison against legacy is meaningless by construction.
  Simpler than the earlier draft's "set the budget high enough", and it cannot be got wrong.

Equivalence is **semantic, not byte-identical** — the filename clock, the 20000→25000 default, safe
break positions and corrected escaping all differ by design. The comparison strips line breaks,
re-parses both, and compares: the `doc_id` set; per document the emitted tags and values including
template constants and the `BU` override; per document the digest and the decoded `ELAR:Content`
bytes against the source file; the document, batch and distribution counts; and the PULL structurally
with the index name substituted.

## 12b. Note on the `sql` executor precedent

The decision above was requested on the grounds that the CSV split conditions in the SQL extraction
executor are alternatives. **They are not.** `CsvWriter:73-76` rolls when
`(maxRows > 0 && rowsInPart >= maxRows) || (countBytes && bytesInPart + rb > maxBytes)` — an OR, so
both may be set and whichever hits first wins. The designer offers the two as independent fields,
each "0 = no split", and `InternalSteps:2056` even logs `"N rows/part or M MB/part"` when both are
present.

The decision stands on its own merits regardless: one trigger removes the "which one fired?" question
before it can be asked, and ELAR imposes no maximum INDX size, so the byte budget is an operational
convenience rather than a constraint that must always be armed. But it is a **divergence** from the
`sql` executor, not a matching of it, and that is worth knowing when the two are read side by side.

If the intent is uniformity rather than exclusivity, the alternative is to leave `elarxml` OR-ing as
`sql` does and keep the two rollover counters — say which, and this section becomes the record of
that choice instead.

## 13. Batch 0 is complete — batch 1 may start

Every blocking question is answered and every design contradiction is decided:

- no value contains the field separator; `quoteChar` stays disabled, and the pre-scan makes the
  design safe if that assertion is ever wrong;
- a malformed row **or a missing content file** fails the run, checked across every input file before
  any output exists;
- the writer is hand-rolled, so the break-position rule is enforceable;
- a content file changing between passes fails the **batch**;
- `WRITE_ALONE` closes the current batch first;
- batching is one selected rule, `batchBy=DOCUMENTS` by default;
- templates differ per family, so the document block is **discovered**, never assumed;
- `maxLineLength` defaults to 25000;
- no source data contains 0x80–0x9F, so `outputCharset=ISO-8859-1` with `REPORT` is free today.

**Three deliberate exceptions to conservative defaults** are recorded, as §1 of the development
prompt requires. Each changes the behaviour of a feed that works today:

1. a malformed row stops the run instead of being dropped in silence (§3.1b);
2. a missing content file stops the run instead of being skipped and the feed delivered short
   (§3.1b);
3. a family without `max.line.length` gets 25000 instead of 20000, moving its line breaks (§8b).

Exceptions 1 and 2 are the point of the rewrite. Exception 3 is a convenience, and the one command in
§8b says whether it touches anything at all.

### Batch 1 — DELIVERED

New package `com.legalarchive.orchestrator.elar`, free of Spring and of the orchestrator's own types
so it compiles and runs on its own:

- **`ElarConfig`** — properties loading and family-prefixed resolution. Every lookup names the key and
  the file when it fails, and a `familyType` matching nothing is caught at load and answered with the
  families the file *does* contain. `max.line.length`, `elar.namespace` and `idms.namespace` accept
  the bare key, with a prefixed form taking precedence. `docIdTag()` performs the column→tag
  translation the legacy `Validator` never did. `contentTag`/`dsakTag` are configurable per family,
  defaulting to this family's names so nothing changes for an existing properties file.
- **`FlatCsvReader`** — one explicit charset with `REPORT` by default, `split(sep, -1)` so trailing
  empty fields survive, optional quoting, BOM stripped from the header, physical line numbers, and a
  decoder failure rewritten into a message naming the file, the line, the approximate byte offset and
  both ways out.
- **`ElarPreScan`** — the blocking scan of §3.1b across every input file, reporting every offending
  line in one message, capped at 50 listed per category with the full count kept. It reuses
  `FlatCsvReader`, which is what makes "a file that passes the scan cannot fail the read" true by
  construction rather than by intention. `resolveContentFile` re-roots on the file name only, as
  legacy did, which also means a traversal cannot escape `documentPath`.

### Batch 2 — DELIVERED

- **`WrappingXmlOut`** — the writer, and the owner of where a line may break. Emitter-driven, so it
  knows at every moment whether a break is legal: between elements and between attributes always,
  inside a Base64 payload at any multiple of 4, inside any other text node never. A value that cannot
  fit a line of its own fails naming the tag rather than being split or letting the line over-run.
  The XML declaration is **generated from the output charset**, and a value that cannot be represented
  fails naming the tag and the code point — `canEncode` is checked per value so the message can name
  the tag, with `REPORT` on the writer as the backstop.
- **`IndxTemplate`** — the model, discovered rather than assumed: container by namespace and local
  name from the properties, exactly one element child as the per-document block, everything else
  emitted verbatim. Any other shape fails at parse naming what was looked for and what was found.
  `unknownMappedTags()` reports a mapping naming a tag the template does not contain, which would
  otherwise be a silent no-op.
- **`AtomicOutput`** — `.part` then rename, in the same directory so the rename stays on one volume.
  An existing final name is refused unless `overwriteExisting`; `close()` aborts unless `commit()`
  succeeded, so every failure path leaves no deliverable.

### Batch 3 — DELIVERED

- **`ContentEmbedder`** — the two passes. `sha256Hex` streams the file through `MessageDigest` in
  blocks; `encodeBase64` streams Base64 straight to the writer in chunks that are a whole number of
  3-byte groups, so every full chunk yields complete quads with no padding and no state carried
  between chunks — padding appears once, on the final partial group, which is where Base64 puts it.
  Neither pass holds the file. The digest is over the **raw bytes**, the only convention ever
  executed. `fill()` loops on the returned count, which is the defect the prototype encoder had:
  a single unchecked `read` left the tail as zeros and Base64-encoded them silently.
- **The change-between-passes guard**, per §4.1: size and modification time are captured before the
  digest, the encoded byte count is compared against them **during** the pass so the failure is
  immediate, and a `ContentChangedException` tells the caller to discard the whole batch — by then
  the hash and most of the payload are already in the stream and cannot be retracted.
- **`encodedLength`** — `ceil(bytes/3)*4`, the estimate batch 4's byte budget will use.
- **`ElarCounters`** — skips counted by category and reported. The legacy tool printed
  `Skipping doc: file not found` to stdout with no counter, so a batch that had silently dropped half
  its documents looked exactly like a successful one. The summary states the skip counts **even when
  zero**: a line that only appears when something went wrong trains people not to look for it.

**Verified** by 28 assertions compiled with `--release 8`: the digest matching a one-shot digest of
the same bytes and **differing from the digest of the Base64 text**, the dead-code convention; the
known SHA-256 of an empty file; the streamed payload identical to a one-shot encode; **all twelve size
classes across the chunk boundary and both padding cases**; the wrapper still holding on a 70 KB file
with a 100-character limit — nothing over the limit, every payload line whole quads, payload
byte-identical once breaks are stripped; a file that grew and one that shrank between the passes both
caught, with the message naming the file and saying nothing is left deliverable; the size estimate
**exact** against the real encoder at nine sizes rather than approximate; and the counters, including
that the summary is counters only and can leak no record.

Rule scans clean, and `ByteArrayOutputStream` now appears **zero** times in the package — the buffer
that held each whole file before encoding is gone, not merely avoided.

### Batch 4 — DELIVERED

- **`BatchNaming`** — the synthetic clock and the Julian segment. The clock starts at
  `output.start_time` or the run's wall-clock time and advances **exactly sixty seconds per batch**;
  the day segment is `String.format("%02d%03d", year % 100, getDayOfYear())`, built arithmetically so
  a `DateTimeFormatter` pattern is never written at all — `ofPattern` now appears **nowhere** in the
  package, and the scan asserts it. The clock **wraps past midnight** rather than overflowing, and
  restarts when the Julian day rolls after `files_per_julian_date` pairs. `countSameDayPairs` reports
  how many pairs for today already sit in the output directory.
- **`BatchPolicy`** — one selected rule. `describe()` names the rule in force **and the ignored limit
  with its value**, because that limit sits in the family's properties file where anyone can read it.
  A rule constructed without its own limit is refused immediately. `WRITE_ALONE` closes the open batch
  first (§4.2); `FAIL` explains why the document can never be written and gives both ways out.
  `estimateDrifted` flags an estimator that has come loose — a budget built on a wrong estimate would
  roll over in the wrong place with nothing downstream to say so.
- **`PullTemplate`** — the manifest, with `[INDEX_NAME]` substituted in **every attribute of every
  element**, a superset of the legacy behaviour that cannot change any file it produced. The legacy
  hardcoded namespace inconsistency between the PULL and INDX templates is **preserved rather than
  tidied**: the receiving system has been accepting it, and this rewrite is not the place to discover
  whether it would accept anything else.

**Verified** by 61 assertions compiled with `--release 8`: the reported filename reproduced exactly
and its PULL sharing the counter by construction; the clock advancing sixty seconds and an hour after
sixty batches; the Julian segment at day 1, day 229, day 366 of a leap year and year 2000 — **with a
control assertion recording that `ofPattern("DD")` on 17 August yields 229**, the trap this avoids;
the day rolling only after the limit; the clock wrapping past midnight; `start_time` validation naming
the property for a short value, an impossible hour and letters; same-day pairs counted without
blocking a re-run; both batching rules including the boundary where a document lands exactly on the
limit; the oversize document closing the open batch first and `FAIL` explaining itself; a rule with no
limit refused at construction; and the PULL substituted everywhere with no placeholder surviving.

### Batch 5 — DELIVERED

**`ElarValidator`**, off by default. Three checks per row: duplicate document id, duplicate value for
each tag in `not_duplicated_tags_list`, and the document id present and non-empty — the replacement
for the reference check, for the reason in §10.

Two reporting decisions worth stating. A **duplicate document id names the id**, because it is a
document identifier and is what an operator needs to find the row — the same class of value the
pre-scan already reports for a missing content file. A **duplicate value on any other tag names the
tag and the line numbers but never the value**, because an arbitrary tag can carry anything, and tag
plus line number is enough to act on. Findings are data, not errors: nothing here throws, including on
a null row.

**Verified** by 30 assertions compiled with `--release 8`: a clean file *saying so* rather than
staying silent; the duplicate id flagged on the **second** occurrence with the id named; an empty,
absent or whitespace-only id caught and *not* also counted as a duplicate; duplicate tag values naming
the tag but not the value; absent and blank not counting as duplicates of each other; values compared
trimmed; the listing capped at twenty with the count uncapped; the message saying *no
`not_duplicated_tags_list` configured* rather than looking as though it passed; a repeated tag in the
list collapsed; and a null row handled.

Two assertions failed on the first run and **both were mine, not the code's**: the test helper put the
id tag into the unique list as well, so two checks legitimately fired where the assertion expected one.
Isolated, with the two-checks case kept as its own deliberate assertion.

### Batch 6 — DELIVERED

**`ElarRun`** — the run loop, still free of Spring and of the orchestrator's own types, so the whole
executor is exercised end to end **against real files in a test** rather than only on deploy. Pre-scan
every input, then stream each row: map, digest, embed, discard. Nothing accumulates.

`IndxTemplate` gained the streaming form it needed. The one-call `write(out, docs)` would have meant
building the list of documents in a batch first — which is exactly the accumulation this rewrite
exists to remove — so prologue, each document and epilogue are written separately and the caller
decides how many go between them.

**Registration in five places**, not four: the parser whitelist, **its error message**, the parser
`internal` set, `WorkflowEngine.internalKind()`, the `InternalSteps` dispatch, and the designer
dropdown. The message and the dropdown are the two that get forgotten — the `reportQuery` defect was
exactly a preview that disagreed with the writer.

`runElarXml` does two things only: translate parameters into options and counters back into run
variables. Every missing required parameter is named in **one** message; an operator configuring a new
feed should not have to run the step six times to be told six things.

**Verified** by 39 assertions across two harnesses. End to end against real files on disk: five
documents in three batches, filenames sixty seconds apart, each INDX parsing as XML with its
prologue, its constants, its mapped values and an uppercase DSAK; **the digest in the delivered INDX
matching the source file's raw bytes, and the embedded payload decoding back to that same digest**;
the PULL referencing its own INDX; the input renamed only after delivery; a missing content file
refusing the run with the output directory untouched and the input unrenamed; batching by BYTES with
the ignored `max_index_docs` named in the log; an oversize document alone in its batch; validate off
by default and reporting when on; an empty input directory saying so; leftover `out_*` intermediates
ignored; an existing final name refused with no PULL and no `.part` left behind; a mapped tag missing
from the template warned about; and **no row content anywhere in the log**. Separately, `runElarXml`
extracted against stubs of the real signatures: all six missing parameters in one message, a partial
configuration naming only what is missing, and a failure inside the run reported rather than thrown at
the engine.

**A test of mine caught a real behaviour I had not pinned.** With `output.start_time` set explicitly,
the synthetic clock restarts at the same value, so a same-day re-run produces **colliding filenames**
and the run is refused on its first batch rather than replacing a delivered file. With `start_time`
unset it takes the wall clock, so a re-run produces new names and duplicates accumulate — which is
what the warning covers. Both are now pinned as separate assertions rather than one assumption.

**NOT compiled**: `InternalSteps`, `WorkflowXmlParser`, `WorkflowEngine` and `designer.html` need the
full Spring tree from the internal Nexus, which the sandbox cannot reach. They were checked
structurally — brace and parenthesis balance, every helper and field verified against its real
declaration (`xStr`, `blankToNull`, `VarResolver.resolve`, `Result.outVars`, `Result.exitCode`), no
name collision on the two new helpers, and the designer line free of literal `\n`, `[[` and `[(`.
**`mvn clean package` is the gate before this is deployed.**

### Batch 7 — DELIVERED. The executor is complete.

**`USAGE.md`** gained a full `elarxml` section: the parameters, the pre-scan and why the step may
refuse to start, the one-rule batching, the filename clock and what a re-run does under each
`start_time` setting, encoding and the `windows-1252` escape hatch, line breaks, validation and why it
is off, what the step reports, the three deliberate changes from the legacy tool, and how to run the
comparison. Written to the renderer's rules — one source line per paragraph, one per bullet, no
markdown tables — and **verified by running `docs.html`'s own `render()` against the real file**: no
raw markdown leaks outside code blocks, and of the 32 paragraphs in the new section, **zero** are
sentence fragments, which is what a wrapped source line would produce.

**`ElarEquivalence`** compares a legacy output directory against a new one, semantically, and runs as
a `main` so it can be pointed at real directories on the server. It strips line breaks from both sides
and re-parses each, then compares the document-id set, each document's tags and values including the
template constants, the document count, the batch count and the distribution across batches, and the
PULLs structurally with attribute order and whitespace normalised away.

The decision worth stating: **each payload is checked against the source file itself, not against the
other side.** Comparing the two outputs to each other would pass any mistake they share, which is
exactly the class of mistake a rewrite is most likely to inherit.

**Verified** by 24 assertions driving the comparator against hand-built legacy-shaped output: identical
content with blind 97-character breaks on one side and none on the other reported as equivalent; a
changed metadata value caught with the tag and document named but **not the value**; a missing document
caught and named; a different batch distribution caught, showing both shapes and naming the rule to
re-run with; **a wrong payload that both sides share still caught**, because the source file is the
reference; attribute order and whitespace normalised away; a genuinely different PULL caught; `&amp;`
and `&#38;` treated as the same value; and an accented id comparing equal, proving each file is read
with the charset it declares.

**A real defect in the comparator was caught by these tests.** `docBlocks` descended to the element
containing the id tag — which is the id tag itself, since every ancestor contains it too — so every
document appeared to carry exactly one field and both the metadata and payload comparisons silently
passed. It now walks **up** from each id element while the ancestor still holds exactly one, stopping
at the container. A comparator that reports equivalence because it is looking at nothing is the worst
possible failure for this particular tool, and it took writing the "both sides share a mistake" case
to find it.

### The seven batches

Spec and answers, flat CSV reader and pre-scan, streaming writer and template model, streaming Base64
and SHA-256, naming and batching, the run loop and registration, documentation and equivalence. The
remaining gate is `mvn clean package`, which the sandbox cannot run, and a first comparison against a
real feed.

**Verified** by 46 assertions compiled with `--release 8`: the declaration following the charset
parameter rather than the template; template constants surviving while a row value overrides, `BU`
included; two documents inside the container; escaping in text and in attributes; **no line over the
maximum, every payload line a whole number of quads, no line ending inside a tag, and the payload
byte-identical once the breaks are stripped**; the over-long value refused by name; the euro sign
refused in ISO-8859-1 and accepted under the documented `windows-1252` escape hatch; three malformed
templates refused with actionable messages; a family with entirely different tag names working
unchanged; and the atomic file, including that an aborted batch leaves neither a deliverable nor a
temp. End to end the file is re-read with its declared charset and the accented byte on disk is
**0xE9** — the declaration is proven against the bytes rather than asserted.

Rule scans clean on the new code with comments excluded, and now also asserting the **absence** of
`StringWriter`, `ByteArrayOutputStream` and `Transformer`/`DOMSource`: the three vehicles of the
legacy OutOfMemoryError are gone by construction rather than by discipline.

**Verified** by 62 assertions compiled with `-source 8`, covering the production NPE and its
replacement message, both unprefixed-key paths and their precedence, the doc-id translation and its
failure, the charset failure and the `REPLACE` escape, trailing empty fields against the legacy
`split` for comparison, the BOM, quoting on and off over the same line, and the pre-scan across
several files including the capped listing, the "malformed row is not also counted missing" rule and
the traversal case. The rule scans run clean on the new package with comments excluded: no family
literal, no charset-less `getBytes()`, no `FileWriter`/`FileReader`, no platform-default
`OutputStreamWriter`, no `ofPattern` containing `DD`.

**Verified** by 46 assertions compiled with `--release 8`: the declaration following the charset
parameter rather than the template; template constants surviving while a row value overrides, `BU`
included; two documents inside the container; escaping in text and in attributes; **no line over the
maximum, every payload line a whole number of quads, no line ending inside a tag, and the payload
byte-identical once the breaks are stripped**; the over-long value refused by name; the euro sign
refused in ISO-8859-1 and accepted under the documented `windows-1252` escape hatch; three malformed
templates refused with actionable messages; a family with entirely different tag names working
unchanged; and the atomic file, including that an aborted batch leaves neither a deliverable nor a
temp. End to end the file is re-read with its declared charset and the accented byte on disk is
**0xE9** — the declaration is proven against the bytes rather than asserted.

Rule scans clean on the new code with comments excluded, and now also asserting the **absence** of
`StringWriter`, `ByteArrayOutputStream` and `Transformer`/`DOMSource`: the three vehicles of the
legacy OutOfMemoryError are gone by construction rather than by discipline.

**Verified** by 62 assertions compiled with `-source 8`, covering the production NPE and its
replacement message, both unprefixed-key paths and their precedence, the doc-id translation and its
failure, the charset failure and the `REPLACE` escape, trailing empty fields against the legacy
`split` for comparison, the BOM, quoting on and off over the same line, and the pre-scan across
several files including the capped listing, the "malformed row is not also counted missing" rule and
the traversal case. The rule scans run clean on the new package with comments excluded: no family
literal, no charset-less `getBytes()`, no `FileWriter`/`FileReader`, no platform-default
`OutputStreamWriter`, no `ofPattern` containing `DD`.
