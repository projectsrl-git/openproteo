# Feed schema index — the record layout of every archived feed, in one CSV and one viewer

A searchable census of every feed OpenProteo loads: its SOURCE, its identity, its record layout from
`dataschema.json`, the field descriptions from `displayschema.json`, and any workflow-level static
variables asked for by name.

Two deliverables, deliberately separate:

* **`tools/Get-FeedSchemaIndex.ps1`** — read-only PowerShell, no build and no deploy. Writes the index CSV.
* **`feed_index_viewer.html`** — a standalone single-file page at the repository root, alongside
  `csv-viewer.html` / `xml_viewer.html` / `json_viewer.html`. Reads the index CSV and navigates it as a
  tree.

Batch 0 is this document. No code.

## 1. Why this exists

The record layout of a feed is currently spread over three places that never appear together: the
workflow XML says which SOURCE a feed belongs to and what its variables are, `dataschema.json` says
what the CSV columns are, and `displayschema.json` says what a human calls them. Answering "which
feeds carry an NDG, and what does each of them call it" means opening 144 directories. Answering
"what is the record layout of this feed" means finding the feed directory first.

The index makes that one file and one page. It is a census, not a pipeline artefact: nothing consumes
it, nothing is scheduled on it, and it is regenerated whenever the answer needs to be current.

## 2. Why a PowerShell tool and not an internal executor

The choice was left open in the request. It goes the same way as `tools/Get-ElarDocumentIndex.ps1`,
for the same four reasons, which apply here at least as strongly.

1. **It is a cross-feed analysis, not a step in a pipeline.** An internal executor belongs to one feed
   and runs inside one workflow; this reads the definition and the schema files of every feed at once.
   An executor would have to be configured on some arbitrary feed and would then read its 143 peers,
   which is not what an executor is.
2. **Time to answer.** The tool is: copy one file, run it. An executor is a patch, `mvn clean package`,
   a WAR, a Tomcat restart, a step to configure and a run to start — for a question that is asked in
   the middle of an analysis session.
3. **An executor would add a run and a log to the history**, and this tool reads the definitions the
   history is written against. Not fatal, but it is noise created by the act of measuring.
4. **No new behaviour reaches production.** The strongest argument: an internal executor means new
   code inside `InternalSteps` on the deploy path of 144 live feeds, in exchange for a report. A
   read-only script cannot change what a feed delivers.

The cost is stated rather than hidden, and it is the same cost as last time: the sandbox has
PowerShell 7 for Linux, not Windows PowerShell 5.1. See §9.

**What would change this decision**: if the index has to be visible *in the application* — a page in
the topbar, refreshed on demand — then the reader belongs in Java behind an endpoint and the viewer
becomes a Thymeleaf template. That is a different feature and it is not this one. It is written down
here so that answering "can I see it in OpenProteo?" later is a decision and not a discovery.

## 3. Inputs, and what the tool is allowed to touch

| input | where from | why |
|---|---|---|
| workflow XML | `orchestrator.workflows-dir` | `sourceId`, `targetId`, their descriptions, `name`, `<description>`, `production`, `<variables>`, and the per-feed `baseDir` override |
| `dataschema.json` | `<baseDir>\<feedId>\dataschema.json` | the record layout — the order the CSV is written in |
| `displayschema.json` | `<baseDir>\<feedId>\displayschema.json` | `DisplayName`, `DataType`, `DisplaySequenceNr`, `Viewable`, `anonType` |

`BaseDir` and `WorkflowsDir` are parameters, defaulted from a `-PropertiesFile` exactly as
`Get-ElarDocumentIndex.ps1` does it, so the same invocation shape works for both tools.

**Read-only, and the same way it was made verifiable last time**: the tool opens every file with
`FileShare.ReadWrite` so it cannot block a running feed, and it writes nothing but its own output.
A build scan asserts that no write API appears in the script outside the output-writing function.

## 4. The CSV: one row per FIELD

The decision that shapes everything else. Three shapes were considered.

* **One row per feed, fields as columns.** Refused: the number of fields varies per feed and reaches
  ~100, so the header would be the union of every field name in the estate and almost every cell would
  be empty. Unreadable by a person and useless to a filter.
* **One row per feed, fields as a packed list in one cell.** Refused: it cannot be filtered, sorted or
  joined, which is the whole reason for producing a CSV rather than a text report.
* **One row per field.** Chosen. `SELECT ... WHERE field_name = 'NDG'` answers the question that
  motivated the index, the feed columns simply repeat, and 144 feeds × ~100 fields is ~15 000 rows —
  a file a spreadsheet opens without complaint.

Header (default form):

```
source_id;source_description;target_id;target_description;feed_id;feed_name;feed_description;
production;field_seq;field_name;field_type;field_nullable;display_name;display_type;display_seq;
viewable;anon_type;in_dataschema;in_displayschema[;<variable columns>]
```

* `field_seq` is the **1-based position in the dataschema**, and the rows are written in that order.
  That order *is* the record layout; `DisplaySequenceNr` is a presentation choice and is carried in
  its own column rather than being allowed to reorder the file.
* `in_dataschema` / `in_displayschema` are `yes` / `no`. A displayschema entry naming a column the
  dataschema does not have is an **orphan**: it is emitted after the feed's real fields with
  `in_dataschema=no` and an empty `field_seq`. It is not dropped, because a description pointing at a
  column that does not exist is exactly the defect an index should surface.
* Separator `;`, encoding **UTF-8 with BOM**, line ends CRLF — what the product's own CSV export
  writes, and what Excel opens correctly, which is who reads a census. The viewer strips the BOM.
* `-Delimiter` overrides the separator. A value containing the separator, a quote or a line break is
  RFC-4180 quoted; `Export-Csv` is not used, because it quotes everything and the file is meant to be
  read by eye as well as by a program.

### 4.1 Field descriptions: the join must be the product's join, exactly

`display_name` comes from `displayschema.json` joined to the dataschema on the column name. The rule
is copied from `ApiController.displayNameMap` and `InternalSteps.readSchemaColumnNames` and is
**exact and case-sensitive after trimming**:

* the name key is `name`, else `ColumnName`, else `COLUMN_NAME`;
* the display key is `DisplayName`, else `displayName`, else `display_name`;
* both dialects of the container are accepted — a top-level array, or an object with a `columns` array;
* an entry that is a bare string is a column name with no attributes.

**This is a second implementation of a rule that already exists in Java, and two implementations that
quietly disagree are worse than one.** The suite therefore lifts the alias lists out of
`ApiController.java` and `InternalSteps.java` at build time and asserts the PowerShell reader agrees
with them over the whole cross-product of key spellings and container shapes — the technique that kept
json2csv's `FileMask` honest against elarcheck's matcher.

A case-insensitive join was rejected: the product resolves `DisplayName` case-sensitively, so an index
that matched `ndg` to `NDG` would report a description the application does not use.

### 4.2 Which feeds are in

`-Require` is `Dataschema` (default) | `Both` | `Any`.

Default is `Dataschema`, because the record layout is the point and a feed without one has nothing to
index. **A feed with a dataschema but no displayschema IS included**, with the description columns
empty: a feed whose fields nobody has described is a finding, and an index that hid it would be
answering a different question from the one asked. `Both` narrows it to feeds carrying both files, as
the request literally reads; `Any` widens it to feeds carrying either.

A feed matching nothing appears in **no row and in the summary**, counted with the reason
(`no dataschema`, `feed directory missing`, `unparseable JSON`). Silence about a feed the tool looked
at and rejected is the failure mode this line exists to prevent.

### 4.3 Where the schemas are read from, and what happens when a step disagrees

The feed root is the primary location, because that is where `ApiController` writes uploads and what
`${feedDir}/dataschema.json` resolves to in every workflow in the repository.

But nothing forces it: `validate` takes `dataschema` / `displayschema` params, `sql` and `json2csv`
take `columnsSchema`, and each is a free path. So:

1. If `<feedDir>\dataschema.json` exists, it is the one used.
2. Otherwise, if a step param resolves to an existing file, that file is used and `schema_source`
   records which step it came from.
3. If both exist and are **different paths**, the feed-root file wins and the disagreement is
   **reported in the summary naming both paths**. It is never merged and never silently preferred:
   two schemas for one feed is a configuration question, not something a report should resolve.

`${feedDir}` is resolved per feed from the workflow's own `baseDir` attribute when it carries one,
falling back to `-BaseDir` — the same precedence `FeedLayout` applies. Other variables inside a schema
path are **not** resolved (there is no run to resolve them against): such a path is reported as
unresolved rather than being read as a literal.

## 5. Workflow variables as columns (requirement 1.2)

`-Variables 'recordBusinessDate:Record Business Date','originTableName:Origin table'`.

* One extra column per entry, appended after the fixed columns in the order given, **repeated on every
  row of that feed** — the feed columns already repeat, and a variable is a feed-level fact.
* The value is the variable's value from the workflow's `<variables>` block. A feed that does not
  define it gets an empty cell, which is different from defining it empty and is reported as a count
  in the summary ("`originTableName` defined in 96 of 144 feeds").
* **The header is the description**, with the name as the fallback when a `:` is absent — that is what
  `name:description` asks for and a census is read by people. `-VariableHeaders Name` writes the
  machine name instead, for the case where the file feeds another program. Both spellings are in the
  tool so that neither has to be argued about again.
* A duplicate name in `-Variables` is refused at start, naming it, rather than producing two columns
  with the same header.

### 5.1 Values are RESOLVED — decided, reversing this document's own recommendation

Batch 0 recommended emitting the raw declaration. **The answer at Gate 0 was RESOLVED**, and it is
recorded here as the decision rather than corrected quietly, because §5.2 exists only because of it.

The map resolved against is the one **Operations already uses for feed tags** (`overviewFeeds`):
file globals, plus `feedId` / `parentId` / `sourceId` / `targetId`, plus the workflow's own
`<variables>`. That choice is not convenience — it means **the index and the Operations grid cannot
disagree about the same feed**. Resolution is iterative and innermost-first, as `VarResolver` does it,
so a variable built out of another one (`${TargetDestination.${targetId}}`) resolves for real, which is
the case where resolving earns its keep.

### 5.2 The deliberate divergence: an unresolvable token STAYS a token

`VarResolver.resolve` resolves an unknown name to the **empty string**. That is right at runtime and
wrong here: `${runDate}` and `${extract.rowCount}` do not exist at design time, so an empty cell would
be indistinguishable from *this feed does not define the variable*. That is the "right for some,
quietly wrong for others" failure the raw recommendation was trying to avoid — resolution does not
remove it, it only hides it better.

**So this tool leaves what it cannot resolve as the literal `${name}`.** A cell either carries a value
that is true at design time or carries visible evidence that it is computed at run time. It can never
be a lie. `variables_unresolved` counts the occurrences in the summary, so an isolated case and a
variable that should never have been in the list look different.

`${list[N]}` and `${COL@key}` are **not interpreted** and stay literal: both only mean anything against
a run's published lists, and a design-time reading of them could only invent one.

### 5.3 The cost, stated

This is a **third** implementation of `${}` resolution, after `VarResolver` and the Operations tag map.
It is contained by scope — `${name}` with nesting and a depth cap, nothing else — and by the suite,
which lifts `VarResolver`'s patterns out of the Java at build time and asserts the two agree on every
case both cover. Same technique as the displayschema alias lists in §4.1, and for the same reason: two
implementations that quietly disagree are worse than one.

**Global variables become an input.** With values resolved, a feed's `${someGlobal}` needs
`orchestrator.global-vars` to be readable from wherever the script runs, or every such cell degrades to
a visible token. `-GlobalVarsFile` names it; the summary says how many globals were loaded, and says
`0` explicitly rather than staying silent, so a missing file cannot look like a feed that uses none.

`-Variables` accepts a name only (`recordBusinessDate`), in which case the header is the name.

## 6. Summary output, and why it is not decoration

The tool prints a summary in the `key=value` shape the OpenProteo PowerShell scripts already use (no
interactive prompts, no colour, so it can be an exec step later without changes):

```
feeds_seen=144
feeds_indexed=138
feeds_skipped_no_dataschema=6
rows_written=13871
displayschema_missing=11
orphan_display_columns=4
schema_path_conflicts=1
```

`orphan_display_columns` and `schema_path_conflicts` are the two numbers that make this a check as well
as a report. A run where both are zero says so explicitly rather than omitting the lines — the
`ElarCounters` rule: a line that only appears on failure trains people not to look for it.

## 7. The viewer

`feed_index_viewer.html`, repository root, opened from `file://`.

* **Zero external references**, asserted by scan, like `csv-viewer.html`. The three viewers this was
  modelled on (`EOR_viewer.html`, `CS_relationships_viewer.html`, `cmod_ielar_index_viewer.html`) load
  PapaParse and Chart.js from a CDN; this one does not, and reuses the CSV parser that already lives in
  `csv-viewer.html`. A census page that stops working when the proxy changes is a page nobody trusts.
* **No literal `\n` or `\r` anywhere in the JavaScript** — `String.fromCharCode(10)` / `(13)`, the
  standing UBS proxy rule, and it applies to regular expressions too.
* The file is loaded by picker or drag-drop, as `csv-viewer.html` does it.

### 7.1 The tree

```
SOURCE  (source_id — source_description)            n feeds
  └ FEED  (feed_id — feed_name)          PROD       n fields
      └ the record layout, as a TABLE
```

Three levels, not four. The leaf is a **table and not more tree rows**, because a record layout is a
table: seq, name, type, nullable, display name, display seq, viewable, anon type, with orphan rows
marked. Making each field a collapsible row would put a `+` in front of 15 000 items that have nothing
underneath them.

A feed's variable columns are shown as a small key/value block in the feed node's header, not as table
columns: they are constant for the whole feed and repeating them on every field row would be noise on
screen even though it is the right shape in the file.

`TARGET` is deliberately **not** a level. The request is to navigate SOURCE and feeds; target is
carried on the feed node and is searchable, and a second grouping level would make the same feed appear
in one place under one grouping and another under the other.

### 7.2 Search

One box, matching **every** field of the model at once — source id and description, feed id, name and
description, field name, type, display name, and the variable values — with `<mark>` highlighting,
non-matching branches hidden, and the ancestors of every match auto-opened. This is exactly the
semantics the XML and JSON tree views in `viewer.js` already have, and it is the one that works: a
search that only matched node titles would miss the field names, which is what people search for.

### 7.3 Navigation

* Per-node **`+` / `−` in a bordered box**, not a small triangle. Recorded lesson: the toggle is the
  only way to open a node and a small target is a bad target.
* **Expand all / Collapse all**, and **Collapse to sources** (level 1 only) — the state a 144-feed tree
  should return to, which "collapse all" alone does not give when it also closes the sources.
* **`‹ prev` / `next ›` match** with a live `n of m` counter; the current match is scrolled to and
  outlined, the same treatment `viewer.js` gives its go-to-row. `Enter` in the search box is `next`.
* A **SOURCE jump** dropdown, because with a filter active the top of the tree can be far away.
* Counts on every node (`n feeds`, `n fields`) and, while a search is active, `n of m` — a node showing
  3 of 97 fields must not look like a feed with 3 fields.

### 7.4 Sizing

144 feeds × ~100 fields is ~15 000 rows: small. But the leaf tables are still **built on demand from a
model parsed once**, not up front, and **search runs on the model** so it finds matches inside feeds
that have never been drawn. That is the architecture the XML and JSON viewers arrived at after the
first version did not scale, and there is no reason to rediscover it. It also means the page behaves if
the estate doubles.

## 8. Batches

| batch | content | gate |
|---|---|---|
| 0 | this document | Gate 0 answers below |
| 1 | `tools/Get-FeedSchemaIndex.ps1` + suite, dialect scans, alias-agreement harness | a real run over the real workflows dir |
| 2 | `feed_index_viewer.html` + jsdom suite | opened on the real index |
| 3 | `USAGE.md` / `README.md` paragraph, rendered through `docs.html`'s own `render()` | — |

Batch 1 delivers a usable answer on its own: the CSV opens in `csv-viewer.html` today. The viewer is
the second batch precisely so that the first one is not held up by it.

## 9. What cannot be verified here, said now rather than discovered later

* **Windows PowerShell 5.1.** The sandbox has PowerShell 7 for Linux. The dialect is asserted by the
  same six scans used for `Get-ElarDocumentIndex.ps1` (no `??`, no ternary, no `&&`/`||`, no
  `-Parallel`, no three-argument `Join-Path`, no literal backslash-n), each with a positive control
  proving the scan can fire. That is a syntax argument, not a run.
* **The real estate.** No workflow XML from the real installation and no real `dataschema.json` are
  available here. The suite runs against the repository's own `workflows/` and `samples/`, which
  exercise both schema dialects but are not the real data. The first run on the real directory is also
  the first measurement of `schema_path_conflicts` and `orphan_display_columns`.
* **`mvn clean package`** is not affected: neither deliverable is in the WAR. Nothing in this feature
  touches Java, and that is the point of §2.

## 10. Gate 0 — two questions still open, one answered

**G0.1 — Does the machine that will run the script see BOTH directories?** The tool needs
`orchestrator.workflows-dir` (for SOURCE, descriptions and variables) and the feed base dir (for the
schema files). If only one is reachable the design changes: without the workflows dir there is no
SOURCE and no variables, and the tree loses its first level; without the feed dirs there are no record
layouts at all. If they are on different shares, both paths are needed.

**G0.2 — Does any feed keep its schemas somewhere other than `<feedDir>\dataschema.json`?** §4.3 handles
it, but the answer decides whether that path is the normal case or the exception, and it is one command:

```powershell
Select-String -Path '<workflows-dir>\*.xml' -Pattern 'dataschema|displayschema|columnsSchema' |
  ForEach-Object { $_.Line.Trim() } | Sort-Object -Unique
```

Anything that is not `${feedDir}/dataschema.json` or `${feedDir}/displayschema.json` is the answer.

**G0.3 — Variable values raw or resolved? ANSWERED: RESOLVED.** This document recommended RAW; the
answer went the other way and §5.1-5.3 are written to it. The recommendation is left standing above
rather than deleted, because §5.2 — an unresolvable token stays a token instead of becoming an empty
cell — is the guard against the exact failure the RAW recommendation was arguing about, and it only
reads as a decision beside what it overruled. G0.1 and G0.2 still gate batch 1.

**G0.3a, raised BY the answer and now part of the gate.** Is `orchestrator.global-vars` readable from
the machine that runs the script? Resolution needs it (§5.3); without it every `${someGlobal}` degrades
to a visible token, which is honest but not what the column is for.

Optional, and it only changes a default: is `Both` wanted instead of `Dataschema` for `-Require` — that
is, should a feed with no `displayschema.json` be absent from the index rather than present with empty
description columns? §4.2 recommends present.
