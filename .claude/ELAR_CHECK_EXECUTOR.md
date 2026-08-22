# ELAR INDX checker (`elarcheck`) — specification

Status: **Batch 0 — specification only. No implementation.**

Inspects delivered ELAR INDX files and reports every defect that has caused a real rejection, before
the files are sent. Read-only by construction. Self-contained: everything needed to implement is here.

---

## 1. Why this is worth its runtime

ELAR validates an INDX **in full and rejects it in full**: one bad character produces
`VALIDATION FAILURE` with `COUNT OF BUSINESS RECORDS: 0`, so eighteen hundred good documents are lost
because of one. The cost of a rejection is a whole regeneration and redelivery cycle. A pre-flight
check that takes tens of seconds pays for itself the first time it prevents one.

It is also built **before** the generator on purpose. It becomes the acceptance harness: the decisive
criterion for `elarxml` is that a regenerated file passes every check here while the original does
not. A regeneration that reproduces the original's defects has reproduced the wrong thing.

## 2. Read-only, and why that is a design property rather than a promise

The executor must never modify, rename or delete anything in the directory it inspects, and must not
write a temporary file there. That is what makes it safe to run against a live delivery folder at any
moment, including while something else is writing to it.

Made verifiable rather than asserted: **no write API may appear in the `elarcheck` package** — no
`FileOutputStream`, `Files.write`, `Files.delete`, `File.renameTo`, `File.delete`, `File.createNewFile`,
`FileWriter`. A build scan asserts their absence, the same way the `elar` package already asserts the
absence of `StringWriter`, `ByteArrayOutputStream` and family literals. The findings file is written
to the **step directory**, never to `inputDir`.

## 3. The reference scripts — READ

The five scripts live in `github.com/projectsrl-git/htmlviewers`, not in this repository. They were
read; the three points the first draft of this spec listed as open are answered from the code, and two
of the answers differ from what the descriptions alone implied.

**Line numbering** (`Repair-ElarIndxLineBreaks.ps1:281-282`). `$lineNo` starts at 0 and is incremented
before the line is processed, so the first physical line is **1**, and the XML declaration line is
counted like any other. But the number *reported* depends on the finding:

- a line break is reported at **`$lineNo - 1`** (`:311`) — the line where the break *starts*, which is
  the one that has to be repaired, not the one the parser was reading when it noticed;
- `InvalidSpaceAfterAngle` is reported at **`$lineNo`** (`:347`), the line the defect is on.

Reporting both at the current line would put every break one line late, and the definition of done
requires matching what the scripts and ELAR reported.

**Record numbering** (`:333`). `$docCount` accumulates `<Doc` starts across the **whole file** and never
restarts, so it is the count of documents *begun so far*: a finding inside the first document reports
1, and a finding before any document reports **0**. Note the order in the loop — the break checks run
against the count *before* the current line's `<Doc` starts are added, the bad-angle check *after*. Two
findings on the same physical line can therefore legitimately carry record numbers differing by one.

**The length threshold** (`Test-ElarLineLength.ps1:7,37-38,103`). 25000 is the default and "the agreed
target"; **30000 is what the receiver enforces**, horizontally, by truncation. So 25000 is a margin and
not the limit, which is worth saying in the message: a line at 27000 is over target but would still
arrive. The script also derives its re-wrap chunk as `floor(MaxLength / 4) * 4` (`:140`) — whole Base64
quads — which is the same rule `WrappingXmlOut` already enforces in the generator.

**A distinction the descriptions did not carry** (`:302-304`). The script separates
`MarkupLineBreak` from `TextLineBreak` by whether the previous line ended *inside a tag*. The two need
different repairs, and the script's comment records a defect it caused itself: rejoining a markup break
by inserting a space is right only when the break separated two attributes, i.e. straight after a
closing quote — anywhere else, and after `<` in particular, the space produces `< ELAR:TaxCode>`, which
is exactly the invalid element start that check 5.2 exists to find. **This checker reports the two
kinds separately for the same reason**, even though it never repairs anything.

**How the payload is excluded** (`:400-401`). The script tracks the current element while scanning and
sets a suppression flag when its **local name** equals `ContentElement`, prefix ignored. That is the
mechanism to port: local-name matching, carried across lines, rather than a guess based on line length
or content.

## 4. What the `elarxml` work already established about these files

Recorded so it is not rediscovered:

- Delivered INDX files **declare `ISO-8859-1` while the legacy writer emitted the JVM platform
  default**, which on the target server is `windows-1252`. The two agree below 0x80 and on accented
  Latin letters and differ over 0x80–0x9F. This is why `inputCharset` defaults to `windows-1252`
  rather than to the declaration: trusting the declaration would surface an encoding mismatch as a
  spurious structural error, which is the most misleading thing a checker can do.
- A scan of source data for bytes in the 0x80–0x9F range came back **empty**, so the mismatch has
  never been exercised. That is a measurement of today's data and not a property of the feed.
- The legacy writer chopped the serialized XML at blind character offsets, so a break can land
  anywhere — inside markup, inside a value, inside the payload. That is the origin of both 5.3 and
  5.4.
- The payload element and the digest element are **configurable per family**, because every family has
  its own template with its own tags. `contentElement` and `hashElement` are parameters here for the
  same reason.

## 5. The checks

Every finding carries: file, physical line, record number (the ordinal of the enclosing document
element, which is what ELAR reports back), element local name where applicable, and a short excerpt
that **never contains a field value**.

**5.1 Well-formedness.** Parse the whole file with StAX; on `XMLStreamException` report line and
column. The single most valuable check, because it catches every structural defect at once including
forms nobody anticipated — and the one that says nothing about correctness, which is 5.3's job.

**5.2 Whitespace after a tag opener.** `< Name`, `< /Name`, `</ Name`. Subsumed by 5.1, reported
separately because the message names the repair instead of describing a symptom.

**5.3 Line breaks inside character data.** The check that justifies the executor.
`<ELAR:DSAK>P` + break + `DF</ELAR:DSAK>` is **perfectly well-formed** and yields a value with a
newline in it: the file passes every structural check and the value is wrong. Report every element
except the payload whose text contains CR or LF.

Following the reference script, report **two kinds**: a break inside a value (`TextLineBreak`) and a
break inside markup (`MarkupLineBreak`, where the previous line ended inside a tag). They have
different repairs, and the script's own comment records that repairing a markup break carelessly —
inserting a space anywhere other than between two attributes — produces `< ELAR:TaxCode>`, the invalid
element start of 5.2. A checker that merged the two kinds would hide which repair applies.

The payload is excluded by tracking the current element's **local name** across lines and suppressing
while it equals `contentElement`, which is what the script does. Inside the payload a break is
whitespace, ignored by any Base64 decoder, and is the intended wrapping — reporting it would bury the
real findings under tens of thousands of false ones.

**5.4 Line length.** ELAR truncates horizontally beyond roughly **30000** characters, so a payload
emitted on one line loses its closing tag and the file is rejected with the content unterminated.
`maxLineLength` defaults to **25000**, which the reference script calls the agreed target and which
leaves margin under the receiver's limit — so the message must distinguish *over target* from *over
what the receiver accepts*, or a line at 27000 reads as fatal when it would still arrive.

Report the longest line per file and every line over the limit, **distinguishing lines that contain
payload from lines that do not**: the first are re-wrappable, the second need a data fix. Different
finding, different remedy.

**5.5 Mandatory single-occurrence elements.** ELAR requires certain elements exactly once per record.
Report **missing**, **duplicate** and **empty** as three distinct conditions, because they have three
different causes. An empty element serializes as `<ELAR:AccountID/>` and the receiver treats it as
absent, which is why it cannot be folded into "present".

Report the **proportion** per tag: a tag missing on nearly every record is a mapping problem — the
column is absent from `tagNameMapping` and the element is never emitted — while a tag missing on three
records is a data problem. Same finding, opposite investigation, and the proportion is what separates
them at a glance.

**5.6 Pair integrity.** For each INDX, the matching PULL must exist (same name with `INDX` replaced by
`PULL`) and must contain the INDX name at least once. A PULL that does not reference its INDX means
the pair is broken however good the INDX is.

**5.7 Payload integrity, behind `verifyHash` (default off).** Decode each payload in streaming and
compare the SHA-256 of the decoded bytes against the sibling digest element. The only check that
verifies the archived document matches its declared digest. Expensive — it decodes everything — hence
off by default.

## 6. Two design points that decide the shape

### 6.1 One physical read, or two — and why it is two

The checks split into two families that cannot share a parser:

- **Textual** (5.2, 5.4) need physical lines and must keep going after the document stops being
  well-formed, precisely because 5.2's whole purpose is to report *every* occurrence of a defect that
  makes StAX stop at the first.
- **Structural** (5.1, 5.3, 5.5, 5.7) need the parser, and StAX stops at the first fatal error.

Sharing one `Reader` between them does not work: StAX consumes and closes it, and a fatal error ends
the scan for both. **Two sequential passes over the file**, therefore, and the spec says so rather
than leaving it to be discovered: the cost is I/O, not memory, and on a 530 MB file it is tens of
seconds more. That is also why 7.4's progress reporting is a requirement and not a nicety — a step
that reads half a gigabyte twice must be visibly distinguishable from a hung one.

The textual pass runs **first**. When it finds a defect that guarantees rejection, the structural pass
still runs, because a file can be both malformed and corrupted and the operator needs both lists in
one report rather than one per run.

### 6.2 StAX coalescing must be OFF, and 5.3 is written for that

`IS_COALESCING = true` gives each element's text as one string — convenient for 5.3, and fatal here:
a payload of tens of megabytes would be materialised as a single `String`, which is the exact
accumulation that killed the legacy generator and what the 256 MB heap requirement forbids.

So coalescing stays **off**, and StAX may report one element's text as several `CHARACTERS` events.
5.3 is written for that: it tests **each fragment** for CR or LF rather than assembling the value.
This is not a workaround — a break inside a value shows up in whichever fragment carries it, so
fragment-wise testing is exactly as sensitive and needs no memory. The element's text is never
assembled at any point, for any check.

The payload element is skipped fragment by fragment, by local name, so its text never accumulates
either. With `verifyHash` on, its fragments feed the Base64 decoder and the digest directly and are
discarded.

## 7. Parameters

Required: `inputDir`.

Optional, with defaults: `filePattern=*INDX*`, `inputCharset=windows-1252` (see §4),
`maxLineLength=25000`, `contentElement=Content`, `hashElement=HashValue`, `docElement=Doc`,
`mandatoryTags` (empty), `checkPull=true`, `verifyHash=false`, `maxFindingsPerFile=100`,
`failOnFindings=false`.

Element parameters are matched on **local name**, ignoring the prefix: a family that binds the same
namespace to a different prefix must not need a different configuration.

`${...}` resolution consistent with the other executors, and anything the step needs inside its own
parameters must be seeded alongside `stepId`/`stepName`/`stepDir` **before** resolution — the ordering
defect already fixed once for `${dataSource}`.

## 8. Output

**8.1 Counters into `run.vars`**: `filesScanned`, `filesWellFormed`, `filesRejectedLikely`,
`documentsTotal`, `whitespaceAfterAngle`, `valueLineBreaks`, `linesOverLimit`, `longestLine`,
`tagsMissing`, `tagsDuplicate`, `tagsEmpty`, `pullMissing`, `pullUnreferenced`, `hashMismatches`.

Counters are **exact even when the findings list is capped** by `maxFindingsPerFile`. A capped list
with an exact count tells the operator both what to fix and how big the problem is; a capped count
would quietly understate it.

**8.2 A findings file** in the step directory, one record per finding, in a format the existing viewer
renders. **No field value in any excerpt**: these files carry customer names, tax codes and account
identifiers. Element names, positions and counts only — the same rule the `elarxml` pre-scan follows,
where a malformed row is reported by line number and never by content.

**8.3 A per-file verdict**, because that is the decision the operator actually needs:

- `OK` — nothing found.
- `CORRUPTED` — well-formed but wrong. **This is the verdict that matters**: the file will be accepted
  by ELAR and archived with a wrong value in it. Nothing downstream will ever flag it.
- `MALFORMED` — would be rejected. Expensive, but self-announcing.

A file can be both; it is reported as `MALFORMED`, with the corruption findings listed, since it has
to be regenerated either way.

**8.4 Progress on the step log** every few seconds: bytes read, percentage, rate, and which pass. Two
passes over 530 MB take long enough that silence is indistinguishable from a hang.

## 9. Engine registration

Five places, not four: the parser `exec` whitelist, **the parser's error message**, the parser
`internal` set, `WorkflowEngine.internalKind()`, the `InternalSteps` dispatch, and — if exposed —
the designer dropdown with its `buildXml`. The message and the dropdown are the two that get
forgotten; the `reportQuery` defect in this project was exactly a preview disagreeing with the writer.

JavaScript touched in the designer: no literal `\n` or `\r` (the corporate proxy rewrites them), no
Thymeleaf `[[ ]]` or `[( )]` outside comments. Any endpoint returns HTTP 200 with `ok:false` for
actionable conflicts, because IIS replaces non-2xx bodies.

## 10. Round-trip acceptance

An INDX that ELAR rejected has been reversed into its inputs with `Export-ElarIndxCsv.ps1`: a flat
CSV, the decoded content files and an identity-mapping properties file. Feeding those to `elarxml` and
comparing gives the acceptance test for the generator.

The comparison is **semantic, not byte-identical** — the filename counter derives from wall-clock time,
the wrapping default changed, breaks now fall at safe boundaries, and correct escaping differs from
the original wherever the original was wrong. Compare the record-id set with the same cardinality, the
elements and values per record after stripping wrapping, the decoded payload and digest per record,
and the document, batch and distribution counts.

The decisive criterion: **the regenerated file passes every check in §5 while the original does not.**

`ElarEquivalence`, delivered with `elarxml` batch 7, already performs the structural half of this
comparison and checks each payload against the **source file** rather than against the other side — so
a mistake both tools share is still caught. This executor supplies the other half: the verdict.

## 11. Batch plan

1. Streaming reader, file enumeration, charset, record numbering, progress. Checks 5.1 and 5.2.
2. Checks 5.3 and 5.4, with the payload/non-payload distinction.
3. Check 5.5, with missing, duplicate and empty distinguished and the proportion reported.
4. Check 5.6, and 5.7 behind its flag.
5. Findings file, counters, per-file verdict.
6. Registration in the five places.
7. `USAGE.md`.

`USAGE.md` note: the `docs.html` renderer makes every source line its own paragraph. One long line per
paragraph, one per bullet; fenced code blocks render as-is; markdown tables do not render — use
bullets.

## 12. Definition of done

- A 530 MB INDX is inspected with `-Xmx256m`, peak heap flat and independent of file size.
- The executor never writes to, renames or deletes anything in `inputDir`, and no write API appears in
  the package.
- On the known-bad file the reported defects, line numbers and record numbers match both the
  PowerShell scripts and what ELAR reported.
- On a file whose only defect is a line break inside a value: verdict `CORRUPTED`, well-formedness
  passes. **The distinction that matters most, and the one a naive checker gets wrong.**
- No log line and no findings record contains a field value.
- A mixed directory produces a verdict per file and counters that add up.
- Counters reach `run.vars` and can drive a conditional step, so a workflow can regenerate only when
  the check fails.
- The findings list is capped while the counters stay exact.

## 13. Before batch 1

Nothing. The three open points are answered in §3 from the scripts themselves, and the scripts are at
`github.com/projectsrl-git/htmlviewers` for review during implementation.

One thing to have ready rather than to decide: **a known-bad INDX**, and if possible the ELAR report
that rejected it. The definition of done requires the reported line and record numbers to match what
ELAR reported, and that can only be checked against a real pair.
