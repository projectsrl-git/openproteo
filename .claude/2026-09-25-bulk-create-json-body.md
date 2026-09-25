# 2026-09-25 — Bulk create: JSON body instead of form-urlencoded

## Symptom
`/bulk` → *Generate workflows* showed a bare **Bad Request**. Server log:

    Resolved [MissingServletRequestParameterException:
      Required request parameter 'csv' for method parameter type String is not present]

The page always sends `csv`, so the parameter did not go missing in the browser.

## Cause
The page posted `application/x-www-form-urlencoded`. Tomcat parses form
parameters only up to `maxPostSize` (2 MB default, embedded and external alike)
and, past it, **drops every parameter silently** (the reason is logged by Tomcat
at DEBUG only). Spring then sees no `csv` and answers 400 before the method runs,
so our own `missing 'csv' content` message never appeared.

The feeds CSV carries dataschema/displayschema JSON inline. Measured on a real
schema row: URL encoding inflates it by about **1.75x**, so the cap is reached at
roughly **1.2 MB** of raw CSV — a few dozen feeds with full schemas.

## Fix
* `ApiController.bulkCreateJson` — `POST /api/workflows/bulk` with
  `consumes = application/json`, `@RequestBody Map<String,Object>`. Each field
  falls back to the same default as the form endpoint when absent or null, then
  delegates to `bulkCreate(...)`. Response contract unchanged.
* `bulk.html` — sends `JSON.stringify(p)` with `Content-Type: application/json`.
  A non-JSON error response now shows `HTTP <status>` instead of a parse error;
  `j.message` is used when `j.error` is absent.
* The form-urlencoded endpoint is kept, unchanged, for any existing caller.
  Spring picks the `consumes` mapping for JSON requests (more specific condition);
  form requests only match the original one.

Why not raise `maxPostSize`: it is a connector setting on the external Tomcat
(`server.xml`) and a different property on the embedded one, i.e. two places to
keep in sync on every environment, and it would only move the wall.

## Behaviour change
None on generated output: same generator, same parameters, same defaults.

## Files
* `src/main/java/com/legalarchive/orchestrator/web/ApiController.java`
* `src/main/resources/templates/bulk.html`
* `CLAUDE.md` (bulk create paragraph)

## Verified / not verified
* Inline JS of `bulk.html`: `node --check` OK; no literal `\n`/`\r`; no `[[`/`[(`.
* `ApiController.java`: brace balance (string/comment aware) OK; imports
  already present (`MediaType`, `RequestBody`, `LinkedHashMap`, `Map`).
* Inflation ratio measured in Python on a schema row.
* **Not verified**: Maven build (no Maven Central from the sandbox), and no JDK
  available in the sandbox this turn, so `bulkArg` was not compiled standalone.
  Not run against Tomcat: the maxPostSize explanation matches the log exactly
  but was not reproduced end-to-end.

## Follow-up
* If an IIS reverse proxy sits in front, its request filtering limit
  (`maxAllowedContentLength`, 30 MB default) still applies.
* Other pages posting large form-urlencoded bodies would hit the same wall;
  none known to carry CSV-sized payloads today.
