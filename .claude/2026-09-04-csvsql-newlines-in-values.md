# csvsql: expose newlinesInValues in the designer + log the count

## What was actually missing

Nothing in the engine. `runCsvSql` has read the `newlinesInValues` step parameter and published
`${newlinesSanitized}` since `.claude/2026-07-24-extraction-newlines-conservative-defaults.md`, through
the *same* `SqlSupport.exportResultSet(..., nlMode)` / `nlReplacement(mode)` used by `sql` — so the three
user options are identical by construction, not by convention.

What was missing was the way in:

1. `designer.html` rendered the `newlinesInValues` dropdown only in the `ex === 'sql'` branch. The
   `csvsql` branch had no control, so the parameter could only be set by hand-editing the workflow XML.
2. The Variables page could not fill the gap: `PARAM_OPTIONS.newlinesInValues` already exists there, but
   the editor lists only params **already present** on the step (`st.params.forEach`). Until the designer
   writes the param once, a mass edit cannot see it.
3. `runCsvSql` did not log the count; `runSql` does (`normalised line breaks inside N value(s)`). The
   2026-07-24 note recorded this asymmetry explicitly ("sql *also* logs when > 0"), so it was a known
   residue, not a defect to investigate.
4. `static/USAGE.md` already states "The same option applies to the **csvsql** step" — the documentation
   was ahead of the UI. This patch makes it true.

## Changes

* `designer.html`, `csvsql` branch: a `Line breaks inside values (CR/LF arriving from the input CSVs)`
  dropdown (keep / space / strip), bound with `setNodeParam`, placed after the split row and before the
  query box. Wording differs from the `sql` panel deliberately: `sql` normalises what a **database**
  returns, `csvsql` what an **input CSV** carries. The values and their order are identical, so the
  neutral `PARAM_OPTIONS` entry on the Variables page stays correct for both.
* `InternalSteps.runCsvSql`: `if (er.newlinesSanitized > 0) line.accept("normalised line breaks inside
  " + er.newlinesSanitized + " value(s)");` — the same statement as `runSql`, in the same position.
* `InternalSteps.runCsvSql`: a comment at the `exportResultSet` call recording the scope limit below.

No parser/writer/DTO/model change: `newlinesInValues` is a generic `<param>` that already round-trips.
No `clientValidate` change: it is not a required field. The 4-location rule does not apply (no new
executor). Default stays **keep** — no feed changes output on deploy.

## Scope limit, now measured rather than assumed

The normalisation happens while **writing** the result, not while reading the inputs. Measured on real
H2 2.1.214, driving the exact `CREATE TABLE ... AS SELECT * FROM CSVREAD(path, NULL, 'fieldSeparator=;
charset=UTF-8')` staging statement from `runCsvSql` into the verbatim `exportResultSet`:

| input | keep | space / strip |
|---|---|---|
| break inside a **quoted** field | CSVREAD reassembles it; output record still spans 2 physical lines, `newlinesSanitized=0` | one physical line, `newlinesSanitized=1` |
| break at the **edges** of a quoted value | already gone (`trim` defaults true and `String.trim()` eats CR/LF), `newlinesSanitized=0` | same |
| **bare** (unquoted) break | CSVREAD had already shredded the record into two malformed rows before the exporter sees anything; `newlinesSanitized=0` and the option cannot help | same |

So: the option fixes case 1, case 2 needs no option, and case 3 stays with `dequote`
(`embeddedNewlines`) or the extraction upstream. The designer hint says exactly this.

## Verify

* Real end-to-end run against H2 2.1.214 with `CsvWriter` and the `exportResultSet`/`nlReplacement`
  methods lifted verbatim from `SqlSupport` (not retyped): the table above is the measured output.
* The new fragment rendered in Node for `absent / "" / keep / space / strip / SPACE / garbage`: exactly
  one `selected` in every case, div and select tags balanced.
* `designer.html`: inline scripts extracted, `node --check` OK; zero literal `\n`/`\r`; zero unsafe
  `[[` / `[(`. Each of the three scans was re-run against a deliberately broken copy first and did flag
  the injected defect, so the clean result means something.
* `InternalSteps.java`: brace balance 0 ignoring strings and comments (positive control with an injected
  brace returns 1); the added statement uses only `er` and `line`, so no new import.
* **Not verified here**: `mvn clean package` (Maven unreachable from the chat sandbox).

## Known, unchanged

A value of `SPACE` (uppercase) shows as `keep` in the dropdown but is applied as `space` by the engine
(`nlReplacement` is `equalsIgnoreCase`). Identical expression and identical behaviour in the `sql` panel;
it can only be reached by hand-editing the XML, since both the designer and the Variables page write
lowercase. Left alone rather than fixed silently in a patch about something else.

## Follow-up not taken

`static/USAGE.md` line 379 promises the option for `csvsql` but says nothing about the quoted-field
limit or about `trim` already handling the edges. One sentence would align it with the measurements
above; out of scope for this patch.
