# Spec — `xlsx2csv` executor (Excel sheet → CSV, Apache POI)

> Handoff document for the OpenProteo dev chat. Drop into `.claude/2026-06-19-xlsx2csv-executor-poi.md`.
> Companion of `csvsql`: `xlsx2csv` prepares a CSV from one Excel sheet; its output then feeds a
> `csvsql` `<input>`. Follows `CLAUDE.md`. Java 8.
>
> **PREREQUISITE (gate the whole batch).** Unlike `csvsql` (pure JDBC, no compile dep), POI is a
> **compile-time dependency** — the module will not build until POI **and all its transitives** resolve
> on the corporate Nexus. **Do step 0 first** (§8 probe). Do not write the Java until `PoiProbe` passes.

## 1. Goal (user-facing behaviour)

A new internal STEP that reads **one sheet** of an `.xlsx` workbook, lets the user pick **which columns
(and order)** to keep, and streams them to a CSV using the house conventions — so the result drops
straight into a `csvsql` `<input>`.

In the designer the user:

1. Selects the **xlsx path** with the usual field (`class="sugin" list="pathsug"`): uploaded workflow
   files, step dirs, shared files, `${var}` — same selection mode as every other step.
2. Picks a **sheet** (dropdown populated by introspecting the workbook).
3. Picks **columns** (by header name or column letter), sets their **order** and optional **rename**.
4. Sets the **output CSV** (`csvFile`), delimiter, optional split — same as `sql`/`csvsql`.
5. Hits **Preview** to see the first rows of the resulting CSV.

One `xlsx2csv` per workbook/sheet; chain several, then a `csvsql` joins their outputs.

## 2. Design decisions (locked)

- **All cells → text/VARCHAR**, deterministically (consistent with the `csvsql` all-VARCHAR line):
  no type inference downstream; the CSV carries plain text. See the cell contract in §3.
- **Streaming event API** (`OPCPackage` + `XSSFReader` + SAX), **not** the usermodel `XSSFWorkbook`
  (which loads the whole sheet into memory). Constant memory → large workbooks are fine. Base the
  reader on POI's official `XLSX2CSV` event example, customised for the §3 contract.
- **Deterministic rendering, not Excel-displayed.** Dates are emitted in a fixed `dateFormat`
  (default `yyyyMMdd`), numbers as plain decimals (no scientific notation, no locale grouping) — *not*
  the per-cell numFmt as Excel shows it. This needs a custom SAX handler that reads the raw value +
  style (POI's `StylesTable`/`DateUtil`), because `XSSFSheetXMLHandler`'s callback only hands back the
  already-Excel-formatted string.
- **Reuse one CSV writer.** Refactor the CSV-writing core (shared with `sql`/`csvsql`) into a small
  row-oriented `CsvWriter` (open / writeHeader / writeRow(String[]) / rotate-on-split / close) so
  `xlsx2csv` writes through the same code → identical UTF-8/optional-BOM/CRLF/quoting/split and the
  same `csvFile`/`csvFiles`/`csvParts`/`rowCount` outputs (LOOP-compatible).
- **POI is a hard compile dependency** (see prerequisite). Confine all `org.apache.poi.*` imports to the
  new `xlsx/` reader package.
- **Scope: `.xlsx` only** (OOXML). `.xls` (BIFF/HSSF) is out of scope for this batch.

## 3. Cell rendering contract (the heart of the step)

For each selected cell, decode by its XML type (`<c t="...">`) and style, then emit text:

| Excel cell                         | Emitted text |
|------------------------------------|--------------|
| Shared string (`t="s"`)            | the string, verbatim |
| Inline string (`t="inlineStr"`)    | the string, verbatim |
| Formula string (`t="str"`)         | cached string value (`<v>`); empty if absent |
| Boolean (`t="b"`)                  | `TRUE` / `FALSE` |
| Error (`t="e"`)                    | empty (configurable) |
| Number, **date-formatted style**   | `DateUtil.getJavaDate(serial, is1904)` → formatted with `dateFormat` (default `yyyyMMdd`) |
| Number, plain                      | `BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()` (no exponent, `.` decimal) |
| Empty / missing cell               | empty field (preserve column position) |

Date detection: `DateUtil.isADateFormat(numFmtId, formatCode)` from the cell's style in `StylesTable`.
`is1904`: read `date1904` from `xl/workbook.xml` (default false).
Option `rawValues=true` → emit the raw stored value instead (serial for dates, plain double for
numbers) for users who want zero interpretation. Default false.

> **Important data-loss caveat (document in README).** If the source stored a *code* (NDG, CF, IBAN)
> as a **number** instead of text, Excel already dropped leading zeros / lost precision (e.g. shows
> `1.23E+15`). That damage happened in the source workbook and **cannot be recovered here** — codes
> must be stored as text in the xlsx. Flag in the UI help.

## 4. XML shape

`source` = xlsx path (reused field). Scalars in `<param>` (like `mask`); columns as repeating
`<column>` (new `ColumnSel` model, mirrors `CsvInput`). Output via `csvFile`/`delimiter`/split (reused).

```xml
<step id="prep_clienti" name="Clienti.xlsx -> CSV" exec="xlsx2csv"
      source="${landingIn}/clienti.xlsx"
      csvFile="${stepDir}/clienti.csv" delimiter=";"
      csvSplitRows="0" csvSplitMb="0">
  <param name="sheet"        value="Anagrafica"/>   <!-- or sheetIndex -->
  <param name="headerRow"    value="1"/>
  <param name="firstDataRow" value="2"/>
  <param name="selectBy"     value="header"/>        <!-- header | letter -->
  <param name="dateFormat"   value="yyyyMMdd"/>
  <param name="rawValues"    value="false"/>
  <param name="skipEmptyRows" value="true"/>
  <column src="NDG"            as="NDG"/>
  <column src="Ragione Sociale" as="NOME"/>
  <column src="Data Apertura"  as="DATA_APERTURA"/>
</step>
```

No `<column>` children → include **all** columns, using the header-row names as output headers.

## 5. Implementation checklist (by file)

> Same **four-location rule** as `csvsql` (parser whitelist + `internal` set, `WorkflowEngine`,
> `InternalSteps` dispatch) plus writer/DTO/def→dto/designer/preview/pom/probe.

**Model**
- `model/def/ColumnSel.java` — `{ public String src; public String as; }` (mirror `Replacement`).
- `model/def/StepDef.java` — add `public java.util.List<ColumnSel> columns = new ArrayList<>();`
  (`source`, `csvFile`, `delimiter`, `csvSplitRows`, `csvSplitMb`, `params` already exist).

**Parser — `parser/WorkflowXmlParser.java`**
- Add `"xlsx2csv"` to the **exec whitelist** and to the **`internal` boolean**.
- Parse repeating `<column>` children into `s.columns` (mirror the `<replace>` loop:
  `ci.src = c.getAttribute("src"); ci.as = c.getAttribute("as");`).

**Engine — `engine/WorkflowEngine.java`**
- Add `e.equals("xlsx2csv")` to `internalKind(String exec)`.

**Dispatch + executor — `engine/InternalSteps.java`**
- `else if ("xlsx2csv".equals(kind)) { runXlsx2Csv(step, resolvedParams, vars, res, line); }`.
- `runXlsx2Csv(...)`:
  1. Resolve `source` (xlsx path), `csvFile`, `delimiter` (default `;`), params, `columns`.
  2. Validate: source exists and ends `.xlsx`; sheet given (name or index) and present; if `selectBy=
     header`, header row resolves all `<column src>` to indices; `csvFile` non-blank. Else exit code 2.
  3. Open via the `xlsx/` reader (§6), stream rows, project selected columns (fill missing as empty),
     write through the shared `CsvWriter` honouring `csvSplitRows`/`csvSplitMb`.
  4. Publish `##VAR rowCount/csvParts/csvFile/csvFiles` **and** `##VAR outputFile` (= first file), so
     both `${id.csvFiles}` and `${id.outputFile}` work as a `csvsql` input.
  5. `finally`: close package/writer.

**Reader package — `xlsx/` (the only place importing `org.apache.poi.*`)**
- `xlsx/XlsxSheetReader.java` — POI event reader (§6).

**CSV writer refactor — `ds/SqlSupport.java` (or new `store/CsvWriter.java`)**
- Extract the row-level CSV writer (open, header, `writeRow(String[])`, split rotation, close) so
  `sql`, `csvsql` and `xlsx2csv` share it. **Quoting matters more here**: Excel cells can contain the
  delimiter, `"`, and embedded newlines (Alt+Enter) — the writer must RFC-4180 quote (wrap in `"`,
  double internal `"`) any field containing delimiter/CR/LF/quote.

**DTO — `web/dto/WorkflowDto.java`**
- `class ColumnSelDto { public String src; public String as; }` + `public List<ColumnSelDto> columns;`
  on `NodeDto`.

**Def→DTO (designer load) — `web/ApiController.java`**
- Copy `st.columns` → `nd.columns` next to the existing `nd.query`/`nd.replacements` copies.

**Writer — `parser/WorkflowXmlWriter.java`**
- `if (n.columns != null) for (ColumnSelDto c : n.columns) { Element ce = doc.createElement("column");
   attr(ce,"src",c.src); attr(ce,"as",c.as); s.appendChild(ce); }` (next to `<replace>`).

**Introspection + preview endpoints — `web/ApiController.java`** (§7)

**Designer — `templates/designer.html`** (§ below)

**Dependency + probe** (§8)

## 6. Reader implementation notes (POI event API)

- `OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ);`
- `XSSFReader r = new XSSFReader(pkg);`
- `ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);`
  (loads all shared strings into memory — fine for typical extracts; note the limit in §9.)
- `StylesTable styles = r.getStylesTable();`
- Sheets: `XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) r.getSheetsData();`
  each `it.next()` is the sheet `InputStream`; `it.getSheetName()` is its name → select by name/index.
- Parse the chosen sheet with a **custom `ContentHandler`** (or `XSSFSheetXMLHandler` + a
  `SheetContentsHandler` if you accept Excel-displayed formatting; for the deterministic §3 contract the
  custom handler is required). Per `<c>`: read `r` (cell ref → column index), `t` (type), `s` (style
  index → `styles.getStyleAt(...)` → numFmt for date detection), `<v>` (value). Decode per §3.
- **Empty-cell gaps:** derive the column index from the cell ref (`A`,`B`,…); buffer the row into a
  fixed array sized to the selected columns; any selected column with no cell stays empty.
- **Header / data rows:** skip until `headerRow`; on `selectBy=header`, build the header-name→index map
  from that row; start emitting at `firstDataRow`.
- **`skipEmptyRows`:** drop rows where all selected cells are empty (Excel emits phantom trailing rows).
- Always close the sheet `InputStream` and the `OPCPackage` in `finally`.

## 7. Introspection + preview endpoints (`web/ApiController.java`)

- `GET /api/workflows/{feedId}/xlsx/sheets?path=...` → resolve path, open package, iterate
  `SheetIterator` → `{ ok, sheets:[{name, index}] }` for the sheet dropdown.
- `POST /api/workflows/{feedId}/xlsx/preview` (JSON body `{ path, sheet|sheetIndex, headerRow,
  firstDataRow, selectBy, columns, dateFormat, delimiter, rawValues }`) → run the real conversion but
  stop after, say, 50 data rows → `{ ok, headers:[...], rows:[[...]], note }`. Used both for the column
  picker (return the header row + samples even before columns are chosen) and the Preview button.
  Note: not the text/plain `@RequestBody String` stub — use a JSON body DTO (Jackson).

## 8. Dependency / Nexus — Apache POI (mirror ARX/H2 placeholder; **do this first**)

- Coordinates (pin a version; POI is **not** managed by the Spring Boot BOM):
  ```xml
  <dependency><groupId>org.apache.poi</groupId><artifactId>poi</artifactId><version>5.2.5</version></dependency>
  <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.2.5</version></dependency>
  ```
  POI 5.2.x is Java-8-compatible (confirm the exact floor for the version you pin). License Apache-2.0
  (internal license review may still apply).
- **Confirm ALL transitives resolve on Nexus** — POI's tree is large (the "anti-H2"). Verify with
  `mvn dependency:tree`; at minimum expect: `poi-ooxml-lite` (OOXML schemas — the heavy one),
  `org.apache.xmlbeans:xmlbeans`, `commons-codec`, `org.apache.commons:commons-collections4`,
  `commons-io:commons-io`, `org.apache.commons:commons-math3`, `org.apache.commons:commons-compress`,
  `com.zaxxer:SparseBitSet`, `com.github.virtuald:curvesapi`, `org.apache.logging.log4j:log4j-api`.
  Any that don't resolve → vendor via `mvn install:install-file` (the no-CDN fallback, as for ARX).
- `xlsx/README_POI.md` + `xlsx/PoiProbe.java` (mirror `arx/`): probe opens a tiny 2-row `.xlsx` via the
  event API and prints `rows=2` → the "POI available?" link test. **Only after the probe passes** add
  the pom deps and the Java.
- **WAR size:** `poi-ooxml-lite` is several MB; it inflates the otherwise-light WAR. `poi-ooxml-lite`
  (default) is sufficient for reading common workbooks; do **not** pull `poi-ooxml-full` (~15 MB) —
  it's only needed for exotic features we don't use.

## 9. Known gotchas (document in README.md)

- **Codes stored as numbers** — leading zeros / precision already lost in the source (see §3 caveat).
- **Merged cells** — value lives only in the top-left cell; other covered cells are empty. Relevant for
  multi-row or merged headers; prefer a clean single header row.
- **Newlines inside cells** (Alt+Enter) — handled only if the CSV writer quotes correctly (§5).
- **1904 date system** — handle via `date1904`; otherwise dates shift by ~4 years.
- **Huge shared-strings table** — `ReadOnlySharedStringsTable` is in-memory; for pathological files
  (millions of unique strings) consider a streaming SST in Batch 2.
- **Formulas never calculated** — no cached `<v>` → emitted empty (POI event mode doesn't evaluate).

## 10. Outputs / composition with `csvsql`

Outputs `##VAR rowCount`, `csvFile`, `csvFiles`, `csvParts`, `outputFile`. Chain:

```xml
<step id="prep_anag" exec="xlsx2csv" source="${landingIn}/clienti.xlsx"
      csvFile="${stepDir}/anag.csv" delimiter=";"> ... </step>
<step id="prep_conti" exec="xlsx2csv" source="${landingIn}/conti.xlsx"
      csvFile="${stepDir}/conti.csv" delimiter=";"> ... </step>

<step id="join" exec="csvsql" csvFile="${landingOut}/report_${runDate}.csv" delimiter=";">
  <input csv="${prep_anag.outputFile}"  table="anagrafica"/>
  <input csv="${prep_conti.outputFile}" table="conti"/>
  <query>
    SELECT a.NDG, a.NOME, COUNT(*) AS N_CONTI
      FROM anagrafica a JOIN conti c ON a.NDG = c.NDG
     GROUP BY a.NDG, a.NOME
  </query>
</step>
```

The `delimiter` of each `xlsx2csv` output must match the `delimiter` the `csvsql` step uses for `CSVREAD`.

## 11. Designer (`templates/designer.html`)

- Node template defaults: add `columns: []`.
- Executor `<select>`: add `<option value="xlsx2csv">xlsx2csv (Excel sheet -> CSV)</option>`.
- New `else if (ex === 'xlsx2csv')` branch:
  - xlsx path field (`sugin/pathsug`, bound to `source`) + a **Load sheets** button →
    `xlsx/sheets` → fill a **sheet** `<select>` (param `sheet`).
  - `headerRow` / `firstDataRow` number fields; `selectBy` select (header/letter); `dateFormat` text
    (default `yyyyMMdd`); `rawValues` + `skipEmptyRows` toggles (params).
  - **Load columns** → `xlsx/preview` → render a column picker (checkbox + order + optional rename),
    writing the chosen set into `columns` (repeating, like the param-rows / replacements UI).
  - Output `csvFile` (`sugin/pathsug`), `delimiter`, split fields (copied from the `sql`/`csvsql` branch).
  - **Preview** button → `previewXlsx2Csv(i)` → `xlsx/preview` → render resulting rows in a `xmlbox`.
  - Help line: all cells become text; dates → `dateFormat`; numbers → plain decimals; codes must be
    text in the source. outputs: `##VAR rowCount, csvFile, csvFiles, csvParts, outputFile`.
- `toXml`/`load`: handle `columns` ↔ `<column src as>` (mirror replacements).
- **JS gotchas (CLAUDE.md):** reuse `NL = String.fromCharCode(10)`; no literal `\n`/`\r`; no
  uncommented `[[`/`[(`; only defined CSS vars.

## 12. Validation

- Client + server: source ends `.xlsx`; sheet selected and present; if `selectBy=header`, every
  `<column src>` resolves in the header row; column `as` names unique; `csvFile` non-blank.

## 13. Out of scope (Batch 2+)

- `.xls` (HSSF event), streaming shared-strings table, multi-sheet-in-one-step, merged-header
  flattening, per-cell type overrides, formula evaluation, direct xlsx as a `csvsql` `<input>` (inline).

## 14. Deliverables for the turn

- **Step 0 (prerequisite):** POI + transitives confirmed on Nexus, `PoiProbe` passes. Only then code.
- The code changes above.
- `COMMIT_MSG.txt` (English, ≤72), e.g. `Add xlsx2csv executor: Excel sheet -> CSV via POI (streaming)`.
- `.claude/2026-06-19-xlsx2csv-executor-poi.md` — what/why/files/follow-ups.
- Build/verify per CLAUDE.md: `javac` (now needs POI on the classpath), `node --check` on extracted
  inline JS, scan templates for literal `\n`/`\r` and uncommented `[[`/`[(`, repackage the zip from the
  parent dir, deploy (stop Tomcat, delete WAR **and** exploded dir), `Ctrl+F5`.

---

## Implementation status (2026-06-19) — Batch 1 done

Implemented per §5 (same four-location rule as csvsql):
- **Model**: `model/def/ColumnSel.java` `{src, as}`; `StepDef.columns`.
- **Parser**: `xlsx2csv` added to the exec whitelist + the `internal` boolean; repeating
  `<column src as>` parsed into `s.columns` (`as` defaults to `src`).
- **Engine**: `internalKind()` returns `xlsx2csv`.
- **Reader (the only POI importer)**: `xlsx/XlsxSheetReader.java` — streaming event API
  (`OPCPackage` + `XSSFReader` + a custom SAX `DefaultHandler`), constant memory.
  `sheetNames(File)`; `read(file, sheetName, sheetIndex, dateFormat, rawValues, RowSink)`; the §3
  cell contract (shared/inline/formula strings verbatim; bool `TRUE`/`FALSE`; error empty;
  date-styled number via `DateUtil.getJavaDate` + `SimpleDateFormat`(UTC); plain number via
  `BigDecimal.stripTrailingZeros().toPlainString()`; empty preserved; `date1904` read from
  `xl/workbook.xml`); `Plan` + `plan(header, srcs, ases, selectBy)` column resolution
  (header-name exact-then-case-insensitive, or column letter; empty = all columns; throws on
  missing); `columnLetterToIndex`.
- **CSV writer refactor**: new shared `ds/CsvWriter.java` (open/header/row/split-rotate/close,
  UTF-8, optional BOM, CRLF, RFC-4180 quoting). `SqlSupport.exportResultSet` now delegates to it
  (dead `buildLine`/`csvField`/`utf8Len`/`partName` removed), so `sql`, `csvsql` and `xlsx2csv`
  produce byte-identical CSV.
- **Executor**: `InternalSteps.runXlsx2Csv` + dispatch. Validates source `.xlsx` exists + `csvFile`;
  POI presence guard (clear message); streams the sheet, builds the `Plan` at `headerRow`, projects
  selected columns (missing -> empty), `skipEmptyRows`, writes through `CsvWriter` with **no BOM**;
  publishes `##VAR rowCount/csvParts/csvFile/csvFiles/outputFile` (`outputFile` = first part, so
  `${id.outputFile}` feeds a `csvsql` `<input>`).
- **DTO**: `WorkflowDto.NodeDto.ColumnSelDto` + `columns`.
- **ApiController**: `toDto` copies `st.columns`; `feedVars(def,feedId)` helper;
  `GET /api/workflows/{feedId}/xlsx/sheets?path=` (sheet dropdown);
  `POST /api/workflows/{feedId}/xlsx/preview` (JSON DTO, mirrors the executor projection, caps at
  50 data rows via a private `StopPreview`).
- **Writer**: emits `<column src as>` (`as` omitted when blank).
- **pom.xml**: `org.apache.poi:poi` + `poi-ooxml` 5.2.5 at **compile** scope (NOT BOM-managed).
- **xlsx/**: `README_POI.md` (Nexus / transitives / vendoring / gate-first) + `PoiProbe.java`
  (write 2 rows via usermodel, event-read them back, expect `event rows = 3`).
- **designer.html**: executor option; full `xlsx2csv` branch (xlsx path + Load sheets -> sheet
  select; headerRow/firstDataRow/selectBy/dateFormat/rawValues/skipEmptyRows; repeating column rows
  + Add column + Load columns from sheet; output csvFile/delimiter/split; Preview; help);
  `toXml`/`load` for `<column>`; `addCol/delCol/updCol`; `loadXlsxSheets/loadXlsxColumns/
  xlsxPayload/previewXlsx2Csv`; client validation.

### Verification (sandbox)
- Full compile: **96 classes**, against hand-written POI stubs (same approach as Spring/Jackson/
  jt400 — the sandbox has no real libraries).
- `TestXlsx`: writer emits `exec=xlsx2csv` + `<column>` (incl. rename); parser round-trips them
  (whitelist OK); `plan()` resolves header names to indices + order, applies renames, empty=all,
  letter mode A->0/C->2, missing-column throws, `columnLetterToIndex("AB")==27`. **All pass.**
- Designer inline JS passes `node --check`; no bare `\n`/`\r`; no unsafe `[[`/`[(`.

### NOT verifiable in sandbox / Nexus gate
- **POI is a COMPILE-TIME dependency**: the real WAR will NOT build until POI **and all transitives**
  resolve on Nexus. Run `xlsx/PoiProbe` first (expect `POI OK: event rows = 3`); see
  `xlsx/README_POI.md`. Live `.xlsx` reading, the two endpoints and browser rendering are UBS-side
  (Ctrl+F5).

### Follow-ups (Batch 2+, §13)
`.xls`/HSSF event reader; streaming shared-strings table for pathological files; merged-header
flattening; per-cell type overrides; formula evaluation; inline `.xlsx` directly as a `csvsql`
`<input>`.
