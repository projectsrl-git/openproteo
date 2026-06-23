# Bulk "schema only" mode — restore/refresh dataschema & displayschema of existing feeds

## Why
The earlier Clear-History bug deleted the workflow-level `dataschema.json` / `displayschema.json`
uploads for ~140 feeds. The workflow definitions (XML in the workflows dir) survived, so the feeds
exist but have no schema files. This mode re-creates them in bulk from the original bulk CSV.

## What
`POST /api/workflows/bulk` now accepts `schemaOnly=true`:
- No template, no workflow XML is generated or touched.
- `BulkWorkflowGenerator.parseSchemas(csv, delim, mapping)` reads only the `feedId`, `dataschema`
  and `displayschema` columns.
- For each row whose feed **already exists** (registry.get(feedId) != null), the JSON is validated
  (well-formedness, via the existing `checkJson`) and written to `${feedDir}/dataschema.json` /
  `${feedDir}/displayschema.json` — exactly as the original bulk-create did (same file names, same
  write), so it restores the prior state. Rows for unknown feeds are skipped; per-row status is
  reported (`updated` / `skipped` / `error`) with counts `updated/skipped/failed/schemasWritten`.
- `template` is now optional on the endpoint (only required for the normal create path).

## UI (bulk.html)
A **Mode** selector: "create / update workflows" (default) or "schema only (existing feeds)". In
schema-only the template is disabled and a hint explains the behaviour; the result table shows an
`updated` (green) status.

## How to use (your case)
Open Bulk, set Mode = "schema only", paste the same feeds CSV that carries the `feedId`,
`dataschema` and `displayschema` columns, and run. Every existing feed gets its two schema files
re-written.

## Verification
- Compiles (96 classes). bulk.html JS `node --check` OK; no literal `\n`/`\r`; no unsafe `[[`/`[(`.
- Live write/validate is UBS-side.
