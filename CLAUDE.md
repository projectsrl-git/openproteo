# CLAUDE.md

Orientamento per Claude (e altri tool AI in VSC) che vengono ricollegati a
questo repository. Tieni questo file aggiornato a mano: è la mappa del progetto
e dei vincoli che NON sono ovvi dal codice.

## Cos'è OpenProteo

Web app **Java 8 / Spring Boot 2.7 / Thymeleaf** che orchestra ed esegue in
modo programmato gli script PowerShell e gli step built-in di preparazione e
spedizione dei feed Legal Archive in un ambiente corporate UBS (Credit Suisse →
UBS decommissioning).

Pacchettizzata come **WAR per un Tomcat esterno**, senza embedded server.

## Stack e regole irrinunciabili

* **Java 8** — niente API ≥ 9 nemmeno per zucchero sintattico.
* **Spring Boot 2.7**, Thymeleaf, Maven, Tomcat 8.5/9 esterno.
* **Zero CDN, zero dipendenze a runtime di rete**: ogni risorsa è bundled
  (font, JS, CSS, pool di masking) o vive in path locali configurati nella
  `application.properties` esterna.
* **Niente database server**: lo stato vive su **file** (JSON / JSONL pretty,
  audit hash-chained, run logs).
* **ARX non disponibile**: la dipendenza ARX NON è nel `pom.xml`. Lo step
  `anonymize` è un placeholder (Batch 2a free-text funziona, Batch 2b
  k-anonymity rinviato). Lo step **`mask`** è l'executor attivo per il masking.

### Vincoli ambientali con effetti silenziosi

1. **JavaScript servito al browser** (sia `static/js/*.js` sia gli inline
   `<script>` dei template Thymeleaf): **MAI** scrivere `\n` o `\r` letterali
   nelle stringhe. Il proxy/DLP UBS normalizza le sequenze di escape in newline
   reali, rompendo stringhe e regex. Usare `String.fromCharCode(10)` /
   `String.fromCharCode(13)`, oppure costruire i pattern regex dinamicamente.
   *Non vale per il sorgente Java (compilato).*
2. **Thymeleaf templates**: il pattern `[[` o `[(` fuori dal commento di
   inlining `/*[[${...}]]*/` provoca **`TemplateOutputException`** a render.
   Verifica template DEVE includere uno scan di `[[` / `[(` non commentati.
   Errore tipico: array JS `[[ 'a', 'b' ]]` — separa con spazio: `[ [ ... ] ]`.
3. **Browser corporate UBS**: comportamento simile a Edge/IE molto vecchi.
   Evitare CSS recente: niente `grid` con `auto-fit` + `minmax()` come unico
   layout (cade in stacking verticale). Preferire **flex con wrap** e
   prefissare `-webkit-` quando serve.
4. **Tema chiaro/scuro**: default scuro; preferenza in localStorage (`op-theme`),
   attributo `data-theme="light"` su `<html>`. Palette chiara in `:root[data-theme="light"]`.
   Toggle iniettato nella topbar da `static/js/theme.js` (incluso da tutte le pagine, con
   snippet anti-flash nel <head>). Le label uppercase usano `--label` (alto contrasto).
5. **CSS variables**: usare solo le variabili effettivamente definite in
   `app.css` (`--bg`, `--bg-raise`, `--bg-panel`, `--line`, `--line-soft`,
   `--ink`, `--ink-dim`, `--ink-faint`, `--accent` ambra `#f5a623`,
   `--accent-dim`, `--ok`, `--ok-bg`, `--run`, `--run-bg`, `--fail`).
   **Mai inventare** `--panel`, `--bg-hover`, `--fg-mute` ecc.: appaiono OK in
   un browser tollerante e si rompono altrove.
6. **Log applicativo**: timestamp **a precisione di millisecondo**.
7. **PII**: **MAI loggare** valori originali né mascherati di campi PII.
8. **Deploy WAR**: a Tomcat fermo, cancellare **sia** `webapps/openproteo.war`
   **sia** la cartella esplosa `webapps/openproteo/` prima di ricopiare il
   WAR. Altrimenti rimane l'esploso stantio.

## Layout del progetto

```
.
├── pom.xml                       WAR build, spring-boot repackage DISATTIVATO
├── README.md                     documentazione in italiano (UI in inglese)
├── CLAUDE.md                     questo file
├── .claude/                      changelog per turno di sessione AI
├── src/main/java/com/legalarchive/orchestrator/
│   ├── OrchestratorApplication.java   bootstrap
│   ├── ServletInitializer.java        per il deploy WAR
│   ├── config/AppProperties.java      tutti i parametri orchestrator.*
│   ├── parser/                        parsing + writer XML workflow
│   ├── model/                         def + run dei workflow
│   ├── engine/                        WorkflowEngine FIFO, StepExecutor,
│   │                                  InternalSteps (validate, csvreplace,
│   │                                  encoding, anonymize, mask),
│   │                                  WorkflowScheduler (cron), VarResolver
│   ├── mask/                          MaskEngine HMAC, MaskPools, MaskGenerators
│   ├── audit/AuditLogger.java         JSONL hash-chained
│   ├── store/                         FeedLayout, RunStore, WorkflowRegistry,
│   │                                  AssetStore, CsvService (byte-offset index)
│   ├── ds/                            DataSource (DB2/AS400), SqlSupport, IfsSupport
│   └── web/                           PageController, ApiController
├── src/main/resources/
│   ├── application.properties         defaults (override in esterno)
│   ├── templates/                     Thymeleaf, UI inglese
│   ├── static/                        css + js (no CDN)
│   └── maskdata/                      pool nomi/città/vie/aziende bundlati
├── workflows/                         SAMPLE-*.xml NON nel WAR, vivono in
│                                      orchestrator.workflows-dir esterno
├── samples/                           dataschema/displayschema/eor_sample.csv
├── scripts/                           PowerShell di feed (Prepare/Process/Send)
├── arx/                               probe Java per ARX su Nexus
└── tools/                             patch-war-resources.bat/.sh
```

## Configurazione esterna

`application.properties` esterno sotto `CATALINA_HOME/config/`, attivato via
`-Dspring.config.additional-location=file:...`. Chiavi principali:

```
orchestrator.workflows-dir=        # dove vivono i SAMPLE-*.xml e i workflow reali
orchestrator.scripts-dir=          # PowerShell
orchestrator.default-base-dir=     # base dir dei feed (sovrascrivibile per feed)
orchestrator.datasources-file=     # JSON datasources

orchestrator.masking-secret=       # OBBLIGATORIO per step mask. MAI nel repo.
orchestrator.mask-normalize=trimUpper
orchestrator.mask-pools-dir=       # opzionale: override dei pool senza rebuild
```

## Step built-in e loro stato

* `validate`, `csvreplace`, `setvar`, `filecopy`, `ifscopy`, `sql`, `jar`,
  `powershell`, `cmd`, `auto` — stabili.
* `sql` — export risultato a CSV. Supporta `{{columns}}` nella query: espande la lista
  campi dal dataschema (param `columnsSchema`), `columnQuote=none|double`. Stabile.
* **Loop a blocco**: nodi LOOP/ENDLOOP (kind omonimi). LOOP itera `over` (lista, split per
  `delimiter`, default ;) eseguendo i nodi fino a ENDLOOP una volta per item, in sequenza,
  con ${itemVar}/${indexVar}/${countVar} (default item/loopIndex/loopCount). Stato persistito
  in run.vars (sopravvive a pause su gate). Matching annidato via stack. maxTransitions
  (default 500) limita i giri totali: alzarlo per loop su molti file.
* `encoding` — single + directory batch (filter, recursive, outputDir). Stabile.
* `mask` — executor attivo per il masking. Streaming deterministico (HMAC-SHA256),
  memoria costante, format-preserving + pool + free-text + 3 modalità CID. Mappa
  colonne via **liste per anonType** (il displayschema è opzionale, serve solo per
  `DataType=date`). Stabile.
* `anonymize` — **placeholder**. Batch 2a (free-text + ruoli colonne) funziona;
  Batch 2b (k-anonymity via ARX) **NON wired** — ogni colonna quasi/sensitive è
  passthrough. Dipendenza ARX assente dal `pom.xml`; rinviato a quando il jar
  sarà disponibile su Nexus corporate.
* `split` — divide un file in parti per righe/MB, riusa la logica di export SQL.
  Output: csvFiles/csvParts/csvFile/rowCount (iterabili da LOOP). Stabile.
* `safecopy` — copia wildcard dir→dir via temp `.on_fly_` + rename atomico. Stabile.
* `dequote` — rimuove quoting da file CSV. Stabile.
* `csvsql` — query SQL H2 su CSV locali (join tra file). Stabile.
* `xlsx2csv` — conversione foglio Excel → CSV. Stabile.

## Workflow di sviluppo

* **Ritmo**: incrementale, **confirmation-gated**. Una feature/batch alla
  volta, verifica reale sul deploy UBS, poi si prosegue.
* **GitHub**: `https://github.com/projectsrl-git/openproteo` (pubblico).
* **Sviluppo**: direttamente sulla working copy git (`D:\SVILUPPO\openproteo`)
  con **Claude Code**. Niente zip, niente `robocopy /MIR`, niente
  `deploy_openproteo.bat`. Commit e push diretti da git.
* **Deploy locale**: `mvn clean package` nella working copy, poi deploy
  manuale del WAR su Tomcat (stop → rimuovi WAR + esploso + work → copia WAR
  → start). Spring in locale usa i default bundled.
* **Sample**: i `SAMPLE-*.xml` NON sono nel WAR. In locale e su UBS vanno
  copiati a mano nella `orchestrator.workflows-dir` configurata.

* **Modali UI**: `static/js/modal.js` (incluso ovunque) espone `opConfirm(msg,onYes,opts)`
  e `opAlert(msg,opts)`; sostituiscono confirm()/alert() nativi. opts: {title,okText,
  cancelText,danger}. Mai piu' confirm()/alert() nei template.
* **Delete workflow**: `POST /api/workflows/{feedId}/delete` cancella il file XML e fa
  reload (rifiuta se c'e' un run attivo; storia/dati su disco restano). Bottone nel designer.
* **Multi-selezione dashboard**: checkbox per riga + barra azioni (Run/Delete massivi);
  loop client-side sugli endpoint per-feed. closest() NON usato (browser UBS): risalita
  DOM manuale.
* **Bulk create**: pagina /bulk + `POST /api/workflows/bulk`. Genera N workflow da un
  template XML + DUE CSV con nomi colonna configurabili. CSV#1 feeds: feedId (obblig.),
  name, sourceId, description, dataschema/displayschema (JSON inline -> scritti in feedDir).
  CSV#2 tables: feedId -> tableName, iniettato come variabile (default originTableName).
  name/sourceId/description accettano template con token {Nome Colonna} (spazi ammessi
  nel nome) per concatenare piu' colonne; senza graffe = singolo nome colonna.
  Colonne non mappate ignorate. Scrive nella workflows-dir + reload; schema JSON validati
  con Jackson e scritti nel feedDir dopo il reload. Generatore in
  parser/BulkWorkflowGenerator (DOM+CSV, no Jackson, unit-testabile).

## Convenzioni di commit / changelog

Ogni turno di sviluppo (= ogni "consegna" di Claude) produce:

1. uno o più commit con messaggio strutturato
   (riga 1 ≤ 72 char, riga 2 vuota, corpo wrap a 78);
2. un file `.claude/YYYY-MM-DD-slug.md` con il **riepilogo della modifica**
   (cosa, perché, file toccati, follow-up). Si committano insieme alle modifiche.

## Deploy & commit

Sviluppo diretto sulla working copy git con Claude Code. Niente più zip /
`robocopy /MIR` / `deploy_openproteo.bat`.

Flusso: modifica codice → `mvn clean package` (verifica build) →
generare `COMMIT_MSG.txt` → commit → push.

**COMMIT_MSG.txt**: ad ogni prompt che produce modifiche, Claude **DEVE**
creare/aggiornare `COMMIT_MSG.txt` nella root del repo con il messaggio di
commit (riga 1 ≤ 72 char, riga 2 vuota, corpo wrap a 78). Il commit si
esegue con `git commit -F COMMIT_MSG.txt`. Il file è versionato (entra nel
commit stesso). Il WAR prodotto si deploya manualmente su Tomcat.

## Regola delle 4 location per nuovi executor interni

Ogni nuovo executor interno (es. `dequote`, `csvsql`, …) va registrato in
**4 punti** — dimenticarne uno causa errori silenti o executor invisibile:

| # | File | Cosa |
|---|------|------|
| 1 | `engine/InternalSteps.java` — metodo `run()` | Aggiungere `else if` nel dispatch (chiama il metodo privato di esecuzione) |
| 2 | `engine/WorkflowEngine.java` — metodo `internalKind()` | Aggiungere `.equals("nome")` nella catena (valida il tipo come interno) |
| 3 | `templates/designer.html` — dropdown executor | Aggiungere `<option>` nel `<select>` del tipo executor |
| 4 | `templates/designer.html` — funzione `clientValidate()` | Aggiungere validazione campi obbligatori per il nuovo executor |

Se l'executor ha campi obbligatori specifici (source, dest, ecc.), la
validazione nel punto 4 deve verificarli e segnalare errore.

## Verifica build

Prima di ogni commit, eseguire:

```
mvn clean package
```

Il build deve completare **senza errori**. Warning accettabili, errori di
compilazione no. Il WAR risultante è in `target/openproteo.war`.

## Checklist pre-commit

1. **`mvn clean package`** — build OK (nessun errore di compilazione).
2. **Scan `[[` / `[(`** nei template Thymeleaf modificati — nessuna occorrenza
   fuori da `/*[[${...}]]*/` (causa `TemplateOutputException`).
3. **Niente `\n` / `\r` letterali** nelle stringhe JS (né in `static/js/*.js`
   né in `<script>` inline dei template). Usare `String.fromCharCode(10/13)`.
4. **CSS variables** — usare solo quelle definite in `app.css` (vedi sezione
   "Vincoli ambientali" punto 5). Mai inventare variabili non esistenti.
5. **PII** — nessun valore originale né mascherato nei log.
6. **Nuovi executor interni** — verificare tutte e 4 le location di
   registrazione (vedi sezione sopra).
7. **Java 8** — niente API ≥ 9.

## Mask pools: selezione per-file + gestione

* MaskGenerators e' parametrico: campi *File (firstNameFile/lastNameFile/cityFile/streetFile/
  companyAnimalsFile/companyColorsFile/companyActionsFile/companySuffixesFile), default _it.
  runMask li legge dai param dello step via poolFile() (hardened a bare filename).
* Designer step mask: 8 tendine (categoria -> file), filtrate per prefisso, combinabili
  it/intl. Niente piu' selezione locale via properties. Catalogo da GET /api/mask/pools/files.
* Pool files page (/pools, "pools.html", riusa filespanel.js su api/mask/pools/): list/view/
  replace/create/delete. File effettivo = override esterno (orchestrator.mask-pools-dir) se
  presente, altrimenti bundled in /maskdata/. Upload/replace SOLO se mask-pools-dir e' settata.
* Endpoint: GET /api/mask/pools/files (catalogo bundled∪esterni), GET .../download?path=,
  POST .../files (upload/replace), POST .../files/create, POST .../files/delete,
  GET .../alias-suggest. MaskPools.BUNDLED (15 nomi), hasExternal, readRaw.
* Dati pool: nomi/cognomi it+intl con lettere interne invertite (one-off, fake);
  company_animals/colors/actions/suffixes in *_it e *_international.

## This batch (split / docs / UI)

* Docs & commit messages are English-only from now on.
* SPLIT executor (exec="split"): splits an existing file into parts by rows/MB reusing the SQL
  export logic (header per part, stem_NNN.ext, CRLF, optional BOM, verbatim lines). Outputs
  csvFiles/csvParts/csvFile/rowCount, like SQL split -> a LOOP can iterate ${csvFiles} from
  either source. Fields: source (input), csvFile (output base), csvSplitRows/csvSplitMb,
  delimiter (list sep), params hasHeader/bom. Registered in InternalSteps dispatch + engine
  internal-executor list. Designer: executor option + branch + clientValidate(source).
* In-app docs: static/USAGE.md (single English source, == README.md) rendered by docs.html
  (/docs route) with a tiny no-CDN markdown renderer + TOC. Link in dashboard nav. The link
  regex uses char classes to avoid a literal [( (Thymeleaf-safe).
* Designer: all steps collapsed on open (load() seeds collapsedNodes for every node).
* Light theme: editable inputs/selects/textareas + .ms-box now get a light background
  (override added; dark theme unchanged).
* Step working dirs NN_<stepId>: NN = execution-order x 10 (00,10,20,...), NOT a version;
  gaps allow inserting steps; folders sort in run order.

## Loop index (update)
* ${indexVar} (default loopIndex) is now **1-based** (was 0-based).
* New ${indexStringVar} (default loopIndexString): the 1-based index LPAD '0' to indexPad
  chars (default 3), e.g. 001 / 00005. LoopDef.indexStringVar + indexPad; parser/writer/
  NodeDto/definition/designer wired; engine setLoopIndexVars() computes both. Internal
  __loop.<id>.i stays 0-based for items.get(i).

## Batch: safecopy / loop viz / light BPMN / toggle
* safecopy executor: wildcard copy dir->dir via <name>.on_fly_ temp + atomic rename. Params
  source/dest/pattern + tmpSuffix (default .on_fly_). InternalSteps.runSafeCopy; engine list;
  designer option+branch+validate. Skips files already ending in tmpSuffix.
* bpmn.js: LOOP<->ENDLOOP matched by nesting; dashed accent back-edge arched over the top
  (ENDLOOP->LOOP) with bpLoopArrow marker + "loop" label; LOOP/ENDLOOP nodes get bp-loopmarker.
* Light theme: BPMN node fills/strokes overridden for light (were hardcoded dark).
* Designer: LOOP shows "paired ENDLOOP: <id>", ENDLOOP shows "Closes LOOP: <id>" (loopPartner,
  nesting) so the pairing is explicit; warns if unmatched.
* .nc-toggle enlarged + boxed (border/bg, 30x26) to not be confused with the move buttons.
* Pending: run-time loop animation (turn off executed blocks per pass + live iteration counter).

## Loop run-time animation
* bpmn.js setLoopState(loopId, iter, count): "iteration N / total" label near the LOOP, a
  xN badge on each body block (body = nodes whose innermost enclosing loop is this one),
  back-edge pulse + body flash when the pass advances. loops registry built when drawing the
  back-edges; addCls/rmCls preserve concurrent status changes.
* run.html: LOOP_IDS captured from def; refresh() reads __loop.<id>.i/.n from run.vars and
  calls setLoopState (iter = i+1, count = n); clears when absent. Internal __ vars hidden in
  the variables dump.

## Workflow export / import (port/WorkflowPorter)
* New package `port/` with `WorkflowPorter` (@Component): packs workflows + every file they
  need into one ZIP and unpacks it on import. JDK + Jackson only. See
  `.claude/workflow-export-import.md`.
* Export: bulk-select on the dashboard → **⤓ Export selected** → `GET /api/workflows/export?feeds=a,b,c`
  streams a ZIP. Layout: `manifest.json`, `workflows/<feedId>.xml` (copied verbatim, byte-stable),
  `schemas/<feedId>/{dataschema,displayschema}.json`, `scripts/<name>` (step `script` attrs
  resolved vs scripts-dir, deduped), `datasources/datasources.json` (referenced defs, **passwords
  blanked**), `globals/global-vars.properties` (referenced ${name} file-globals, secret-looking
  keys redacted). Secrets never leave: masking-secret and application.properties globals omitted.
* Import page `/import` (import.html): upload ZIP → `POST /api/workflows/import/inspect` (multipart)
  extracts to a token-keyed staging dir (zip-slip guarded, TTL-swept) and returns a Variables-page-
  shaped view per workflow + `exists` (create/update) + bundled-asset summary. Editor reuses the
  Variables selection model (SOURCE/TARGET/FEED cascading multiselects; single=full, multi=common)
  and adds a **Feed identity** block editing **targetId** and the **production** flag (the test→prod
  switch) — dirty-tracked, applied only when changed.
* `POST /api/workflows/import/apply` {token, edits[]}: per selected feed the staged XML is parsed →
  toDto → edits applied (targetId/production via new fields; vars/tags/steps via the shared
  `applyEditsToDto`, refactored out of the variables-save path) → `xmlWriter.toXml` → validated with
  `xmlParser` before ANY write; nothing imported if any feed fails or has an active run. On update
  the existing file (by its real sourceFile name) is overwritten. Then reload, schemas → feedDir,
  scripts → scripts-dir (skip existing), datasources merged (create-if-missing, blank pwd),
  globals merged (add-if-missing) — all non-destructive and reported; then reschedule + cleanup.
* Dashboard: **⇪ Import** toolbar link + **⤓ Export selected** bulk-bar button (`bulkExport()`).

## Operations inline detail + CSV, and Files share/abs-path
* Operations (overview.html): the per-feed detail table is now **always visible**;
  the rollup tiles act as a filter, not a show/hide toggle (`drill` defaults to
  `{total,null}`, "Show all" resets it). An **inline feed filter** and the CSV/Copy
  buttons live in the static panel header (survive the 20s auto-refresh);
  `renderDrill` applies the text filter and records the visible list in
  `drillDisplayed`. **⤓ CSV (displayed)** and **⤓ CSV (selected)** download the
  filtered vs checkbox-selected rows; **⧉ Copy** copies the displayed rows as CSV.
  CSV is built client-side with `String.fromCharCode` line ends and `rows.push([…])`
  (no `[[`), UBS-safe. Multi-select bulk bar (Run/Lock/Unlock/Clear history/Delete)
  unchanged. See `.claude/2026-07-14-operations-inline-detail-csv.md`.
* Files panel (filespanel.js, used by workflow page / shared / pools): each row
  gains a **🔗 Share** button (copies an absolute direct-download URL via
  `new URL(dl, location.href)`), and **📋 Copy path** now copies the **absolute**
  path (`scopeDir` from the list response's `dir` + relative), so a step can
  reference a file across feeds. No backend change. See
  `.claude/2026-07-14-files-share-abspath.md`.

## Operations drill: per-feed EDIT button
* overview.html: the drill-down row actions now include a "✎ edit" link to
  `/designer/{feedId}` (the existing designer-edit route), next to "open
  workflow" / "open last run", so a failing feed can be opened straight in the
  designer. Pure UI. See `.claude/2026-07-14-operations-drill-edit-button.md`.

## diff executor — Batch 1 (CSV_POSITIONAL)
* New internal step `diff` (reconcile two CSVs), registered in the four locations
  (parser whitelist + internal set, WorkflowEngine.internalKind, InternalSteps
  dispatch). Batch 1 = **CSV_POSITIONAL** only: shared columns (by header name)
  compared row-by-row by position, streaming; surplus rows → missing_in_A/B.
  Writes `<name>_recon_report.md` + `<name>_recon_differences.csv` to the step
  dir; outputs `${id.diffResult}` (PERFECT_MATCH|DIFFERENCES), diffCount, etc.;
  optional `failOnDifferences`. Config is via step params (fileA/fileB/mode/
  delimiter/reportName/failOnDifferences) — designer branch bound with
  nodeParam/setNodeParam, no model/DTO/writer change. A small CSV line parser was
  added (no CSV reader existed). Master design + roadmap in
  `.claude/DIFF_EXECUTOR.md`; batch note in
  `.claude/2026-07-14-diff-executor-batch1-positional.md`. Next batches: report
  metrics polish, CSV_KEY (keys+matches+H2), ADD MATCH UI, cross-workflow file
  selection, TEXT mode.

## diff executor — Batch 2 (CSV_KEY)
* `runDiff` dispatches by `mode`; new **CSV_KEY** mode (`runDiffKey`) aligns rows
  by declared keys and compares attribute matches. H2 pipeline (reuses the csvsql
  CSVREAD loader): per-side GROUP BY keyExpr with COUNT(DISTINCT)+MAX per match,
  full-outer emulated via LEFT JOIN + UNION ALL anti-join. Categories
  value_mismatch / missing_in_A / missing_in_B / inconsistent_key (multi-occurrence
  agreement). Numeric matches compared via BigDecimal (0100==100). CONCAT_WS is
  used only for 2+ columns (H2 rejects single-arg CONCAT_WS); key sep = CHAR(1).
  Column names validated as identifiers; separators SQL-escaped; match indices are
  scanned from params (gap-tolerant). Config via params keysA/keysB + match.N.a/.b/
  .sep/.type/.label; designer mode dropdown + ADD MATCH repeater bound with
  nodeParam/setNodeParam; clientValidate requires keys + one complete match.
  Verified by running the real runDiffKey against H2 on sample CSVs (all four
  categories, duplicate collapse, numeric leading-zero, gap-tolerant indices).
  Note in `.claude/2026-07-14-diff-executor-batch2-csvkey.md`. Deferred: key
  SUBSTRING L/R, header-dropdown column pickers, cross-workflow selection, TEXT.

## diff executor — Batch 3 (TEXT mode)
* `runDiff` dispatches TEXT → `runDiffText`, the third comparison mode (all three
  now exist). Small files (both <= textMaxLines, default 2000) use a real LCS diff
  (only_in_A / only_in_B with line numbers); larger files fall back to a streaming
  positional line comparison (line_changed + surplus), noted in the report. Same
  report pair; output vars linesA/linesB/commonLines/onlyInA/onlyInB
  (+changedLines in fallback). Config via params (fileA/fileB/reportName/
  failOnDifferences/textMaxLines); designer adds TEXT to the mode dropdown + a
  Max-lines field (delimiter ignored in TEXT). No model/DTO/writer change.
  Verified by running the real runDiffText on sample files (LCS + fallback both
  correct). Note in `.claude/2026-07-14-diff-executor-batch3-text.md`. Remaining
  (UX/plumbing): key SUBSTRING L/R, header-dropdown pickers, cross-workflow file
  selection + run correlation.

## diff executor — Batch 4 (cross-workflow picker + run correlation)
* Because a feed's outputs live in stable dirs overwritten each run, a stable
  absolute path already = the latest run's output. New `GET /api/workflows/catalog`
  ({feedId,name}) feeds a designer picker under File A/B: a workflow select
  (lazy-loaded) + a file select (reuses `/api/workflows/{id}/files`); choosing a
  file writes the absolute path (dir + rel) into fileA/fileB. Free-text still
  works. Every mode's report now stamps `Sources produced: A @ <mtime>, B @
  <mtime>` (run correlation). Verified: updated runDiffKey run standalone shows the
  stamp; designer passes node --check. Async picker flow not live-testable in the
  chat sandbox (mirrors the xlsx-sheets pattern). Note in
  `.claude/2026-07-14-diff-executor-batch4-crossworkflow.md`. Remaining (optional):
  key SUBSTRING L/R; CSV_KEY column dropdowns from a header-preview endpoint.

## diff executor — Batch 5 (CSV_KEY key substring L/R)
* CSV_KEY key columns accept an optional per-column substring suffix in the
  existing keysA/keysB fields (no new widget, backward compatible): `NDG:L8` →
  `LEFT(NDG,8)` (first 8), `CODCLI:R4` → `RIGHT(CODCLI,4)` (last 4); plain names
  compare in full. Lets feeds whose keys differ by padding/prefix reconcile
  (e.g. A `AB1234` ↔ B `1234` via `code:R4`). runDiffKey parses each token via
  keyColSql into an SQL expr for the key CONCAT_WS; raw tokens are still echoed in
  the report. Designer shows a syntax hint under the key inputs. Verified on real
  H2 (LEFT/RIGHT + substring-keyed alignment). Note in
  `.claude/2026-07-14-diff-executor-batch5-key-substring.md`. Only remaining
  (optional QoL): CSV_KEY column dropdowns from a header-preview endpoint.

## diff executor — Batch 6 (CSV_KEY column preview / autocomplete)
* New `GET /api/workflows/{feedId}/diff/columns?path=&delimiter=` → {ok, columns}
  (mirrors xlsx/sheets path resolution; reads the header, strips BOM, quote-aware
  split). Designer CSV_KEY panel gains a "⟳ Load columns" button that fills two
  datalists (diffcolsA/diffcolsB); Key A/B and each match's A/B column inputs are
  wired via `list=` so column names autocomplete, with a reference line for the
  multi-column cases. Fields stay free-text. Endpoint header-split verified
  standalone; designer passes node --check. Note in
  `.claude/2026-07-14-diff-executor-batch6-column-preview.md`. **This completes the
  DIFF_EXECUTOR.md design — all three modes, matches, multi-occurrence, key
  substring L/R, cross-workflow picking + run correlation, and column autocomplete
  are implemented.**

## diff CSV_KEY performance fix (materialise ag/bg)
* runDiffKey was slow at scale because the reconciliation ran as one WITH-CTE
  query referencing ag/bg twice (LEFT JOIN + anti-join); H2 re-evaluates
  non-recursive CTEs, so the per-side GROUP BY ran ~4x and the join had no index
  (~44 s at 20k×20k). Fix: materialise ag/bg into LOCAL TEMPORARY tables with an
  index on k, then join (query drops the WITH). Same output; 20k×20k ~44 s → ~2 s.
  Verified on real H2 (correctness sample unchanged; timing measured). Note in
  `.claude/2026-07-15-diff-csvkey-perf-materialize.md`.
