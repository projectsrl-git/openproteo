# Three changes: audit-proof SQL report, workflow versioning on structural edits, missing steps in Variables

Design and handoff spec. Nothing here is implemented yet: it exists so the decisions are made before code,
and so each batch can be reviewed on its own.

---

## 1. `sqlreport` executor — read-only queries, Markdown evidence, collected variables

### 1.1 Why a new executor instead of extending `sql`
`sql` exists to move data: one query, streamed to CSV, split by size. This one exists to *prove
something*: several queries, no data file, a human-readable document that can be attached to a
reconciliation. Overloading `sql` with a second output mode would make both harder to reason about, and
the audit story is different (the report IS the deliverable). New executor id: **`sqlreport`**.

Registration follows the project's four-location rule: parser exec whitelist, parser `internal` set,
`WorkflowEngine.internalKind()`, `InternalSteps` dispatch.

### 1.2 Read-only enforcement
Queries are validated BEFORE execution and the step fails if any is rejected. Two layers, both required:
- **statement check**: after stripping comments, the statement must start with `SELECT` or `WITH`, and
  must not contain a second statement (no `;` followed by anything but whitespace). Reject
  INSERT/UPDATE/DELETE/MERGE/CALL/GRANT/DROP/ALTER/CREATE/TRUNCATE/SET as the leading keyword.
- **connection**: `Connection.setReadOnly(true)` and, where the driver honours it, a query timeout.
This is a guard against mistakes, and it is stated as such: a determined author can still write a
`SELECT` calling a function with side effects. The real guarantee is the database account's own rights,
which is the right place for it - worth saying explicitly to whoever asks for "read-only".

### 1.3 Configuration (designer UI)
- **datasource** (as today, any JDBC datasource).
- **queries**: a repeatable block added with **[+ Add query]**, each with:
  - `title` - the heading in the report;
  - `sql` - the statement (supports `${vars}` like everywhere else);
  - `keyColumn` (optional) - see 1.5;
  - `collect` (optional) - comma-separated column names to expose as variables;
  - `maxRows` (optional, default 200) - rows rendered in the table; the row count is always reported in
    full even when the table is truncated, since a truncated table that hides the real count would be
    misleading in an audit document.
- **report file**: default `${stepDir}/${feedId}_${stepId}.md`.
- **failOnEmpty** (default no): a query returning zero rows fails the step - useful for "this must
  reconcile" checks.

Storage: one XML element per query under the step, NOT a delimited string, so a `;` inside SQL cannot
corrupt the definition.

### 1.4 The report
Markdown, UTF-8, one section per query:

```
# SQL report - tf0003819
Run: 20260804_004512 - executed 2026-08-04 00:45:12 (Europe/Rome)
Datasource: uki0bxf.eu.hedani.net (as400) - user PROTEO - database PROTEOLIB
Workflow: tf0003819 "Client static data" - step CHECK_RECON

## 1. Row count on the source
Executed 2026-08-04 00:45:12, 0.42 s, 1 row

    SELECT COUNT(*) AS N FROM ...

| N |
|---|
| 21292 |
```

Audit-proof means: every query text as executed (after variable substitution), its own timestamp and
duration, host/datasource/user/database, the run id, and the row count. Values are rendered verbatim
with `|` escaped. NO password, ever - the datasource id and user only.

The report path is published as `${reportFile}`, plus `${queriesExecuted}` and `${rowsTotal}`.

### 1.5 Collecting variables
Two forms, both writing normal run variables so everything downstream (`${...}`, output data, the log
report) works unchanged:

- **scalar**: a single-row, single-column result, or `collect=COL` on a single-row result, sets
  `${<queryVar>}` / `${<COL>}`.
- **indexed by key**: with `keyColumn=CID` and `collect=AMOUNT,STATUS`, each row contributes
  `${AMOUNT[<key>]}`... but the existing `${list[N]}` syntax is positional, so to stay consistent the
  executor instead publishes:
  - `${<COL>}` = the `;`-separated list of values in row order (so `${AMOUNT[3]}` keeps working with the
    existing 1-based indexing),
  - `${<keyColumn>}` = the `;`-separated list of keys in the same order.
  Two aligned lists, not a map: it reuses machinery that already exists and is already displayed nicely
  in OUTPUT DATA, and a later comparison step can join them by position. A real keyed lookup
  (`${AMOUNT@CID12345}`) would need a new resolver syntax - worth doing only if you actually need it.

**DECIDED: keyed syntax.** `${COL@key}` returns the value of `COL` on the row whose key column equals
`key`. The executor publishes, for each collected column, the `;`-separated values in row order AND the
companion `${COL.keys}` list of keys in the same order; VarResolver resolves `@` against that pair.
Positional `${COL[N]}` keeps working and OUTPUT DATA renders the lists as before.

Deliberate choices in the resolver: an absent key resolves to the empty string, and so do two lists of
different lengths - the signal that the variables were not produced together. Returning a neighbouring
row would be far worse than returning nothing in a reconciliation. Keys are compared trimmed and
case-sensitively. Implemented as batch 0; the executor that produces the pair follows.

### 1.6 Batches
1. Executor + read-only validation + Markdown report + designer UI + docs.
2. Variable collection (scalar and lists).
3. Optional: comparison helper between two collected lists (diff/reconcile), if wanted.

---

## 2. Structural edit of a workflow that already ran -> propose a versioned copy

**Trigger**: on save, when steps were **added or removed** (not merely edited) AND the feed has at least
one run. Renaming or reordering is out of scope for the trigger unless you say otherwise.

**Behaviour**: a dialog offers *Save as a new version* (default) or *Overwrite this workflow*. The
proposed id is the current one plus `.v1`, incrementing to `.v2`... if taken; the name gets ` v1`
appended. Uploaded files are copied to the new feed, exactly like Duplicate as new.

**DECIDED: `tf0003819.v1` is an acceptable feed id**, so versioning stays a pure naming convention and
the registry is untouched.

**DECIDED: built-in `${parentId}`** carrying the original id. Derivation is textual and total: strip a
trailing `.v<digits>` from the feed id, so `tf0003819.v2` -> `tf0003819`, and on an unversioned feed
`${parentId}` equals `${feedId}`. Never null, so a query, a path or a report can use it unconditionally
without the author knowing whether that feed happens to be a version. Published wherever `${feedId}`
already is (run variables and the design-time preview map), and worth showing next to the id in
Operations so a version is recognisable at a glance.

Two consequences to keep in mind while implementing:
- the version inherits nothing automatically. `${parentId}` is information, not a link: run history,
  output data and audit stay separate per feed id, which is the point of versioning;
- since ids end up in file paths, `${feedId}` remains what names directories and files. Anything that
  must keep the ORIGINAL naming across versions - typically the delivered file name - has to use
  `${parentId}` explicitly. This is the single decision an author has to make per step, and the docs
  must say so plainly.

**DECIDED: the original keeps its schedule.** Creating a version changes nothing about what runs
tonight; the new workflow is created inactive as far as scheduling is concerned, and the operator
retargets deliberately when ready. Silently moving a schedule onto a freshly edited workflow is the kind
of surprise that costs a night.

Practical consequences for the implementation:
- after saving a version, the dialog says explicitly which workflow is still scheduled, so nobody
  assumes the switch happened;
- the two workflows are both live and would both deliver if the new one were scheduled too - Operations
  must make the version visible (id plus `${parentId}`) rather than letting `tf0003819` and
  `tf0003819.v1` look like unrelated feeds;
- retargeting stays a normal edit of the schedule, with no special path: one less mechanism to get
  wrong.

Detecting "has runs" is cheap (the run directory is already read for the history).

---

## 3. Variables page: show steps that only some of the selected feeds have

Today the page shows the intersection - steps common to all selected feeds - which silently hides the
difference. Proposal:

- steps present in **some but not all** selected feeds are listed **greyed out and disabled**, with a
  badge "in 3 of 5 feeds" and, on hover, which feeds lack it;
- an **[Add to all selected]** action enables the row: its fields become editable, prefilled from the
  feeds that do have it (or blank when they differ), and on save the step is **created** in the feeds
  that lack it and updated in the others;
- insertion position must be explicit - **after which step** - because appending at the end would be
  wrong for most pipelines. Proposal: a dropdown listing the common steps, defaulted to the position the
  step occupies in the feeds that already have it, when that position is consistent.

**Open questions**: what to do when the step exists with the same id but a *different executor* in some
feeds (proposal: treat as a conflict, show it, refuse to mass-add); and whether adding a step to a
production feed should require the same explicit confirmation as Clear History (proposal: yes).

---

## Suggested order
`sqlreport` first (self-contained, no impact on existing feeds), then the Variables addition (UI-only,
reuses the existing save endpoint), then versioning (touches registry and run history, so it deserves the
most careful review).
