# DIFF Executor & Reconciliation-as-Workflow — Design & Handoff Spec

Status: design / pre-implementation
Scope: OpenProteo orchestrator (`com.legalarchive.orchestrator`), Java 8 / Spring Boot 2.7,
Thymeleaf designer, H2 2.1.214 (runtime, Spring Boot BOM), Tomcat 8.5.
Design decision: reconciliation is delivered as an explicit, designer-driven `diff`
executor, not as always-on instrumentation. There is no per-step manifest sidecar; every
comparison is an explicit `diff` step the user configures and can re-run.

## 1. Model — reconciliation is a dedicated workflow

Reconciliation is not instrumentation embedded in each feed. It is its own workflow, e.g.:

1. an `sql` / `csvsql` (H2) step that runs a query representing the **source-side** dataset,
   producing a CSV;
2. a `diff` step that compares that CSV against a file **produced by another workflow**
   (selected from the designer, see §7).

Either side of a `diff` may be a step output in the current workflow or a cross-workflow
file reference. This keeps reconciliation logic out of the feed pipelines and makes each
reconciliation an auditable, re-runnable artifact on its own.

## 2. The `diff` executor — three modes

- **TEXT** — two files compared as plain ASCII, line-oriented; output the differing lines.
- **CSV_POSITIONAL** — same-named attributes, row-by-row by position; rows in the same
  position are compared, differences reported.
- **CSV_KEY** — non-sequential; rows aligned by a declared key (simple or composite), then
  declared attribute matches compared. Handles differing column names between the two files.

All three modes emit the same report artifacts (§8).

## 3. Mode TEXT (2.1)

Line-based comparison of two ASCII files. Output: the set of differing lines
(unified-diff-style: line number, side, content). Metrics reduce to the line level:
lines compared, lines only-in-A, only-in-B, changed, and % difference over the larger file.
Attribute/key metrics do not apply.

Large-file guard: a full line diff (LCS/Myers) is not constant-memory. Apply a configurable
size cap; above it, fall back to a streaming line-by-line positional comparison (like
CSV_POSITIONAL but on raw lines) and note the degradation in the report. TEXT mode is
intended for small control files; CSV_POSITIONAL and CSV_KEY are the scalable paths.

## 4. Mode CSV_POSITIONAL (2.2.2)

- Both files read in lockstep, streaming, constant memory.
- Attributes compared = columns present in **both** headers (matched by name).
- Row *i* of A compared to row *i* of B, cell by cell over the shared columns.
- Row-count mismatch: surplus rows on either side reported as `missing_in_A` /
  `missing_in_B`.
- No key configuration; alignment is purely positional.

## 5. Mode CSV_KEY (2.2.3)

The primary reconciliation mode. Two independent concepts:

- **Keys** align rows between the two files.
- **Matches** are the value comparisons performed once rows are aligned.

Both need per-file column selection because the two files may have different schemas.

### 5.1 Keys

- Simple or composite (one or more columns), selected from a dropdown **per file**
  (key columns of A and key columns of B chosen separately). (2.2.3, 2.2.3.4)
- **Substring L / R** applicable on each key column, reusing the exact control already used
  in the grouping functions — do not reinvent; reuse the existing widget and the same
  offset semantics (maps to `SUBSTRING` in the H2 expression). (2.2.3.1)
- Rows align when the normalized key tuple of A equals that of B.

### 5.2 Matches (ADD MATCH)

- An **ADD MATCH (+)** control adds a match. Each match selects one or more attributes of A
  (dropdown) and one or more attributes of B (dropdown). (2.2.3.2)
- Multiple matches allowed; each removable individually. (2.2.3.3)
- **A match is always a 1:1 comparison at the match level.** Each side of a match yields
  exactly **one value**: if a side selects a single attribute, that value is the attribute;
  if a side selects several attributes, they are **concatenated** (ordered, with a per-match
  separator) into one composite value. So one A-attribute can be matched against several
  B-attributes concatenated (or vice versa, or many-to-many) — but once added, the match is
  a single composite-vs-composite comparison. This is exactly the
  `firstName+lastName` (A) ↔ `fullName` (B) case: side A concatenates two attributes, side B
  is one, and the match compares the two resulting single values. (confirmed)
- **Per-match separator**: used when a side concatenates multiple attributes. Default a
  single space (so `firstName + " " + lastName` lines up with a space-delimited `fullName`);
  configurable per match (e.g. empty, `-`, `/`).
- **Per-match comparison type** (2.2.3 numeric handling): `text` (VARCHAR as-is, default) or
  `numeric` (both composite values normalized before comparison — leading zeros stripped,
  decimal scale aligned; unparseable values fall back to a reported difference). Numeric is
  meaningful mainly for single-attribute numeric matches. (confirmed — per-match option)
- Per-attribute reporting (§8) is per match: each match is one logical "attribute".

### 5.3 Multiple occurrences of a key (2.2.3.5)

Keys need not be unique. For a given key value with multiple rows on a side, **every match's
value must be identical across all occurrences** on that side. Implementation via H2:

1. per side, `GROUP BY keyExpr`; for each match compute `COUNT(DISTINCT matchValue)`;
   if any match has `> 1`, the key is flagged `inconsistent_key` (a difference) and its
   canonical value is undefined;
2. collapse each side to one canonical row per key (the agreed match values);
3. full outer join A↔B on the key expression; compare each match's canonical A value vs B
   value.

This avoids the cartesian blow-up a naive join on duplicate keys would cause, and directly
implements the "all occurrences must agree" rule.

Difference categories in CSV_KEY: `value_mismatch`, `missing_in_A`, `missing_in_B`,
`inconsistent_key`.

## 6. Execution engine

- **CSV_KEY** → H2 (all-VARCHAR schema, verbatim, runtime scope). Keys and matches expressed
  as SQL; `SUBSTRING` for L/R; the group/collapse/outer-join pipeline above. Reuses the
  existing csvsql H2 patterns.
- **CSV_POSITIONAL** → streaming, constant memory, no DB.
- **TEXT** → in-memory line diff under the size cap, streaming fallback above it.

## 7. Cross-workflow file selection (points 1 & 3)

When configuring a `diff` step, each side may be:
- a step output within the current workflow, or
- a **{workflow, file}** cross-reference: a first dropdown selects another workflow; a second
  dropdown lists that workflow's produced files and picks one. (point 3)

Backend: a catalog endpoint lists selectable workflows; for a chosen workflow, an endpoint
enumerates its output artifacts (by producing step / declared output filename) to populate
the second dropdown. At execution, the {workflow, file} reference resolves to a concrete file
path.

**Run correlation (confirmed).** The reference resolves to the file produced by the
**latest run of the referenced workflow**. The report must therefore stamp which run was
used (workflow id, run id, timestamp of the resolved file) so the reconciliation evidence
records exactly which produced instance it compared against.

## 8. Report outputs (2.3)

Two artifacts per `diff` run, whatever the mode:

**`<name>_recon_report.md`** — human- and audit-readable summary:
- headline result: **PERFECT MATCH** or **DIFFERENCES**; (2.3.1)
- config echo: the two files (with source workflow if cross-referenced), mode, and — for
  CSV_KEY — the key columns of each file and the match definitions; (2.3.1.1)
- summary block: rows compared, attributes compared (= shared columns for POSITIONAL,
  = number of matches for KEY, = n/a for TEXT), keys listed when key-based; (2.3.1.1)
- totals: total differences and **% differences over checked cells**
  (`cells = rowsCompared × attributesCompared`); (2.3.1.2)
- **per-attribute table**: for each attribute/match, differing rows and its own %. (2.3.1.2)

**`<name>_recon_differences.csv`** — the detail, one row per difference: (2.3.1.3)
key value(s) (or row index for POSITIONAL / line number for TEXT), attribute/match label,
value in A, value in B, difference category.

Report-format decision (delegated): summary in **Markdown** (renders cleanly, git-diffable,
audit-friendly), detail in **CSV** as required. No TXT.

## 9. Designer UI

- Mode selector: TEXT / CSV_POSITIONAL / CSV_KEY.
- Two file pickers, each toggling between "current workflow step output" and
  "{workflow → file}" cross-reference dropdowns (§7).
- CSV_KEY panel: per-file key dropdowns (multi-select for composite) with L/R substring
  controls reused from grouping; an ADD MATCH (+) repeater, each match with an A-attributes
  dropdown and a B-attributes dropdown, individually removable.
- Column lists for dropdowns come from each file's header (preview endpoint).

**UBS constraint reminder for the designer JS**: no literal `\n` / `\r` in JS source (use
`String.fromCharCode(10/13)` / runtime constants); no uncommented `[[` / `[(` in Thymeleaf;
no CDN / external runtime dependency.

## 10. Backend registration & endpoints

Four-location rule for the new internal executor `diff`:
1. parser executor whitelist — add `diff`;
2. parser `internal` set — add `diff`;
3. `WorkflowEngine.internalKind()` — map `diff`;
4. `InternalSteps` dispatch — implement `diff` handler.

Plus: DTO + def-to-DTO mapping, result writer, designer branch, preview endpoints, `pom`
entry / probe file per existing convention.

New endpoints:
- list workflows (catalog for the first dropdown);
- list a workflow's produced files (second dropdown);
- header/columns of a selected file (populate key & match dropdowns).

Dependencies: none beyond JDK + H2 already present. No new Nexus artifact to confirm.

## 11. Open items / decisions

Resolved:
- **Run correlation** (§7) — latest run of the referenced workflow, stamped in the report.
- **Match comparison** (§5.2) — per-side concatenation into one composite value; match is a
  1:1 composite-vs-composite unit; per-match separator (default space).
- **Numeric handling** (§5.2) — per-match comparison type `text` (default) / `numeric`.

Still open:
1. Dropdown source: enumerate a workflow's **declared** outputs (design-time) vs **actually
   produced** files (last run). Since the reference resolves to the latest run at execution,
   the picker likely lists declared outputs and runtime resolves to the latest produced file.
2. TEXT-mode large-file cap value and whether the streaming fallback is acceptable, or TEXT
   is simply refused above the limit.
3. Default per-match separator confirmation (single space assumed for the
   `firstName+lastName` ↔ `fullName` case).

## 12. Suggested delivery order

1. `diff` executor skeleton + registration (four locations), CSV_POSITIONAL only, report
   artifacts wired — smallest end-to-end slice.
2. Report metrics complete (totals + per-attribute) for POSITIONAL.
3. CSV_KEY: keys (with L/R substring) + single 1:1 matches, H2 pipeline, multi-occurrence
   rule.
4. CSV_KEY: multi-attribute matches, ADD MATCH repeater UI.
5. Cross-workflow file selection (dropdowns + endpoints + run correlation).
6. TEXT mode.
