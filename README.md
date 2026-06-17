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

Variables are referenced as `${name}`. The engine seeds `feedId`, `sourceId`, `targetId`,
`feedName`, `runId`, `runDate`, layout paths (e.g. `feedDir`, `landingIn`, `landingOut`,
`stepDir`), plus every workflow variable you declare. A step can publish output variables
(printed in the log as `##VAR name=value`) that later steps can read.

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
  Supports `{{columns}}` expansion from a per-feed `dataschema.json` and splitting the export
  into parts by row count and/or size (see Splitting below).
- **split** — split an **existing file** into parts by rows and/or MB, using the same logic
  as the SQL export. Use it to run a LOOP only over the final steps, after validation and
  anonymization (see Splitting and Loops).
- **mask** — deterministic streaming masking of a CSV (constant memory). Strategies are driven
  by the displayschema; pool-based strategies (names, cities, company parts) pick their values
  from selectable pool files (see Masking pools).
- **encoding** — convert a file (or a whole directory) to UTF-8.
- **filecopy** — copy / move / list files.
- **safecopy** — copy files matching a wildcard from one directory to another, writing each
  file as `<name>.on_fly_` and renaming it to the final name only after the copy completes
  (atomic move when possible). Prevents a downstream watcher from picking up a partial file.
- **ifscopy** — copy from an AS400 IFS path to local.
- **csvreplace** — string substitution inside CSV columns.
- **validate** — run a checklist of validations over a CSV.
- **anonymize** — ARX-based CSV anonymization (statistical; in progress).
- **setvar** — assign workflow variables.

External executors run a PowerShell (or other) script from the scripts directory or an
absolute path; the script path can use `${alias}` of an uploaded executable.

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
files: the first maps feed attributes (id, name, sourceId, targetId, description, and inline
`dataschema`/`displayschema` JSON), the second maps a per-feed table name. Attribute fields
accept `{Column Name}` tokens mixed with literal text, e.g. `{Bank} - {ICTO Code}`; `targetId`
accepts comma-separated tokens for multiple destinations.

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
the datasources file, `orchestrator.masking-secret`, `orchestrator.mask-pools-dir` and
`orchestrator.max-transitions`.

The deploy script syncs the latest package into the working copy (preserving `.git`), builds
the WAR, then commits and pushes using the `COMMIT_MSG.txt` shipped inside the package, and
finally restarts Tomcat. Documentation and commit messages are kept in English.

After deploying, hard-refresh the browser (Ctrl+F5) so updated CSS/JS are picked up.
