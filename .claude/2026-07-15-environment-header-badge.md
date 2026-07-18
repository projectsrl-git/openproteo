# Environment badge in the header (DEV/SIT/UAT/PROD)

Distinguish the test vs prod installation from the page header.

## Config
`orchestrator.environment=PROD` (or DEV / SIT / UAT / anything) in
application.properties. Empty (default) hides the badge.

## Wiring (single point — no per-page template edits)
- AppProperties.environment (+ getter/setter).
- GET /api/env -> { environment, host } (host = server hostname, also shown as the
  badge tooltip, covering the "obvious server name" ask).
- theme.js (already loaded on every page, and already injects the theme toggle)
  now also fetches /api/env and injects an `.env-badge` into the .topbar. For PROD
  it adds `.is-prod` to the topbar.
- CSS: `.env-badge` with per-environment colours; PROD is white-on-red and
  prominent, plus a red accent line under the whole topbar; DEV neutral, SIT blue,
  UAT amber; light-theme variants included.

## Verify
theme.js passes node --check with zero literal \n/\r escapes (proxy constraint);
app.css braces balanced. AppProperties/endpoint are trivial and mirror existing
code (@RestController, so the Map serialises). Full Maven build to be confirmed on
deploy; then set orchestrator.environment on each installation.
