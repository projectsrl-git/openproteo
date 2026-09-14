# Datasources: every database in one dropdown

## What was asked

"Make the drivers visible in the driver dropdown: right now there is only DB2 IBMi and a
generic JDBC. Make all the drivers you already provided visible and easily selectable from
the GUI. Add PostgreSQL too."

## What was actually there

The vendor presets have existed since `451c6fc` (2026-08-07) — Oracle, Microsoft SQL Server,
PostgreSQL, MySQL, MariaDB, IBM DB2, IBM i over JDBC, H2 and SQLite, each with its URL
template and driver class. PostgreSQL included. Nothing was missing from the catalogue.

What was missing was any way to *see* them. The presets lived in a second dropdown inside
`#customFields`, which is `display:none` until the type dropdown is set to `custom` — and
`newDs()` opens the form on `as400`. So the form's first state hid the entire vendor list
behind a control the operator had no reason to touch, and the type dropdown he did see
offered exactly two entries. The feature was complete and invisible.

This is the gap this change closes. No new vendor was added; one was made reachable.

## The change

**One dropdown, labelled Database.** It lists the native IBM i entry, then one option per
vendor, then a generic `JDBC — any other database` last. Values are `as400`, `ds:<presetId>`
and `custom`.

**Stored values are unchanged: `as400` and `custom`.** The vendor is a UI concept only.
`collect()` folds every `ds:*` value down to `custom`, and `edit()` recovers the vendor by
matching `driverClass` against the preset table. Nothing migrates; a connection saved before
this reopens on the vendor its driver class identifies, or on the generic entry if it matches
none, and in neither case is its stored URL, driver class or test query touched.

**`typeChanged(apply)` takes a flag.** `true` only from the operator's `onchange`; `false`
from `edit()` and `newDs()`, where showing the right fields must not overwrite what the
connection holds. The old code called `typeChanged()` from `edit()` before populating the
fields, which worked only because it had nothing to prefill.

**Prefill rule, unchanged in spirit:** URL and test query are templates, filled only when
empty, never overwriting something typed; the driver class is always corrected, being the
field nobody remembers.

## Two defects fixed on the way, both Oracle/SQL-Server specific

**`ojdbc11.jar` → `ojdbc8.jar`.** The Oracle preset named a JAR that cannot load here:
ojdbc11 is Java 11 bytecode (major 55) and the runtime is Java 8 on Tomcat 8.5, so it fails
with `UnsupportedClassVersionError`. The driver class is identical in both. The SQL Server
preset was already correct (`mssql-jdbc-*.jre8.jar`).

**`SELECT 1` is not universal.** `SqlSupport.testQuery()` returned `SELECT 1` for every
`custom` datasource. Oracle rejects a SELECT without FROM (`ORA-00923`), so Test connection
answered a perfectly healthy Oracle connection with a syntax error — read by an operator as a
connection failure, on the first thing he tries after configuring it. Fixed in two places:
the presets now carry a per-vendor `test` query that fills the field, and `testQuery()` falls
back on the JDBC URL prefix (`jdbc:oracle` → `FROM DUAL`, `jdbc:db2`/`jdbc:as400` →
`SYSIBM.SYSDUMMY1`) for a field left empty or a connection saved earlier. An explicit test
query on the datasource still wins over both.

**`trustServerCertificate=true` added to the SQL Server URL template.** From mssql-jdbc 10.x
`encrypt` defaults to true with strict certificate validation; against an internal server with
a private CA the connection fails on the certificate, not on anything the operator did. It is
a template to edit, so removing it is one keystroke.

## Also

The connections list showed `custom` in the Type column for every JDBC connection, which tells
a reader nothing. It now names the vendor via `typeLabel()`, falling back to `JDBC`.

## Files touched

- `src/main/resources/templates/datasources.html` — dropdown, `typeChanged(apply)`,
  `fillTypes()`, `presetById()`, `presetByDriver()`, `typeLabel()`, `collect()`, `edit()`,
  `newDs()`; `#d_preset` and `presetChanged()` removed (absorbed)
- `src/main/java/com/legalarchive/orchestrator/ds/SqlSupport.java` — `testQuery()`
- `src/main/resources/static/USAGE.md` — three paragraphs, one line each (docs.html renderer)

## Verified

- jsdom against the real template, **90 assertions passing**, plus a positive control that
  fails on purpose to prove the harness can see a wrong value.
- `javac --release 8 -Xlint:all` on the extracted `testQuery()`, **18 assertions passing** on
  its truth table, plus its own positive control.
- `node --check` on both inline scripts; zero literal `\n`/`\r`/`\t` escapes in JS; zero
  `[[` / `[(` in the template; brace/paren balance 0/0 on `SqlSupport` ignoring strings and
  comments; no new imports needed.

## NOT verified

- `mvn clean package` — no Maven Central from the sandbox.
- **Any actual connection to Oracle or SQL Server.** That needs the driver JAR in
  `CATALINA_HOME/lib` and a reachable server, so it can only happen on deploy. The IBM i
  connections are the ones to re-test first, being the ones already in production.
- That `ojdbc8.jar` and `mssql-jdbc-*.jre8.jar` are obtainable from the internal Nexus.

## Follow-up

`rs.getObject()` on an Oracle `NUMBER` returns a `BigDecimal`, and `cellValue()` calls
`toString()` on it — which emits scientific notation (`1E+10`) for some scale/exponent
combinations. Oracle uses `NUMBER` for every numeric column, so this is far more likely to
bite there than on DB2. Worth **measuring against a real Oracle column** before deciding
anything: the fix would touch the extraction path shared by every existing feed.
