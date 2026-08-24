# JSON to CSV executor (`json2csv`) — specification

Status: **Batch 3 delivered** — the mapper panel and the two catalogue endpoints. §14–§16 record what
was built and what the suites proved. Batch 4 (`USAGE.md`, a run on real files) remains.

**This feed is Transarch, not ELAR.** Corrected throughout; the executor is unaffected.
Revised after Gate 0 was answered (§3). **The answers removed more of this than they added**, and the
removals are recorded here rather than deleted: §6.3–§6.5 stay on the page, marked DEFERRED, because
they are the design for the day the deferred half comes back.

Reads the JSON files matching a wildcard mask in a directory and writes ONE flat CSV whose shape is
the feed's **dataschema**, filling its columns from JSON attribute paths chosen against a **JSON
schema** (an uploaded sample, or a real JSON Schema).

**One JSON file is one document and produces exactly one CSV row.** Each file is a serialised
database row: an object of scalars, possibly with nested objects, possibly carrying arrays that
nothing maps into. Multi-row flattening — one row per element of an array, outer values repeated — is
**specified but not implemented**, and a path that asks for it is refused rather than guessed at.

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

## 3. Gate 0 — ANSWERED

Answered by the feed owner. Kept with the reasoning intact: what the questions were for is why the
design is the shape it is, and half of them removed code rather than adding it.

### Q1 — size — ANSWERED: small

Each JSON is one row of one database entity. The tree reader of §1 is right and streaming is not
needed. `maxFileMB` drops from 64 to **16** (§9.3): the guard exists to catch a file that is not what
this feed thinks it is, and a limit set far above anything real cannot do that.

### Q2 — one file, one document — ANSWERED: yes, and one CSV row

**One file is one document is one row.** `documentsPath`, which would have let one file hold an array
of documents, is **removed**: it was specified for a case that does not exist here, and an unused
parameter is a thing to misconfigure.

A file may *contain* arrays. Nothing maps into them: only a header-like set of scalars is taken out,
and it fits on one row. This is what makes §6.3–§6.5 deferred rather than wrong.

### Q3 — sibling arrays — ANSWERED: no

They never occur, and with array mapping deferred they cannot. `onSiblingArrays` is **removed**; the
static check it guarded is subsumed by the flat refusal of §6.2.

### Q4 — dates in the JSON — ANSWERED: `YYYY/MM/DD`, `YYYYMMDD`, `YYYY-MM-DD`

Three masks in the product's own dialect, and — this is the part that matters — **mutually
unambiguous by shape**. Eight digits, or ten with two slashes, or ten with two dashes: no value can
parse as two of them. So trying all three in order is safe here, where trying `DD/MM/YYYY` and
`MM/DD/YYYY` in order would silently read the third of April as the fourth of March. §7.3.

Epoch milliseconds is **removed** from the defaults: it was a guess, and guessing at a value that
parses as a date either way is exactly the failure this executor should not have.

### Q5 — MIMEType — ANSWERED: `.json`

The file extension, dot included. `COLUMN_EXTENSION` — the extension of a document named by another
attribute — is **removed**: it was speculation about an attachment model this feed does not have.
`FIXED` and `SOURCE_EXTENSION` remain and, for a `*.json` input, produce the same string; §7.4 says
why both are kept anyway.

### Q6 — `nullable:false` — ANSWERED: not here

Checked by later steps in the workflow, not by this one. `checkNullable` and `onNullViolation` are
**removed** along with the `${nullViolations}` counter. The dataschema is read here for column names
and order, and for nothing else.

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

  <column as="NDG"                  src="ndg"                type="String"/>
  <column as="NOMINATIVO"           src="customer.name"      type="String"/>
  <column as="DATA_KYC"             src="kycDate"            type="Date"/>
  <column as="IMPORTO"              src="saldo"              type="Number"/>
  <column as="IBAN"                 src="conti[0].iban"      type="String"/>
  <column as="PROGRESSIVO"                                   type="Serial"/>
  <column as="ORIGINAL_OBJECT_NAME"                          type="ObjectName"/>
  <column as="MIME"                                          type="MIMEType" value=".json"/>
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
~~case-insensitive on Windows~~ **case-sensitive on every platform — see §15.1(c)**. Directories are not descended into; `.done` and non-matching files are
ignored.

**Files are processed in file-name order (`String.compareTo`, not locale-dependent).** Two runs over
the same directory then produce byte-identical CSVs, and `Serial` means something stable. A
`Files.list` order that happened to be sorted on one filesystem and not on another would make the
Serial column quietly non-reproducible.

Zero matching files is **not** an error: 0 rows, `${rowCount}=0`, the step succeeds. A feed with no
input that day is normal, and failing it would wake somebody for nothing.

### 5.2 One file, one row

Gate 0 Q2. The root of the file is the document, the document is one row, and `${rowCount}` equals the
number of files successfully read. `documentsPath` is removed; §3 Q2 says why.

`original_object_name` is therefore a genuine key and not just a label — one file name, one row.

### 5.3 Charset

`inputCharset`, default **UTF-8**. JSON is UTF-8 by specification and Jackson auto-detects UTF-8/16/32
from the BOM, so the default is right and the parameter exists for a source that is neither.

## 6. Paths, and the array line

### 6.1 Path syntax

Dot-separated keys, with an **explicit index** for an array element:

```
ndg
customer.name
conti[0].iban                       the first element, chosen by hand
data['odd.key'].value               a key containing a dot or a bracket
```

Keys are matched **case-sensitively and exactly**, as JSON keys are. A key containing `.` or `[` is
written in the bracket-quoted form; the catalogue of §8 emits that form automatically when it meets
such a key, so it is never typed by hand.

`conti[0]` is a decision taken rather than asked for, and it is the one place this design goes past
Gate 0: Q2 says a file may carry arrays while only one row comes out, and an explicit index is how you
reach into one without asking for the deferred half. It is five lines of parser and it does exactly
what it says — element 0, or empty if there is no element 0. **Veto it at this gate if you would
rather not have arrays reachable at all**; after batch 2 it is a mapping that exists in workflow XML.

### 6.2 `[]` is refused, not guessed at

An unbounded `[]` — `conti[].iban` — asks for one row per element, which is the deferred half. It
fails **static validation** (§9.1), before a file is opened, naming the column and the path and saying
that multi-row flattening is not implemented.

Refusing beats the two alternatives. Reading it as `[0]` would silently deliver a feed that is short
by every element after the first, with nothing in the output saying so. Ignoring the column would
deliver it empty. Both are discovered in Transarch, months later; this is discovered when the step is
saved.

The catalogue of §8 still **shows** array paths, marked unavailable with that reason. Hiding them
would leave an operator hunting for an attribute that is plainly in the sample.

### 6.3 DEFERRED — the chain

> Not implemented. Recorded because it is the design for when array mapping arrives, and because the
> refusal of §6.2 is only defensible if what is being refused is written down.

For a path `p`, `arrayPrefixes(p)` is every prefix of `p` ending in `[]`, shortest first.
`accounts[].movements[].amount` gives `accounts[]`, then `accounts[].movements[]`.

`S` is the union of `arrayPrefixes` over **the mapped columns that have a `src`** — an array nobody
reads from does not multiply rows. Order `S` by length; if every element is a prefix of the next it is
a **chain** `a1 ⊂ a2 ⊂ … ⊂ ak`, and rows come from nested iteration over it, outermost first. A column
whose deepest array prefix is `ai` reads at the current index of `ai`; a column with no array prefix is
constant for the document. **Repetition of the outer values is not a step — it is what reading at the
current index does.**

```json
{ "ndg": "12345",
  "accounts": [
    { "iban": "IT01", "movements": [ {"amount": 10}, {"amount": 20} ] },
    { "iban": "IT02", "movements": [ {"amount": 30} ] } ] }
```

```
NDG   ; IBAN ; IMPORTO
12345 ; IT01 ; 10
12345 ; IT01 ; 20
12345 ; IT02 ; 30
```

### 6.4 DEFERRED — siblings

Two paths in `S` where neither contains the other, `accounts[]` and `notes[]`, are a cartesian
product: 3 accounts and 4 notes give 12 rows, each account repeated 4 times, with nothing in the
output saying which repetitions are real. It would default to refusing, with `CROSS` available per
step.

Gate 0 Q3 answered that this never occurs, so the parameter is not carried into the implementation.

### 6.5 DEFERRED — an empty array

At each array level, an array that is empty, absent, or not an array would yield **one** iteration in
which everything at or below that level is empty — a LEFT JOIN, not an INNER one, recursively. The
alternative, dropping the document, must not be the default: **the default must not lose a document in
silence.**

### 6.6 A leaf that is not a scalar

Live, and now the main way a mapping can be wrong. A `src` resolving to an object or an array where a
value is expected: `onNonScalar` defaults to **`FAIL`**, naming the column, the path and the file.
`EMPTY` writes nothing and counts it; `JSON` writes the compact JSON text of the node.

A missing intermediate node is **not** this case — that is an absent value, which is empty and counted
in `${valuesMissing}`. The two have different causes: absent is data, non-scalar is a wrong mapping,
and a wrong mapping found on the first file is worth a stop.

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
a bad value here and finding it in Transarch.

### 7.3 Date (requirement 6.3)

Output is **always** `${recordBusinessDateFormat}`, translated by `InternalSteps.fmtToJavaPattern` —
see §2. There is no per-column output format: requirement 6.3 says the feed has one date format, and a
second place to set it would guarantee the two disagree.

Input, with no `from` on the column, is tried against the three masks of Gate 0 Q4 **in this order**:

```
YYYY/MM/DD      YYYYMMDD      YYYY-MM-DD
```

Order is safe here and it is worth being explicit about why: eight digits, or ten with two slashes, or
ten with two dashes — **no value can parse as two of them.** A list of formats tried in order is a
dangerous idea in general (`DD/MM/YYYY` then `MM/DD/YYYY` reads 03/04 as two different days and never
says so); it is safe when the shapes are disjoint, which these are. Adding a fourth mask to this list
later is not automatically safe and must be checked against the other three.

`from` on the column overrides the list with a single mask, same dialect, same translator.

- **Parsing is STRICT** (`ResolverStyle.STRICT`, on `uuuu` which `fmtToJavaPattern` already produces).
  `20260230` is refused, not quietly resolved to the 28th. A Date column exists to validate as much as
  to reformat, and a resolver that repairs impossible dates gives away the validation.
- **A JSON number works.** `20260824` arrives as `BigDecimal`, is written plain as `20260824`, and
  parses under `YYYYMMDD`. No special case needed.
- **Empty or null is empty**, not a parse failure. An absent date is data; an unparseable one is not.
- A value matching none of the masks follows `onNonScalar`, whose default is `FAIL`.
- A `recordBusinessDateFormat` that is unset, or still contains `${`, is reported as an **undefined
  variable** and fails the step — the wording the validate step already uses for the same condition,
  because it is a different problem from a bad mask and needs a different fix.

### 7.4 MIMEType (requirement 6.1)

Gate 0 Q5: the value wanted is `.json`, the file extension with its dot. `mode`:

- `FIXED` (default) — the literal in `value`.
- `SOURCE_EXTENSION` — from the name of the JSON file being read, `report.json` → `.json`,
  lower-cased, dot included, empty if the name has no dot.

`COLUMN_EXTENSION` is removed (§3 Q5).

For a `*.json` input the two produce the same string, which is a fair question to ask of a design.
Both are kept because they fail differently: `FIXED` keeps writing `.json` if the mask is widened to
`*.txt` one day, and `SOURCE_EXTENSION` follows it. Neither is more correct — but the step should say
which one it meant, and a single mode would let it say nothing.

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
DATA_KYC                     [ kycDate                        ▼ ]  [Date   ▼]
IMPORTO                      [ saldo                          ▼ ]  [Number ▼]
IBAN                         [ conti[0].iban                  ▼ ]  [String ▼]
PROGRESSIVO                  [ —                              ▼ ]  [Serial ▼]
ORIGINAL_OBJECT_NAME         [ —                              ▼ ]  [ObjectName ▼]
MIME                         [ —                              ▼ ]  [MIMEType ▼]  value: .json
```

- **Left is loaded from the dataschema**, by `[Load columns from dataschema]`, reading
  `columnsSchema`. The order is the dataschema's and is not editable: it is the order the CSV is
  written in, and the whole point of requirement 1 is that the schema decides it.
- **Right is a dropdown over the path catalogue**, plus a free-text field. The dropdown is a
  convenience; the field is the escape hatch for a path the catalogue does not have, which is not a
  rare case — see §8.2.
- **Type** is the dropdown of requirement 6. **Extra** appears only for the types that need it: `from`
  for Date (empty = the three masks of §7.3), `value`/`mode` for MIMEType, `value` for a constant
  String.
- **An array path is shown and not selectable**, with the reason (§6.2). Picking it in the dropdown is
  refused there rather than at save, so the message arrives where the mistake is made.
- A dataschema column left unmapped is written as an empty column. It is not dropped: the CSV keeps
  the schema's shape, which is what Transarch is given.

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
duplicate `as`; every path parses; **no path contains an unbounded `[]`** (§6.2);
`recordBusinessDateFormat` resolved if any Date column exists.

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

Default **16** (Gate 0 Q1: each file is one database row). A file larger than this is refused by
`onBadFile` **without being read**, so the message is "this file is 900 MB, over the 16 MB limit" and
not an `OutOfMemoryError` halfway through a delivery.

The limit is deliberately close to reality rather than far above it. A guard set at 64 MB for files
that are kilobytes would pass anything that is not what this feed thinks it is — a whole export
dropped into the input directory by mistake, say — and catching that is most of what the guard is for.

### 9.4 Output

`csvFile`, UTF-8, no BOM, CRLF, `;` by default (`delimiter`), RFC-4180 quoting, header = the
dataschema column names in dataschema order. All of it is `CsvWriter`'s and none of it is new.

`csvSplitRows` / `csvSplitMb` on the step split it into `<stem>_001.<ext>`, as for `sql`.

One document is one row (§5.2), so a part boundary never falls inside a document and there is nothing
to decide about straddling. That changes if the deferred half of §6 arrives, and the decision then is
to let rows straddle: the parts are one delivery.

### 9.5 `checkNullable` — removed

Gate 0 Q6: the dataschema's `nullable:false` is enforced by later steps in the workflow, not here.
`checkNullable`, `onNullViolation` and `${nullViolations}` are gone. The dataschema is read for column
names and order, and for nothing else.

### 9.6 `renameProcessed`

**Off.** When on, each input is renamed to `.done` once the CSV is closed, so the next run does not
re-read it.

Off by default, and — unlike elarxml, where the rename is per file as soon as that file's batches have
reached their final names — the rename here can only ever happen at the end. Every input feeds one
output, so no input is "processed" until the whole step is. That is not a limitation to work around:
renaming a file whose row sits in a CSV that has not been closed would be the elarxml `.done` defect,
reintroduced from the other direction.

## 10. Step outputs

`${csvFile}`, `${csvFiles}`, `${csvParts}`, `${rowCount}` — the names `sql` and `split` already
publish, so a LOOP over the parts is written the same way as for those.

`${filesRead}`, `${filesFailed}`, `${rowsWritten}`, `${valuesMissing}`, `${valuesNonScalar}`.

`${documentsWithNoRows}`, `${nullViolations}` and `${maxRowsPerDocument}` are removed with the features
that produced them.

**`${filesRead}` and `${rowsWritten}` must be equal**, one row per file, and the step log says so
explicitly rather than leaving it to be worked out. It is the cheapest possible assertion that the
executor did what §5.2 says it does, and it is the one number a gate can branch on.

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

Confirmation gate between each. **Gate 0 shrank batch 1 considerably**: with flattening deferred there
is no chain analysis, no sibling check and no empty-array recursion to build or to test.

- **Batch 0** — this specification, revised against the Gate 0 answers. No code.
- **Batch 1** — `json2csv` core, Spring-free and Jackson-free: the path parser (dotted keys,
  bracket-quoted keys, explicit `[n]`, and the refusal of `[]`), the value resolver over
  `Map`/`List`/scalars, and the six column types. Compiled with `javac --release 8` and run in the
  sandbox against trees built by hand. Suites: every path form of §6.1; `[]` refused with the path
  named; all three date masks in both directions, **including that `20260230` is refused under STRICT
  and that a JSON number parses as `YYYYMMDD`**; `1.10` keeping its scale and `1e3` losing its
  exponent; Serial across a split boundary; a missing value against a non-scalar one. **Negative runs
  first**, against the pre-batch code, so it is known the suites bite.
- **Batch 2** — ~~the executor, opening with the no-op proof: every workflow XML in the repo read,
  written back and compared by SHA-256~~ **DELIVERED, and that proof was mis-specified — see §15.1(a).
  No workflow in this repo has a `<column>` element, so the test could not have failed. Replaced by a
  pre-batch versus post-batch comparison on a workflow that uses the feature.**
- **Batch 3** — the designer panel and the two API endpoints. Panel assertions in the shape the
  elarxml and elarcheck panels established — including balanced tags, which is what caught the panel
  defect recorded in CLAUDE.md, where an extra `</div>` silently ate the rest of a section.
- **Batch 4** — `USAGE.md`, the counters end to end, and a run against real files on the deployment.

## 13. Not in scope

- **One CSV per input file.** One aggregated CSV is what requirement 5 implies and what requirement 7
  splits. Per-file output is a different step and would want a different name.
- **Writing JSON.** This reads only.
- **Multi-row flattening.** §6.3–§6.5 hold the design; §6.2 refuses the paths that would need it. The
  path parser already understands `[]`, so the day it arrives the mapping format does not change.
- **Streaming very large files.** §1 and Q1: files are one database row each. The reader is behind one
  class precisely so this can be answered later without touching the core.
- **Enforcing `nullable`.** Gate 0 Q6: later steps in the workflow do it.
- **Repairing malformed JSON.** As with elarcheck: repair stays in PowerShell, run as ordinary
  `powershell` steps in the same workflow.

---

## 14. Batch 1 delivered — the core

Eleven classes in `com.legalarchive.orchestrator.json2csv`, **no Spring and no Jackson**, compiled
with `javac --release 8` and run in the sandbox. **203 assertions, all green.**

Nothing outside the new package is touched, so this batch cannot change the behaviour of any existing
feed — there is no call site yet. The executor arrives in batch 2.

| class | what |
|---|---|
| `JsonPath` | parses a path and resolves it against a `Map`/`List`/scalar tree; `Resolution` is FOUND / ABSENT / MISMATCH |
| `ColumnMapping` | one `<column>`, with its path parsed once at construction |
| `ColumnType`, `MimeMode`, `OnNonScalar`, `ObjectNameValue` | the dropdowns, each refusing an unknown value rather than defaulting quietly |
| `DateCoercion` | the three input masks, STRICT parsing, output in `recordBusinessDateFormat` |
| `MaskTranslator` | the one-method seam onto `InternalSteps.fmtToJavaPattern` |
| `RowBuilder` | one document to one row; Serial, ObjectName, MIMEType, and the `onNonScalar` policy |
| `MappingValidator` | everything checkable before a file is opened, reported all at once |
| `DocumentContext`, `Json2CsvCounters`, `Json2CsvException` | the file a document came from, the counts, the refusals |

### 14.1 ABSENT and MISMATCH, which is the distinction the whole core turns on

§6.6 asked for "absent is data, non-scalar is a wrong mapping". Implementing it made the rule wider
than the spec had it, and better:

- **ABSENT** — a key that is not there, an index past the end of an array, or an explicit JSON null.
  The document does not have it. Writes empty, counts `valuesMissing`.
- **MISMATCH** — a key applied to something that is not an object, an index applied to something that
  is not an array, or a leaf that turned out to be an object or an array. **The document is not
  shaped the way the path assumes.** Follows `onNonScalar`, default FAIL.

Folding them together is the expensive mistake: a mapping typo would deliver an empty column for the
whole feed and look exactly like a customer who happens to have no value. The mutation that folds
them is caught by thirteen assertions (§14.4).

A value that will not read as a Number, or as a date under any mask, takes the same route. That makes
`onNonScalar` a slightly wrong name for what it now does — it is really "the value cannot be used as
this column's type". The name is kept because it is already in the committed spec; **the designer
label in batch 3 will say what it means rather than repeat what it is called.**

### 14.2 The date masks are disjoint, and that is measured rather than asserted

The spec argues that trying `YYYY/MM/DD`, `YYYYMMDD`, `YYYY-MM-DD` in order is safe because no value
parses under two of them. An argument is not a measurement, so the suite renders **every day of a
full year in all three forms — 1 095 strings — and asserts each parses under exactly one mask.**
Overlaps found: zero.

That assertion is the guard on the sentence in §7.3 warning that a fourth mask is not automatically
safe: add one, and this test tells you at once whether it overlaps.

STRICT is exercised for real: `2026-02-30`, `2026-13-01`, `20260230` and `2026-02-29` are refused,
`2024-02-29` is accepted.

### 14.3 The translator is extracted from the real source, not retyped

The suite does not contain a copy of `fmtToJavaPattern`. It **reads `InternalSteps.java` at build time
and lifts the method and its `JT_PASSTHROUGH` constant verbatim** into a `MaskTranslator`. So
"`YYYYMMDD` becomes `uuuuMMdd`" is a fact about the shipped translator and not about a copy of it
that could drift.

### 14.4 The suites were seen to fail first

Eight mutations of the real source, each compiled and run against the suite. Every one was caught, by
named assertions:

| mutation | caught by |
|---|---|
| the `[]` refusal is removed from the validator | B6 B7 B8 B9 B10 H11 H11d H12 |
| `[]` is resolved as `[0]` instead of refused | B5 B12 |
| dates parse SMART instead of STRICT | D14 D16 D18 |
| Serial restarts on every row | F2 F3 F4 F5 F8 |
| MISMATCH is folded into ABSENT | C12–C19, I1 I3 I4 I5 I6 |
| Number goes through `double` instead of `BigDecimal` | E1 E2 E4 E6 |
| the output-mask probe is removed | D28 |
| `describe()` leaks the value into the finding | C18 C20 |

**The first run of the mutations found a defect in the suite, not in the core**: under the first
mutation the suite died on `probs.get(0)` of an empty list and stopped reporting everything after it
— the opposite of what `MappingValidator` does on purpose. The runner now turns an unexpected throw
into a failure, and the mutation is caught by eight assertions instead of a stack trace.

### 14.5 What is NOT verified

- **`mvn clean package`.** Maven Central is unreachable from the sandbox. The package compiles
  standalone with `javac --release 8` against the JDK alone, which is a **stronger** check than the
  project's own build: the pom sets `maven.compiler.source/target 1.8`, which does not check the API
  surface, while `--release 8` does. Verified on the day: `List.of` fails to compile under the flag.
- **The suite itself is not committed.** It lives in the sandbox, as the `elar` suites do. Say the
  word and it goes into the repo with a runner.
- Everything the executor does with files: reading, the wildcard mask, `CsvWriter`, the counters end
  to end. That is batch 2.

---

## 15. Batch 2 delivered — the executor

`json2csv` is registered in all six places and runs. **267 assertions green** across two suites, plus
the no-op proof and a compile check of the assembly code. Batch 3 is the mapper panel; until then a
step is configured with `+ param` and hand-written `<column>` entries, as `elarxml` was.

### 15.1 Three things this specification got wrong

Recorded rather than quietly corrected, because the reasoning is the artefact.

**(a) The no-op proof as written proves nothing.** §12 promised "every workflow XML in the repo read,
written back and compared by SHA-256". Run, it returned the SHA of the empty string for every file:
**no workflow in this repo contains a single `<column>` element.** The test could not have failed. And
comparing the writer's output to the hand-written original was never going to work either — the
writer re-indents through a `Transformer`, so the two differ before any change of mine.

What actually proves the claim is **pre-batch output against post-batch output**, on a workflow that
uses the feature. So: the real parser and the real writer are compiled from a clean clone and from the
patched tree, and both are run over the 15 repo workflows *and* over a synthetic `xlsx2csv` step
carrying four `<column>` elements. Identical, both hashes, both trees. The same harness shows
`json2csv` refused by the pre-batch parser and accepted by the patched one.

**(b) `JsonDocumentReader` cannot live in the json2csv package.** §1 put it there, "the one class
where Jackson appears". Maven Central is unreachable from the sandbox, so one Jackson import would
have made the package uncompilable there — costing the other twelve classes their test bench for the
sake of one. It moved to `engine`, where Jackson already lives. The seam is unchanged; only its side
of the wall moved.

It also carries its **own** `ObjectMapper`. `InternalSteps.jsonMapper` has four other call sites, and
enabling `USE_BIG_DECIMAL_FOR_FLOATS` on the shared instance would silently change how each of them
reads a number.

**(c) "Case-insensitive on Windows" was wrong.** §5.1 said the file mask should be. That would make
the same workflow select a different set of files on a developer's machine and on the server, and an
input set that depends on which filesystem it lands on is not reproducible. `FileMask` is
**case-sensitive everywhere**, which also matches what `elarcheck` already does.

### 15.2 The run loop moved out of InternalSteps

Written first as a method there, it could not be exercised at all. Everything interesting about that
loop — the order files are visited, what the counters do when one fails, whether the rename can
happen before the CSV is closed — is exactly what is wrong the first time.

So reading and writing became seams: `Json2CsvRun.DocumentReader` is Jackson in production and a map
of hand-built trees in the suite; `RowSink` is `CsvWriter` in production and a list in the suite. What
is left is Spring-free, Jackson-free and tested. `InternalSteps` keeps only the assembly.

**`renameProcessed` is deliberately not an option on `Json2CsvRun`.** The rename belongs to the
caller, after it closes the sink. A flag on the run object could not be honoured without renaming too
early, and a setting that cannot take effect is worse than one that does not exist — the same rule
that refuses a fixed `value` on a `SOURCE_EXTENSION` column.

### 15.3 What the suites cover

`RunSuite`, 64 assertions: mask matching including backtracking and case; listing in name order,
ignoring subdirectories and `.done`; one row per file; Serial across files; FAIL versus SKIP on a
malformed file, with the counters and the log line each way; an empty directory writing a header and
no error; numbers and dates end to end; and the rename — that `run()` does **not** do it, that the
extension is appended rather than replaced, that a second run finds nothing, and that **a skipped file
is never renamed** so it is still there to be looked at.

`FileMask` is a second implementation of something `elarcheck` already has, since json2csv must not
depend on another executor's package. Two implementations that quietly disagree are worse than one, so
the suite **lifts elarcheck's matcher from its source at build time** and compares them across 90
name-and-pattern combinations. Zero disagreements — and making `FileMask` case-insensitive is caught
by that comparison as well as by its own assertions.

### 15.4 The assembly code was compiled, not just written

`InternalSteps` is Spring-coupled and does not compile in the sandbox, so its 183 new lines — four
anonymous inner classes among them — would otherwise have shipped never having met a compiler.

They are **lifted verbatim from `InternalSteps.java` at build time** and compiled against the real
`StepDef`, `VarResolver`, `StepExecutor.Result`, `CsvWriter` and json2csv classes, all of which are
Spring-free. Only `JsonDocumentReader` is stubbed, because it is the one class that needs Jackson.
Syntax and types are therefore checked; behaviour is not, and `mvn clean package` remains the final
word.

### 15.5 Six mutations, all caught

| mutation | caught by |
|---|---|
| `list()` no longer sorts by name | N2 O3 O4 O6 O24 O25 |
| the rename happens inside the loop | O12–O17, P1 P2 P3 |
| a file that failed to parse is counted as read | O15 O16 |
| a skipped file is marked processed anyway | O19 P8 |
| the file mask becomes case-insensitive | L16 L17 **M1** |
| `?` is allowed to match nothing | L8 |

### 15.6 Not verified

- **`mvn clean package`**, and the executor running against real files. Nothing here has been through
  Spring, a WAR or Tomcat.
- **The designer changes.** The `<option>` and the `clientValidate` block were checked for the two UBS
  rules — no literal `\n` or `\r` in the added JS, no `[[` or `[(` — but not rendered. Batch 3 brings
  the panel and its assertions.
- **`ApiController.toDto`**, whose four new lines are the one edit no harness here reaches.

---

## 16. Batch 3 delivered — the mapper

**104 assertions green** in two new suites (56 on the catalogue, 48 on the panel), on top of the 267
from batches 1 and 2. Eight further mutations, all caught.

### 16.1 What the real sample changed

Three answers closed without touching code — RFC-4180 quoting is what `CsvWriter` already does,
`ObjectName` already writes the file name, and `recordBusinessDateFormat` is already the output mask.
The sample changed the **catalogue**, not the executor:

- **Keys contain dots.** `VM.CAP.DATE.CHARGE`, `VM.ALT.ACCT.TYPE`, `VM.ACCR.CR.CATEG` are single keys
  with dots inside, not nesting. Emitted bare, `VM.CAP.DATE.CHARGE` parses as **four nested keys** and
  resolves to nothing at all — on a path the dropdown itself handed over. The catalogue emits
  `['VM.CAP.DATE.CHARGE']`, and the suite asserts both halves: that the quoted form is produced, and
  that the bare form parses to four segments and resolves to nothing.
- **Arrays hold exactly one object.** `"VM.ALT.ACCT.TYPE":[{"ALT_ACCT_TYPE":"OLD-ID",…}]`. The
  catalogue therefore lists **two** things per array: the unbounded `[]`, shown and **disabled** with
  its reason, and the first element's members under `[0]`, which are selectable. Listing only `[]`
  would leave those values unreachable; offering `[0]` as though it were the whole array is the
  mistake §6.2 exists to prevent. This is what `[0]` was added for at the Gate 0 review.
- **The two vocabularies are the same one**, over about a hundred columns. Hence **"map by exact
  name"**, and hence the dataschema's declared type preselecting `Number`: choosing a type a hundred
  times by hand is how a mistake gets made out of boredom. Both are suggestions and stay editable, and
  **map-by-name never overwrites a column already mapped** — the ones a person set deliberately are
  exactly the ones a bulk action must not touch.

### 16.2 A sample is not a schema, and the panel says so

Every catalogue entry carries `seenIn` against `scanned`. **Seen in 3 of 20 is not the same as seen in
20 of 20**, and only the person mapping the column can say which is expected. The dropdown shows the
count; it does not decide. The panel repeats the caveat in words, because an attribute missing from
the catalogue may exist in the feed all the same.

The catalogue is held **per node in the browser and never in the workflow**. It is a picture of what
some sample files happened to contain, not part of the feed definition; persisting it would let a
stale snapshot decide a mapping months later.

### 16.3 The panel is RUN, not inspected

`tests/panel.js` extracts the `json2csv` branch from `designer.html`, wraps it in the helpers it leans
on, and **executes it** to produce real HTML — the same technique that verified the `USAGE.md`
renderer. It then asserts tag balance, that unavailable paths render disabled and not hidden, that the
counts appear, that a quoted dotted path round-trips verbatim through the attribute, and that **every
handler the panel emits names a function that actually exists**.

A regex over the source would not have caught the mutation that matters: an extra `</div>`, which is
the panel defect CLAUDE.md records, where the rest of a section was silently eaten. Rendering catches
it in four assertions.

### 16.4 Two assertions that were wrong, and one mutation that lied

- **X8 was my mistake, not a defect.** It asserted that apostrophes in a path must be escaped inside
  an attribute. They need not be: inside a double-quoted attribute an apostrophe is legal HTML, and
  the value round-trips verbatim. Replaced with the assertion that matters — the path survives exactly
  — plus a new one proving a **double** quote *is* escaped, since that one would end the attribute.
- **Two mutations came back green and neither was a pass.** One had a `sed` that matched nothing; the
  other had shell quoting that never applied the edit. Rewritten in Python with an anchor assertion,
  both are caught — the first by seven assertions. **A mutation that stays green is a claim about the
  mutation before it is a claim about the suite**, and the only way to tell is to look.

### 16.5 Not verified

- `mvn clean package`, and the executor against real files.
- The panel's **network calls**. Rendering is exercised; `fetch` is not. The three functions are
  reachable and their handlers resolve, but no request has been made to either endpoint.
- **The two endpoints themselves.** They are Spring controllers and cannot run here — the logic they
  wrap (`PathCatalog`, `Json2CsvRun.list`) is tested, the wiring is not.
- The real `dataschema.json`. The catalogue suite is built on the sample's shape read from
  photographs, so the column NAMES are not the real ones. Nothing depends on them — the panel reads
  the dataschema at run time — but the exact-match test is a shape test, not a data test.
