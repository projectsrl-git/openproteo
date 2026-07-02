# Workflow export / import

## Problem / goal

Operators need to move workflows between OpenProteo instances (typically
**test → prod**) without hand-copying XML, schemas and scripts. Two capabilities:

1. **Export** one or many workflows (bulk selection from the dashboard) as a
   single ZIP that carries the workflow XML plus every file the workflows need
   to run on another instance.
2. **Import** such a ZIP: create the workflows, or **update** existing ones when
   the `feedId` matches (like the bulk creator). Before writing, an editor —
   modelled on the Variables page (SOURCE / TARGET / FEED cascading selectors) —
   lets the operator retune common variables and, unlike the Variables page,
   also change the **targetId** and the **production** flag (the test → prod
   switch).

## What goes in the ZIP (confirmed with the user)

- `manifest.json` — package descriptor (feeds + referenced assets, redaction flag).
- `workflows/<feedId>.xml` — the workflow definition, **copied verbatim**
  (byte-stable; no round-trip through the writer on export).
- `schemas/<feedId>/dataschema.json` and `.../displayschema.json` — per-feed,
  from the feed directory, when present.
- `scripts/<name>` — the `.ps1/.bat/.cmd/.jar` files referenced by each step's
  `script` attribute, resolved against `orchestrator.scripts-dir`, de-duplicated
  by base name.
- `datasources/datasources.json` — the datasource **definitions** referenced by
  `sql` / `ifscopy` steps, **with passwords blanked**.
- `globals/global-vars.properties` — the file-based global variables actually
  referenced (`${name}`) by the exported workflows. Secret-looking keys
  (password/secret/pwd/token/apikey/credential) are redacted to empty.

### Secrets — never exported

Datasource passwords are blanked; the masking secret
(`orchestrator.masking-secret`) and the read-only `application.properties`
globals are **never** included; secret-looking global keys are redacted. The ZIP
is safe to hand over; credentials are re-entered on the destination.

## Import behaviour

- Upload the ZIP → server extracts it to a staging directory keyed by a random
  token and returns, per workflow, the same shape the Variables page consumes
  (feedId/name/source/target/production/tags/vars/steps) plus `exists`
  (create vs update) and the lists of bundled scripts / datasources / globals.
- The import page reuses the Variables-page editor (cascading SOURCE/TARGET/FEED
  multi-selects; single-feed = full edit, multi-feed = common vars/steps) and
  adds a **Feed identity** block that edits, for the selected feeds:
  - **targetId** (dirty-tracked; only applied when changed),
  - **production** flag (`leave / production / test`).
- Apply: for every **selected** feed, the staged XML is parsed → DTO → edits
  applied (targetId, production, common vars/steps/tags) → re-serialised with
  `WorkflowXmlWriter` → **validated** with `WorkflowXmlParser` before anything is
  written (same guarantee as the Variables page). Then:
  - XML written to `orchestrator.workflows-dir` (create or overwrite by feedId),
  - `registry.reload()`,
  - schemas written into each feed's `feedDir` (after reload, like bulk-create),
  - scripts copied into `orchestrator.scripts-dir` (skip existing, reported),
  - datasources merged (create-if-missing with blank password; never overwrite
    an existing definition/credential),
  - globals merged (add-if-missing keys; never overwrite an existing value),
  - `scheduler.reschedule()`.

Only the **selected** feeds are imported. With no SOURCE/TARGET/FEED filter the
selection is "all feeds in the ZIP" (same semantics as the Variables page).

## Files touched

New:
- `port/WorkflowPorter.java` — export ZIP builder + import staging/extraction +
  reference scanning + scripts/datasources/globals merge. JDK + Jackson only.
- `templates/import.html` — upload + Variables-style editor + apply.
- `.claude/workflow-export-import.md` — this spec.

Edited:
- `web/ApiController.java` — `GET /api/workflows/export`,
  `POST /api/workflows/import/inspect` (multipart), `POST /api/workflows/import/apply`.
  Injects `WorkflowPorter`; reuses `toDto` / `applyStepField` / `setOrRemoveParam`.
- `web/PageController.java` — `/import` route.
- `templates/dashboard.html` — EXPORT button in the bulk bar + `bulkExport()`;
  Import link in the toolbar.
- `CLAUDE.md` — feature notes.

## Key decisions / trade-offs

- **Export copies XML bytes verbatim** (fidelity + byte-stability); **import
  re-serialises** through the DTO because it edits identity/vars — accepted, and
  consistent with how the Variables page and designer already write.
- **Stateful import** (extract-to-staging + token) avoids re-uploading binary
  scripts on apply. Staging dirs live under the JVM temp dir; best-effort
  cleanup on apply plus a TTL sweep (older than a few hours) at inspect time.
- **Zip-slip guarded**: entry names containing `..` or absolute paths are
  rejected on extraction.
- **Non-destructive merges**: existing datasources, existing global values and
  existing scripts are never overwritten by an import (reported instead), so an
  import can't silently clobber destination credentials.

## Backward compatibility

Purely additive: new endpoints, one new page, one new bulk button, one new
component. No change to the XML schema, the parser/writer, or any existing
executor. Unreferenced features stay byte-stable. Export reads existing files;
import uses the already-proven DTO→writer→parser validation path.
