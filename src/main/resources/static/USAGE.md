# OpenProteo — Usage Guide

OpenProteo is a workflow orchestrator for preparing and delivering Legal Archive feeds.
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

Variables are referenced as `${name}`. Resolution is iterative and innermost-first, so a variable can build the **name** of another variable (indirection / factory pattern): with `targetId=T1`, `${TargetDestination.${targetId}}` first becomes `${TargetDestination.T1}` and is then resolved to that variable's value. Unknown names resolve to the empty string. The engine seeds `feedId`, `sourceId`, `targetId`,
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

### Operations: resources, clickable rollup, last run/success

The Operations page now shows, top to bottom: a **Resources** panel (JVM heap used/available, processors, load average, running/queued/waiting and test-run counts; refresh 5s); the **By source** rollup FIRST, with **clickable** totals — click any tile or any number in the table to drill down to the matching feeds, each showing **last run** (status + time) and **last success**; then **Executions in progress**, which now also shows the **Target** and each feed’s last run / last success. Test runs are excluded from the production rollup and last-run stats.

### Operations board

The **Operations** page (link on the dashboard, or `/overview`) shows two things, refreshed automatically: **Executions in progress** — every queued/running/waiting run across all feeds, with its current step, progress, an **Open** link and a **Stop** button (auto-refresh 3s); and **By source** — a rollup of every feed grouped by source, counting *not run / running / success / failed / aborted / other* (based on each feed's latest run), with totals and a per-source mix bar (refresh 20s, or the Refresh button).

### Bulk: schema only

The Bulk page has a **Mode** selector. **Schema only** ignores the template and does **not** modify any workflow: for each CSV row it (re)writes `dataschema.json` / `displayschema.json` into the matching **existing** feed (matched by `feedId`), from the `dataschema` / `displayschema` columns. Use it to restore or refresh schemas for many feeds at once without regenerating their workflows. Rows whose feed does not exist are skipped.

### Clear History

The designer has a **Clear History** button that deletes every entry under the feeds base
directory and recreates it empty (all run history and data for all workflows). It is
irreversible (double confirmation), refuses to run while a run is active, and is disabled for
production workflows.

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

- **sql** — run a query against a DB2/AS400 datasource and stream the result set to CSV.
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
- **mask** — deterministic streaming masking of a CSV (constant memory). Strategies are driven
  by the displayschema; pool-based strategies (names, cities, company parts) pick their values
  from selectable pool files (see Masking pools).
- **encoding** — convert a file (or a whole directory) to UTF-8.
- **filecopy** — copy / move / list files.
- **dequote** — read an input CSV and write an output CSV with double quotes (escaped or not)
  stripped from the chosen text columns; re-quotes a field only when it still contains the
  delimiter or a newline (or never, with quoteIfNeeded=false).
- **safecopy** — copy files matching one or more wildcards (comma-separated, e.g. `*.md5, *.tar`) from one directory to another, writing each
  file as `<name>.on_fly_` and renaming it to the final name only after the copy completes
  (atomic move when possible). Prevents a downstream watcher from picking up a partial file.
- **ifscopy** — copy from an AS400 IFS path to local.
- **csvreplace** — string substitution inside CSV columns.
- **validate** — run a checklist of validations over a CSV.
- **anonymize** — ARX-based CSV anonymization (statistical; in progress).
- **setvar** — assign workflow variables.

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
