# OpenProteo — Usage Guide

OpenProteo is a pipeline workflow orchestrator: it prepares, validates and delivers the Legal Archive feeds.
Each **workflow** (a `feedId`) is an ordered chain of **steps** and **gates** that extract
data, transform it, validate it, anonymize/mask it and hand it off. Workflows are stored as
XML in the workflows directory and are designed visually in the **Workflow Designer**.

This guide is written in English and is the single source of truth for usage. It is viewable
inside the application from the **Docs** page (top bar) and shipped as `README.md`.

## Core concepts

A workflow has a unique `feedId`, an optional friendly `name`, a `sourceId` (originating
application), a `targetId` (one or more destinations, comma-separated), an optional `cron`
(6 fields; empty means manual only), an optional `baseDir`, free-form `variables`, and an
ordered list of `nodes`. Nodes are executed top to bottom.

There are four node kinds: **STEP** (does work via an executor), **GATE** (routes the flow on
a condition or waits for human approval), **LOOP** and **ENDLOOP** (repeat the steps between
them once per item of a list).

Variables are referenced as `${name}`. Resolution is iterative and innermost-first, so a variable can build the **name** of another variable (indirection / factory pattern): with `targetId=T1`, `${TargetDestination.${targetId}}` first becomes `${TargetDestination.T1}` and is then resolved to that variable's value. Unknown names resolve to the empty string. The engine seeds `feedId`, `parentId`, `sourceId`, `targetId`,
`feedName`, `runId`, `runDate`, layout paths (e.g. `feedDir`, `landingIn`, `landingOut`,
`stepDir`), `sharedDir` (the shared-files directory), `stepId` and `stepName` (the id and name
of the step currently running), plus every workflow variable you declare. A step can publish
output variables (printed in the log as `##VAR name=value`) that later steps can read. Variables common to **all** workflows (see *Global variables* below) are seeded first, with the lowest precedence, so built-ins and per-workflow variables override a global of the same name.

### Production environment flag

A workflow has a **Production environment** switch (default off). When ON, the workflow XML
carries `production="true"` and **every anonymize/mask step runs as passthrough**: the input file
is copied unchanged to the step output so downstream steps still get a file, but no masking is
applied. The **Clear History** button is also disabled for production workflows. A single step
can also be forced to passthrough with a `passthrough=true` param regardless of the flag.

### Step-by-step test from a step (▶▶ From here)

Each step also has a **▶▶ From here** button: it starts a step-by-step test from that step to the end of the workflow, off the main queue. The first step runs immediately, then the run **pauses** and the run page shows **▶ Continue (next step)** / **■ Stop**. All steps share ONE run, so output variables and files accumulate — the next step sees the previous step’s output. Start from step 1 to walk the whole workflow and produce inputs as you go, or from a later step if its inputs already exist on disk. Uses the SAVED workflow; gates and loops are not evaluated; refused only if the same feed has a normal run active.

### Test a single step (step-by-step config)

Each step in the designer has a **▶ Test** button. It runs that one step **immediately on a separate executor**, off the main FIFO queue, so it works even while other feeds are running. It uses the **last SAVED** workflow (save first), runs the step once, writes to this feed’s normal step folders and opens the standard run page (live console + Stop) in a new tab. Because outputs persist in the step folders, testing steps in order gives a true step-by-step run: step N reads step N-1’s output. Testing is refused only if the **same feed** has a normal run in progress (to avoid clobbering its working files); concurrency with other feeds is fine. Caveat: a step that relies on LOOP-node iteration context (${item}) is run once without that context.

### Parallel runs (adaptive scheduler)

By default OpenProteo runs one workflow at a time. Set **orchestrator.max-parallel-runs** (external application.properties) above 1 to let DIFFERENT feeds run in parallel; the same feed is always serialised. An ADDITIONAL run starts only while at least **orchestrator.run-admission-headroom-mb** (default 256) of JVM heap is still free — the first run always starts. A background scheduler re-checks every **orchestrator.scheduler-tick-sec** (default 20s) and admits runs that were deferred for lack of memory. Watch "Parallelism (admitted / max)" and heap in the Operations Resources panel while raising the limit.

### Operations: resources, clickable rollup, last run/success

The Operations page now shows, top to bottom: a **Resources** panel (JVM heap used/available, processors, load average, running/queued/waiting and test-run counts; refresh 5s); the **By source** rollup FIRST, with **clickable** totals — click any tile or any number in the table to drill down to the matching feeds, each showing **last run** (status + time) and **last success**; then **Executions in progress**, which now also shows the **Target** and each feed’s last run / last success. Test runs are excluded from the production rollup and last-run stats.

### Operations board

The **Operations** page (link on the dashboard, or `/overview`) shows two things, refreshed automatically: **Executions in progress** — every queued/running/waiting run across all feeds, with its current step, progress, an **Open** link and a **Stop** button (auto-refresh 3s); and **By source** — a rollup of every feed grouped by source, counting *not run / running / success / failed / aborted / other* (based on each feed's latest run), with totals and a per-source mix bar (refresh 20s, or the Refresh button).

### Bulk: schema only

The Bulk page has a **Mode** selector. **Schema only** ignores the template and does **not** modify any workflow: for each CSV row it (re)writes `dataschema.json` / `displayschema.json` into the matching **existing** feed (matched by `feedId`), from the `dataschema` / `displayschema` columns. Use it to restore or refresh schemas for many feeds at once without regenerating their workflows. Rows whose feed does not exist are skipped.

### Clear History

**Clear History** deletes run records, step logs and step working directories. Uploaded files
(dataschema/displayschema), the declared input and the audit trail are always kept. It is
irreversible and asks for confirmation, and the dialog offers two options:

- **production confirmation** — when the selection includes a production workflow you must tick
  an explicit checkbox to proceed (production is no longer blocked outright, but it can never be
  cleared by accident);
- **keep the most recent run** — clears the whole history except the last run, so the feed keeps
  its latest evidence.

It is available on the workflow editor (this feed only) and on **Operations**, where it clears
the history of every feed currently selected in the drill grid.

### Editing the generated XML

Under *Generated XML* the designer can open a direct XML editor: paste/edit a full workflow,
then *Validate & save XML* — it is parsed/validated and, on success, the page reloads on the
saved feed. Handy to clone a workflow by changing a few details.

### Publishing output variables from a script (`##VAR`)

A script publishes a variable by printing a line to **stdout** in the form:

```
##VAR name=value
```

The marker is `##VAR ` (two hashes, `VAR`, one space). The engine splits on the **first** `=`,
so the value may itself contain `=`; the name and the value are trimmed. Each captured variable
is exposed to the following steps in two forms: globally as `${name}` (last writer wins) and
namespaced as `${<stepId>.name}` (preferred, collision-free). If a step emits
`##VAR outputFile=...`, that path also becomes the canonical `${<stepId>.outputFile}` handle.

PowerShell (`.ps1`):

```powershell
$out = Join-Path $env:TEMP 'eor_clean.csv'
# ... produce the file ...
Write-Output "##VAR outputFile=$out"       # -> ${<stepId>.outputFile} and ${outputFile}
Write-Output "##VAR rowCount=$($rows)"      # -> ${<stepId>.rowCount}
```

`Write-Output` writes to the success/stdout stream, which is what the engine captures; if in
doubt use `[Console]::Out.WriteLine("##VAR outputFile=$out")`, which is the most robust. Do not
wrap the value in extra quotes unless you want them literally. The same works for `.bat`
(`echo ##VAR name=value`) and `.sh` (`echo "##VAR name=value"`).

Example: a step with id `extract` that prints `##VAR outputFile=D:/landing/out/eor.csv` lets a
later step read it as `${extract.outputFile}`.

## Step working directories: why `10_`, `20_`, ...

Each step gets its own working directory under the feed folder, named `NN_<stepId>` where
`NN` is the step's **execution order × 10** (`00_`, `10_`, `20_`, `30_`, ...). The number is
**not a version** — the `stepId` is already unique. The numeric prefix exists for two reasons:
the folders sort in execution order on disk, and the ×10 gap leaves room to insert new steps
between existing ones without renaming everything. So `20_validate` simply means "the step
whose id is `validate`, third in the run order".

## Executors

A STEP runs one executor. Built-in (internal) executors:

**Datasources are plain JDBC, and no driver is bundled.** A connection is either **JDBC — any database** (a URL plus a driver class) or **IBM i — native** (host, user and password, which the `ifscopy` executor also uses for the file transfer). The Datasources page offers **presets** that fill in the URL shape and the driver class for Oracle, Microsoft SQL Server, PostgreSQL, MySQL, MariaDB, IBM DB2, IBM i, H2 and SQLite; the URL is a template to edit, and an address you have already typed is never overwritten by choosing a preset.

OpenProteo deliberately **ships no database driver**. Bundling one per vendor would drag several transitive dependency trees through the internal Nexus for a feature most feeds never use. Put the driver JAR in the servlet container's shared library directory — `CATALINA_HOME/lib` — and restart it; it is then found at connection time, exactly as the IBM i driver already is. If it is missing, **Test connection** says which class it looked for and where to put the JAR, instead of reporting a bare "class not found". Existing connections are unaffected: the stored `type` values are unchanged, so every connection configured before this keeps working untouched.

- **sql** — run a query against any JDBC datasource and stream the result set to CSV. The executor is plain JDBC and is tied to no vendor: it works with the **JDBC** datasource type, where you supply the URL and driver class (the Datasources page has presets for Oracle, SQL Server, PostgreSQL, MySQL, MariaDB, DB2, IBM i, H2 and SQLite), and with the **IBM i native** type, which is the same JDBC connection plus the credentials the IFS file copy needs.
  Write `{{columns}}` in the query and set the step's "Column list from dataschema" field to
  the dataschema JSON path (param `columnsSchema`, e.g. `${feedDir}/dataschema.json`); at run
  time `{{columns}}` is replaced by that schema's column names (optionally double-quoted).
  Can also split the export into parts by row count and/or size (see Splitting below).
- **split** — split an **existing file** into parts by rows and/or MB, using the same logic
  as the SQL export. Use it to run a LOOP only over the final steps, after validation and
  anonymization (see Splitting and Loops).
- **csvsql** — run an arbitrary SQL query (joins, aggregates, subqueries, CTEs, window
  functions) across several CSV files and stream the result to a new CSV. Each `<input>` is a
  CSV path plus a table alias the query uses; you write only the SELECT over those aliases — all
  H2 plumbing (staging via `CSVREAD`, the export) is generated and hidden. The output honours the
  same conventions as `sql`/`split` (delimiter, row/MB split, `${csvFile}`/`${csvFiles}`/
  `${csvParts}`/`${rowCount}`), so it is fully LOOP-compatible. All columns are VARCHAR: cast
  inside the query when you do arithmetic or date math, e.g. `CAST(col AS DECIMAL(18,2))` or
  `PARSEDATETIME(col,'yyyyMMdd')`. Fixed-width `yyyyMMdd` dates compare and sort correctly as
  strings, and string equi-joins preserve leading zeros (NDG etc.). It uses a temporary H2
  database created under `${stepDir}` and deleted afterwards. H2 is a **runtime-only** dependency
  (the code is pure JDBC); a `csvsql` step fails with a clear message if the H2 driver is not yet
  on the classpath (see `h2/README_H2.md`).
- **xlsx2csv** — read **one sheet** of an `.xlsx` workbook and stream selected columns to a CSV.
  Pick columns by header name or by column letter, set their order and an optional rename; leave the
  column list empty to keep every column in sheet order. All cells become **text** deterministically
  (not as Excel shows them): shared/inline/formula strings verbatim, booleans as `TRUE`/`FALSE`,
  date-styled numbers formatted with `dateFormat` (default `yyyyMMdd`), plain numbers as plain
  decimals (no scientific notation). The output uses the same conventions as `sql`/`csvsql`
  (delimiter, split, `${csvFile}`/`${csvFiles}`/`${csvParts}`/`${rowCount}`) **plus** `${outputFile}`
  (the first part), and carries **no BOM** so it drops straight into a `csvsql` `<input>` — chain
  several `xlsx2csv` steps, then a `csvsql` to join them. Reading uses the POI streaming event API
  (constant memory). **POI is a compile-time dependency**: the WAR will not build until POI and its
  transitives are on Nexus — run `xlsx/PoiProbe` first (see `xlsx/README_POI.md`).
Both business-date bound checks read **Date format** as OpenProteo's own date **mask** — `YYYY/MM/DD`, `YYYYMMDD`, the same shape the `sql` executor takes — and not as a `java.time` pattern, which is the same reading the other date checks have always used. The two dialects disagree on exactly the letters that matter: in `java.time`, `DD` is the day of the **year** and `YYYY` is the week-based year. Keep writing the mask as you always have — and a feed that already writes a `java.time` pattern such as `yyyy-MM-dd` keeps working too, because only `Y` and `D`, the two letters where the dialects disagree, are rewritten. The mask is usually a workflow variable, so it can differ from feed to feed; if it is still unresolved at run time the check reports the **variable** as undefined rather than blaming the format.

**validate: business date not in the future.** A new check, **Business date not in the future**, fails the step when a row's business date is later than **today**. It needs the business date column and the date format, like the other business-date checks, and needs no configuration otherwise; set **Business date max** only when a feed legitimately carries forward-dated records, in which case that date becomes the bound. The bound itself passes — only a strictly later date fails. Unlike every other check it is **on for every feed**, including ones written and run long before it existed. The other checks are a positive list, so a workflow whose XML already carries `checks="…"` could never pick up a new id, and rewriting every definition to add one is not a deploy anybody should have to do. This one is therefore on **unless a step turns it off**: unticking the box writes `businessDateNotFuture=false` on that step, and ticking it removes the param again. So it applies everywhere by default and stays de-selectable one step at a time.

**Which datasource a step ran against.** Every executor that takes a datasource — `sql`, `sqlreport` and `ifscopy` — publishes it as a step output, so it is recoverable afterwards as `${<stepId>.dataSource}`, and unqualified as `${dataSource}` like any other step output (the unqualified name is therefore the *last* SQL step that ran, while the qualified one is stable per step). It is also seeded as `${dataSource}` **before the step runs**, alongside `${stepId}` and `${stepDir}`, so a step can name **its own** datasource inside its own parameters and queries — a reconciliation query stamping which system it counted, typically. That seeding is what makes it work *during* the step; the step output is what makes `${<stepId>.dataSource}` available to *later* steps and puts it in the audit trail. The output is written as the **first** thing the step does, before the datasource is even looked up, so the value is there **even when the step then fails** — which is exactly when the question matters. A step with no datasource leaves the value alone rather than clearing it, exactly as every other step output behaves, so the unqualified `${dataSource}` is the last SQL step that ran and still has a value in OUTPUT DATA at the end of a run whose last step is not a SQL one. It is also what makes the audit report say which database a run actually hit. For convenience the same value is published under both `dataSource` and `datasource`: the XML attribute is spelled all lowercase while every other step output is camelCase, and either spelling would otherwise resolve to an empty string in silence.

- **sqlreport** — run a list of **read-only** queries against one JDBC datasource and write a single **Markdown evidence report**. No CSV and no data file: the report *is* the deliverable, so it carries everything a reader needs to trust it — the statement **as executed** (after `${var}` substitution), its own timestamp and duration, the datasource / host / user / database, the run id, and the **real row count even when the rendered table is truncated** to Max rows (default 200), because a truncated table hiding the real number would be misleading in an audit document. Values are rendered verbatim with `|` escaped; a password is never written, only the datasource id, host and user. Queries are configured one at a time with **[＋ Add query]** and are stored as one `<reportQuery>` XML element each (not a delimited string), so a `;` inside a statement cannot corrupt the workflow definition. Note the element is `reportQuery`, not `query`: `<query>` already holds the single statement of the `sql` executor. **Read-only enforcement has two levels and both run before anything is executed**: every statement must be a single `SELECT` or `WITH` — comments are stripped and the content of quoted literals is ignored, a second statement after `;` is refused, and a DML/DDL keyword anywhere is refused, which is what catches a data-modifying CTE such as `WITH x AS (...) DELETE FROM t` — and the JDBC connection is opened with `setReadOnly(true)` plus the step's TIMEOUT SEC as query timeout. **This is a net against mistakes, not a guarantee**, and the report says so itself: a `SELECT` can still call a function with side effects and a driver is free to ignore a read-only connection. The real guarantee is the rights of the database account the datasource connects with — that is the right place for it. If any query is rejected, nothing is executed at all. The **Format** selector writes the report as `.md` (the default), as `.docx`, or as **both**; the Word file is a rendering of the same Markdown and `${reportDocxFile}` names it. Optional **Fail on empty** (off by default) fails the step when a query returns zero rows, useful for "this must reconcile" checks; the report is written anyway, so the evidence survives the failure. Outputs `${reportFile}`, `${queriesExecuted}` and `${rowsTotal}`. Query results can also be **collected into run variables** — see the sqlreport notes below.
- **mask** — deterministic streaming masking of a CSV (constant memory). Strategies are driven
  by the displayschema; pool-based strategies (names, cities, company parts) pick their values
  from selectable pool files (see Masking pools).
- **encoding** — convert a file (or a whole directory) to UTF-8.
- **filecopy** — copy / move / list files.
- **dequote** — read an input CSV and write an output CSV with double quotes (escaped or not)
  stripped from the chosen text columns; re-quotes a field only when it still contains the
  delimiter or a newline (or never, with quoteIfNeeded=false). Records can optionally be read as
  **logical rows**: when a quoted field contains a real line break the physical lines are joined
  so that every record stays on one line — **Line breaks inside quoted fields** offers `keep`
  (**the default**: nothing changes, the record stays split), `space` (the break becomes a space)
  or `strip` (the break is removed). **Drop blank lines** (default no) removes empty lines.
  Reports `${dataRows}`,
  `${columns}`, `${quotesRemoved}`, `${blankLinesRemoved}` and `${embeddedNewlinesRemoved}`.
- **safecopy** — copy files matching one or more wildcards (comma-separated, e.g. `*.md5, *.tar`) from one directory to another, writing each
  file as `<name>.on_fly_` and renaming it to the final name only after the copy completes
  (atomic move when possible). Prevents a downstream watcher from picking up a partial file.
- **ifscopy** — copy from an IBM i IFS path to local. This is the one executor that needs the **IBM i native** datasource type, because it reuses that connection's credentials for the file transfer.
- **csvreplace** — string substitution inside CSV columns.
- **validate** — run a checklist of validations over a CSV.
- **anonymize** — ARX-based CSV anonymization (statistical; in progress).
- **setvar** — assign workflow variables. Each assignment is `name = expression`, where the expression is resolved for `${vars}` first and then, if what remains is a chain of whole numbers joined by `+` and `-`, evaluated **left to right** with no operator precedence: `${A} + ${B} - ${C}` gives one number. A **space on each side of every operator is required** — that is a guard, not a formatting rule: without it a literal like `2026-08-05` would be read as arithmetic and silently become 2013, so any value with no spaces (a path, a date, a `;`-separated list) passes through untouched. Anything that is not exactly a chain of integers is also left as it is, which is the normal case. Only `+` and `-` are evaluated; `*` and `/` are not. An overflow returns the expression unchanged rather than a wrapped number. Note that assignments within one step cannot refer to each other: every parameter of a step is resolved before the step runs, so a value computed from another assignment needs a second `setvar` step.

External executors run a PowerShell (or other) script from the scripts directory or an
absolute path; the script path can use `${alias}` of an uploaded executable.

**Using generated files as a source.** Files produced by a run (the `output` files you see in the
Feed Files panel, e.g. `10_SQL_EXTRACTION/...csv`) can be fed straight into a later `csvsql`
`<input>` or an `xlsx2csv` `source`. In the designer they appear in the path autocomplete as
`${feedDir}/<relative-path>` (type to filter). In the Feed Files panel each row has a **Copy path**
button that copies the **feed-relative** path; you can paste that bare relative path as a source —
relative paths are resolved against `${feedDir}`, so both forms point at the same file. Absolute
paths and `${landingOut}/...`-style paths are used as-is.

**csvsql notes.** Each input's field separator is **auto-detected** from its header row (comma / semicolon / tab / pipe); set the per-input **Sep** field to force one. The input separator is independent of the output **Delimiter**, so you can read comma CSVs and still write semicolon output. Inputs are read with H2 `CSVREAD`. **Performance:** for large inputs list the join/filter columns in each input's **Index cols** (e.g. `NDG,CODCLI`) — they are indexed after load and speed up complex queries dramatically. The **Engine** selector picks the H2 backend: `auto` (default) uses a fast in-memory DB below `orchestrator.csvsql-mem-max-mb` (default 512 MB of total input) and an on-disk DB above it; `mem` forces in-memory (fastest, but uses heap — raise `-Xmx` or switch to `file` if you hit OutOfMemory on very large joins);  `file` forces on-disk. A csvsql step now honours **TIMEOUT SEC** (else the app default, 1800s): H2 aborts a runaway query instead of running for hours, and the step is marked timed-out. **Avoid OR conditions in JOINs** (e.g. `ON b.x=a.y OR b.z=a.w`): they cannot use an index/hash join and degrade to a nested loop (rows x rows), which is unusable at millions of rows — rewrite each OR-join as a UNION of separate equi-joins so each branch can be indexed/hashed. A UTF-8 **BOM** on an input is folded into
the first header cell, so a query that references the first column by name would not match;
`csvsql` writes its own output **without a BOM** so csvsql→csvsql chains are safe, but a `sql`/
`split` output (which carries a BOM) used as a csvsql input can hit this until BOM-stripping on
ingest is added. Empty fields are read as `NULL` (use `COALESCE(col,'')` when `''` is required).
Staging copies each input into the temp DB (≈ input size), so make sure `${stepDir}`'s volume has
room and pre-filter upstream for very large joins. Per-input separators/charset, an in-memory mode
for small inputs, opt-in join indexes and header-based column suggestions are planned follow-ups.

**sqlreport notes: collecting variables.** A query's **Collect** field lists result columns to publish as run variables, so a later step, a gate or OUTPUT DATA can use them. With **one row** the column becomes a scalar `${COL}`; with several rows it becomes a `;`-separated list, so `${COL[N]}` (1-based) picks a position. A single-column result with an empty Collect is published implicitly under its own column label. Add a **Key column** and every collected column additionally gets its companion `${COL.keys}` list, aligned position by position, which is exactly the pair `${COL@key}` resolves against: `${AMOUNT@CID12345}` returns the AMOUNT on the row whose CID is that key. An **absent key, or two lists of different lengths, resolve to the empty string** — never to a neighbouring row, which in a reconciliation would be far worse than nothing — and keys are compared trimmed and case-sensitively; duplicate keys make `${COL@key}` return the first match and are flagged in the report. Column names must be plain identifiers (letters, digits, underscore): give the column a SQL alias such as `AS N` otherwise, and note that a name colliding with a built-in run variable (`feedId`, `runId`, `stepDir`...) is refused rather than allowed to overwrite it. Because the lists are `;`-separated, a collected value containing a `;` or a line break has it replaced by a space and the report says how many values were touched — otherwise one value would shift every later position and misalign the keys. Collection is **not** limited by **Max rows**, which caps only the rendered table; it is limited by **Collect max rows** (default 5000, 0 = no cap), and a query exceeding it publishes **nothing at all** and fails the step, because a silently truncated list is a trap. Finally: collected values become run variables, which appear in OUTPUT DATA and are written to the audit trail — **collect counts, sums, statuses and keys, not personal data**.

**xlsx2csv notes.** The biggest trap is **codes stored as numbers**: if an NDG/CF/IBAN was typed as
a number in Excel, the leading zeros and/or precision were already lost in the source workbook (shown
as e.g. `1.23E+15`) and **cannot be recovered here** — such columns must be stored as text in the
xlsx. **Merged cells** keep their value only in the top-left cell (others read empty), so prefer a
single clean header row. **Newlines inside cells** (Alt+Enter) are preserved and the field is
RFC-4180 quoted. Workbooks using the **1904 date system** are handled via the `date1904` flag; other
dates would shift by ~4 years. **Formulas are never calculated** — only the cached value stored in
the file is emitted (empty if absent). The shared-strings table is read in memory, fine for typical
extracts. `.xls` (the old BIFF format), merged-header flattening and formula evaluation are
out of scope for this batch.

## Gates

A GATE routes the flow. An **auto** gate evaluates a `condition` and jumps to its `onTrue` or
`onFalse` target (a step id, or `END:<STATE>` to finish the run with that state). A **manual**
gate pauses the run and waits for a human decision; loop state and variables survive the pause.

## Loops (LOOP / ENDLOOP)

When a step produces several files — for example the SQL export or the split executor produce
`${csvFiles}` (a delimited list), `${csvParts}` (count) and `${csvFile}` (the first) — you can
repeat a **chain of steps** once per item with a LOOP block:

```
<step id="extract" exec="sql" ... csvSplitRows="100000"> ... </step>
<loop id="perFile" over="${csvFiles}" delimiter=";" itemVar="file" indexVar="fileIdx"/>
    <step id="mask" exec="mask" csvFile="${file}"/>
    <step id="send" exec="powershell" script="send.ps1"/>
<endloop id="endPerFile"/>
```

The steps between LOOP and its ENDLOOP run **sequentially, once per item**, exposing
`${file}` (current item), `${fileIdx}` (the index, **1-based**), `${loopCount}`, and a padded
index string. The padded variable (default name `loopIndexString`) is the 1-based index
left-padded with `0` to a configurable width N, e.g. `001`, `00005` — handy for ordered output
file names. An empty list skips the block. Blocks can be nested. In the designer use the
**Add loop** button (it inserts the LOOP + ENDLOOP pair) and set the index var names and pad
width there.

The engine has a safety limit `orchestrator.max-transitions` (default 500) against runaway
gate loops; for a loop over many files raise it (transitions are roughly files × steps in the
block). During a run the diagram shows the loop live: a back-arrow links ENDLOOP to its LOOP, an `iteration N / total` label appears near the LOOP, each body block carries a `xN` badge, and the arrow pulses while the executed blocks flash as the pass restarts.

## Splitting (SQL export and SPLIT step)

Both the **sql** executor (on export) and the **split** executor cut a file into parts:

- `max rows per file` (0 = no split) starts a new part every N data rows.
- `max MB per file` (0 = no split) starts a new part when the next row would exceed the size.
- Each part repeats the header; parts are named `stem_001.ext`, `stem_002.ext`, ...; output is
  UTF-8 with CRLF and an optional BOM.

Both publish the same variables: `csvFiles`, `csvParts`, `csvFile`, `rowCount`. Choose where to
split based on what is convenient: split at extraction to parallelize everything, or split late
(SPLIT step) so heavy validation/anonymization run once on the whole file and only the final
delivery steps loop over the parts.

## Masking pools

Pool-based mask strategies (first names, surnames, cities, streets, company parts) read values
from pool files. In the mask step you choose **which file** each category uses from a dropdown
— Italian or international, freely mixable (e.g. Italian animals with international colors).
Empty means the bundled default.

Pool files are bundled in the application. Setting an external directory
(`orchestrator.mask-pools-dir`) lets you **view and replace** them without a rebuild, from the
**Pool files** page (top bar). Replacements written there take priority over the bundled files.
The shipped name lists are intentionally fake: some inner letters are swapped (Marco → Macro).

## Bulk creation

The **Bulk create** page generates many workflows at once from a template plus one or two CSV
files: the first maps feed attributes (id, name, sourceId, targetId, **sourceDescription**, **targetDescription**, description, and inline
`dataschema`/`displayschema` JSON), the second maps a per-feed table name. Attribute fields
accept `{Column Name}` tokens mixed with literal text, e.g. `{Bank} - {ICTO Code}`; `targetId`
accepts comma-separated tokens for multiple destinations.

## Global variables and the Variables page

**Global variables** are shared by every workflow and are usable as `${name}` anywhere a variable is. They come from two sources, merged with *application.properties winning*:

1. a **properties file** edited in-app (default `<sharedDir>/global-vars.properties`, or set `orchestrator.global-vars-file` to an absolute path); and
2. **`orchestrator.global-vars.NAME=value`** entries in `application.properties` (ops-controlled, shown read-only in the UI).

They have the lowest precedence: any built-in or per-workflow variable of the same name wins.

The **Variables** page (from the dashboard) has two parts. At the top, the file-based global variables can be added, edited and saved; the application.properties globals are listed locked. Below, three multi-select filters (by **source**, **target** and **feed**) narrow each other progressively. Selecting a **single** workflow shows all its variables and step parameters, grouped by section and step, all editable. Selecting **several** shows only the variables they have in common (by name), and a value entered there is applied to every selected workflow. On save, the modified XML of **every** affected workflow is regenerated and validated with the runtime parser before anything is written: if any one fails, nothing is saved and the per-feed result is reported.

## Files

Each workflow has a **Workflow files** panel (documents and executables, the latter with a
unique `${alias}`). **Shared files** are available to every workflow. **Pool files** manage the
masking pools. All panels support upload, create, view, download and delete.

A step can also **write a shared file** by targeting `${sharedDir}` — e.g. a `sql` export
with `csvFile=${sharedDir}/report.csv`, a `split` output base, or a `filecopy` dest under
`${sharedDir}`. The file then appears on the Shared files page and is available to every
workflow.

## Deployment and configuration

The application is a WAR deployed on an external Tomcat. Environment-specific and secret
configuration lives only in an external `application.properties` under the Tomcat config
directory — never in the repository. Key settings include the workflows/scripts directories,
the datasources file, `orchestrator.masking-secret`, `orchestrator.mask-pools-dir`, `orchestrator.max-transitions`,
`orchestrator.global-vars-file` and any `orchestrator.global-vars.*` entries.

The deploy script syncs the latest package into the working copy (preserving `.git`), builds
the WAR, then commits and pushes using the `COMMIT_MSG.txt` shipped inside the package, and
finally restarts Tomcat. Documentation and commit messages are kept in English.

After deploying, hard-refresh the browser (Ctrl+F5) so updated CSS/JS are picked up.


### Maintenance lock

Any feed can be **locked** to block execution during maintenance. A locked feed refuses manual and scheduled runs; **step-by-step testing stays available** so you can still configure and verify it. Toggle it from the **Lock / Unlock** button on the dashboard, or the **Maintenance lock** switch in the designer. Locked feeds show a 🔒 badge on the dashboard, the workflow page and Operations, and their Run button is disabled.

### Variables in the home feed list

Set **orchestrator.home-list-vars** (external application.properties) to a comma-separated list of workflow variable names (e.g. `recordBusinessDate,businessDate`). Each becomes a column in the home feed list showing that feed’s value, and the values are included in the list search box — so you can search feeds by, for example, Business Record Date. Leave it empty for the default layout.

### CSV viewer: range search and sort

In the CSV table view, build FROM/TO range filters: pick a column, type a from and/or to value, and "+ Add range" (add several; they combine with AND, and each is a removable chip). Comparison is numeric when the values are numbers, otherwise alphabetical (so dates/codes work). Click a column header to sort (ascending, then descending, then off). Filtering and sorting run server-side; sorting very large results is capped at the first 300k matching rows (a notice is shown).

### JSON / XML viewer

.json and .xml files are pretty-printed on open in the file viewer — no need toenter Edit. Use Edit to change and save them.

### PROD badge

Feeds set as production show a red PROD badge on the home feed list and on the Operations board.

### CSV header: friendly names

In the CSV table view, each column header shows the **DisplayName** from the feed’s displayschema.json (matched to the column = ColumnName), with the technical ColumnName underneath. Feeds without a displayschema (or shared files) keep the plain column names.

### Editing step fields (incl. SQL query) across feeds

**Fields the designer edits with a dropdown are dropdowns here too.** A free-text box on this page would let one edit write a value the executor does not accept into every selected feed at once, which is the sort of mistake a mass editor makes easy to commit at scale. The option lists are taken from the designer, so the two pages cannot offer different values for the same field. A value already on disk that is not in the list is kept and marked *current, not a standard value*, rather than being snapped to the first entry — that would change a feed nobody asked to change.

The feed pickers are **Source**, **Target**, **Last status** and **Feed**, and they narrow each other. **Last status** is the status of each feed's most recent real run, the same one Operations shows, with `(never run)` offered as a value of its own so a feed that has never executed can be selected as such. Above the Feed list a **search box** narrows what that list shows — matching the feed id, its name, its source and target ids and descriptions, its tags and its status — and it deliberately **never changes the selection**: a feed you have already picked stays in the list even when it stops matching the text, so typing cannot silently drop it. **Clear selection** empties the search box too.

The Variables page edits not only workflow variables but also step **core fields** — above all the **SQL query** — and step params. Select one feed to edit its steps, or select several: a **Common steps** section appears with the step ids present in *every* selected feed, and editing a field (e.g. the query) applies the same value to all of them. Each change regenerates and validates the workflow XML before saving (all-or-nothing). A second section, **Steps missing from some feeds**, lists the step ids present in *some but not all* of the selection — the difference the intersection above would otherwise hide. Each is shown with a badge saying **in N of M feeds**, its executor, and the feeds it is missing from (the full list on hover), plus a read-only preview of how it looks where it does exist. When the same step id uses a **different executor** depending on the feed, that is flagged as a **conflict**: its fields do not mean the same thing everywhere, so it can never be mass-edited or mass-added. Each non-conflicting partial step also offers **＋ Add to N feed(s)**, which creates it in the feeds that lack it, copied from one that already has it. The mirror action, **✕ Remove from N feed(s)**, deletes the step from the feeds that DO have it — the other way of making a selection uniform. It asks for two confirmations rather than one, because it is the direction that can break a workflow, and the server refuses a feed where **another node still references the step** (`${STEP.…}` or `${dir.STEP}`), naming the referrer: deleting it would leave that resolving to an empty string, silently, at the next run. It also refuses a feed with a run in progress, a step that is not there, and the last remaining step. As always nothing is written unless every feed validates. Two things must be settled first, and the button refuses until they are: **where** to insert it — a dropdown of the steps common to the whole selection, defaulted to the position the step occupies in the feeds that already have it **only when they all agree**, because guessing a position in a pipeline is how a step ends up running after the send — and any field the source feeds **disagree** on, which is blanked and marked required rather than being copied from whichever feed happened to come first. Fields they agree on are copied as they are. PRODUCTION feeds in the selection require the same explicit checkbox as Clear History. Server side the request never carries a step definition, only the id of the feed to copy from; the insertion is refused if the feed has a run in progress (a structural edit would change what that run executes next), if the id already exists, or if the anchor is not in the target; and, as for every save from this page, each modified XML is regenerated and validated before anything is written, so nothing is saved unless every feed validates.

### Line breaks inside extracted values

A source column can contain a real CR/LF (a free-text NOTE, an address...). Written as-is, that record spans several physical lines and every downstream tool has to guess where a record ends. The **sql** step can normalise this at the source, where the column count is known from the query: **Line breaks inside extracted values** offers `keep` (**the default**: the value is written exactly as the database returns it), `space` (the break becomes a single space) or `strip` (the break is removed). The default is deliberately conservative, so feeds already in production are unaffected until you opt in on the step. The number of values that were normalised is published as `${newlinesSanitized}`. The same option applies to the **csvsql** step. The **dequote** step keeps its own recovery for files that were not produced this way: it reassembles a record whose quoted field was split across lines.

### Step mode: skip and on hold (pause)

Every step has a **Step mode** in the designer (in the row with Timeout / Retry / Retry delay):

- **normal** — executed as usual;
- **skip (passthrough)** — the step is not executed and its input is passed straight through to its output, so the downstream steps keep working on the same data;
- **on hold (pause)** — the run stops *before* that step with status **ON HOLD**. Operations shows a blue chip, the partial "N of TOT steps successful" count and the outputs produced so far. Resume with **▶ Continue (resume)** on the run page or on the Operations row.

Note the difference from a **manual gate**: a gate asks for an approve/reject *decision* and routes the run to `onTrue`/`onFalse` (status `WAITING_APPROVAL`), while *on hold* is just a pause that you release with Continue (status `ON_HOLD`).

### Mass-editing step mode (and other step properties)

The **Variables** page edits the properties that the selected feeds have in common — step fields, parameters, output data, on-success delete — and includes a **step mode** dropdown with the same options as the designer. This is the quickest way to put the same step *on hold* or *skip* on many feeds at once (for example to pause every feed before the delivery step). The hint under the dropdown tells you the current value, or that it differs across the selection.

### Variables matrix (▦ Matrix)

`/matrix` (linked from the dashboard and from the Variables page) is a spreadsheet-like editor: **one row per feed, one column per variable** (the union of every workflow variable), plus optional `tags` and `PROD` columns. The feed column and the header row stay fixed while you scroll.

- Type in a cell to change a value; **only the cells you touch are saved**, and they stay highlighted until you save.
- An empty cell means the variable is **not defined** for that feed: typing a value **creates** it on save. Use **+ Add column** to introduce a brand-new variable across the feeds.
- The **▾** button in a column header copies that value down to every visible feed.
- Arrow Up/Down and Enter move between cells, and pasting a block copied from Excel fills the cells to the right and below.
- Filters: feeds, column names, and **only columns that differ** — which shows just the variables whose value is not identical across the visible feeds.

### Reading Markdown: the preview

A `.md` file opened in the viewer shows two tabs and starts on **Preview**: headings, tables, fenced code, lists and inline formatting rendered as they are meant to be read, with **Source** one click away for the raw text. This is what the audit report and the `sqlreport` report are for — a reconciliation table read as raw pipes is the one thing it was never written to be.

The renderer **escapes everything first and only then adds markup**, which is not a theoretical precaution here: these reports carry values taken straight out of a database, and a `<` or a stray tag in a column must never become part of the page it is being read in. Links are kept only when they point at `http`, `https` or a relative target.

### Reading JSON: the table view

A `.json` file opened in the viewer shows two tabs and **starts on Table**, with **Code** one click away for the re-printed source. The reason for the default is the same as the reason the view exists: a list of entities is what people come to a JSON file to read, and reading it as text is the thing it was never meant to be.

- an **object** becomes a titled block with a **Key | Value** table;
- an **array of objects** becomes **one table whose header is the union of the keys** found across its elements, one numbered row per element. A key that a given element does not have is left visibly blank rather than silently empty, so a ragged list reads as ragged;
- an **array of scalars** becomes a numbered two-column table;
- a value that is itself an object or an array shows a short summary — `{ 4 keys }`, `[ 12 items ]` — and a **+ / −** button that opens it in a full-width sub-row, so a deep document stays navigable instead of becoming a wall.

Strings, numbers, booleans and `null` are coloured apart, and `null` is shown as `null` rather than as an empty cell, which is a different thing. **Expand all** / **Collapse all** work on the whole document, and one search box matches **keys and values** at once, highlighting matches, dropping branches with none and opening the path to every hit. A file that is not valid JSON says so, quotes the parser's own message, and stays readable under **Code**.

The same viewer exists as a **standalone page, `json_viewer.html` in the repository root**, beside `csv-viewer.html` and `xml_viewer.html`: browse or drag-drop, same table, same search, its own theme switch, and no upload, no network and no external library. The standalone page does three things the in-app tab does not yet:

**Several files at once.** Each file gets its own tab with its own table, search and expand state. A **memory budget** across all open files is counted in **model records**, not in megabytes, because the model costs about eighteen times the file: a 2.1 MB document measured 168 329 records and 37 MB of heap, so a megabyte limit would be wrong by an order of magnitude. The count is taken from the parsed document **before** the model is built, so a file that would not fit is refused without ever being materialised and **the files already open are untouched** — the page says by how much it overflowed, so you can choose between raising the limit and closing something. The default is 400 000 records and the field next to the bar changes it. Lowering it below what is already open closes nothing: it only refuses the next file.

**Declared relationships.** When a file is added the page asks whether it contains references between entities — after parsing it, because before that there are no fields to offer. Answering yes opens a panel where a relationship is declared as *child file · entity list · field* → *parent file · entity list · field*. The entity lists are every array-of-objects in the document, named by path (`$.records`, `$.records[].relationships`) and merged, so a list nested inside five thousand rows is **one** entry. From the second file on the parent side can be another open file, which is all a cross-file relationship is. Adding a relationship immediately reports what it did on **both** sides — how many distinct values each field has, whether each is **unique**, and the share that resolves **in each direction**. That detail exists because a bare resolution rate cannot catch a relationship declared backwards: on a real customers file the inverted direction resolved 3 315 of 5 000 and looked perfectly healthy. Two signals do catch it and both are checked — a **PARENT field is an identity** so it should be unique, while a child field is a reference and usually is not; and the correct direction resolves a higher share. When they agree the page says **these sides look swapped** and offers to turn the relationship round in one click; **⇆ Swap sides** does the same in the panel, before adding. A legitimately many-to-one key does not trigger it, because it needs both signals.

**The relationship diagram.** A declared field becomes a link on **both** sides: clicking a child value follows it to the parent, clicking a parent value shows everything pointing at it. The diagram puts the focused entity in the middle. **On the left goes everything that points AT it**, on the right **what it points at** — the side follows the direction of the reference, not the wording of the declaration, because the direction is what anyone reads off a picture: a relationship row naming a customer is that customer's parent. Each badge carries the **first three scalar fields** of that entity, the file it belongs to, and — when it is nested — **the row it lives inside**. That last one matters more than it sounds: a customer whose own `relationships` list is empty can still have four relationship rows naming it, and without saying that those rows live inside *other* customers the diagram looks as though it invented them. Every badge re-focuses the diagram on itself, so the graph is walked one hop at a time — which is also why it scales: five thousand entities cannot be drawn at once, one entity and its neighbours always can. **Previous entity** retraces the walk and **Back to the data** returns to the table. A child value that resolves to nothing gets a dashed red **NOT FOUND** badge rather than no badge at all: in a data set that is meant to be clean, a dangling reference is the finding.


### Reading XML: the table view

An `.xml` file opened in the viewer has two tabs. **Code** is the formatted source. **Table** shows the same document as tables rather than as indented text, which is the difference between reading a file and reading its data:

- a single element becomes a titled block with an **Attribute | Value** table — the name in one cell, its value in the cell beside it;
- a run of sibling elements that share a tag becomes **one table with a header**: the columns are the union of their attribute names, one row per element, numbered. That is what a list of `<var>`, `<step>` or `<Obs>` actually is. An attribute that a given row does not have is left visibly blank rather than silently empty;
- a row whose element has children of its own gets a toggle that opens that element's own block underneath it, inside the table.

**Expand all** / **Collapse all** work on the whole document, and one search box matches **tag names, attribute names, attribute values and text content** at once. Matches are highlighted, branches with no match are dropped, and the path to every match is kept and opened so a hit is never buried. Element headers, table headers and value cells have distinct backgrounds and every colour comes from a theme variable, so the light and dark themes are both legible. A file that is not well-formed XML says so and stays readable under **Code**.

The document is walked once into a small model and the tables are rendered from it on demand, so a large file does not build tens of thousands of cells before the first paint, and the search still finds matches inside branches that have never been drawn.

The same viewer exists as a **standalone page, `xml_viewer.html` in the repository root**, alongside `csv-viewer.html`. Open it in a browser, choose a file or drag one onto the page, and it behaves identically. Everything happens in the page — no upload, no network, no external library — so it can be used on a machine with no access to OpenProteo, and the file never leaves it.

### `audit_report.md`: the evidence report of a run

Any **successful** run can be turned into a single document at `{feedDir}/_logs/runs/{runId}/{feedId}_{runDate}_audit_report.md`, next to that run's step logs. The file name carries the feed and the run date on purpose: the moment a report is pulled out of its folder — which is what happens as soon as one is attached to an email — a name like `audit_report.md` is indistinguishable from every other feed's. It is written on request, never automatically, and writing it again simply overwrites it.

The same report can be produced as a **Word document** instead: the buttons come in pairs, **.md** and **.docx**. The .docx is a rendering of the very same Markdown, so the two files can never say different things about a run.

Two ways to ask for it. From **Run history**, each successful run carries **⬇ audit report .md** and **⬇ .docx** beside its **open ▷** link. Clicking one **creates the report and downloads it in the same action** — a copy still stays under `_logs/runs/{runId}/`, because that is where it belongs as evidence, but you do not have to go and find it. If the report cannot be produced, the reason is shown instead and nothing is downloaded. From **Operations**, select feeds and use **⎘ Audit .md** or **⎘ Audit .docx** in the action bar: that writes the report for the **last run** of each selected feed, skipping the feeds whose last run did not succeed and reporting how many were skipped. Only SUCCESS runs are accepted — a failed run's evidence is its log and its audit trail, and calling a document for it a "report" invites it being read as a delivery record.

The document opens with the run summary (feed, workflow, run id, trigger, who started it, start, end and computed duration, and the parent id when the feed is a version), then an **Output data** section reproducing exactly the list the Operations grid shows in its "output data" column for that run — same variables, same labels, same order, because both are built from the same resolution of the workflow's declared output data. A variable declared but never produced by that run appears with an empty value rather than being dropped, so the reader can see it was expected.

When a step produced its **own** report — an `sqlreport` step writing a Markdown file — that report is **embedded under that step**, so the queries, their results and the evidence they produced sit next to the step that ran them instead of in a separate file that has to be found and matched to the run by hand. Its headings are pushed down three levels so it nests under the step rather than competing with the audit report's own structure; nothing else is rewritten, and the tables, the SQL and the numbers are the file as it was written. A report that is no longer on disk, is larger than 2 MB, or was produced only as `.docx` is named rather than embedded.

Then one **paragraph per step**, in execution order, each with its status, exit code, attempts, **start and end timestamp and the duration between them**, its validate checks when it has any, the variables that step published, and its **standard output** — the same lines the run page shows when you click **open** on a step, so for a `sql` step the executed query is in the report. Manual and automatic gates appear in the same sequence with their condition, outcome and who decided: a report that silently dropped the approval step would not be evidence. A very long log is shortened to its first 100 and last 400 lines with the omission marked, keeping the head because what an auditor opens the report for — the query, the datasource, the parameters — is printed at the start of a step log.

One limitation is stated in the report itself when it applies: the per-step attribution of variables comes from the namespaced `${stepId.var}` entries the engine records in the run, so a run older than that recording gets a note saying the per-step breakdown is unavailable for it. The Output data section is unaffected. Note also that the declared output-data list is the one in the workflow **as it is now**, so for an old run a variable added since will show empty and one removed since will not appear at all — which is precisely what workflow versions exist to avoid.

### Workflow versions

Saving from the designer a change that **adds or removes steps** on a workflow that has **already run** is intercepted: nothing is written, and a dialog offers to save it as a new **version** instead. The reason is that the run history is audited against a definition — if the steps change under it, a past run no longer matches the workflow it says it executed, and a reconciliation done months later has no way to tell.

Only **STEP** nodes count. Adding, removing or re-arranging gates and LOOP/ENDLOOP is an ordinary edit and saves normally; so does reordering steps without adding or removing any, and so does any edit at all on a workflow that has never run. Renaming a step id counts as one removed and one added, because that is what it is as far as the history is concerned.

The default is **Save as a new version**: the next free id in the family, `tf0003819.v1`, then `.v2` and so on. Versions form a flat list under one parent, so editing `tf0003819.v2` allocates `tf0003819.v3`, not `tf0003819.v2.v1`. Gaps are never reused. The new workflow is created **with no cron**: the original keeps the schedule and remains the one that runs tonight, and the version runs only when started by hand — the dialog and the confirmation banner both say which is which. Uploaded files are copied to the version, exactly as for **Duplicate as new**. The original and its run history are left completely untouched.

To change the workflow in place anyway, tick **Overwrite ... instead** in the dialog. It is deliberately a checkbox rather than a second button: overwriting is the exception, and its label spells out the consequence — the past runs will no longer match the definition.

In **Operations** a version carries a badge reading `v1 of tf0003819`, and the original carries one reading how many versions it has; clicking either filters the grid down to the whole family, so a parent and its versions stop looking like unrelated feeds. If the original has since been deleted the badge shows only `v1` and says so on hover, rather than naming a workflow that is no longer there. Nothing is grouped or merged: they remain separate feeds with separate runs, and the badge is a signpost, not a relationship.

A version **inherits nothing at runtime**: runs, output data and the audit trail are per feed id, which is the point. `${parentId}` (see above) is what carries the link, and since `${feedId}` names directories and files, anything that must keep the **original** naming across versions — typically the delivered file name — has to say `${parentId}` explicitly.

Note that the mass **＋ Add to N feed(s)** action on the Variables page does **not** trigger this: it modifies the selected feeds in place and creates no versions. Producing one version per feed would leave a pile of unscheduled workflows and change nothing about what actually runs.

### `parentId`: the id a versioned feed descends from

A feed id ending in `.v<digits>` is a **version** of the id before that suffix: `tf0003819.v2` is a version of `tf0003819`. Versioning is a pure naming convention — a dot is already a legal character in a feed id, so nothing in the registry treats these feeds specially.

`${parentId}` is the feed id with **one** trailing `.v<digits>` stripped. The derivation is textual and total: on a feed that is not a version it equals `${feedId}`, so it is **never empty** and a query, a path or a report can use it unconditionally without its author knowing whether that particular feed happens to be a version. `tf0003819.v1.v2` gives `tf0003819.v1` (only the last suffix goes), and `tf0003819.v` is not a version at all because there are no digits. It is published everywhere `${feedId}` is: run variables, the designer preview and autocomplete, and tag resolution.

Two things to keep in mind. A version **inherits nothing**: runs, output data and the audit trail stay separate per feed id, which is exactly the point of versioning — `${parentId}` carries information, not a link. And since `${feedId}` is what names directories and files, anything that must keep the **original** naming across versions — typically the delivered file name — has to say `${parentId}` explicitly. That is the one decision an author has to make per step.

### `currentDate` / `currentTs`

`${runDate}` and `${runTs}` are fixed when the run starts. `${currentDate}` (`yyyyMMdd`) and `${currentTs}` (`yyyyMMdd_HHmmss`) are re-evaluated **before every step**, so a step resumed days after an ON HOLD pause — and every step after it — can use today's date instead of the date the run began.

### Indexing a list variable: `${list[N]}`

Variables such as `csvRowCounts`, `csvFiles` or `matchedFiles` hold a single `;`-separated string. `${name[N]}` returns the **N-th element, 1-based**, trimmed. Combined with the loop index this gives the value for the current iteration:

```
${csvRowCounts[${loopIndex}]}     rows of the file being processed
${csvFiles[${loopIndex}]}         path of the file being processed
${csvRowCounts[1]}                the first part
```

`${name@key}` looks the value up by key instead of by position: it finds `key` in the companion list `${name.keys}` and returns the value at the same position in `${name}`. A step that publishes a column indexed by a key column produces both lists, so `${AMOUNT@CID12345}` gives that client's amount without knowing its row number. A key that is not there, or two lists of different lengths, give an empty string rather than a neighbouring row.

`loopIndex` is 1-based, so `[1]` is the first element. An index out of range, or a missing base variable, resolves to an empty string.

### Output data and run variables: one value per line, with a total

In Operations each output-data variable is shown on **its own line**. When a value is a `;`-separated list of two or more items — typically `csvRowCounts` from an SQL step that split its output into several files — it is shown as a block with the **Σ total** (sum of the numeric values), the number of values, and each value on its own line in a small scrollable box. The same applies to the **Variables** panel of the run page; path lists (like `csvFiles`) wrap the same way but without a total.

### Operations: filtering and columns

- The **Sources** dropdown in the "By source" panel header filters the **whole summary**: the status tiles and the by-source table recount only the selected sources, and the drill grid follows. Inside the drill you can narrow further with the **Source** and **Target** multi-select filters and the free-text feed filter.
- The by-source table has one column per status: Not run, Running, **Waiting appr.** (paused on a manual gate), Success, Failed, Aborted, **On hold**. Every cell is clickable and drills into those feeds. The columns always add up to Total; an extra *Other* column appears only in the rare case of a feed in an unmapped status (e.g. rejected or skipped).
- Each source has a **weather icon** summarising it: 🌞 all successful · ⛅ done + still to run · ☁️ all still to run · 🌩️ some failed · ⛈️ all failed · 🌫️ on hold · 🌥️ waiting for approval · 🌤️ running · 🌦️ aborted.
- Feed **tags** are shown as badges, with `${...}` placeholders already resolved.

### Viewer: line numbers and "go to"

The file viewer numbers the rows and lets you jump straight to one:

- **CSV** — a fixed `#` column on the left shows the row number, and **go to row** scrolls to it and outlines it;
- **TXT / log** — line numbers in the gutter, plus **go to line**;
- **JSON / XML** — the pretty-printed output is numbered too (with the line count next to the file name) and supports **go to line**.

### Aggregate honours the active filters

In the CSV view, the free-text filter and the FROM/TO range filters also apply to the **Aggregate** tab, so group-by counts, DISTINCT and totals always describe the same rows you see in the table.

### Standalone CSV viewer for testers (`csv-viewer.html`)

`csv-viewer.html`, at the root of the repository, is a single self-contained HTML file that runs by double-clicking it (`file://`) — no server, no network, nothing to install. Hand it to testers who need to inspect a CSV without access to OpenProteo. Two clearly separated boxes let you pick the **CSV** (required) and its **displayschema.json** (optional, to get the friendly column names). It mirrors the internal viewer: same parsing (BOM, delimiter sniffing, quote-aware split), virtualised grid, per-column auto-width plus drag-resize, free-text filter, per-column range filters, click-to-sort, date formatting applied only to the cells on screen, and an **Aggregate** tab with group-by, DISTINCT COUNT, SUM, optional pivot, substring specs (`COL=L4` / `COL=R2`), a pinned TOTAL row and CSV export.

### Duplicating a workflow

**Duplicate as new** in the designer clears the feed id so you can type a new one and save. The uploaded files of the original feed (dataschema, displayschema, scripts) are **copied into the new feed's directory**, so the duplicate is a faithful copy and is ready to run without re-uploading anything.

### Deleting a run

Deleting a run removes its record, its step logs and its step working directories, and the run disappears from the run history. The **audit trail is deliberately kept**: the events of that run (including the deletion itself) remain in the audit log for compliance, they are simply no longer listed as a run.
