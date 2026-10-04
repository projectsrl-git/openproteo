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

**Datasources are plain JDBC, and no driver is bundled.** The Datasources page has a single **Database** dropdown listing every database it knows about: **IBM i — native** (host, user and password, which the `ifscopy` executor also uses for the file transfer), then **Oracle, Microsoft SQL Server, PostgreSQL, MySQL, MariaDB, IBM DB2 (LUW / z/OS), IBM i via JDBC only, H2 and SQLite**, and last **JDBC — any other database** for a vendor that is not listed. Choosing a database fills in the JDBC URL shape, the driver class and the connection-test query. The URL and the test query are templates to edit, and ones you have already typed are never overwritten; only the driver class is always corrected, being the field nobody remembers. What is stored is unchanged — `as400` for the native connection and `custom` for every JDBC one — so connections configured before this keep working untouched and reopen on the database their driver class identifies.

OpenProteo deliberately **ships no database driver**. Bundling one per vendor would drag several transitive dependency trees through the internal Nexus for a feature most feeds never use. Put the driver JAR in the servlet container's shared library directory — `CATALINA_HOME/lib` — and restart it; picking a database names the JAR to install right under the form. Mind the Java 8 runtime when you fetch it: Oracle needs **ojdbc8.jar** (ojdbc11 is Java 11 bytecode and will not load) and SQL Server needs the **jre8** flavour of `mssql-jdbc`. it is then found at connection time, exactly as the IBM i driver already is. If it is missing, **Test connection** says which class it looked for and where to put the JAR, instead of reporting a bare "class not found". Existing connections are unaffected: the stored `type` values are unchanged, so every connection configured before this keeps working untouched.

- **sql** — run a query against any JDBC datasource and stream the result set to CSV. The executor is plain JDBC and is tied to no vendor: it works with every database in the Datasources **Database** dropdown — Oracle, SQL Server, PostgreSQL, MySQL, MariaDB, DB2, IBM i, H2, SQLite or any other JDBC URL you type — and with the **IBM i native** entry, which is the same JDBC connection plus the credentials the IFS file copy needs.
  Write `{{columns}}` in the query and set the step's "Column list from dataschema" field to
  the dataschema JSON path (param `columnsSchema`, e.g. `${feedDir}/dataschema.json`); at run
  time `{{columns}}` is replaced by that schema's column names (optionally double-quoted).
  Can also split the export into parts by row count and/or size (see Splitting below).
- **json2csv** — read the JSON files matching a wildcard mask in a directory and write one flat CSV whose shape is the feed's dataschema, one row per file. A mapper pairs each dataschema column with a JSON attribute path, with per-column types (String, Number, Date, MIMEType, Serial, ObjectName). Splits by rows and/or MB like the SQL export. See The json2csv step below.
- **ftpsend** — deliver the files of a packaging directory to an FTPS server over explicit TLS, authenticating with a client certificate held in a PKCS#12 file. An ordered list of file masks decides what is sent and in what order; every upload is verified with `SIZE` against the local byte count and the first failure ends the step. See The ftpsend step below.
- **objpack** — build a Transarch **object submission**: renamed objects, metadata CSV, audit JSON, control file, `.tar` and `.md5`, from a source metadata CSV and a directory of objects. One CSV row per object. Objects are streamed into the archive from where they already are, so no temporary copy is made. See The objpack step below.
- **filerename** — rename the files of a directory according to a CSV mapping: the name on disk is built from a template and an id, the new name is a column. The whole mapping is checked before the first rename, and a collision stops the step with nothing renamed. The executor form of `Rename-FilesFromCsvMap.ps1`. See The filerename step below.
- **unarchive** — extract the archives of a directory (zip, tar, tar.gz/tgz, gz), each into its own folder. The format comes from the file's content, not its name; entries that would leave their folder, collide on Windows or are links are refused; limits stop archive bombs; nothing incomplete appears under a final name. See The unarchive step below.
- **objunpack** — the inverse of **objpack**: take one Transarch object package (`.tar` or `.tar.gz`, with its `.md5`) and give back its objects under their original names and its metadata CSV, ready to be rebuilt and resent. The original names come from the package's own audit and metadata files, never from a naming pattern. See "The objunpack step".
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
- **filecopy** — copy, move or list files from a directory by wildcard, or copy exactly the files named in one column of a CSV (see «Copying the files listed in a CSV (ifscopy, filecopy, safecopy)»). With a file list the step may only copy: `move` and `list` are refused rather than quietly downgraded.
- **dequote** — read an input CSV and write an output CSV with double quotes (escaped or not)
  stripped from the chosen text columns; re-quotes a field only when it still contains the
  delimiter or a newline (or never, with quoteIfNeeded=false). Records can optionally be read as
  **logical rows**: when a quoted field contains a real line break the physical lines are joined
  so that every record stays on one line — **Line breaks inside quoted fields** offers `keep`
  (**the default**: nothing changes, the record stays split), `space` (the break becomes a space)
  or `strip` (the break is removed). **Drop blank lines** (default no) removes empty lines.
  Reports `${dataRows}`,
  `${columns}`, `${quotesRemoved}`, `${blankLinesRemoved}` and `${embeddedNewlinesRemoved}`.
- **safecopy** — copy files from one directory to another, writing each file as `<name>.on_fly_` and renaming it to the final name only after the copy completes (atomic move when possible), so a downstream watcher never picks up a partial file. The files are chosen either by one or more wildcards (comma-separated, e.g. `*.md5, *.tar`) or by naming them in one column of a CSV (see «Copying the files listed in a CSV (ifscopy, filecopy, safecopy)»).
- **ifscopy** — copy from an IBM i IFS path to local, either by listing a directory and matching a pattern or by copying exactly the files named in one column of a CSV (see «Copying the files listed in a CSV (ifscopy, filecopy, safecopy)»). This is the one executor that needs the **IBM i native** datasource type, because it reuses that connection's credentials for the file transfer.
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

**csvsql and the case of column names.** H2 does not keep a CSV header as written. A header that
is a valid SQL identifier is stored **uppercased** — `trn_CSVAXRef` becomes `TRN_CSVAXREF`; one
that is not, such as `Transaction Num` or `weird-name`, is kept **verbatim**; and headers that
differ only in case are **renamed**, `dup` and `Dup` becoming `DUP` and `DUP1`. Three consequences:
a bare `trn_CSVAXRef` in a query happens to work; a quoted `"trn_CSVAXRef"` does not; and **the
output header of `SELECT *` comes out uppercased**. For most files that is cosmetic. For a
Transarch metadata file it is a schema change, and the archive does not accept header changes
without re-onboarding. The long-hand fix is an alias per column, `trn_CSVAXRef AS "trn_CSVAXRef"`.

**`{{columns}}` in csvsql** does that for you. Set **Column list from dataschema** to the feed's
`dataschema.json` and write `{{columns}}` in the query:

```sql
SELECT {{columns}} FROM SOURCE
```

It becomes the dataschema's columns, **in the dataschema's order**, each read from the column H2
actually stored and written back under the dataschema's exact name. The names are read back from
the staged table rather than guessed, because H2's rule has exceptions no hand-written expansion
would anticipate. A dataschema column the table does not have fails the step and lists what the
table does have; two dataschema names that would read the same column fail rather than duplicating
one and losing the other. With more than one input, set **Table it describes**. Unlike `sql` there
is no *Quote columns* choice: here the quoting is not a preference, and offering one would offer a
way to break it.

The step log's `staged SOURCE <- … (N rows)` now reports the rows actually loaded. It previously
printed the DDL update count, which H2 returns as 0 for `CREATE TABLE … AS SELECT` whatever was
staged — so every csvsql run ever logged `0 rows`, which was never a statement about the data.

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

### External scripts: PowerShell, cmd, jar and bash

An external step runs a file of yours through an interpreter. Which one is decided by the file's extension, or by the **Executor** field when you set it: `.ps1` runs with PowerShell, `.bat` and `.cmd` with cmd, `.jar` with `java -jar`, `.sh` with bash.

The four receive the step's parameters in different ways, and a script must be written for the way its runner uses:

- **PowerShell** — by name. A parameter called `InputFile` arrives as `-InputFile 'value'`, so the script declares `param([string]$InputFile)`. The order of the parameters does not matter.
- **cmd** and **jar** — by position. The values arrive as `%1`, `%2`, ... (or `args[0]`, `args[1]`, ...) in the order they are listed in the step; the names are only labels. Reordering the parameters in the designer changes what the script receives.
- **bash** — by name, as environment variables. A parameter called `inputFile` arrives as `$OP_inputFile`. The order does not matter, and nothing arrives as `$1`.

**bash parameter names.** The variable is `OP_` followed by the parameter name, with every character that is not a letter, a digit or an underscore replaced by an underscore: `inputFile` becomes `$OP_inputFile`, `dir.STEP` becomes `$OP_dir_STEP`. The prefix is there so that a parameter called `PATH` cannot change where the shell finds its commands. Two parameters that would become the same variable (`a.b` and `a_b`) stop the step before it starts, naming both.

```
#!/bin/bash
# step parameters: inputFile, outDir
wc -l < "$OP_inputFile" > "$OP_outDir/rows.txt"
echo "##VAR rowCount=$(cat "$OP_outDir/rows.txt")"
```

A value reaches the script exactly as written in the step, after `${variables}` are resolved: quotes, `$`, backticks, line breaks and accented letters are not interpreted and not altered. Always quote the variable in the script (`"$OP_inputFile"`), as with any shell variable.

**A bash script is always run through the interpreter**, never started as a program. So it needs no execute permission, and it runs even when the scripts directory is on a disk mounted `noexec`. The interpreter is `orchestrator.bash-exe`, `/bin/bash` unless configured, and it must really be bash: with another shell the step ends with exit code 126 and a line saying so.

**A bash step can be refused before it starts.** The step fails, nothing is run, and the reason is the first line of the step log, beginning with `!!! NOT STARTED`:

- the script has Windows (CRLF) line endings — the message gives the first line where one occurs. bash needs LF endings; a script edited on Windows must be saved with LF before it is uploaded. It is not converted for you, so that what runs is always the file you see in the scripts directory.
- two parameters map to the same variable, or a parameter has no name.
- the parameters together are larger than about 120 KB. Pass large data in a file and give the script its path.

**PowerShell on a Linux server.** When `orchestrator.powershell-exe` is not configured, the server uses `powershell.exe` on Windows and `pwsh` elsewhere. Scripts receive their parameters in the same way on both. A value you configured yourself is never changed.

**cmd and jar on a Linux server.** If the server was started without a UTF-8 locale, a parameter containing an accented letter cannot be passed to a program intact. The step is refused before it starts, naming the parameter, instead of handing the program a `?`. The Platform page shows whether the server has this problem. PowerShell and bash steps are not affected.

### What a timeout and Stop reach

When a step exceeds its timeout, or an operator presses **Stop**, the orchestrator kills the step. What that includes depends on the server, and the Platform page says which case applies:

- **On Linux** the step is killed together with everything it started: programs it launched, programs those launched, and background jobs whose parent script has already ended. The step log ends with a line beginning `!!! process tree:` that says how many processes were killed.
- **On Windows** only the interpreter is killed, as before. A program the script started keeps running until it ends by itself. The step log says `!!! process tree: interpreter only`.

**An experimental option for Windows.** Setting `orchestrator.windows-tree-kill=true` makes a Windows server also kill what the step started, with `taskkill /T`. It is off unless set, and it must be said plainly why: it was written without a Windows machine to run it on, and has been exercised only on its decisions, not on Windows itself. It is built so that it cannot kill the wrong program: the step is looked for among the server's own child processes by the moment it was started and by its command line, and if it is not found, or two running steps cannot be told apart, only the interpreter is killed, as before, and the step log says which of the two happened. It reaches the programs still attached to the step; one whose parent has already ended is not found. Try it on a test instance first, with a step that starts a long-running program and is then stopped, and read the `!!! process tree:` line.

One thing escapes even on Linux: a program that deliberately detaches itself completely from the script that started it (a daemon). Such a program is left running.

A script that starts a background program and then ends normally is not touched: nothing is killed when a step ends by itself.

**Stop on a parallel step stops every item.** A step that runs once per item of a list, several at a time, used to lose all but one of its running scripts on Stop: the others carried on to their end. Stop now reaches all of them.

### Moving a workflow from Windows to Linux

A workflow written on a Windows server runs on a Linux one, but Linux treats file names differently in a few ways that Windows hides. None of them produces an error by itself, which is why they are listed here. Open the **Platform** page on the new server first: it shows most of them before a run does.

**File names are case-sensitive.** On Windows `Prepare.ps1` and `prepare.ps1` are the same file; on Linux they are two. A script, an input file or a folder must be written in the workflow exactly as it is named on disk.

**A pattern is case-sensitive too.** In `filecopy` and `safecopy`, `*.csv` takes `DATA.CSV` on Windows and leaves it behind on Linux. The step does not fail: it copies what matches. So on a server where case matters the step log says `file names are matched case-sensitively on this server`, and when files were left behind for that reason alone it says how many, in a line containing `only if case is ignored`. If you see that line, fix the pattern or the file names.

**The order of the files.** `${matchedFiles}` from `filecopy` and `safecopy`, and the order in which `encoding` converts a folder, are in name order on Linux, ignoring case: `ab.csv`, `Alpha.csv`, `a_b.csv`, `beta.csv`. On Windows they stay in the order the disk returns them, as they always were, which for a local disk is normally the same. A step that runs once per file therefore numbers its items the same way on both. The `tiffcompress` report is the exception: it lists files in the order the disk returns them, on every server.

**A backslash is not a separator.** `${stepDir}\out.csv` works on Windows. On Linux it creates one file whose name contains a backslash, in the folder above, without any error. Write paths with `/`, which works on both. When a parameter looks like a Linux path joined with a backslash, the step log carries a line beginning `WARNING:` that names the parameter; nothing is changed for you, because a backslash is legitimate in other values, such as a search pattern.

**An input is never renamed over an older one.** The steps that rename what they have processed (`elarxml` and `json2csv` to `.done`, `ftpsend` with its suffix) refuse when a file with that name is already there, and say so, on Windows and on Linux alike. The older file is the record of the earlier delivery and is left untouched.

**What else to check on the new server.** External scripts need their interpreter there (see «External scripts»). An FTPS target saved with the Windows store must be edited once (see «The server certificate, on Windows and on Linux»). Paths that begin with a drive letter must be rewritten.

## Copying the files listed in a CSV (ifscopy, filecopy, safecopy)

The **Files to copy** dropdown on an `ifscopy`, `filecopy` or `safecopy` step chooses between two shapes. **directory + pattern** is what each executor has always done — list a directory and copy what matches the wildcard — and it is what a step with nothing set still does, unchanged. **The ones listed in a CSV column** copies exactly the files named in one column of a CSV, typically the output of an earlier step in the same workflow: an extraction produces a list of document paths, and the step fetches those and nothing else. An unrecognised value fails the step instead of falling back, because a typo answered by a directory copy with no pattern set is a copy of everything.

You give it the **file list CSV** (a `${dir.<stepId>}/…` path from an earlier step works, and so does a feed-relative one), the **column with the file name** (a header name, matched ignoring case, or a 1-based column number — a number is only tried when no column carries that name, so a column genuinely called `2` still wins), and optionally the **path to prepend**, the **delimiter** (empty = detected from the header, exactly as `csvsql` detects it), the **charset** (default UTF-8) and whether the file **has a header** (default yes).

**The path to prepend is for lists that hold bare file names.** A name that already says where the file is gets no prefix, so one step can read a column that sometimes holds a full path and sometimes a bare name — which is the realistic shape of a list produced by a query. When the prefix is left empty the directory field above it is used as the base instead (the **IFS source path** on `ifscopy`, the **source directory** on `filecopy` and `safecopy`), so a list of bare names needs no second copy of the directory it came from. Whichever base was used is written in the step log, so it never has to be inferred.

**What counts as a name that already says where the file is differs between the IFS and the local filesystem, deliberately.** On the IFS it is a leading `/`. Locally it is a leading `/` or `\`, a drive letter such as `D:\landing\x.pdf`, or a UNC name such as `\\server\share\x.pdf`. The leading slash is in the local list for a reason worth knowing: on Windows a leading slash means the root of the **current drive**, so the platform itself does not call such a path absolute — and a list produced against a Unix-flavoured system, or by an earlier `ifscopy` step, carries exactly that shape. Without the explicit rule it would have been joined under the source directory. A name like `C:x.pdf`, a drive with no separator, is also taken as complete: it is relative to that drive's own directory, not to ours.

**A backslash inside a name is left alone in both cases, for opposite reasons.** On the IFS it is a legal character in a file name, and rewriting it would corrupt a genuine name to accommodate a Windows-flavoured list that source system does not produce. Locally it is a separator the platform already understands. When a base is joined to a name the separator follows the base: a base written with backslashes gets a backslash, one written with slashes gets a slash.

**The destination is one flat directory.** A list can name files spread across many directories; they all land beside each other under the destination, keeping only their own file name, exactly as the wildcard shape has always done. Nothing rebuilds the source tree.

**Nothing is lost in silence.** Before any transfer starts the step logs how many rows it read, how many files it is about to copy, how many repeated names it collapsed (a file listed twice is copied once) and how many rows carried no file name at all, naming the first fifty line numbers. A row whose cell is empty, or that is too short to have that column, is counted rather than skipped invisibly; a physically empty line is not counted as a row at all.

**When a listed file is not there the step fails by default**, naming it; it can be set to skip and count instead, in which case the missing ones are counted in `${missingFiles}` and named in the log. **What differs is when the check happens.** `ifscopy` has deliberately no existence pre-scan: over the IFS, checking every file first would cost one round trip per listed file — for a list of thousands, the whole transfer twice over — and the destination there is a step working directory, so stopping at the first problem loses nothing a re-run does not recover. `filecopy` and `safecopy` **do** pre-scan, because locally the check is a stat on the very filesystem the copy is about to read: with the default policy the step therefore copies **nothing at all** rather than stopping half way through. That matters most on `safecopy`, whose whole purpose is that a process watching the landing zone never sees something incomplete — a half-done run would leave real, correctly renamed files there with nothing saying the delivery was short. A listed path that exists but is not a file, a directory most often, is reported separately from one that is not there at all, because the remedy is different.

**Two listed files whose paths differ but whose file name is the same** — `/one/x.pdf` and `/two/x.pdf` — would land on top of each other in the flat destination, and by default that fails the step before anything is copied. The alternative would be a step reporting success with fewer files than it copied. Set the collision policy to let the later one win only when that is genuinely what you want; the usual answer is to copy them in separate steps with different destinations.

**On `safecopy` the staging through `<name>.on_fly_` and the atomic rename apply to a listed file exactly as to a matched one.** One case is new: a listed name that **already** ends in the temp suffix is refused rather than skipped. Under a wildcard, skipping it is right — it is somebody else's in-flight file and nobody asked for it. In a list it was asked for by name, and delivering it would put a file in the landing zone under a name every watcher is built to ignore, which is a delivery that silently never arrives.

**On `filecopy` a file list may only be copied.** `move` and `list` are refused, at save and again when the step runs, naming the mode. They are not quietly downgraded to a copy and the setting is not quietly ignored: a CSV read as an instruction to remove files deserves a refusal rather than an inference. The `Mode` field stays visible under a list so a stored value can be corrected.

**The pattern field is ignored** in this shape, and if one is set the step says so in its log rather than leaving you to wonder which of the two rules applied. **Overwrite** keeps whatever it meant for each executor: on `ifscopy`, with it off, a file already present locally is left alone and counted in `${skippedExisting}`; `filecopy` and `safecopy` replace an existing destination file as they always have.

Outputs. `ifscopy` publishes `${filesCopied}`, `${bytesCopied}` and `${matchedFiles}` as before; `filecopy` and `safecopy` keep their own `${matchedCount}`, `${matchedFiles}` and `${bytesCopied}`, which is why they are not renamed for the sake of symmetry. All three add `${listRows}` (data rows read), `${listedFiles}` (distinct files the list asked for), `${duplicatesInList}`, `${blankNames}` and `${missingFiles}`, and `ifscopy` also `${skippedExisting}`. Comparing `${listedFiles}` with what was actually copied is the one check worth putting in a downstream `validate` step.

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

Pool-based mask strategies (first names, surnames, cities, streets, company parts) read values from pool files. In the mask step you choose **which file** each category uses from a dropdown — Italian or international, freely mixable (e.g. Italian animals with international colors). Empty means the bundled default.

**The street pool holds the COMPLETE street, type word included.** `streets_it.txt` contains `Via Garibaldi` and `streets_international.txt` contains `Oakwood Street`, `Bahnhofstraße`, `Rue Voltaire`, `Calle Serrano` — each in its own language and its own word order. The address strategy adds only the house number, so choosing the street file chooses the language too and there is nothing else to configure. If you replace a street pool from the **Pool files** page, keep the type word on every line: a bare-name list would silently drop it from every masked address. The step log states the pool in effect on every run, with its size and first value.

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

**Relationships are remembered.** Declaring a set of them is real work, and on a workspace re-opened every day it is the same work every day, so the declarations are kept in the browser (the same place the theme lives) and restored when the same files are added again. What is stored is **only the declaration** — file names, entity-list paths and field names. Never a value, never a row, never a parsed document: nothing that could be customer data is written anywhere. Files are matched by **name**, because an id only means something inside one session; a renamed file is therefore a forgotten file, which is the honest behaviour rather than guessing. A saved relationship whose two files are not both open yet simply waits. One whose entity list or field no longer exists in the file is **reported and skipped** — a file that changed shape under a saved declaration is worth hearing about, and it stays remembered in case an older version comes back. Closing a file drops the live relationship but keeps the saved one. **Forget saved relationships** clears the lot.

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

## Platform diagnostics

The **Platform** page (button on the dashboard, after Docs) shows what this instance thinks its host is: the operating system and the Java runtime, where the configured paths really point, which interpreters it can find, and whether file names are case-sensitive in the feed base directory.

It is read-only. It configures nothing and creates nothing: a directory that does not exist is shown as missing, never created for you.

Open it first on a new server, and again whenever a run fails on a path or an interpreter it cannot find. It is the quickest way to see that a server was started from the wrong folder.

### Host and Java runtime

The operating system, the architecture, the Java version and vendor, and the working directory (`user.dir`). Every relative value in the configuration, such as `./workflows`, is resolved against the working directory, which is why it is shown on the same page as the paths.

Two encodings are shown, and they answer different questions. The default charset is what the server uses for the CONTENT of a file when a step does not name a charset. The file-name encoding (`sun.jnu.encoding`) is what it uses for the NAMES of files.

On a Linux server started as a service without a locale, the file-name encoding is often `ANSI_X3.4-1968`, which is plain ASCII. Every file whose name contains an accented letter then fails, with an error that does not mention encodings at all. If you see that value, give the service a UTF-8 locale (for example `LANG=C.UTF-8`) and restart it.

On a server that is not Windows the row carries a verdict: `UTF-8`, or `not UTF-8` in red. The same encoding is used to hand parameters to cmd and jar steps, which is why those steps are refused when a parameter has an accented letter and the verdict is red.

### Paths

One row for each directory and file the instance is configured with: the workflows, scripts, shared and feed base directories, the datasources and FTPS targets files, the mask pools directory, the global variables file actually in use, the directory of the application log, and the Java temporary directory, where a workflow import is staged.

Each row shows the value as configured, the absolute path it resolves to, whether it exists, whether the server can write to it, and a verdict:

- `ok` — it exists, it is the right kind of thing, and the server can write to it.
- `missing` — a directory that does not exist. Create it on the server; the page will not.
- `absent, can be created` — a file that does not exist yet, in a folder the server can write to. This is normal on a new instance: the datasources file appears the first time a datasource is saved.
- `absent, cannot be created` — a file that does not exist, in a folder that is missing or that the server cannot write to. Saving from the application will fail.
- `not a directory`, `not a file` — something exists at that path, but it is the wrong kind: a file where a directory is expected, or the other way round.
- `not writable` — it exists, but the account the server runs as cannot write to it. On Linux this is the usual finding when the paths are left at their defaults: they point inside the folder the server was started from, which normally belongs to root.
- `not set` — an optional setting left empty, such as the mask pools directory. Nothing is resolved for it.
- `error` — the value is not a usable path on this server; the text beside it says why.

### Interpreters

One row for each interpreter the external executors use: PowerShell, cmd, Java and bash. Each is looked for **without being started**.

- `found` — a file is there; the resolved path is shown. It does not mean the interpreter works: a damaged installation is still found. No version is shown, because showing one would mean running it.
- `not found` — steps that use this executor fail on this server. This is expected for `cmd.exe` on Linux, and for `powershell.exe` unless PowerShell is installed there under that name.
- `cannot be determined` — the configured value is a relative path such as `tools/pwsh`. Each step runs in its own folder and the operating systems resolve such a path differently, so the page cannot tell. Use a bare name or an absolute path.
- `not configured` — the setting is empty.

The page states which search it used. On Windows a name without an extension gets `.exe`, and the search goes through the Java directory, the working directory, the Windows system directories and then `PATH`. Elsewhere only the directories of `PATH` are searched, and the file must be executable.

### Stopping a step

What a timeout or Stop can reach when an external step has started other programs. `whole process tree` means the step and everything it started. `partial` means programs still attached to the step are killed, but a background job whose parent has already ended is not; the reason is shown, usually that `setsid` is not installed. `interpreter only` means the step's own interpreter is killed and what it started carries on: this is the case on Windows, unless the experimental `orchestrator.windows-tree-kill` option is on, in which case Windows shows `partial`.

### Windows trust store

Whether this Java runtime has the Windows certificate store provider (SunMSCAPI). It exists only on Windows. Where it is absent, an FTPS target whose trust mode is `WINDOWS` fails when it connects; the FTPS targets page marks such targets, and each needs JVM cacerts or a truststore file instead.

### File-name case in the feed base directory

Whether `Report.csv` and `report.csv` are the same file there. The page measures it: it creates a small temporary file in the feed base directory, looks for it under a different case, and deletes it. That is the only thing the page ever writes.

The answer is about that one directory, not about the whole server. A feed whose own base directory is on another disk can differ.

The measurement is taken once and kept. **Probe again** repeats it, at most once a minute; asked sooner, the page shows the earlier result and says so.

If the directory is missing or not writable the result is `not determined`, with the reason. It is never assumed.

### Who can open it

Until sign-in exists, anyone who can reach the application can open this page. It shows no password, no secret and nothing read from the configuration files. It does show the operating system, the Java version and where the application is installed.

## The elarxml step

`elarxml` builds ELAR INDX and PULL files from a flat source CSV, replacing the standalone `elar-file-maker.jar`. It reads one row per document, embeds that document's content file as Base64 with its SHA-256, and groups documents into INDX/PULL pairs. Nothing accumulates across documents, so the memory it uses does not depend on how many documents a batch holds or how large the embedded files are.

### The parameters it needs

Six are required and have no defaults.

- `inputDir` - the directory scanned for `*.csv`.
- `outputDir` - where the INDX and PULL pairs are written.
- `propertiesPath` - the family mapping properties file.
- `familyType` - the prefix of every key in that file, for example `CLICT@DT`.
- `indexTemplatePath` - the INDX template.
- `pullTemplatePath` - the PULL template.

If any of these is missing the step names **all** of them in one message and stops, so a new feed can be configured in one pass rather than six.

The rest are optional and default to what the legacy tool did, with the three deliberate exceptions listed further down.

- `inputCharset` - default `UTF-8`. The source CSVs from AS/400 are usually `windows-1252`; set it explicitly rather than relying on the platform.
- `outputCharset` - default `UTF-8`, which is what ELAR receives today. See the section on encoding below.
- `onMalformedInput` - `FAIL` (default) or `REPLACE`. `REPLACE` accepts a byte that is invalid in the declared charset by substituting a replacement character, which hides corruption rather than fixing it.
- `separator` - default `;`.
- `quoteChar` - empty by default, which disables quoting and makes the parse exactly the legacy split. Set it only if the source really quotes values that contain the separator.
- `listSeparator` - default `,`, the separator inside `not_duplicated_tags_list`.
- `skipPrefix` - default `out_`. Files with this prefix are skipped so leftover legacy intermediates in an input directory are ignored. It is a compatibility measure now: this executor writes no intermediate at all.
- `maxLineLength` - taken from `max.line.length` in the properties file, and `25000` when that key is absent.
- `batchBy` - `DOCUMENTS` (default) or `BYTES`. See the batching section.
- `maxBytesPerBatch` - default `209715200` (200 MB). Read only under `batchBy=BYTES`.
- `oversizeDocumentPolicy` - `WRITE_ALONE` (default) or `FAIL`. Read only under `batchBy=BYTES`.
- `onMalformedRow` - `FAIL` (default) or `SKIP`. A row whose field count differs from the header's. See the pre-scan section.
- `onMissingFile` - `SKIP` (default) or `FAIL`. A referenced content file that is not on disk. Separate from `onMalformedRow` on purpose: see the pre-scan section.
- `writeSkippedRows` - default `true`. Copy every skipped row to `<input>.skipped`. See the section on discarded rows.
- `formatOutput` - default `true`. Write one element per line, indented. See the section on formatting below.
- `contentSource` - `LOCAL` (default) or `IFS`. Where the embedded document is read from. See the section below.
- `contentIfsPath`, `contentIfsMaxListing` - only under `IFS`.
- `contentIfsLookup` - `STAT` (default) or `LISTING`, only under `IFS`. See below.
- `deleteContentAfterEmbed` - `false` by default. Delete each embedded document from the local document directory once the INDX carrying it has been delivered. Only under `LOCAL`; refused under `IFS`. See the section below.
- `checkFreeDisk` - default `true`, and only under `batchBy=BYTES`. See the section on running out of disk.
- `logDocuments` - default `true`. One log line per document, naming the INDX it went into.
- `validate` - `false` by default. See the validation section.
- `renameProcessed` - `true` by default; the input is renamed to `.done` after its output is delivered.
- `overwriteExisting` - `false` by default; a final output name that already exists fails the run rather than being replaced.
- `descriptorsElement` - default `DocumentDescriptors`, the element in the INDX template that holds the per-document blocks.

Three tags are referred to by name and all three come from the properties file, because every family has its own template with its own tags: `output.content_tag` (default `ELAR:Content`), `output.dsak_tag` (default `ELAR:DSAK`) and `output.hash_tag` (default `ELAR:HashValue`). The defaults are what this family's template happens to use, so an existing properties file needs no edit; a family whose template calls them anything else sets all three and nothing in the code changes.

Batching keys that already live in the properties file are read from there and are not step parameters: `max_index_docs`, `files_per_julian_date`, `julian_date_start`, `start_time`, `index_name_pattern` and `pull_name_pattern`. The byte budget is the exception because it is new and has no properties-file equivalent.

### The pre-scan, and why the step may refuse to start

Before a single byte of output is written, every CSV in `inputDir` is scanned for two things: a row whose field count differs from the header's, and a referenced content file that does not exist. They are **two different problems and have two separate policies**, because a malformed row means the input itself is broken and re-running will not help, while a missing content file usually means staging has not finished - and the rows that do have their files are perfectly deliverable.

A malformed row is governed by `onMalformedRow`, `FAIL` by default: the run stops with nothing written and no input renamed, and the message names every offending file and line rather than only the first. A missing content file is governed by `onMissingFile`, **`SKIP` by default**: the row is skipped, counted, and copied to the discards file described below. Set `onMissingFile=FAIL` for a feed where a missing file should stop everything, which is what the executor used to do for every feed.

This is deliberate and it is a change from the legacy tool, which dropped such rows silently and delivered the feed short. Checking first rather than mid-file matters: by the time a bad row is reached during processing, some batches have already been renamed to their final deliverable names, so the output directory would hold a partial set with nothing to say so, and a re-run would then re-deliver what had already gone out. Scanning first makes the refusal complete - either everything is written or nothing is.

Set `onMalformedRow=SKIP` for a feed where the source cannot be corrected. That restores the legacy behaviour with one difference: the loss is counted and reported instead of being invisible.

### Knowing what went into each INDX

With `logDocuments` on, which it is by default, every document produces one line naming the INDX it went into, the document id taken from `input.doc_id_reference` and the content file name; each INDX then reports its own total and the PULL it is paired with when it is delivered. ELAR validates an INDX in full and rejects it in full, so when one comes back rejected the first question is always which documents were in it - and without this the file has to be reopened and parsed to answer it.

Only those two identifiers are logged, never a field value. That is the same line the pre-scan and the findings file already draw: a document id and a file name are document identifiers, whereas the metadata beside them is customer data.

A feed of two hundred thousand documents produces two hundred thousand lines, so `logDocuments=false` turns it off for a feed where that is more than the step log should carry.

### Files while they are being written

An INDX or PULL only exists under its deliverable name once it is complete. While it is being written it carries a `.part` suffix **and** a different token: `INDX` becomes `I_PART` and `PULL` becomes `P_PART`, so `RZ2.ELA.FTP.CLIAC@DT.D26236.INDX.C152100` is written as `RZ2.ELA.FTP.CLIAC@DT.D26236.I_PART.C152100.part`.

The suffix alone would not be enough. Everything downstream that looks for deliverable files matches on the token rather than the extension - the reference PowerShell scripts default to `*INDX*` and the `elarcheck` file pattern does the same - so a half-written file would still be a match for anything scanning that way. Replacing the token means a file in flight cannot be picked up by a permissive pattern, whatever it matches on. A name whose pattern contains no such token still gets the suffix: the protection degrades rather than disappearing.

### Running out of disk part-way through

Under `batchBy=BYTES` the executor checks the free space on the output directory **before opening each new INDX**, and refuses to start one the disk could not hold: it wants twice `maxBytesPerBatch` plus a tenth. Between batches is the only place where that question has an unambiguous answer, because every INDX opened so far has already reached its final name.

Without the check, filling the disk in the middle of an INDX is not a clean failure. That batch aborts and nothing of it goes out, but the batches before it are already on the share and the input still carries its own name - so the next run reads it from the top and delivers those documents a second time, unless somebody splits the CSV by hand first.

When the space is not there the run stops **before the file exists** and the input becomes three files. The original is kept as `<name>.failed`, untouched, so the evidence of what arrived survives exactly as it arrived. The rows the run already dealt with go to `<name>.done_before_failure`, which is deliberately not a `.csv` so no later run picks it up. Everything from the row that was not processed goes to `<name>.remaining.csv`, which the next run reads as an ordinary input with nothing to rename by hand.

The rows of the two halves add up to the rows of the original exactly, and that is checked rather than assumed: if it did not add up, nothing would be renamed and the run would say so instead of leaving a split nobody can reconcile. The discards file for the delivered part is published as usual, because that part really was delivered.

The check does nothing under `batchBy=DOCUMENTS`, where there is no size to reason from, and nothing when the filesystem declines to report its free space, since refusing every run on a share that does not answer would be worse than not checking. Set `checkFreeDisk=false` to turn it off.

### Where the embedded document comes from

`contentSource=LOCAL`, the default, reads the payload from the family's `documentPath` on the machine running the step, and uses **only the last segment** of the column value. That is right for a local run: an `ifscopy` step has already copied the documents down and flattened whatever tree they came from into a single directory, so the file name is the only part that can still be meaningful.

`contentSource=IFS` reads each document **in place from the AS/400**, so they need not be copied down at all - for a feed of a hundred thousand scanned PDFs that is the difference between staging half a terabyte and staging nothing. The column value is used **as it stands**, because it carries a full IFS path; `contentIfsPath` is joined only to a value with no leading slash, and the family's `documentPath` is not read at all, which the step log says outright rather than leaving it looking effective. The step needs a **datasource**, the same AS/400 one an `ifscopy` step uses, and the designer refuses to save without it.

Two things about the IFS mode are worth knowing before turning it on. The first is how a document is found. Under `contentIfsLookup=STAT`, the default, each document costs one round trip and the cache holds one entry per document the feed references. Under `LISTING` each parent directory is listed once and cached whole, which is only worth it for a small directory that a feed uses densely - the cost of a listing is not the round trip, it is every file in the directory, so a store of millions listed to reach thousands pays for all of them. `contentIfsMaxListing` (default 500 000) fails the run rather than risking an OutOfMemoryError, and its message says which of the two situations you are in. The second is that each document crosses the network **once**: it is staged to a single temp file in the step directory and both the digest pass and the Base64 pass read local disk, so peak local disk is one document rather than one batch.

A document rewritten on the IFS while the run is in progress is caught by its length, in two independent places: the staging step compares what it downloaded against the size the listing reported, and the writer compares what it encoded against the same figure. Modification times come from the listing and are stable for the run, so it is the length that guards here, not the timestamp.

### Giving the staging space back

`deleteContentAfterEmbed`, off by default, deletes each document from the family's `documentPath` once the INDX carrying it has been delivered. It exists because what sits under `documentPath` is a **copy**: an `ifscopy` step staged it there, the archive still holds the original, and after the feed has gone out that copy is dead weight - the first real run of CLIAC@DT left 9.3 GB of it behind. This is the only option in the executor that removes something, which is why it is off unless it is asked for.

**It is refused under `contentSource=IFS`, not ignored.** There the document is the archive's own copy and there is no staging to give back. The step fails at configuration with a message saying so, and the designer refuses to save the combination. That is deliberately louder than the treatment `contentIfsPath` gets under `LOCAL`, which only warns: an inert setting still leaves you with the run you asked for, while an operator who turned this on to reclaim disk and got silence would be told nothing about the one thing they wanted.

**When a document is deleted matters more than the option itself.** Deletion happens at exactly the moment the input becomes `.done` - after `Batch.close` has renamed the INDX and its PULL to their final deliverable names - so `.done`, `.skipped` and "the document is gone" all mean the same thing: every batch this input contributed to has been delivered. Deleting at the point the document is written instead would look identical on a good run and lose documents on every path that discards an open batch. An exception aborts the batch; the disk guard cuts the input into `.remaining.csv` for the next run; an oversize document rolls it. The rows in `.remaining.csv` would then reference files that no longer exist, and since `onMissingFile` is `SKIP` by default the next run would drop every one of them and still report success.

So nothing is deleted for a batch that is not delivered. If the INDX is committed but its PULL cannot be published, the pair is incomplete and **none** of that batch's documents is deleted, including the ones already inside the delivered INDX - the staging stays full rather than being reclaimed against half a pair.

A deletion that fails - a file still held open by a scanner or an indexer, which on Windows is the ordinary case - is counted in `documentsDeleteFailed`, named in the log, and never fails the step: the INDX is already delivered, so failing here would be worse than the leftover it warns about. One line per batch reports how many went and which ones did not, capped at ten names; which document went into which INDX is already answered per document by `logDocuments`. Two run variables are published, `documentsDeleted` and `documentsDeleteFailed`, so a gate can act on leftover staging without parsing a log.

### Formatting the INDX

`formatOutput` is on by default: each element goes on its own line, indented by depth. It costs space and buys a file that can be checked by eye, which is what a feed being validated against a legacy one needs.

Nothing about the content changes. Whitespace between elements is insignificant in XML, every element carrying a value is written as one unbreakable unit so no value is touched, and the Base64 payload stays **attached to its own tags** - `<ELAR:Content>` is immediately followed by the first quad and the end tag immediately follows the last one, exactly as in an unformatted file. Turning the option on or off produces files that differ in whitespace and in nothing else.

Formatting only ever makes lines **shorter**, so it cannot push a line past the receiver's limit, and it never causes a document to be refused: when the indent and an element together would not fit a line, the indent is dropped rather than the element rejected.

Set `formatOutput=false` to deliver the compact form.

### Rows that produced no document

A row can fail to produce a document for three reasons: its field count does not match the header, its content path is empty, or the file that path points at is not on disk. Whenever any of them is skipped rather than fatal, the row is copied into a **discards file** named after its input with `.skipped` appended - `feed_2026.csv` produces `feed_2026.csv.skipped`, beside it in `inputDir`.

The file opens with the input's own header line and carries each dropped row **exactly as it was read**, byte for byte, trailing spaces and all. That is the point: correct whatever was wrong, rename the file so it ends in `.csv`, and the next run picks it up and delivers the rows. Nothing needs editing by hand, and nothing was rewritten on the way out - re-serialising the parsed fields would have quietly changed quoting and separators.

The discards file appears at the **same moment** its input is renamed to `.done`, once every batch that input produced has reached its final name. Until then it is a temp file, and if the run fails it is removed. A discards file left behind by a run that delivered nothing would read as a complete account of what was dropped, and would be the opposite of one - so `.skipped` means exactly what `.done` means. An input with nothing to discard leaves no file at all, rather than an empty one that could be mistaken for a report.

Rows with an empty content path are included even though re-running will not rescue them - the source has to be corrected for those. A discards file that listed only some of the dropped rows would misrepresent what was archived.

`writeSkippedRows=false` turns the file off. The rows are still skipped and still counted; there is simply no record of which ones. `${skippedFilesWritten}` counts the inputs that produced a discards file, so a gate can branch on it without reading a log.

### Batching: one rule, not two

`batchBy` selects the rule, and the other limit is not read at all.

- `batchBy=DOCUMENTS` is the default and is exactly what the legacy tool did: a new INDX every `max_index_docs` documents. `maxBytesPerBatch` and `oversizeDocumentPolicy` are ignored.
- `batchBy=BYTES` rolls over when the next document would take the batch past `maxBytesPerBatch`, checked before the document is written. `max_index_docs` is ignored.

Under `BYTES` the step logs, at start, that `max_index_docs` is not in effect **and what its value is**, because that key sits in the properties file where anyone can read it and would otherwise silently stop mattering.

A single document whose estimated size exceeds the whole budget cannot be split. Under `WRITE_ALONE` the open batch is closed first and the document is written in a batch of its own, exceeding the cap, with a warning and a counter. Under `FAIL` the run stops, explaining that the document can never be written and giving both ways out.

ELAR imposes no maximum INDX size, so the byte budget is an operational convenience rather than a compliance requirement.

### File names, and what happens on a re-run

The `D26229` segment is the two-digit year and the three-digit day of the year. The `C152100` segment is a **synthetic clock**, not a timestamp and not a sequence: it starts at `start_time`, or at the run's own wall-clock time when that key is absent, and advances by exactly sixty seconds per batch. Each PULL takes the counter of its INDX by construction, so a pair always matches.

**There is no file extension, and none is expected.** ELAR requires no particular one, and the delivered name ends at its `.C152100` counter. The whole name comes from `index_name_pattern` and `pull_name_pattern` in the properties file, so if a family did append an extension it would appear there; nothing in the executor adds, assumes or requires one. The only place an extension is ever removed is the name the PULL uses to reference its INDX, and only a literal `.xml` is removed, so a name that ends at its counter is passed through whole.

This has a consequence worth knowing before it surprises anyone. With `start_time` set explicitly, a second run on the same day produces the **same names**, so the run is refused on its first batch rather than replacing a file that may already have been delivered - which is the protection working. With `start_time` absent, the clock takes the wall time, so a re-run produces **new** names, nothing is overwritten, and duplicate deliverables quietly accumulate. In that case the step reports how many files for today's date it already found in the output directory. It reports rather than refuses, because a re-run is most needed immediately after a partial failure.

### Encoding

`outputCharset` defaults to `UTF-8`. That default was **measured, not assumed**: a byte probe over a real delivered INDX produced by the PowerShell scripts found 147 valid multibyte sequences covering every non-ASCII byte in the file, no stray high bytes at all, and a declaration of UTF-8 - so what ELAR receives today is UTF-8, and it is coherent. The earlier default of `ISO-8859-1` came from the legacy JAR, which is a different producer, and had the new executor deliver a file that differed from the current one on every accented character. The XML declaration written into each file is **generated from that setting** rather than copied from the template, so the encoding a file declares is always the encoding it was actually written in - on any platform and under any locale. The legacy JAR copied the declaration and wrote the bytes with the JVM platform default, which on the Italian Windows Server is `windows-1252`, so files it delivered have been declaring one encoding and potentially written in another.

A metadata value that cannot be represented in `outputCharset` fails that document with a message naming the tag and the character's code point. It is never substituted with a question mark, because a silent substitution would place a corrupted value inside a legally archived document with nothing downstream to flag it.

A family whose receiver genuinely wants an 8-bit encoding sets `outputCharset=ISO-8859-1` or `outputCharset=windows-1252` on its step. Under an 8-bit charset a character the charset cannot represent - typographic quotes, en and em dashes, the euro sign - fails the document naming the tag and the code point, rather than being silently substituted. Under UTF-8 that failure cannot arise. Every option is honest; only silence is not.

One thing to know before changing this on a live family: `maxLineLength` counts **characters**, not bytes. Under an 8-bit charset the two are the same; under UTF-8 a line of 25 000 characters can be more than 25 000 bytes. Whether the receiver's 30 000 limit is counted in bytes or characters has not been confirmed with the receiving team, and with accented characters at the density measured so far - 147 in 1 000 documents - the difference is far inside the margin between 25 000 and 30 000.

Input and output charsets are independent and are configured separately. The source CSV is typically `windows-1252` while the INDX is written in `UTF-8`.

### Line breaks

Output lines stay within `maxLineLength`, which is a maximum and not an exact width. Breaks fall only where they are legal: between elements, between attributes inside a start tag, and inside a Base64 payload at a multiple of four so every line holds whole quads. A break never falls inside any other text node, because that would change the value. If a value cannot fit on a line of its own the document fails naming the tag, rather than the line running over or the value being split.

The legacy tool chopped the serialized XML at blind character offsets. That survived because at twenty-five thousand characters a line and payloads of megabytes essentially every break landed inside the Base64, where whitespace is ignored by any decoder - but a metadata value straddling a boundary would have been silently corrupted.

### Validation

`validate` is `false` by default, because these checks have **never executed on any delivered feed**: the legacy validator required four columns and was handed a three-column file, so it returned before running anything. Whatever they find has therefore been in production for as long as the feed has, and enabling them may reject data that is already archived. Run them on a feed that has already been delivered before turning them on anywhere.

Three checks run per row.

- The document id must not repeat. A duplicate names the id, because that is what you need to find the row.
- Each tag named in `not_duplicated_tags_list` must not carry a repeated value. A duplicate names the tag and the line numbers but not the value, because an arbitrary tag can carry anything.
- The document id must be present and non-empty.

That third check is not the one the legacy tool intended. Its reference check compared the document id against the value of the tag it maps to, which on a flat row is the same value by construction and can therefore never fail. A check that cannot fail reports confidence it does not have, so it was replaced by the invariant that survives and does still fail on real data.

Memory during validation is two sets of small keys - the document ids, and one value set per configured tag - bounded by the number of documents and by nothing about content size. At a few thousand documents this is nothing. At tens of millions it is not, and that is the point at which this needs revisiting.

### What the step reports

These reach `run.vars` and the step log: `filesProcessed`, `filesFailed`, `documentsWritten`, `documentsSkippedNoPath`, `documentsSkippedFileMissing`, `rowsMalformed`, `tagsWritten`, `batchesWritten`, `documentsOversize`, `bytesEmbedded` and `sameDayPairsFound`.

Skip counts are stated even when they are zero. A line that appears only when something has gone wrong teaches people not to look for it.

No log line ever carries a row's content, a field value, or a path that embeds a customer identifier. File names, line numbers and counters only.

### Three deliberate changes from the legacy tool

Everything else reproduces the legacy behaviour. These three do not, and each changes what happens to a feed that works today.

- A malformed row stops the run instead of being dropped in silence. Escape hatch: `onMalformedRow=SKIP`.
- A missing content file stops the run instead of the feed being delivered short. Same escape hatch.
- A family whose properties file does not set `max.line.length` gets `25000` instead of the legacy fallback of `20000`, which moves where its lines break. Families that set the key explicitly, as `CLICT@DT` does, are unaffected.

### Comparing against the legacy output

`ElarEquivalence` compares a directory of legacy output against a directory of new output. Equivalence is semantic rather than byte-identical, because byte-identical is unattainable by construction: the filename clock differs between runs, the line-length default moved, breaks now fall at safe positions, and correct escaping differs from the legacy output wherever the legacy output was wrong.

Run it with `batchBy=DOCUMENTS`, the default and the only rule the legacy tool had. Under `BYTES` the distribution comparison is meaningless.

```
java -cp openproteo.war com.legalarchive.orchestrator.elar.ElarEquivalence \
     G:\legacy\out G:\new\out G:\ELAR\OUT\CMOD\S210967_CLICT\SRC
```

It strips line breaks from both sides and re-parses each, so wrapping cannot register as a difference, then compares the set of document ids, the tags and values of each document including the template constants, the document count, the batch count and the distribution of documents across batches, and the PULLs structurally with attribute order and whitespace normalised away.

The payload of each document is checked against the **source file itself** rather than against the other side, so a mistake both tools share is still caught. It exits `0` when equivalent and `1` when not, and names the document and the tag for every difference - never the value.

## The elarcheck step

`elarcheck` inspects delivered ELAR INDX files and reports every defect that has caused a real rejection, before the files are sent. It is **read-only**: it never modifies, renames or deletes anything in the directory it inspects, and no write API appears anywhere in its code, so it is safe to run against a live delivery folder at any moment.

Why it is worth its runtime: ELAR validates an INDX in full and rejects it in full, so one bad character produces a validation failure with zero business records and eighteen hundred good documents are lost because of one. The cost of a rejection is a whole regeneration and redelivery cycle.

**What it does and does not replace.** It *detects* everything `Repair-ElarIndxLineBreaks.ps1` detects - a line break inside a value, a line break inside a tag, whitespace after a tag opener - plus well-formedness, both line-length thresholds, the mandatory tags, name reuse, the PULL pairing and the digest. It *repairs* nothing, and no write API exists anywhere in its code, so for a file that is already corrupt the repair script remains the only thing that fixes it. Running the script merely to find out whether a file is sound is redundant; running it to mend one is not.

**A line break at the head of a value.** Both tools used to treat a line ending in `>` as safe without looking further, which is the fast path that makes scanning a half-gigabyte file cheap. That is wrong in one case: when the `>` closed a **start tag**, the next character is the first of the element's content, so the break gives the value a leading line feed. `elarcheck` now decides that case on the line that follows - markup means the element has children and the break was between elements, anything else is character data and the value is wrong. A value can never begin with `<`, since it would be escaped, so the test is exact. The repair script still has the blind spot: it will neither report nor mend that break.

### Parameters

`inputDir` is the only required one.

- `filePattern` - default `*INDX*`, which files to inspect.
- `inputCharset` - default `windows-1252`. This is deliberate and is **not** necessarily the encoding the files declare: files from the legacy JAR declare ISO-8859-1 while it emitted the JVM platform default, which on the target server is windows-1252. Trusting the declaration would surface an encoding mismatch as a spurious structural error, which is the most misleading thing a checker can do. Note that files produced by the PowerShell scripts, and by `elarxml` from now on, are UTF-8 and declare it - so set this explicitly when checking those, or accented characters will be read as two characters each.
- `maxLineLength` - default `25000`, the agreed target.
- `receiverLineLimit` - default `30000`, what the receiver actually enforces by horizontal truncation.
- `contentElement`, `hashElement`, `docElement` - defaults `Content`, `HashValue`, `Doc`. Matched on **local name**, so a family binding the same namespace to another prefix needs no change.
- `mandatoryTags` - comma-separated local names that must occur exactly once per record. Empty by default, which disables that check.
- `checkPull` - default `true`.
- `deliveredDir` - empty by default; when set, each file is checked against it for a name already delivered.
- `verifyHash` - default `false`, because it decodes every payload.
- `maxFindingsPerFile` - default `100`. The list is capped; the counters never are.
- `failOnFindings` - default `false`. See the workflow note at the end.

### What it checks

**Well-formedness.** The whole file is parsed. This catches every structural defect at once, including forms nobody anticipated - and it says nothing at all about whether the content is correct.

**Malformed tag openers.** `< Name`, `< /Name`, `</ Name` are all invalid and all three have been seen in delivered files. The parser stops at the first; this check reports every one, which is why it runs as a separate textual pass over the whole file.

**Line breaks in character data.** This is the check that justifies the executor. A break inside a metadata value leaves the document **perfectly well-formed** while corrupting the value: a DSAK split across two lines parses fine and yields a value with a newline in it. Nothing else finds this.

Two kinds are reported separately, because they need different repairs: a break inside a value, and a break inside markup. Repairing a markup break by inserting a space is correct only where the break fell between two attributes; anywhere else, and after an opening angle bracket in particular, it produces exactly the invalid element start the previous check exists to find.

Breaks inside the payload are **not** reported: there they are whitespace, ignored by any Base64 decoder, and are the intended wrapping. Reporting them would bury the real findings under tens of thousands of false ones.

**Line length.** Two distinct findings, because the remedies differ: over the target is a line that would still arrive, while over the receiver's limit is a line that loses its closing tag and gets the file rejected with the content unterminated.

**Mandatory elements**, reported as three distinct conditions rather than one. Missing, duplicate and empty have three different causes: a tag missing on nearly every record is a mapping problem, since the column is absent from `tagNameMapping` and the element is never emitted, while one missing on three records is a data problem. An empty element serialises as a self-closing tag and the receiver treats it as absent, which is why it cannot be folded into "present".

**Pair integrity.** The matching PULL must exist and must name its INDX at least once. A PULL that does not reference its INDX means the pair is broken however good the INDX is.

**Name reuse.** ELAR refuses a resend that reuses a name it has already seen, so a file that has to be regenerated cannot go back out under the name it left with. When `deliveredDir` is set, the check reports whether the name was already delivered and what the next available name is. The trailing counter is a synthetic clock, so the next name is computed by real time arithmetic and not by a numeric increment: `C113859` becomes `C113900`, never `C113860`, because a numeric increment produces a name that is not a valid time and therefore a second rejection for a new reason.

**Payload integrity**, behind `verifyHash`. Each payload is decoded in streaming and its SHA-256 compared against the sibling digest. The only check that verifies the archived document matches what it claims to be.

### The verdict, and which one matters

Each file gets `OK`, `CORRUPTED` or `MALFORMED`, because that is the decision the operator actually needs: send, or regenerate.

`CORRUPTED` means well-formed but wrong, and it is the verdict that matters. Such a file is **accepted** by ELAR and archived with a wrong value inside it, and nothing downstream will ever flag it. `MALFORMED` is expensive but self-announcing: the receiver rejects it and says why. A file that is both is reported `MALFORMED`, with the corruption findings still listed, since it has to be regenerated either way.

### What it produces

Counters reach `run.vars`: `filesScanned`, `filesWellFormed`, `filesRejectedLikely`, `filesCorrupted`, `documentsTotal`, `whitespaceAfterAngle`, `valueLineBreaks`, `markupLineBreaks`, `linesOverLimit`, `linesOverReceiverLimit`, `longestLine`, `tagsMissing`, `tagsDuplicate`, `tagsEmpty`, `nameAlreadyDelivered`, `pullMissing`, `pullUnreferenced`, `hashMismatches`, `findingsTotal`.

A findings file, `elarcheck_findings.tsv`, is written into the **step directory** - never into the inspected one - with one record per finding: file, verdict, line, record ordinal, kind, element and a short description. **No field value appears anywhere in it**, nor in any log line: these files carry customer names, tax codes and account identifiers, so findings carry element names, positions and counts only.

The findings list is capped by `maxFindingsPerFile` while the counters stay exact. A capped list with an exact count tells you both what to fix and how big the problem is; a capped count would quietly understate it.

### It never repairs, and that is the point

Repair happens through the PowerShell scripts that already exist, run as ordinary `powershell` exec steps in the same workflow. Read-only is what makes this step safe to run against a live delivery folder, and a single write anywhere in it would turn a verifiable property into a conditional promise.

That makes the natural workflow shape **check, then repair only if the check found something**, which is what the counters in `run.vars` are for and why `failOnFindings` defaults to `false`: a step that always failed could not drive a conditional. Set it to `true` only when you want the run to stop rather than branch.

## The json2csv step

`json2csv` reads the JSON files matching a wildcard mask in a directory and writes **one flat CSV** whose shape is the feed's dataschema, filling its columns from JSON attribute paths you choose in a mapper. It exists for feeds whose source system exports one file per database row - Transarch account extracts, for instance - where the CSV that the archive expects has to be reassembled from a directory of documents.

**One JSON file is one document is one row.** The step log says so explicitly - `filesRead = rowsWritten` - because it is the cheapest possible assertion that the executor did what it claims, and the one number a gate can branch on. Multi-row flattening, meaning one row per element of an array with the outer values repeated, is **not implemented**: a path containing `[]` is refused before a single file is opened. It is never read as `[0]` and never quietly ignored, because either of those would deliver a feed that looks complete and is not - short by every element after the first, or with a column empty for the whole run - and both are found in Transarch months later rather than when the step is saved.

### The mapper

The panel's left-hand column is the **dataschema**, loaded by **Load columns from dataschema**, in dataschema order. That order is the order the CSV is written in and it is not editable: the whole point of driving the shape from the schema is that the schema decides it. A dataschema column you leave unmapped is written **empty, not dropped**, so the CSV always keeps the schema's shape.

The right-hand side is a dropdown over the **attribute catalogue**, built by **Load attributes from sample** from an uploaded sample file and/or the first twenty files in the input directory, merged. There is a free-text field beside the dropdown, and it is not decoration: **a sample is not a schema**. An instance only reveals attributes present in it, an empty array hides everything under it, and a field absent from one record is absent from the catalogue. Every path therefore shows how many of the scanned documents contained it - seen in 3 of 20 is a different thing from seen in 20 of 20, and only you can say which is expected.

**Map by exact name** fills every column whose name matches a JSON attribute exactly. Matching is case-sensitive, because JSON keys are, and because a near-match offered as a match would be accepted without being read. It only fills columns that are still **empty**: a mapping you made by hand is never overwritten, since the ones set deliberately are exactly the ones a bulk action must not touch. Where the dataschema declares `long`, `integer` or `double`, the type dropdown is preselected to Number - a suggestion to save you choosing a hundred times, and editable like everything else.

### Attribute paths

A path is dot-separated keys, with an explicit index for an array element: `ndg`, `customer.name`, `conti[0].iban`.

A key that itself **contains a dot** is written in bracket-quoted form: `['VM.CAP.DATE.CHARGE']`. This matters more than it looks. Written bare, `VM.CAP.DATE.CHARGE` parses as four nested keys and resolves to nothing at all, and it would do so silently. The catalogue emits the quoted form for you, so you never type it.

An array is listed twice in the dropdown: the unbounded `[]`, shown **disabled** with the reason, and the members of its first element under `[0]`, which are selectable. When an array holds exactly one object - a common shape in these extracts - `['VM.ALT.ACCT.TYPE'][0].ALT_ACCT_TYPE` is how you reach the value inside it.

### Column types

- **String** - the value as text. With a fixed value and no path, it is a constant column.
- **Number** - validated as much as formatted. Scale is preserved, so `1.10` stays `1.10`, and exponents are expanded, so `1e3` is written `1000` and no consumer ever meets `1E+3`.
- **Date** - the output is **always** `${recordBusinessDateFormat}`, the feed's own variable. There is deliberately no per-column output format: the feed has one date format, and a second place to set it would guarantee the two disagree. For input, leave the mask empty and `YYYY/MM/DD`, `YYYYMMDD` and `YYYY-MM-DD` are tried in order; set it to read something else. Parsing is strict, so `20260230` is refused rather than quietly resolved to the 28th.
- **MIMEType** - a fixed literal such as `.json`, or the extension of the file being read.
- **Serial** - the row number in the CSV. It restarts neither per input file nor per split part: the parts are one delivery, and a Serial that restarted would give two rows the same number.
- **ObjectName** - the name of the JSON file the row came from, which is what identifies the attachment.

**Trying three input date masks in order is safe here, and it is worth knowing why.** The three are disjoint by shape - eight digits, or ten with slashes, or ten with dashes - so no value can parse under two of them and the order cannot change an answer. That is a property of these three masks and not of the technique: a list of formats tried in order is a dangerous idea in general, since `DD/MM/YYYY` followed by `MM/DD/YYYY` reads the third of April as the fourth of March and never says so. If you add a fourth default one day, check it for overlap against the other three first.

### Parameters

`inputDir` and the output CSV are the only required ones.

- `filePattern` - default `*.json`. Wildcards `*` and `?` only, matched on the file name, **case-sensitive on every platform**. A mask that behaved differently on Windows and on the server would make the same workflow read a different set of files depending on where it ran.
- `columnsSchema` - the dataschema JSON, e.g. `${feedDir}/dataschema.json`. It decides the CSV header and its order.
- `jsonSchema` - a sample JSON document for the catalogue, e.g. `${feedDir}/jsonschema.json`. Optional: the catalogue can be built from the input directory instead.
- `onNonScalar` - default `FAIL`. What to do when a value cannot be used as its column's type: a path landing on an object or an array, a Number that will not parse, a date matching no mask. `EMPTY` writes nothing and counts it; `JSON` writes the node as compact JSON. An **absent** value is not this case - that is data, and it writes empty and is counted separately.
- `onBadFile` - default `FAIL`. A malformed JSON file stops the run, because the output is a single CSV about to be delivered and a short delivery that looks complete is worse than a run that stops. `SKIP` counts it, logs the name, and carries on.
- `maxFileMB` - default `16`. A larger file is refused **without being read**, so the message names the size and the limit instead of arriving as an `OutOfMemoryError` halfway through a delivery. The limit is deliberately close to reality: a guard set far above anything real cannot catch a whole export dropped into the input directory by mistake.
- `serialStart` and `serialPad` - default `1` and no padding. Step-level, not per column: two Serial columns disagreeing about their width is not a feature.
- `objectNameValue` - default `FILENAME`; also `FILENAME_NOEXT`, `RELATIVE_PATH`, `ABSOLUTE_PATH`.
- `inputCharset` - default is auto-detection. JSON is UTF-8 by specification and the BOM is read, so set this only for a source that is neither.
- `renameProcessed` - default off. When on, each input that produced a row is renamed to `.done` **after the CSV is closed**. It cannot happen sooner: every input feeds one output, so nothing is processed until the whole step is, and renaming earlier would mark inputs done for a delivery that never finished. A file that was skipped is never renamed, so it is still there to be looked at.

The step splits its output by rows and/or MB exactly as the SQL export does, and publishes the same variables, so a LOOP over the parts is written the same way: `${csvFile}`, `${csvFiles}`, `${csvParts}`, `${rowCount}`. It also publishes `${filesRead}`, `${filesFailed}`, `${rowsWritten}`, `${valuesMissing}` and `${valuesNonScalar}`.

### Things worth checking on a first run

An input directory with no matching files is **not** an error: the CSV is written with its header and zero rows, because a feed with no input on a given day is normal and failing it would wake somebody for nothing.

Files are read in **file-name order**, so two runs over the same directory produce byte-identical output and the Serial column means something stable.

`${valuesMissing}` counts values the documents did not have. A number far higher than you expect usually means a path is mapped one level off, since a wrong path resolves to absent rather than to an error - which is exactly why the catalogue shows how often each attribute was actually seen.

The output is RFC-4180: a value containing the delimiter is quoted rather than mangled. If a downstream reader cannot take quoted fields, strip the character upstream rather than relying on it being dropped.

## tiffcompress

Scans a directory of TIFF files and reports what compression they carry, so that whether recompressing them is worth doing becomes a measured question rather than an assumed one. `mode=SCAN` is the default and is the only mode implemented; `mode=COMPRESS` is specified and refuses with an error rather than quietly scanning instead.

The scanner reads the TIFF header and follows the IFD chain to enumerate pages. **It never reads pixel data.** A two-page TIFF carrying the six tags it collects occupies about 160 bytes, so the cost of a file is a couple of seeks rather than a read - which is what makes scanning a share of this size affordable at all.

**Read-only by construction.** No write API exists anywhere in the scanning package and `tools/scan_tiff_readonly.js` asserts it on every build, the same property `elarcheck` has and for the same reason: it can be pointed at a directory something else is writing into without anyone having to read the code first. The rewriting half of the executor lives in its own package so that this stays true. The report is written to the **step** directory, never beside the files it measured.

Parameters:

- `mode` - `SCAN` (default) or `COMPRESS`. `COMPRESS` is specified but not built, and says so rather than doing something else.
- `directory` - the directory to read. Required.
- `recursive` - `false` by default. Descend into subdirectories.
- `maxFilesScanned` - `1000` by default. How many files are OPENED and parsed. `0` means all of them.
- `scanOrder` - `RESERVOIR` (default) or `DIRECTORY`. See below; this one matters more than it looks.
- `sampleSeed` - `0` picks a seed and reports it, so a surprising result can be reproduced exactly.
- `reportFile` - `tiffscan.csv` by default, written to the step directory.

Run variables: `filesEnumerated`, `filesOpened`, `bytesScanned`, `filesTiff`, `filesBigTiff`, `filesNotTiff`, `filesTruncated`, `filesIfdLoop`, `filesUnreadable`, `filesMixedCompression`, `filesAlreadyG4`, `bytesAlreadyG4`, `filesG4Eligible`, `bytesG4Eligible`, `scanOrder`, `sampleSeed`.

### How the sample is drawn, and why it is the whole point

The number this scan produces is a proportion, and it exists to decide whether a compressor gets built. So how the files are chosen matters more than how many are chosen.

`RESERVOIR`, the default, makes one lazy pass over the directory keeping a reservoir of `maxFilesScanned` names, then opens those. Every file has the same chance of being in the sample regardless of where it sits in the enumeration. It opens exactly as many files as the alternative does.

`DIRECTORY` takes the first N entries the filesystem hands back and stops the walk there. That truncated walk is its only advantage, and it comes at a price: the sample is a **prefix of the enumeration**, which is a corner of the store rather than a sample of it. On Windows the enumeration is by file name, and for names built from a Julian date and a counter that tracks creation order closely - so the first N are one feed, from one source system, scanned in one period by one generation of hardware, which are exactly the things that decide which compression a file carries. Use it when a full walk of the share is too slow to afford, and quote the number as that corner's rather than the store's. The step log and the panel both say so when it is selected.

The report header records the order, the seed, how many entries were enumerated and how many files were opened, so a percentage cannot travel without the method that produced it.

### Reading the report

Every row is given **by file count and by bytes**. A million small files already in G4 and ten thousand large uncompressed ones read as 99% compressed by count and as the opposite by bytes, and it is the byte column that decides whether recompressing is worth anything.

A file whose pages do not agree on their compression gets its own `MIXED:` row naming every codec it uses, rather than being charged to whatever its first page happened to be. BigTIFF is detected and reported as its own category, explicitly not parsed, so that it cannot leave the denominator silently; the same goes for files that are not TIFFs, truncated files, and IFD chains that loop. The header line `outcomesSumToFilesOpened` is the cheapest assertion that every file opened landed in exactly one outcome, and the step fails outright if it does not hold.

## The ftpsend step

`ftpsend` delivers the files of a packaging directory to an FTPS server. Explicit TLS on the control channel, passive data connections, and authentication by a client certificate held in a PKCS#12 file - the shape the archives this project feeds actually use. It replaces calling an external FTPS client from a PowerShell step.

The destination is not configured on the step. It is an **FTPS target**, created once under *FTPS targets* in the top bar and referenced by id, the way a datasource is. That is where the host, the account, the certificate and the TLS settings live.

### The ordered mask list

The step does not know which of your files completes a delivery, and deliberately so: the file set differs per feed and the completion semantics belong to the receiving system, not to us. Instead you give it a list of DOS/UNIX file masks, in the order you want them sent, edited with **+ mask**, the **✕** button, and the **↑ ↓** pair.

```xml
<step id="send" exec="ftpsend" source="${feedDir}/40_PACKAGING">
  <param name="target" value="TRANSARCH_XF"/>
  <send pattern="*.tar"/>
  <send pattern="*.md5"/>
  <send pattern="*.audit.xml" optional="true"/>
  <send pattern="*.control"/>
</step>
```

The order of those rows **is** the send order. If the receiving system takes one file as the signal that the package is complete, put it last - and the reason that matters is the next rule.

### The first failure ends the step

Nothing after a failing row is attempted. That is what makes the ordering mean anything: if the archive fails to arrive, the marker announcing the package as complete must not follow it into the remote directory.

The whole plan is built and logged **before the first byte moves**, so a mask that matches nothing stops the step before anything has been uploaded rather than halfway through a delivery. Read the `plan file=...` lines in the step log and you are reading the order that is about to happen, not a post-mortem of one that already did.

### The rules the masks follow

Wildcards are `*` and `?` only, matched on the file name. Matching is **case-insensitive**. A mask names a file and never a path: a separator in it is refused, and subdirectories are never searched or sent.

Masks are applied in list order and, within one mask, files are sent **sorted by name**. The sort is explicit rather than inherited from the filesystem, so the same directory produces the same delivery wherever the step runs.

**First match wins.** A file already claimed by an earlier mask is not sent again by a later one, so a trailing `*` catch-all is safe.

A mask that matches **no file fails the step**, unless you tick *Optional*. A delivery missing its `.md5` is a broken delivery, and the way it breaks is silently. *Enabled* is the other half of the same idea: it parks a mask without deleting it, and a parked mask is never a reason to fail.

### Verification: SIZE, and equality

After each upload the step asks the server for the file's `SIZE` and accepts it **only if it equals the local byte count**.

Both halves of that sentence are load-bearing. A "greater than zero" check would reject a `.control` that legitimately weighs nothing. No check at all would accept a 49 KB archive that arrived empty - which is not hypothetical: a server creates the destination file the moment it accepts `STOR`, before a byte crosses the data channel, so **a remote file existing proves nothing**. Only equality separates the two cases.

`SIZE` travels on the control channel, which is why the verification still works on an estate where the data connection is unreliable. For the same reason the step **never lists the remote directory**: what it sends comes from your masks, and not listing removes a whole class of failure from the delivery path.

### When the server will not answer SIZE

Some accounts may store and not stat. A delivery drop box answers `SIZE` with `550 Operation not permitted` - and so does a drop box that collected the file the moment it landed, which looks identical from here. Neither is a failed transfer, but with the default setting both fail the step, because a transfer that cannot be verified is not a transfer that succeeded.

The target has a **Verify uploads** setting for exactly this. `SIZE`, the default, is the rule above. `NONE` takes the server's `226` as the confirmation and asks nothing further.

`226` is worth more than it looks and less than `SIZE`. It is the server saying it received the stream and closed the file - which is more than "the file exists", and that distinction matters, because a server creates the destination the moment it accepts `STOR`. What it does not catch is a file the server truncated without complaining. So `NONE` is a real weakening, and every run that uses it says so once in the step log before anything is sent, and marks each delivered file `remoteBytes=unverified` rather than printing a number that was never checked.

Nothing else is weakened by it. A refused `STOR`, a data connection that never opens, a transfer the server rejects at the end: all of them still fail the step, and still stop everything after them.


### The server certificate, on Windows and on Linux

An FTPS target says how the server's certificate is judged: **Windows store**, **JVM cacerts**, **Truststore file** or **Accept anything**. Which of these can work depends on the server OpenProteo runs on, and the FTPS targets page shows it.

**Windows store** reads the certificates the operating system trusts. It exists only on Windows. On any other server the page labels it `Windows store (not available on this server)`, and a target that uses it is marked in the list with `Windows trust store: not available on this server`: such a target fails when it connects, with a message that says what to choose instead.

**A new target is proposed the mode that fits the server**: Windows store on Windows, JVM cacerts elsewhere. You can change it before saving.

**A saved target is never changed for you.** A target saved with Windows store is shown, saved and run with Windows store on any server. Moving a target from a Windows server to a Linux one therefore means editing it once and choosing another mode; nothing does it silently, because the trust a transfer relies on should not change without someone deciding it.

**JVM cacerts on Linux.** When Java was installed from the Linux distribution, its `cacerts` normally follows the system's own list of trusted authorities, so an internal CA installed on the system is trusted by OpenProteo as well. This was checked on Ubuntu; other distributions arrange it in their own way. A Java unpacked from an archive carries its own `cacerts` with public authorities only. If the server's CA is not there, use **Truststore file** and give the absolute path of a `.jks`, `.p12` or `.pfx` file on the server.

A target written by hand without a trust mode means Windows store, on every server.

### Parameters

`target` and `source` are the required ones.

- `target` - id of an FTPS target, from the *FTPS targets* page.
- `source` - the local directory holding the files. A relative path is resolved against `feedDir`.
- `remoteDir` - the remote directory. Empty, the default, means the directory the account lands in; a mask can override it for the files it claims.
- `renameSent` - a suffix added to each local file once its upload has been verified. **Empty by default, so nothing is renamed**: a step that renamed by default would change what the next run of an existing feed sees.
- `siteCommands` - commands sent verbatim after login, one per line. Empty by default; the UNIX target needs none, and it is the z/OS dataset allocation that will.

Outputs: `${filesMatched}`, `${filesSent}`, `${bytesSent}`, `${masksEvaluated}` and `${firstFailure}`, which is empty on a good run. On a good run `${filesMatched}` equals `${filesSent}`, and the step log states the invariant in as many words - it is the cheapest check that the step did what it claims.

### Testing a target

The *Test login* button on the targets page connects, negotiates TLS, logs in and hangs up. It transfers nothing and lists nothing, so a green result tells you the **control channel** works and tells you nothing at all about the data channel - which is the half that fails when a passive port range is closed or a load balancer splits the two connections apart. The conversation is shown either way, because when it fails the useful information is which command it reached.

### Two defaults that are on rather than off

**Ignore the address in the 227 reply** and **reuse the control TLS session on data connections** both default to *on*, against the usual rule that a new behaviour starts switched off. Both are switchable per target, and the reason for the exception is measured rather than assumed: a server on this estate answers `PASV` with an address that is not routable from where the client runs, and the FTPS client already in service has session reuse enabled. A default known to be wrong for the servers in scope is not a conservative default.

Session reuse depends on the JVM. Where it is not available the transfer proceeds with a fresh session and the step log says which of the two happened. It also depends on the TLS version: capped at **TLS 1.2** the data channel reuses the control session, while with **1.3** available it will be negotiated and will not, because resumption there is ticket-based. If a server refuses the data connection while everything else works, capping *Maximum TLS* at 1.2 is the first thing to try.

### What it does not do yet

**ASCII transfers are not implemented.** A mask asking for ASCII fails the step rather than sending the bytes unchanged, which would produce a file that arrives, reports the right size and is wrong. ASCII is deferred together with the z/OS target, along with `SITE` commands and quoted dataset names.

There is no download side, no resume, no active mode, and the step never deletes or renames anything on the server. The only thing it changes locally is the rename you asked for.

## The objpack step

`objpack` builds a **Transarch object submission**: the package a feed sends when the things being archived are files — PDFs, images, XML, JSON — rather than rows. It replaces the PowerShell script that did this job, `Object_CS_Archiving_v4_UK_PS5.ps1`.

It is the last mile only. Producing the source metadata CSV belongs to `sql`, `csvsql` or `json2csv`; getting the objects into a landing directory belongs to `ifscopy`, `filecopy` or `safecopy`; delivering the result belongs to `ftpsend`. `objpack` sits between the last two and turns a CSV plus a directory into the five files Transarch expects.

### What it produces

Every artifact of one submission shares a base name, `<tf#>.<transmission date>.S<sequence>.V<version>`:

```
tf0002448.20260918.S001.V001.tar       the package
  ├─ tf0002448.20260918.S001.V001.audit.json     what the submission claims to contain
  ├─ tf0002448.20260918.S001.V001.metadata.csv   one row per object, the search attributes
  ├─ tf0002448.20260918.S001.V001.OID1.pdf       the objects, renamed
  ├─ tf0002448.20260918.S001.V001.OID2.pdf
  └─ tf0002448.20260918.S001.V001.control        0 bytes, triggers ingestion
tf0002448.20260918.S001.V001.md5       beside the tar, NOT inside it
```

With gzip the archive is `…S001.V001.tar.gz` and the `.md5` holds the hash of that; see Compression below.

The `.md5` sitting outside the archive is the detail worth reading twice: it is the checksum **of** the tar, so it cannot be a member of it. Its content is the bare 32-character lowercase hash and nothing else — no file name, no `*` marker. That is deliberately **not** `md5sum` output format.

### The name, and why the date is not filled in for you

Sequence and version are padded to three digits, `S001`, `V001`. The feed id is lowercased. Raise the **version** to resend a submission that was rejected: `V001` then `V002`.

`transmissionDate` is required and has **no default**. The specification is explicit that if a submission cannot be transmitted on its intended date, the field must not change — so a step that quietly used today's date would rename the whole submission on a retry the next morning, and the retry would arrive as a different, unknown package. Where a feed does want today, write `${currentDate}` in the field yourself. The designer previews the resulting base name under the fields as you type, and says so when the feed id or the date is malformed.

### The OID, and why re-running can rename things

Object files are numbered from 1, and the padding depends on **how many objects the submission has**: 5 objects give `OID1`…`OID5`, 62 give `OID01`…`OID62`, 137 give `OID001`…`OID137`. That is the archive's rule, not a preference.

The consequence is worth stating plainly: a feed that packaged 9 objects yesterday and 10 today produces `OID1` then `OID01` for what may be the same document. Give every packaging step its **own output directory** so two runs never share one, which is why `outputDir` defaults to `${stepDir}`.

### Pairing each CSV row with its object

One row describes exactly one object, and the counts must match. Three ways to find it, set by **Find each object by**. Left alone it matches on the original file name, or on the path column when one is mapped — never on position:

- **path from a CSV column** — a column holds the object's location relative to the objects directory, subfolders included. This is the reliable one: the CSV says where each file is. The path is resolved **inside** the objects directory and an absolute path, or one climbing out with `..`, is refused rather than clamped. Both slash styles are accepted.
- **original file name** — the object is looked up by the `original_object_name` value. Without recursion each name is looked up **directly**, one file-system call per row, and the directory is not listed at all: on a network share, listing 19 933 files to find 100 took 101 seconds, and looking up the 100 takes a fraction of one. Only if an exact name does not resolve, on a case-sensitive file system, is the directory listed — once — to match it regardless of case, as it always was. With **Recurse subfolders** on, the whole tree is searched; if two subfolders hold the same file name, the step **fails naming both paths**. Picking one would archive a plausible wrong document under a right-looking name, which is the exact failure this package format exists to prevent.
- **row order** — the i-th row takes the i-th file. **Never the default, and to be avoided unless nothing else can work.** It is right only when the directory listing happens to come out in the CSV's order, and when it does not it pairs every row with somebody else's document. It exists for the case where the CSV names have nothing to do with the names on disk, typically because the objects were renamed on their way into the landing zone.

  The step now refuses positional pairing when it can prove it is misaligned: if the file handed to one row is the file another row declares as its own, the names do describe the files on disk and the order simply does not match, so the run stops. It cannot prove anything when the CSV names and the disk names have no overlap, which is exactly the case order mode is for.

**Recurse subfolders is off by default.** Turning it on for an existing step widens what the step sees.

### Column mapping

The source CSV rarely uses Transarch's column names, so the step maps them. Leave a role empty and the source column of that Transarch name is used, which means a CSV already in the right shape needs no mapping at all.

```xml
<step id="pack" exec="objpack">
  <param name="tfId" value="tf0002448"/>
  <param name="transmissionDate" value="${businessDate}"/>
  <param name="targetDestination" value="https://ubstat1ibamerlanding.blob.core.windows.net/001-tf0002448"/>
  <param name="metadataCsv" value="${dir.EXTRACT}/source.metadata.csv"/>
  <param name="objectsDir" value="${landingOut}/objects"/>
  <param name="map.recordBusinessDate" value="DT_RIFERIMENTO"/>
  <param name="map.recordBusinessDate.format" value="yyyy-MM-dd"/>
  <param name="map.mimeType" value="TIPO_FILE"/>
  <param name="map.originalObjectName" value="NOME_ORIGINALE"/>
  <param name="map.objectPath" value="PERCORSO"/>
</step>
```

The four mandatory columns are written first and in that order, as the archive requires; **every other source column follows unchanged**, so the searchable attributes a feed cares about survive without being listed anywhere.

`object_id` is the exception, and the one role that does **not** fall back to its Transarch name. Left unmapped, the step numbers the objects 1..N — **even if the source CSV has a column called `object_id`**, which is set aside with a line in the log saying so. That is what the PowerShell script did, and it is what makes discarding a row possible: the id belongs to the submission, not to the data. Map it explicitly with `map.objectId` and the values must already ascend from 1 with no gaps: anything else is **refused, not renumbered**, because silently replacing an id the feed chose would break every reference to it elsewhere — and for the same reason, discarding rows under an explicit mapping is refused.

#### The date format, and the one mask that looks right and is not

`map.recordBusinessDate.format` describes the **source** column; the packaged CSV always carries `yyyyMMdd`. Leave it empty when the column is already in that form and the value is taken as it is.

If you do set it, the pattern is Java's, and two of its letters do not mean what they look like:

| you probably mean | write | **not** | because uppercase means |
|---|---|---|---|
| year | `yyyy` | `YYYY` | the *week-based* year |
| day of the month | `dd` | `DD` | the day of the *year* |

So `YYYYMMDD` — the way nearly everyone writes a date mask, and how ISO and several databases spell it — is a **valid pattern that means something nobody intends**. It is not rejected as bad syntax: it is accepted, and then every row fails. Write `yyyyMMdd`, or `yyyy-MM-dd` when the column has dashes.

The step now checks the pattern **before reading a single row** and says which letter is wrong, and the designer shows the same warning as you type it. The uppercase letters are reported rather than quietly corrected, because `yyyyDDD` against `2020283` is a legitimate day-of-year format that means 9 October 2020, and silently rewriting someone's pattern is how a wrong date reaches a legal archive.

`mime_type` carries the **dotted extension** — `.pdf`, `.json` — and it is what the object is named with **inside the package**: `…OID1.json`. That is the name Transarch checks the mime type against, so the two agree by construction. The original file may be called anything at all — `GB0010007_1.2` holding JSON is fine — and its name is kept verbatim in `original_object_name`. A `mime_type` that cannot be a file suffix, such as `application/json`, is refused, because it would put a path separator inside a tar member name.

**Original name vs mime_type** compares the original file's own suffix with the declared type, as a sanity signal only: *warn once, with a count* (the default) reports how many objects differ in a single line, *ignore* says nothing, *fail the step* stops on the first. It is not what the archive validates. Its use is as an early hint that rows and files may have been paired wrongly, which is why the fail message points at *Find each object by* when the row's declared name is not the file it was given.

### Progress in the live console

A large metadata CSV, or objects on a slow network share, can keep the step busy for minutes. The step now reports each phase as it starts and how long it took — listing the objects directory, reading and pairing the rows, pre-flight, writing the tar, reading it back, the md5 — and, inside the long loops, a heartbeat at most every five seconds with the count so far. A fast run prints the phases and nothing else.

The durations are also the way to find where time actually goes. On a network drive, listing a directory and looking up each file are separate round trips; if *listed … files* or *pre-flight done* is where the minutes go, that is the share, not the CSV.

### The dataschema, if you have one

Point `dataschema` at the feed's shared `dataschema.json` and the step checks the source CSV against it before packaging anything: the declared column order, and that no column marked non-nullable is empty. It is the same file the `sql` and `json2csv` steps already use — the shape Transarch validates against and the shape this project stores are the same.

It is **optional**, and **never goes inside the package**. Without one the step still packages, and says in the log that the check did not run, because a check that silently did not happen is worse than one known to be absent.

### Nothing is copied, and nothing is written early

A tar member's name is independent of where its bytes come from, so the rename happens while the object is streamed straight out of the landing directory. The script this replaces copied every object into `%TEMP%` first: at the documented 20 GB ceiling that is 20 GB written and read to produce nothing. Set **Also write renamed objects** if you want the renamed copies on disk as well — for inspection, or for the non-TAR delivery path — but a TAR submission does not need them.

Everything that can be checked cheaply is checked **before the first artifact is written**, so a submission that was going to be rejected fails in seconds instead of after producing a 20 GB archive.

### Reading the package back

Once the tar is written the step **reads it again with a separate parser** and checks that every member it meant to include is there, at the size it has on disk. Verifying an archive with the code that wrote it would prove very little. This is the check that separates "the archive was written" from "the archive contains what we meant", and it is the reason a partial tar is deleted rather than left looking finished.

### A limitation worth knowing before you debug it

The source CSV is read **one record per line**. A value containing a real line break — a record split across two lines in the file — cannot be rejoined here, and shows up as a row whose field count disagrees with the header. The step refuses it and names the fix: normalise upstream with `dequote`, or with the *line breaks inside values* option of `csvsql`. It is not guessed at, because a guessed record is a wrong document archived under a right-looking name.

### Parameters

Required: `tfId`, `transmissionDate`, `targetDestination`, `metadataCsv`, `objectsDir`.

- `tfId` — the Transarch feed id, `tf` and seven digits, from the onboarding team.
- `transmissionDate` — `yyyyMMdd`. Never defaulted; see above.
- `sequenceNr`, `versionNr` — 1 to 999, default 1.
- `targetDestination` — the landing endpoint URL, from the onboarding team.
- `metadataCsv`, `inDelimiter`, `inCharset` — the source CSV. An empty delimiter samples the header for `;`, `,`, tab or `|`.
- `objectsDir`, `objectSource`, `recurse`, `orderBy`, `include`, `exclude`, `onMissingObject`.
- `map.objectId`, `map.recordBusinessDate`, `map.recordBusinessDate.format`, `map.mimeType`, `map.originalObjectName`, `map.objectPath`, `map.nameLabel` — the last places free text between the base name and the OID, e.g. `….monthly_report.OID2.pdf`.
- `outputDir` (default `${stepDir}`), `outDelimiter` (default `;`), `emitObjects`, `compression` (`none` or `gzip`).
- `dataschema`, `maxObjectMb` (2048), `maxSubmissionMb` (20480), `maxObjects` (100000), `failOnOversize` (on), `businessDateMonths` (10), `onStaleBusinessDate` (`warn`, `fail` or `skip`), `failOnStaleBusinessDate` (the older on/off form of the same choice, used only when `onStaleBusinessDate` is absent), `mimeCheck` (`off`, `warn` or `fail`).

Outputs: `${submissionBaseName}`, `${objectCount}`, `${metadataRows}`, `${skippedRows}`, `${tarFile}`, `${md5File}`, `${tarBytes}`, `${md5}`, `${discardedFile}` (empty when nothing was discarded).

### Rows the submission leaves out

**A business date older than the retention window** has three settings: *package it and warn once* (the default), *fail the step*, or **discard the row, list it in a file**. **A missing object** fails by default, as the script did, or can be skipped.

A discarded row is never silent. In a legal archive a discarded row is a document that does not arrive, so every one — stale or missing — is written to `discarded_rows.<base name>.csv` in the output directory, with its source line number, the reason and all its original values, and the step's warnings say how many and where. The file deliberately does **not** start with the submission base name, so a delivery mask such as `<base>.*` can never pick it up and send it to the archive. Its path is `${discardedFile}`; `${skippedRows}` counts the rows.

The window is applied **before** object ids are assigned, so the kept rows are numbered 1..N with no gap and the OID width follows the kept count. That is also why discarding is refused when `map.objectId` is set: an id the feed chose cannot be renumbered, and with rows gone it can no longer ascend from 1. Leave it unset and the kept rows are numbered for you. Discarding every row is refused rather than producing an empty package.

An older workflow that ticked *fail on a business date older than the retention window* keeps failing exactly as before until the new setting is changed; the designer shows it as *fail the step*.

### Compression, and the file name it changes

**The default is `none`, and it is the behaviour this step has always had**: an uncompressed `<base>.tar`, byte for byte what the PowerShell script produced. A feed that never touches the setting is unaffected by anything in this section.

The archive can also be gzipped: set **Compression** to `gzip` and the package is delivered as `<base>.tar.gz` instead.

The name changes because the specification says it changes: gzip *"resulting in a `.tar.gz` file"*, bzip2 in `.tar.bz2`, xz in `.tar.xz`. The naming convention accommodates that already — its pattern ends in `.*` — so the `.tar` examples elsewhere are simply the uncompressed case.

It is worth knowing why the archive is not just left named `.tar`. It would often work: GNU tar and bsdtar recognise compression from the file's first bytes and unpack it regardless of the name. It would also often not: anything reading the ustar headers directly finds nothing at all where they should be. Which of the two Transarch does is not documented, and the failure modes are not symmetric — a wrong extension is rejected at the naming validation, early and loudly, while a compressed file wearing a `.tar` name passes the size and checksum checks and breaks later, at extraction.

Two consequences follow, and the first has bitten people before:

- **An `ftpsend` mask of `*.tar` stops matching.** Change it to `*.tar.gz`, and remember that a mask matching nothing fails the step — which here is the good outcome, because it stops rather than delivering half a package. The designer says so in the panel the moment you pick gzip.
- **The `.md5` keeps its name and holds the hash of the compressed file**, because the checksum is of whatever is delivered. Nothing uncompressed is ever written: the compressor wraps the archive as it is produced, and the package is read back **through the decompressor** afterwards, so what gets verified is the file that will actually be sent.

**bzip2 and xz are refused**, with the reason rather than in silence. Both are permitted by the specification; neither exists anywhere in the Java 8 platform, which has only gzip. Supporting them would mean adding commons-compress, and for xz the tukaani library, as new dependencies. Ask if a feed needs them.

### What it does not do yet

The **non-TAR delivery path** — the one used over AzCopy and Axway, with the same four files and no tar — is not built. It has different size limits and does not need a target destination, and nobody has asked for it.

The **approved delimiter list** was never obtained, so `outDelimiter` is accepted as typed and not validated against it. Choose one the archive has agreed with your feed.

## The filerename step

`filerename` renames the files of one directory according to a mapping held in a CSV: the name each file has on disk is **built** from a template and an id, and the name it must take is **read** from a column. It replaces the PowerShell script `Rename-FilesFromCsvMap.ps1` and does the same thing, with the same parameters, the same defaults, the same checks in the same order, the same log lines and the same exit codes. The few places where it deliberately differs are listed at the end of this section.

The typical use is giving objects their original names back: `tf0005756.20260923.S001.V001.OID00001.json` becomes `TF0005756_ACCOUNT.DEBIT.INT.GG_GB0010007_1.2`, the value of `original_object_name` on that row. Set **Direction** to **column → template** and the same CSV renames them back, which is what a rollback needs.

### How the name on disk is built

The template, `{PREFIX}.OID{ID}{EXT}` by default, is filled in four steps, in this order: `{PREFIX}` becomes the prefix, `{ID}` the padded id, `{EXT}` the value of the extension column, and then **any other** `{Name}` becomes the value of the column with that name. So `{PREFIX}.{docid}{EXT}` names each file after its `docid` column. The replacements are applied one after the other, exactly as the script applied them, so a prefix that itself contains `{ID}` has it replaced too.

Column parameters (new-name, id and extension column) match the CSV header **regardless of case**, as PowerShell property names do. A `{Name}` token inside the template does **not**: it must be written with the header's exact case.

The designer shows, under the fields, the name the step will look for on the first row and what it will rename it to, and says so when the template has neither `{ID}` nor a column token (every row would build the same name) or contains a path separator.

### The id, and its padding

An id that is a whole number is zero-padded to **Id padding** digits, 5 by default, giving `OID00001`. Anything else is used exactly as written, so `A12` stays `A12`. A number means an optional sign and plain digits within the 32-bit range, which is what the script's `int.TryParse` accepted; `2147483648` is not a number here and is used as written.

A negative id is padded the way the script padded it, with the zeros **before** the sign: `-3` at width 5 is `000-3`. It is reproduced rather than corrected because it is the name the script would have looked for.

Padding `0` means **automatic**: the width is derived from the data instead of being stated, which removes the most common cause of a run that finds nothing, a padding that does not match what the producing system used. **Automatic padding from** says what it is derived from.

- MaxId (default) — the number of digits of the largest id in the CSV: ids up to 46 give width 2, up to 1200 give width 4. If no id is a number at all, the width is the length of the longest id as written.
- RowCount — the number of digits of the row count. It differs from MaxId whenever the ids are not a dense sequence starting at 1.
- DirectoryCount — the number of digits of the number of files in the directory. Use it when the directory holds exactly the files being renamed and the CSV is a subset.

A value longer than the width is never truncated: padding only adds zeros. The width actually used is in the log and in `${idPaddingUsed}`.

### Nothing is renamed until the whole mapping is checked

Every row is turned into a planned rename first, and three kinds of problem are found before the first file is touched rather than halfway through:

- an **unusable row** — its id or its new name is empty, or one of the two names is not a bare file name: it contains `\` or `/`, the sequence `..` anywhere, a `:`, or a character Windows does not allow in a file name. The row is skipped and counted.
- a **duplicate target** — the row would produce a name an earlier row already produces. The later row is left out.
- a **target already on disk** — the new name is already taken by a file that is not itself being renamed away.

A duplicate target or a target already on disk **stops the step with nothing renamed**, because a rename that collides either fails or overwrites, and discovering that at row 4000 of 5000 leaves the directory in a state nobody can describe. Set **On collisions** to **force** to proceed anyway: the colliding rows are then skipped and counted, and the rest is renamed. Names are compared regardless of case throughout, as Windows compares them.

### What the check does not see: chains and swaps

The check looks at the directory as it was before the run, and a target that another row is going to rename away counts as free. That is right for most mappings and wrong for one shape: **a chain in the wrong order**. With A → B on one row and B → C on a later row, the first rename finds B still there and fails, the second then succeeds, and the directory is left half renamed. A swap, A → B and B → A, fails the same way. The script behaves exactly like this, and so does the step, which reports each failed rename as a `FAIL` line and ends with exit 1. If a mapping can contain chains, order the rows so each target is already free when it is reached, or rename in two passes through a temporary name.

### Dry run

**Dry run** does everything except rename: the CSV is read, the mapping is built and checked, collisions still stop the step, and each rename that would happen is printed as a `What if:` line, word for word what the script's `-WhatIf` printed. `${renamed}` stays 0 and `${wouldRename}` counts them. It is the first thing to run against a new mapping.

### Reading the CSV

The CSV is read the way PowerShell's `Import-Csv` reads it, because the step has to see the same rows the script saw. The reader is a port of PowerShell's own and was compared with it on several hundred files. What that means in practice:

- a quoted value may contain the delimiter and real line breaks, and `""` inside quotes is one quote;
- blanks at the start of a value are dropped; at the end of an unquoted value they are kept; the id, the new name and the extension are trimmed anyway;
- blank lines, and lines holding only blanks, are skipped;
- a row with fewer values than the header leaves the missing ones empty, and extra values are ignored;
- an empty header name becomes `H1`, `H2` and so on, and two header names differing only in case stop the step;
- a first line starting with `#` is skipped, as the `#TYPE` line is.

**Charset** is `windows-1252` by default, but a byte-order mark at the start of the file wins over it, as it did in the script. **Delimiter** is one character, `;` by default, or the word `tab`, since a tab cannot be typed into the field and a literal tab in an XML attribute is turned into a space by the XML parser unless written `&#9;`.

### The directory is listed once

The directory is listed once at the start, files only, and every question of the form "does this name exist" is answered from that list, so the time does not grow with a round trip per row on a network share. Subdirectories are neither searched nor renamed, even when one has the name a row is looking for. A file that appears in the directory while the step runs is not seen.

### Exit codes and outputs

The exit codes are the script's: **0** every row was applied, **2** the step completed but some rows were not applied (source not found, target already there, unusable or duplicate rows), **1** an error, a run stopped because of collisions, a failed rename, or a Stop. OpenProteo treats any exit code other than 0 as a failed step, which is also what happened to the script when it ran as a `powershell` step; a gate can branch on the counters instead.

Outputs: `${renamed}`, `${wouldRename}`, `${sourceNotFound}`, `${skippedTargetExists}`, `${unusableRows}`, `${duplicateTargets}`, `${targetsOnDisk}`, `${renameFailed}`, `${mappingRows}`, `${idPaddingUsed}` and `${firstLookedFor}`, the first name the step looked for. When sources are missing the log prints that name next to the list: comparing it with a real file name is usually enough to see whether the prefix, the template or the padding is wrong.

### Parameters

Required: `csvPath`, `directory`. Choosing `filerename` in the designer writes every other parameter into the step with its default, so the saved workflow states every value the run uses. A parameter left empty means its default.

- `csvPath` — the mapping CSV. A relative path is resolved against `${feedDir}`.
- `csvDelimiter` — default `;`; one character or `tab`.
- `csvCharset` — default `windows-1252`; a byte-order mark wins.
- `nameColumn` — the new name, default `original_object_name`.
- `idColumn` — the id used by `{ID}`, default `object_id`.
- `extColumn` — the extension used by `{EXT}`, dot included, default `mime_type`. Required only when the template contains `{EXT}`.
- `directory` — the directory whose files are renamed. A relative path is resolved against `${feedDir}`.
- `sourceTemplate` — default `{PREFIX}.OID{ID}{EXT}`.
- `prefix` — the value of `{PREFIX}`, typically the feed stem; empty by default.
- `idPadding` — default 5; 0 for automatic.
- `paddingBasis` — `MaxId` (default), `RowCount` or `DirectoryCount`; used only when `idPadding` is 0.
- `reverse` — default `false`; `true` renames from the column back to the template form.
- `force` — default `false`; `true` skips colliding rows instead of stopping.
- `whatIf` — default `false`; `true` is the dry run.
- `summaryOnly` — default `false`; `true` drops the line per file. `FAIL` lines are always written.
- `maxReport` — default 30: how many names are listed per category. Counts are always complete.
- `logFile` — optional: every line is also written to this file, in UTF-8 with the script's timestamps. An existing directory gets `rename-map-yyyyMMdd-HHmmss.log` inside it. The `What if:` lines go only to the step log, as they went only to the console.

### Where it differs from the script

- **The extension column is required only when it is used.** The script refused a CSV without its `-ExtColumn` column even when the template had no `{EXT}` and the value would have been thrown away. When the column exists it is read exactly as before.
- **Counts are real counts.** The script reported the size of its lists, which stop at `-MaxReport`, so 100 unusable rows were reported as 30. The lists are still capped, with `... +N more` for the rest.
- **The dry run says how many renames it would have done**, in one extra summary line and in `${wouldRename}`.
- **Stop is honoured between two renames**: the step ends cleanly with exit 1 and a line saying how many files were renamed, instead of being killed mid-operation.

Relative paths are resolved against `${feedDir}` rather than the current directory, and a character Windows does not allow in a file name is refused on every host, where the script refused only what the host it ran on refused. Nothing else changes which file gets which name.

One thing worth knowing about the script itself: its default `-Encoding windows-1252` is not accepted by `Import-Csv` in Windows PowerShell 5.1, whose `-Encoding` takes only a fixed list of names, so with that default the script runs only under PowerShell 7. The step has no such limit.

## The unarchive step

`unarchive` extracts the archives found in one directory: **zip**, **tar**, **tar.gz / tgz** and plain **gz**. What an archive is, is decided by its content (its first bytes), not by its name: a gzip file that someone renamed `.tar` is read as the gzip it is, and the log says so. bzip2, xz, 7z, rar and zstd archives are recognised and refused with a message that names the format, because Java 8 cannot read them; recompress them as zip or tar.gz.

In the designer, choose **unarchive** as the executor: the panel has three sections — Archives, Output, Safety and limits — and choosing the executor writes every default into the step, so the saved workflow states every value the run uses. In the XML the step looks like this:

```xml
<step id="unpack" exec="unarchive">
  <param name="sourceDir" value="${feedDir}/incoming"/>
  <param name="pattern"   value="*.zip;*.tar.gz"/>
</step>
```

### Where the files go

Each archive is extracted into its own folder under `outputDir` (by default the step directory), named after the archive without its extension: `report.tar.gz` becomes `report/`, `data.zip` becomes `data/`, `d.csv.gz` becomes `d.csv/` holding `d.csv`. Two archives that would share a folder (`a.zip` and `a.tar`, or `a.tar` and `A.TAR`) stop the step before anything is extracted.

Nothing incomplete ever appears under a final name. Each archive is first extracted into a hidden working folder next to its destination (`.unarchive-…part`) and moved into place in one rename once it is complete. If an archive fails, its working folder is deleted; archives extracted before it in the same run stay extracted and are listed in the log. If the server stops in the middle of an archive, the next run of the step removes the leftover working folder.

### Windows and Linux servers

The name rules follow the operating system of the server the step runs on, detected automatically; the log's first line and `${hostRules}` (`windows` or `linux`) say which rules a run used. On a **Windows** server, names Windows cannot hold are refused (below). On a **Linux** server they are extracted exactly as GNU tar and unzip extract them there: `report_10:41.txt`, `CON`, `nul.txt`, a name ending in a dot or a space, `Makefile` next to `makefile`. The protections that are about safety, not about Windows, are the same on both. An **AIX** server is treated as Linux — the same name rules, and `linux` is what the log and `${hostRules}` say. A server that is none of the three is refused.

On a Linux server the step also does what GNU tar and unzip do there with links and permissions:

- **symbolic links** are created, but only when they stay inside the archive's folder: a link to `/etc/passwd` or to `../outside` stops the archive (GNU tar would create it), and so does a chain of links that leaves the folder only when followed. Nothing is ever written *through* a link;
- **hard links** are created when they point to a regular file extracted earlier from the same archive — GNU tar stores the second name of a file that has two this way;
- **permissions** are restored without setuid, setgid, sticky or owner: a tar's as GNU tar does (minus the server's umask, so `777` becomes `755` with umask `022`), a zip's as unzip does (as stored);
- **folders** get their stored permissions and times after their content, so a read-only folder in the archive is read-only once extracted.

On Windows links are still refused, or left out with `onUnsupportedEntry=skip`, and no permissions are applied.

The same workflow can therefore extract different files on a Windows test machine and on a Linux production server; `${hostRules}` in the run's outputs is how to tell afterwards.

### What is refused, and why

The step refuses rather than repairs. An archive is refused, with the entry and the rule named in the message, when an entry:

- leaves its folder: `../` anywhere, a path starting with `/` (on Windows also `\`, a drive letter `C:` or a network path);
- would collide: the same file twice, a file and a folder with the same name — and, on Windows only, two names that differ only in case (`A.txt`, `a.txt` are one file there);
- on Windows only, would be invisible or ambiguous there: a `:` (it writes a hidden stream inside another file), a reserved name such as `CON`, `NUL`, `AUX`, `COM1` — with or without an extension, `nul.txt` included — a name ending in a dot or a space, characters Windows does not allow;
- is too long: a name over 255 bytes, or a path over `maxPathLength` once extracted (by default 259 characters on Windows, 4096 bytes on Linux);
- is a device or a pipe, or — on Windows — a link (`onUnsupportedEntry=skip` leaves such entries out, still checking their names); on Linux, a link whose target leaves the archive's folder, or a hard link to anything but a file extracted earlier from the same archive.

On Linux, a `\` in a tar entry is part of the name, as GNU tar treats it; in a zip it separates folders only when the zip was made on MS-DOS or Windows tools that declare it (Explorer, Java, .NET do), which is what unzip does. A zip whose entries are encrypted, or compressed with a method other than deflate (bzip2, LZMA, or DEFLATE64, which some Windows tools use for large files), is refused naming the method. A zip entry whose content does not match its checksum is refused as corrupted.

### Archives inside archives

With `nested=extract`, an archive found inside an archive is extracted too, in place: `inner.zip` becomes the folder `inner/` next to where it was, and the file `inner.zip` is removed. It goes on down to `nestedDepth` levels (default 3); an archive deeper than that is kept as a file, with a warning.

Nested archives are found **by name**, with the step's `pattern` — never by content. A `.docx`, `.xlsx` or `.jar` is a zip inside, and is never opened unless the pattern names it. The format of what the pattern selected is then decided by its content, whatever `format` says (which applies to the outer archives only); a file named like an archive that is not one stops the step.

Everything happens inside the outer archive's hidden working folder: if anything at any depth is refused or fails, the outer archive fails and nothing of it is kept. The limits count the outer archive and everything inside it together, so a small archive that hides a huge one is stopped. On Linux, a nested archive's links must stay inside its own folder. In the manifest, nested files are named like `inner.zip!data.csv` (two levels: `inner.tar.gz!deep.zip!y.csv`); the removed nested archives have no row of their own. `afterExtract` applies to the outer archive.

### Limits against archive bombs

The limits count what is actually written, never what an archive declares: `maxEntries` entries per archive, `maxEntryMb` per file, `maxArchiveMb` per archive, and `maxRatio`, how much an entry may expand compared with its compressed size, checked once it has written 10 MB. A zip that declares more than `maxArchiveMb`, or more than the free space on the output drive plus 10%, is refused before anything is written.

### File names inside zips

Zip files do not always say how their names are encoded. The step uses the name as declared when the zip says UTF-8; otherwise it accepts the name as UTF-8 when the bytes are valid UTF-8 (what Linux tools write), and falls back to the Windows Western-European code page IBM850 (what older Windows tools write). The log says, per archive, how many names each rule decided. If the names come out wrong, set `zipNameCharset` to the code page of the machine that made the zip.

### Exit codes and outputs

**0** done; **2** refused — a parameter or an archive, the message names the rule and the archive; **-997** stopped by the user (the archive in progress is removed, earlier ones stay); **1** an unexpected I/O error.

Outputs: `${archivesFound}`, `${archivesExtracted}`, `${archivesSkipped}`, `${entriesExtracted}`, `${entriesSkipped}`, `${bytesExtracted}`, `${warnings}`, `${extractDirs}` (the folders extracted, separated by `;`), `${manifestFile}`, `${hostRules}` (`windows` or `linux`: the name rules applied) and `${nestedExtracted}` (nested archives extracted).

The manifest, `unarchive_manifest.csv` in the step directory, lists every extracted file: archive, name in the archive, where it was written, size, SHA-256 and modification time. It is UTF-8 without BOM, `;`-separated, one row per file, and contains only archives that were completely extracted.

### Parameters

Required: `sourceDir`. A parameter left empty means its default (the designer shows it as the field's placeholder). Relative paths are resolved against `${feedDir}`.

- `sourceDir` — the folder holding the archives.
- `pattern` — default `*.zip;*.tar;*.tgz;*.tar.gz;*.gz`; masks separated by `;` or `,`, case-sensitive on every server. A matching file that is not an archive stops the step. Names ending in `.done` are never selected.
- `recursive` — default `false`; `true` also looks in subfolders.
- `format` — default `auto`; `zip`, `tar`, `tar.gz` or `gz` asserts the format, and a file that is something else is refused. `gz` only decompresses, even when the content is a tar.
- `outputDir` — default the step directory. It may not be `sourceDir`, nor be inside it when `recursive` is on.
- `layout` — `subdir`, the only layout available.
- `onExisting` — default `fail`: a destination folder that already exists stops the step before anything is extracted. `skip` leaves that archive out; `replace` extracts the new content first and swaps it in, putting the old folder back if the swap fails.
- `onUnsupportedEntry` — default `fail`; `skip` leaves out devices and pipes, and on Windows also links. On Linux links are created instead (see above), and a link that leaves the archive's folder stops the archive even with `skip`.
- `zipNameCharset` — default `auto`; a code page name (`IBM437`, `IBM850`, `windows-1252`…) forces it for zip names that do not declare UTF-8.
- `maxEntries` — default 100000.
- `maxEntryMb` — default 2048.
- `maxArchiveMb` — default 20480.
- `maxRatio` — default 200.
- `maxPathLength` — default `auto`: 259 characters on a Windows server, 4096 bytes on Linux. A number applies on both, counted in the server's unit.
- `checkFreeDisk` — default `true`.
- `afterExtract` — default `keep`; `rename` adds `.done` to each archive once it is extracted, so the next run does not take it again; `delete` removes it once it is completely extracted and in place — never when its extraction failed or was stopped, and not when it was skipped (`onExisting=skip`). If the deletion itself fails, the step fails and says the archive was extracted but not deleted.
- `preserveMtime` — default `true`: files keep the modification time stored in the archive.
- `manifestHash` — default `true`; `false` leaves the SHA-256 column empty.
- `nested` — default `none`; `extract` also extracts archives found inside archives (see above).
- `nestedDepth` — default 3: the deepest level extracted when `nested=extract`.
- `failOnEmpty` — default `false`; `true` fails the step when no archive matched. An archive with nothing inside is a warning either way.

### What it does not do yet

No extraction of everything into one folder (one folder per archive only).

## The objunpack step

`objunpack` is the inverse of `objpack`. It takes **one** Transarch object package — `<base>.tar` or `<base>.tar.gz`, with `<base>.md5` beside it — and gives back the objects under their original names and the package's metadata CSV, byte for byte. It exists so a submission can be corrected and resent: its output is what `csvsql`, `dequote`, `validate` and `objpack` need as input.

In the designer, choose **objunpack** as the executor: the panel has three sections — Package, Output (with the file-name rules), Checks and limits — and choosing the executor writes every default into the step, so the saved XML states each value the run will use. Every setting applies from the next run of the step. A workflow template with the whole rebuild chain is described below.

```xml
<step id="OBJUNPACK" name="Unpack the package" exec="objunpack">
    <param name="archive" value="${landingIn}/*.tar"/>
</step>
```

### How the original names are found

Not from the names inside the package. A member is called `tf0005754.20261004.S001.V001.SECTSC2028300108d_pdf.OID3.pdf` by one producer and `tf0005754.20261004.S001.V001.OID000003.2` by another, and neither says what the file was called. The package says it in two of its own files: the audit file lists each member with its `object_id`, and the metadata CSV gives each `object_id` its `original_object_name`. The step joins the two. This works for packages built by `objpack` (with or without a name label, compressed or not) and by the older PowerShell script.

### What you get

- `objects/` in the output folder: one file per object, under its original name, with the modification time stored in the package.
- `package/` in the output folder: the package's audit file, metadata CSV and control file, exactly as they were inside the archive.
- The submission's identity as variables, so the next steps can decide what to send: `${tfId}`, `${transmissionDate}`, `${sequenceNr}`, `${versionNr}`, `${nextVersionNr}` (the version plus one — a corrected resend keeps the date and the sequence and takes the next version), `${submissionBaseName}`, `${targetDestination}`.
- Where things are: `${objectsDir}`, `${metadataCsv}`, `${metadataDelimiter}` (the delimiter the package's metadata uses — pass it to `objpack` as `outDelimiter`, whose own default is `;`, or the rebuilt package changes delimiter), `${compression}` (`none` or `gzip`).
- What was checked: `${objectCount}`, `${metadataRows}`, `${md5}`, `${md5Checked}`, `${inconsistencies}`, `${warnings}`, `${hostRules}`.

In the steps that follow, write these with the step's id in front — `${OBJUNPACK.tfId}`, `${OBJUNPACK.md5}`. Every step also publishes its variables without the prefix, and `objpack` publishes several with the same names (`objectCount`, `md5`, `metadataRows`, `submissionBaseName`, `warnings`): after it has run, the short name is `objpack`'s.

To rebuild the package, point `objpack` at `${OBJUNPACK.objectsDir}` and at the metadata CSV, and leave its pairing by name. The `object_id` column already in the metadata is set aside by `objpack`, which numbers the rows again.

### The ready-made workflow: unpack, rebuild, resend

`workflows/_TEMPLATE-objunpack-resend.xml` holds the whole chain: **objunpack**, **csvsql** (`SELECT {{columns}} FROM SOURCE`, from the feed's dataschema), **dequote**, **validate**, **objpack**, **ftpsend** (on hold, so nothing leaves without an approval). Like every sample it is not inside the application: copy it into the workflows directory, or use it as the template of a bulk creation.

As it stands it is a **corrective resend**: `objpack` takes the feed id, the transmission date and the sequence from the package and `${OBJUNPACK.nextVersionNr}` as the version, sends to the destination written in the package's audit file, and writes the metadata with the package's own delimiter. For a new submission, set `tfId`, `transmissionDate`, `sequenceNr` and `versionNr` on the OBJ PACK step yourself.

Four things to set before the first run:

- `packageArchive` — where the package is; the default is `${landingIn}/*.tar`.
- `dataschema.json` in the feed folder: `csvsql` and `validate` read it.
- **The delimiter of the three middle steps.** The Delimiter field of `csvsql`, `dequote` and `validate` is used as it is written: a variable is not resolved there, so it cannot follow `${OBJUNPACK.metadataDelimiter}`. The template has a comma. Set all three to the delimiter the package uses — the Unpack step shows it as `metadataDelimiter`. With a different one, a value that contains it gets wrapped in quotes on the way.
- `ftpsTarget` — the id of the FTPS target.

What the middle steps do to the metadata, measured on packages from both producers: a value wrapped in quotes because it contains the delimiter is kept whole; a quote **inside** a value is removed by `dequote` (`said "ok"` becomes `said ok`) — that is its purpose, and it is the one place where the rebuilt rows differ from the package's; a value with a line break splits the record unless `dequote` is given `embeddedNewlines=space` (or `csvsql` its *line breaks inside values* option), and `objpack` refuses a split record. If the package's audit has no `TargetDestination` (the older script writes it empty when not given), `objpack` stops and asks for one: set it on that step. A name label (`map.nameLabel`) is not in the template: add it on OBJ PACK if the feed uses one.

### While it runs

The live console shows each phase as it starts — reading the archive, reading the audit and the metadata, checking, giving the objects their names, moving the result into place — and, inside the two long ones, a line every five seconds: how many members have been read and how much of the archive file, then how many files have been named. A package of many small objects is slow in proportion to their **number**, not to its size: every object is one file to create and one to rename, and on a network share or under a real-time virus scanner each of those costs far more than the bytes do. While the step runs, its working folder `.objunpack-…part` in the output directory fills up; it is removed when the step ends, whatever the outcome.

### It never changes what it finds

The archive and its `.md5` are only read: never renamed, moved or deleted. And nothing that already exists is replaced: if the output folder already holds `objects/` or `package/` — even empty — the step stops before reading the archive and leaves them as they are. The step's own directory is new at every run, so this only matters when `outputDir` points somewhere fixed. Nothing appears under `objects/` or `package/` until the whole package has been read and checked; a refused, failed or stopped run leaves nothing behind.

### What stops the step

Always, because the objects could not be restored without guessing:

- the archive's file name is not a submission name (`tf0000001.20250101.S001.V001.tar`): the identity is read from it;
- the checksum is missing or does not match (see `md5Check`);
- no audit file, or more than one; a member the audit lists is not in the archive, or is listed twice; an `object_id` used twice; an object with no row in the metadata;
- the metadata has no `object_id` or no `original_object_name` column, or is not valid UTF-8;
- an original name that is empty, is a path (`sub/x.pdf`), or cannot be a file name on this server;
- **two objects with the same original name.** They would be restored to one file. The message lists every such name with its `object_id` values. They are not renamed: a renamed file would no longer be the one its metadata row names;
- a member that is a link, a folder, or has a path in its name; an archive that is bzip2, xz or a zip.

By default also when the package does not conform, each case named in the log: the record count, the audit and the metadata disagree; a member the audit does not list; a control file that is missing or not empty; a date, sequence or version in the audit that differs from the archive's name; a `mime_type` that differs between audit and metadata. Since the package being unpacked is often one that was rejected, `onInconsistency=warn` restores it anyway, logs each case and counts them in `${inconsistencies}`. A member the audit does not list is then left in `package/`, never in `objects/`.

### Windows and Linux servers

File-name rules follow the server, and the first log line and `${hostRules}` say which were used. Windows, Linux and AIX are recognised; AIX gets the Linux rules, and the first line says so (`file-name rules: linux (os.name AIX, a POSIX system)`). On any other system the step stops and asks you to choose: set **File-name rules** in the panel to `linux` — the POSIX rules: names are case-sensitive and only `/` is forbidden — or to `windows`. It is a setting of the step, so it applies from its next run, with no restart. `windows` can be chosen on any server: it is only stricter. `linux` on a Windows server stops the step, because Windows would not store those names as written. On Windows a name with `:`, `< > " | ? *`, a reserved device name or a trailing dot is refused, and two names that differ only in upper and lower case are one file and are refused; on Linux all of these are legal names and are restored. On a Linux server started without a UTF-8 locale, a name with accented letters cannot be created at all: the step refuses it and says which encoding is in use — start the service with a UTF-8 locale (`LANG=C.UTF-8`; on AIX `LANG=EN_US.UTF-8`); the Platform page shows the encoding. The same holds on AIX.

### Parameters

Required: `archive`. Each applies from the next run of the step.

- `archive` — the package: a path, or a path whose file name has `*` or `?` and matches **exactly one** file (matched case-sensitively on every server). None or several stop the step, which lists them.
- `outputDir` — default the step directory.
- `md5Check` — default `require`: the `.md5` beside the archive must exist and match, and it is checked before anything is read from the archive. `ifPresent` checks it when it is there and warns when it is not; `off` does not read it.
- `onInconsistency` — default `fail`; `warn` restores a package that does not conform (see above).
- `metadataDelimiter` — default `auto`: the character after the `object_id` header. Otherwise one character, or the word `tab`.
- `maxObjects` — default 100000. Objects, not files: the audit, metadata and control files are not counted.
- `maxObjectMb` — default 2048.
- `maxArchiveMb` — default 20480.
- `maxRatio` — default 200, for a `.tar.gz`.
- `maxPathLength` — default `auto`: 259 characters on a Windows server, 4096 bytes on Linux, 1023 bytes on AIX.
- `nameRules` — default `auto`: the rules of the server's operating system. `linux` or `windows` choose them (see "Windows and Linux servers").
- `preserveMtime` — default `true`.

### What cannot be recovered

Rows that were discarded when the package was built are not in it. The folders the objects came from are not recorded: objects come back in one flat folder. The metadata is the packaged one, not the file the package was built from: columns may have been reordered and renamed, and an `object_id` column added. For a package built by the older script in `Order` mode the names are the ones its CSV declared, whatever the files were called. A metadata value containing a line break is restored as it is, and `objpack` will refuse that CSV until `csvsql` has normalised it (*line breaks inside values*).

Not run on Windows yet.
