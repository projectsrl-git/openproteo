# JSON to CSV executor (`json2csv`) — specification

Status: **Batch 0 — specification only. No implementation.**

Reads the JSON files matching a wildcard mask in a directory and writes ONE flat CSV whose shape is
the feed's **dataschema**, filling its columns from JSON attribute paths chosen against a **JSON
schema** (an uploaded sample, or a real JSON Schema). Nested objects and arrays are flattened: the
row count follows the cardinality of the innermost **mapped** array, and everything outside it is
repeated on each row it covers.

Self-contained: everything needed to implement is here.

---

## 1. The constraint that shapes the design

`elar` is free of Spring and compiles standalone with `javac --release 8`. That is what has let every
suite in that subsystem run the real code against real files in the sandbox, and it is what caught
the `.done` rename defect, the value-edge breaks and the split start tag. The same discipline is
wanted here, and here it costs something extra to get, because a JSON reader means Jackson.

**Maven Central is not reachable from the sandbox** (verified: `repo.maven.apache.org` answers 403).
A core that imported `com.fasterxml.jackson` could not be compiled here at all, and the flattening
rules — the part of this executor that is genuinely easy to get wrong — would ship unexercised.

So the package splits at the only place where the split is free:

- **The tree is `java.util` and nothing else.** `Map<String,Object>` for objects, `List<Object>` for
  arrays, `String` / `BigDecimal` / `Boolean` / `null` for scalars. This is precisely what
  `ObjectMapper.readValue(file, Object.class)` already returns — `InternalSteps.readSchemaColumnNames`
  reads the dataschema this way today — so the adapter is not a translation layer, it is one call.
- **The core takes that tree.** Path parsing, the array-chain analysis, row generation, and every
  column type are pure functions over `Map`/`List`/scalars. They compile with `javac --release 8`
  against the JDK alone and run in the sandbox against trees built by hand.
- **Jackson appears in exactly one class**, `JsonDocumentReader`, whose whole body is the read call,
  the `USE_BIG_DECIMAL_FOR_FLOATS` setting and the size guard of §9.3. That class is the part that
  cannot be exercised here, and the delivery will say so rather than imply otherwise.

`--release 8`, not `-source 8 -target 8`: only the former checks the API surface, and that is the
flag that catches a Java 9+ method before the user's build does.

## 2. Answered without asking

- **No new Maven dependency.** `spring-boot-starter-web` already brings `jackson-databind`, and three
  classes in the project already use `ObjectMapper`. Nothing goes to Nexus.
- **Splitting is already written.** `ds/CsvWriter` does UTF-8, CRLF, RFC-4180 quoting and
  split-by-rows / split-by-MB for `sql`, `csvsql` and `xlsx2csv`. Requirement 7 is satisfied by
  passing `step.csvSplitRows` / `csvSplitMb` into it, exactly as those three do. No new splitting code
  and, by construction, byte-identical output conventions.
- **The dataschema reader is already written.** `readSchemaColumnNames` accepts both the
  `[{"name":…}]` and `[{"ColumnName":…}]` dialects. `columnsSchema` is reused as the parameter name —
  it is what the `sql` executor already calls the path to a dataschema JSON.
- **The date format is a MASK, not a java.time pattern.** `recordBusinessDateFormat` carries forms
  like `YYYY/MM/DD`, where `DD` is day-of-month and not day-of-year. Feeding it to
  `DateTimeFormatter.ofPattern` is the defect recorded in CLAUDE.md under "the date MASK is not a
  java.time pattern", which silently broke `businessDateNotBefore` for the life of the product.
  `InternalSteps.fmtToJavaPattern` already translates it. **Date columns go through that translator,
  both for output and for input masks.** Reimplementing it here would reintroduce the bug.

## 3. Gate 0 — questions that gate batch 1

These are about the data, not about the code, and the sandbox cannot answer them. Batch 1 does not
start until they are answered here.

### Q1 — How large is the largest JSON file, and how many are there per run?

The reader of §1 holds one document in memory as a tree. A tree costs roughly 8–15× the file size in
heap. At a few MB per file that is free; at 500 MB it is not, and the design changes to a streaming
reader that is a different and much larger piece of work.

`maxFileMB` (§9.3) refuses a file over the limit instead of dying on it, so a wrong answer here fails
loudly rather than at 3 a.m. with an `OutOfMemoryError` in the middle of a batch. But the answer
decides whether the default is comfortable or whether streaming is needed from the start.

### Q2 — Is one file one document, or does a file hold an array of documents?

`documentsPath` (§5.2) covers both. The question is which one the real files are, because it decides
what `original_object_name` means when 10 000 documents share one filename.

### Q3 — Do two INDEPENDENT arrays ever need to be mapped at the same time?

`accounts[].iban` and `notes[].text` are siblings: neither contains the other. Flattening both into
one CSV is a cartesian product — 3 accounts and 4 notes give 12 rows, each account repeated 4 times
and each note 3 times, with no column saying so.

This is refused by default (§6.4). If the real mapping needs it, say so and the answer is `CROSS`,
per step, in the open. If it does not, the check is an alarm that never rings — which is what it is
meant to be.

### Q4 — What do dates look like INSIDE the JSON?

The output format is settled (`recordBusinessDateFormat`, requirement 6.3). The input is not. ISO-8601
`2026-08-24`, ISO with a time and a zone, epoch milliseconds and `24/08/2026` are four different
parses and the executor must not guess between them silently.

The design tries ISO-8601 then epoch milliseconds when no input mask is given, and takes an explicit
per-column mask otherwise. **A sample of the real values settles which default is right.**

### Q5 — MIMEType: the extension, or a real media type?

Requirement 6.1 gives the example `.json`, which is a file extension. ELAR's own table calls the
column MIMEType and wants `application/json`. Both are one line of code and they are not the same
value; a run that writes the wrong one is not detected by anything downstream.

And: the extension **of what**? The source JSON file, or a document named by an attribute inside it?
§7.4 supports both; the question is which the feed needs.

### Q6 — Should the dataschema's `nullable:false` be enforced?

The dataschema carries it and nothing in this product reads it today. `checkNullable` (§9.5) is
specified and **off**, so this changes nothing unless asked for. Worth a yes or no while the schema is
in front of us.

---

## 4. The executor

`json2csv`, an internal executor, registered in the six places §11 lists.

```xml
<step id="s20" exec="json2csv" csvSplitRows="100000">
  <param name="inputDir"      value="${landingIn}/json"/>
  <param name="filePattern"   value="*.json"/>
  <param name="csvFile"       value="${landingOut}/flat.csv"/>
  <param name="columnsSchema" value="${feedDir}/dataschema.json"/>
  <param name="jsonSchema"    value="${feedDir}/jsonschema.json"/>

  <column as="NDG"                  src="ndg"                            type="String"/>
  <column as="NOMINATIVO"           src="customer.name"                  type="String"/>
  <column as="DATA_KYC"             src="customer.kycDate"               type="Date" from="YYYY-MM-DD"/>
  <column as="IBAN"                 src="accounts[].iban"                type="String"/>
  <column as="IMPORTO"              src="accounts[].movements[].amount"  type="Number"/>
  <column as="PROGRESSIVO"                                               type="Serial"/>
  <column as="ORIGINAL_OBJECT_NAME"                                      type="ObjectName"/>
  <column as="MIME"                                                      type="MIMEType" mode="SOURCE_EXTENSION"/>
</step>
```

### 4.1 Why `<column>` and not one parameter per column

A dataschema of fifty columns becomes a hundred `<param>` entries, unordered, with the mapping and its
type in two places that nothing keeps together. `<column>` already exists — `xlsx2csv` uses
`<column src as>` — with a parser, a writer, a DTO and an add/remove list in the designer, all of which
this reuses.

`ColumnSel` gains four **optional** fields (`type`, `from`, `mode`, `value`). They are written only
when non-empty, so an `xlsx2csv` step round-trips byte-identically. **That is asserted, not assumed**:
batch 2 opens every workflow XML in the repo, writes it back and compares SHA-256 before touching
anything else.

`src` and `as` keep their `xlsx2csv` direction: `src` is where the value comes from, `as` is the
column it lands in.

## 5. Input

### 5.1 `inputDir` + `filePattern`

`filePattern` is a wildcard mask (`*.json`, `CUST_*.json`), `*` and `?` only, matched on the file name,
case-insensitive on Windows. Directories are not descended into; `.done` and non-matching files are
ignored.

**Files are processed in file-name order (`String.compareTo`, not locale-dependent).** Two runs over
the same directory then produce byte-identical CSVs, and `Serial` means something stable. A
`Files.list` order that happened to be sorted on one filesystem and not on another would make the
Serial column quietly non-reproducible.

Zero matching files is **not** an error: 0 rows, `${rowCount}=0`, the step succeeds. A feed with no
input that day is normal, and failing it would wake somebody for nothing.

### 5.2 `documentsPath` — what counts as one document

Empty (default): the file is one document, and the root is the tree.

Set to a path ending in `[]` (`data.records[]`): each element of that array is a document, and the
same file yields many. The path is resolved before any mapping; array markers inside `documentsPath`
do not take part in the flattening of §6.

`original_object_name` is the file name either way (§7.5) — under `documentsPath` many documents share
it, which is correct if it identifies the attachment and misleading if it is meant to be a key. Q2.

### 5.3 Charset

`inputCharset`, default **UTF-8**. JSON is UTF-8 by specification and Jackson auto-detects UTF-8/16/32
from the BOM, so the default is right and the parameter exists for a source that is neither.

## 6. Flattening — the rule, stated exactly

This is requirement 4 and it is the part worth being pedantic about.

### 6.1 Path syntax

Dot-separated keys, `[]` for an array level:

```
ndg
customer.name
accounts[].iban
accounts[].movements[].amount
tags[]                              a scalar array, the element itself
data['odd.key'].value               a key containing a dot or a bracket
```

Keys are matched **case-sensitively and exactly**, as JSON keys are. A key containing `.` or `[` is
written in the bracket-quoted form; the catalogue of §8 emits that form automatically when it meets
such a key, so it is never typed by hand.

### 6.2 Array prefixes

For a path `p`, `arrayPrefixes(p)` is every prefix of `p` ending in `[]`, shortest first.
`accounts[].movements[].amount` gives `accounts[]`, then `accounts[].movements[]`.

`S` is the union of `arrayPrefixes` over **the mapped columns that have a `src`**. Requirement 4's
"ovviamente se tali elementi sono mappati" is exactly this: an array nobody reads from does not
multiply rows. Dropping the mapping of `IMPORTO` in the §4 example takes the run from three rows per
customer to two, and that is the intended behaviour, not a side effect.

### 6.3 The chain

Order `S` by length. If every element is a prefix of the next, `S` is a **chain**
`a1 ⊂ a2 ⊂ … ⊂ ak` and rows are generated by nested iteration over it, outermost first.

A column whose deepest array prefix is `ai` reads at the current index of `ai`. A column with no array
prefix is constant for the document. **Repetition of the outer values is not a step — it is what
reading at the current index does.**

Worked, from §4:

```json
{ "ndg": "12345",
  "customer": { "name": "Rossi" },
  "accounts": [
    { "iban": "IT01", "movements": [ {"amount": 10}, {"amount": 20} ] },
    { "iban": "IT02", "movements": [ {"amount": 30} ] } ] }
```

```
NDG   ; NOMINATIVO ; IBAN ; IMPORTO
12345 ; Rossi      ; IT01 ; 10
12345 ; Rossi      ; IT01 ; 20
12345 ; Rossi      ; IT02 ; 30
```

### 6.4 Siblings are refused

If `S` holds two paths and neither is a prefix of the other — `accounts[]` and `notes[]` — the
combination is a cartesian product. `onSiblingArrays` defaults to **`FAIL`**.

The check is **static, on the mapping**, so it fires when the step starts, before a single file is
opened and before anything is written. It names both paths and the columns that introduced them.

`CROSS` is available per step and produces the product, iterating in declaration order. It is not the
default because the failure mode is silent: a run that should have written 400 rows writes 4 000, each
value repeated, and nothing in the output says which repetitions are real. Refusing costs a
configuration round trip; the product costs an archive full of duplicated documents that look
delivered.

### 6.5 An empty or absent array

`onEmptyArray` defaults to **`ONE_ROW`**: at each array level, an array that is empty, absent, or not
an array yields **one** iteration in which everything at or below that level is empty.

The rule is recursive, so in the §4 example an `IT03` account with no movements still gets its row,
with `IMPORTO` empty — a LEFT JOIN, not an INNER one.

`NO_ROWS` yields zero iterations, dropping the document from the output. It exists because for some
feeds a customer with no movements is not a record. It is not the default, because **the default must
not lose a document in silence**: `${documentsWithNoRows}` counts them either way, and under
`ONE_ROW` they are in the file where they can be seen.

### 6.6 A leaf that is not a scalar

A `src` resolving to an object or an array where a value is expected: `onNonScalar` defaults to
**`FAIL`**, naming the column, the path and the file. `EMPTY` writes nothing and counts it; `JSON`
writes the compact JSON text of the node.

A missing intermediate node is **not** this case — that is an absent value, which is empty and counted
in `${valuesMissing}`. The two have different causes: absent is data, non-scalar is a wrong mapping,
and a wrong mapping found on row one is worth a stop.

## 7. Column types (requirement 6)

`type` on `<column>`, default `String`. A column with no `src` and no type that supplies its own value
is an empty column, which is how an unmapped dataschema column is written.

### 7.1 String

The scalar as text. `true`/`false` become `true`/`false`; `null` is empty. A `value` attribute with no
`src` makes a constant column.

### 7.2 Number

The tree is read with `USE_BIG_DECIMAL_FOR_FLOATS`, so a JSON number arrives as `BigDecimal` and is
written with `toPlainString()`: `1.10` stays `1.10` (scale preserved), `1e3` becomes `1000` (no
exponent, ever, because a CSV consumer reading `1E3` as text is a support call).

The decimal separator is `.` and there is no grouping. Non-numeric text under `type="Number"` follows
`onNonScalar`. **`Number` is a validation as much as a format**: it is the difference between finding
a bad value here and finding it in ELAR.

### 7.3 Date (requirement 6.3)

Output is **always** `${recordBusinessDateFormat}`, translated by `InternalSteps.fmtToJavaPattern` —
see §2. There is no per-column output format: requirement 6.3 says the feed has one date format, and
offering a second place to set it would guarantee the two disagree.

Input: `from` on the column, in the same mask dialect, translated by the same function. With no `from`,
ISO-8601 is tried (date, then date-time with optional zone), then epoch milliseconds if the value is an
integer. A value that parses by none of these follows `onNonScalar`. Q4 decides whether that default
is the right one.

A `recordBusinessDateFormat` that is unset, or still contains `${`, is reported as an **undefined
variable** and fails the step — the wording the validate step already uses for the same condition, for
the same reason: it is a different problem from a bad mask and needs a different fix.

### 7.4 MIMEType (requirement 6.1)

`mode`:

- `FIXED` (default) — the literal in `value`, e.g. `.json` or `application/json`.
- `SOURCE_EXTENSION` — from the name of the JSON file being read, `report.json` → `.json`.
- `COLUMN_EXTENSION` — from the value of another column, named in `value`; that column must be
  declared **before** this one. Cross-references and forward references are refused at start-up, not
  resolved.

The extension **includes the dot** and is lower-cased; a name with no dot yields empty. Q5 decides
whether `.json` or `application/json` is what the feed wants — the executor writes what it is told and
has no opinion.

### 7.5 Serial (requirement 6.2)

The 1-based ordinal of the row **in the CSV the step produces**.

Two things it deliberately is not:

- It does **not** restart per input file. It is a row number in the output, and the output is one
  logical CSV.
- It does **not** restart per part when `csvSplitRows`/`csvSplitMb` split that CSV. Part 2 continues
  from where part 1 stopped, because the parts are one delivery and a Serial that restarted would give
  two rows the same number.

`serialStart` (default 1) and `serialPad` (default 0, no padding; 8 gives `00000001`) are step
parameters, not per-column: two Serial columns disagreeing about their width is not a feature.

### 7.6 ObjectName (requirement 5)

The name of the JSON file the row came from — the attachment identifier.

Requirement 5 says the column is "identified in the dataschema". Making it a **type** rather than a
parameter naming a column puts it in the same dropdown as everything else in requirement 6, next to
`Serial`, which is the other column whose value does not come from the JSON. It is the same mechanism
and it belongs in the same place.

`objectNameValue`, a step parameter: `FILENAME` (default), `FILENAME_NOEXT`, `RELATIVE_PATH` (to
`inputDir`), `ABSOLUTE_PATH`.

> If you would rather have `<param name="objectNameColumn" value="ORIGINAL_OBJECT_NAME"/>` instead,
> say so at this gate — both are the same work, and after batch 2 one of them is a migration.

## 8. The mapper (requirement 3)

A panel in `designer.html`, one row per **dataschema column**, in dataschema order:

```
CSV column (dataschema)      JSON attribute (json schema)          Type        Extra
---------------------------  ------------------------------------  ----------  -----------------
NDG                          [ ndg                            ▼ ]  [String ▼]
NOMINATIVO                   [ customer.name                  ▼ ]  [String ▼]
DATA_KYC                     [ customer.kycDate               ▼ ]  [Date   ▼]  from: YYYY-MM-DD
IBAN                         [ accounts[].iban                ▼ ]  [String ▼]
IMPORTO                      [ accounts[].movements[].amount  ▼ ]  [Number ▼]
PROGRESSIVO                  [ —                              ▼ ]  [Serial ▼]
ORIGINAL_OBJECT_NAME         [ —                              ▼ ]  [ObjectName ▼]
```

- **Left is loaded from the dataschema**, by `[Load columns from dataschema]`, reading
  `columnsSchema`. The order is the dataschema's and is not editable: it is the order the CSV is
  written in, and the whole point of requirement 1 is that the schema decides it.
- **Right is a dropdown over the path catalogue**, plus a free-text field. The dropdown is a
  convenience; the field is the escape hatch for a path the catalogue does not have, which is not a
  rare case — see §8.2.
- **Type** is the dropdown of requirement 6. **Extra** appears only for the types that need it: `from`
  for Date, `value`/`mode` for MIMEType, `value` for a constant String.
- A dataschema column left unmapped is written as an empty column. It is not dropped: the CSV keeps
  the schema's shape, which is what ELAR is given.

### 8.1 Where the catalogue comes from

`GET /api/workflows/{feedId}/json2csv/paths?schema=…&sampleDir=…&pattern=…&max=…`, returning the paths
with, for each, whether it is inside an array and what scalar type was seen.

Two sources, and both are wanted:

1. **`jsonSchema`** — the uploaded file. Auto-detected: a root object carrying `properties` or
   `$schema` is walked as a **JSON Schema** (`properties`, `items`, `$defs`/`definitions` for local
   `$ref`); anything else is walked as a **sample instance**. Detection is three lines and removes the
   need to ask which one will be uploaded.
2. **The first N input files** (`max`, default 20), paths merged. This exists because of §8.2.

The file is uploaded to the feed directory through the existing files panel, beside `dataschema.json`
and `displayschema.json`. No new upload plumbing.

### 8.2 Why a sample is not enough on its own

A sample instance only reveals attributes that are **present in that sample**. An empty `accounts: []`
hides everything under it. A field absent from one customer's record is absent from the catalogue.
Reading a catalogue as complete when it is not is how a column silently ends up empty for a third of
the feed.

Hence: merge across N files, keep the free-text field, and — batch 3 — mark each path with **how many
of the scanned files contained it**. A path seen in 3 of 20 is a different thing from one seen in 20 of
20, and the operator mapping the column is the only one who can tell which is expected.

A real JSON Schema does not have this problem, which is the argument for uploading one where the
source system can produce it.

## 9. Guards, all conservative

### 9.1 Static validation, before any file is opened

`inputDir` exists; `csvFile` set; every `as` present in the dataschema when `columnsSchema` is set; no
duplicate `as`; every path parses; `S` is a chain unless `CROSS`; `COLUMN_EXTENSION` refers to an
earlier column; `recordBusinessDateFormat` resolved if any Date column exists.

All of it fails **before** the first read. The elarxml pre-scan earned its place by refusing a run
rather than half-delivering it; this is the cheap version of the same idea, and it costs nothing
because the mapping is known before the data is.

### 9.2 A file that fails to parse

`onBadFile` defaults to **`FAIL`**: malformed JSON stops the run. `SKIP` counts it in
`${filesFailed}`, logs the name and the parser's position, and carries on.

`FAIL` is the default for the elarxml reason: the output is a single CSV that is about to be delivered,
and a short delivery that looks complete is worse than a run that stops. Under `SKIP` the loss is
counted and reported rather than invisible.

### 9.3 `maxFileMB`

Default **64**. A file larger than this is refused by `onBadFile` **without being read**, so the
message is "this file is 900 MB, over the 64 MB limit" and not an `OutOfMemoryError` two batches into
a delivery. See Q1: the number is a placeholder until the real sizes are known.

### 9.4 Output

`csvFile`, UTF-8, no BOM, CRLF, `;` by default (`delimiter`), RFC-4180 quoting, header = the
dataschema column names in dataschema order. All of it is `CsvWriter`'s and none of it is new.

`csvSplitRows` / `csvSplitMb` on the step split it into `<stem>_001.<ext>`, as for `sql`.

**A document's rows may straddle two parts.** The split is by row, the parts are one delivery, and
keeping documents whole would mean parts of uneven size for a property nothing downstream uses. Stated
here so it is a decision and not a discovery.

### 9.5 `checkNullable`

**Off.** When on, an empty value in a column the dataschema marks `nullable:false` is a finding;
`onNullViolation` is `WARN` (count and continue) or `FAIL`. Nothing in the product reads `nullable`
today, so on-by-default would change behaviour for every existing feed on the day it deploys. Q6.

### 9.6 `renameProcessed`

**Off.** When on, each input is renamed to `.done` once the CSV is closed, so the next run does not
re-read it. Off by default because unlike elarxml — where each input maps to its own INDX files — here
every input feeds one output, and "processed" only becomes true at the end of the whole step.

## 10. Step outputs

`${csvFile}`, `${csvFiles}`, `${csvParts}`, `${rowCount}` — the names `sql` and `split` already
publish, so a LOOP over the parts is written the same way as for those.

`${filesRead}`, `${filesFailed}`, `${documentsRead}`, `${documentsWithNoRows}`, `${rowsWritten}`,
`${valuesMissing}`, `${valuesNonScalar}`, `${nullViolations}`, `${maxRowsPerDocument}`.

`${maxRowsPerDocument}` is there for one reason: it is the number that tells you the flattening did
what you thought. A feed where it reads 1 has no array mapped; one where it reads 4 000 has a
cartesian product that got approved.

**No value ever appears in a log line or a counter.** These are JSON documents from a banking source;
the elarcheck rule holds — findings carry names, paths and counts.

## 11. Registration (the five-location rule, plus the panel)

| # | File | What |
|---|------|------|
| 1 | `parser/WorkflowXmlParser` — exec whitelist | add `"json2csv"` |
| 2 | `parser/WorkflowXmlParser` — the error message | add it to the list of accepted values |
| 3 | `parser/WorkflowXmlParser` — the `internal` boolean | add `"json2csv".equals(ik)` |
| 4 | `engine/WorkflowEngine.internalKind()` | add `e.equals("json2csv")` |
| 5 | `engine/InternalSteps.run()` | add the `else if` in the dispatch |
| 6 | `templates/designer.html` | `<option>` in the executor dropdown, the panel of §8, and `clientValidate` |

`buildXml` emits `<param>` generically and already emits `<column src as>`; the four new `<column>`
attributes are the only addition it needs.

## 12. Delivery

Confirmation gate between each.

- **Batch 0** — this specification. No code.
- **Batch 1** — `json2csv` core, Spring-free and Jackson-free: path parser, array-chain analysis, row
  generator, the six column types. Compiled with `javac --release 8` and run in the sandbox against
  hand-built trees. Suites: the chain and the sibling refusal, empty arrays at every level, scalar
  arrays, a document that yields no rows, Serial across a split boundary, the date mask in both
  directions, and every path form of §6.1. **Negative runs first**, against the pre-batch code, so it
  is known the suites bite.
- **Batch 2** — the executor: `JsonDocumentReader` (the Jackson class, §1), the `CsvWriter` wiring,
  `ColumnSel`'s four fields through parser / writer / DTO, and the six registrations. Opens for the
  no-op proof: every workflow XML in the repo read, written back and compared by SHA-256.
- **Batch 3** — the designer panel and the two API endpoints. Panel assertions in the shape the
  elarxml and elarcheck panels established — including balanced tags, which is what caught the panel
  defect recorded in CLAUDE.md, where an extra `</div>` silently ate the rest of a section.
- **Batch 4** — `USAGE.md`, the counters end to end, and a run against real files on the deployment.

## 13. Not in scope

- **One CSV per input file.** One aggregated CSV is what requirement 5 implies and what requirement 7
  splits. Per-file output is a different step and would want a different name.
- **Writing JSON.** This reads only.
- **Streaming very large files.** §1 and Q1. The reader is behind one class precisely so this can be
  answered later without touching the core.
- **Repairing malformed JSON.** As with elarcheck: repair stays in PowerShell, run as ordinary
  `powershell` steps in the same workflow.
