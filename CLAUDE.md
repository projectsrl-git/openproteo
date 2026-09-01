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
  con **Claude Code**. Vedi «Deploy & commit»: la modalita' chat consegna a patch. Mai `robocopy /MIR`, mai
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

Due modalita', stesse regole di qualita'. **`COMMIT_MSG.txt` e' obbligatorio in
entrambe**: ogni prompt che produce modifiche deve creare/aggiornare
`COMMIT_MSG.txt` nella root (riga 1 <= 72 char, riga 2 vuota, corpo wrap a 78);
il commit si esegue con `git commit -F COMMIT_MSG.txt` e il file e' versionato,
quindi entra nel commit stesso.

### Modalita' A - Claude Code (working copy)

Modifica diretta dei file -> `mvn clean package` (verifica build) ->
`COMMIT_MSG.txt` -> commit -> push. Il WAR si deploya poi su Tomcat.

### Modalita' B - chat (consegna a patch)

Chi lavora in chat **non ha accesso alla working copy**: consegna **un solo
`.zip` per turno**, che `scripts/deploy_openproteo_patch.bat` applica, builda,
committa e pusha. Contenuto:

| file | note |
|---|---|
| `<nome>-<base>.patch` | `git diff` generato **su un clone fresco del main corrente**; `<base>` e' l'hash corto del commit su cui e' stato generato |
| `COMMIT_MSG.txt` | come sopra |
| `csv-viewer.html` | **sempre**, in chiaro: file grande, sta fuori dal patch per evitare conflitti CRLF |

**Il nome porta il commit base.** Lo zip si chiama
`openproteo-<argomento>-<base>.zip` e il patch dentro `<argomento>-<base>.patch`,
dove `<base>` e' l'output di `git rev-parse --short HEAD` sul clone da cui il
patch e' stato generato. Esempio: `openproteo-elarxml-batch8-51019e1.zip`.

Serve perche' lo script prende **lo zip piu' recente** in `D:\downloads` e non
sa da dove viene. Col commit nel nome il controllo e' una sola occhiata prima di
lanciare:

```
git rev-parse --short HEAD
```

Se non coincide col suffisso, il patch e' su una base diversa: non applicarlo e
chiedere la rigenerazione. E' successo due volte - un patch generato su
`0f712c8` mentre `main` era gia' a `4bce645` - e in entrambi i casi `git apply`
ha fatto il suo lavoro rifiutandolo, ma solo dopo aver perso il giro. Il nome
sposta la scoperta **prima** del lancio.

Vale anche al contrario: se il suffisso coincide ma lo zip e' vecchio, e' lo
stesso patch e riapplicarlo e' innocuo.

Chi genera scrive il suffisso **dopo** aver letto l'HEAD del clone, mai a
memoria.

Il controllo e' automatizzabile: `tools/deploy_patch_base_check.bat` e' il blocco
da incollare in `deploy_openproteo_patch.bat` (che vive fuori dal repo), dopo
l'estrazione del patch e prima di `git apply --check`. Tre esiti: base uguale a
HEAD prosegue; base diversa si ferma **distinguendo** se manchi un `git pull` o
se il patch vada rigenerato, perche' il rimedio e' opposto; nome senza suffisso
**avvisa e prosegue**, perche' tutti gli zip precedenti a questa convenzione ne
sono privi e rifiutarli trasformerebbe una rete di sicurezza in un ostacolo.
Dettagli e limiti in `.claude/2026-08-19-deploy-base-check.md`.

Regole imparate sul campo:

* **Generare sempre da un clone fresco di `main`**, applicando li' le modifiche.
  Copiare file da alberi di lavoro precedenti ha gia' prodotto un patch che
  cancellava una riga altrui.
* **`git apply --check` su un secondo clone pulito** prima di consegnare.
* **Un patch per intervento.** Due patch che appendono entrambi in coda a
  `CLAUDE.md` vanno in conflitto: dichiarare l'ordine o rigenerare il secondo
  sopra il primo.
* `main` **avanza a ogni turno**: rileggere l'HEAD prima di generare.
* Se rimandi una versione corretta, **dillo esplicitamente**: lo script prende
  lo zip piu' recente in `D:\downloads` e puo' ripescare quello vecchio. Il
  suffisso col commit base nel nome rende la cosa verificabile in un colpo
  d'occhio, ma non sostituisce il dirlo.
* **Rileggere l'HEAD e metterlo nel nome** e' un solo gesto: se il suffisso non
  compare, il patch non e' pronto per la consegna.

## Verifica prima della consegna (obbligatoria in modalita' B)

In chat **non si puo' compilare il progetto** (Maven Central non raggiungibile
dal sandbox): la build sulla macchina dell'utente e' l'unica prova finale. Va
quindi verificato tutto il verificabile, e **dichiarato** cio' che non lo e'.

* **JS**: estrarre gli `<script>` inline e passarli a `node --check`.
* **Zero `\n` / `\r` letterali nel sorgente JS** - il proxy UBS li riscrive in
  newline reali e rompe l'esecuzione. Usare `String.fromCharCode(10)`/`(13)`.
* **Zero `[[` / `[(`** fuori dai commenti Thymeleaf `/*[[${...}]]*/`.
* **Java**: bilanciamento graffe con un contatore **che ignora stringhe e
  commenti** (quello ingenuo da' falsi positivi noti su `InternalSteps`), e
  **verifica degli import**: un `LinkedHashMap` non importato e' gia' costato
  una build rotta, e il contatore non lo vede.
* **`pom.xml`**: validare con un parser XML (niente `--` nei commenti, un solo
  blocco `<properties>`).
* **Logica isolabile**: estrarre il metodo e **compilarlo ed eseguirlo** con
  `javac` (JDK: `apt-get install -y openjdk-17-jdk-headless`). L'SQL portabile
  si prova su SQLite.
* Nel `COMMIT_MSG.txt` **dichiarare cosa e' stato verificato e cosa no**.

## Principi non negoziabili (entrambe le modalita')

* **Default conservativi**: ogni nuovo comportamento nasce spento. Un deploy non
  deve cambiare l'output dei feed in produzione; l'attivazione e' per step o di
  massa da Variables / matrice.
* **Spec-first**: per una feature non banale, prima una `.md` in `.claude/` con
  le decisioni, poi implementazione a batch con conferma tra uno e l'altro.
* **Lingua**: conversazione in italiano; codice, commit e documentazione in
  inglese.
* **Dichiarare i limiti**: se qualcosa non e' stato provato, dirlo nella
  consegna invece di lasciarlo intendere.

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

## diff CSV_KEY — substring L/R on match columns
* The key-column substring syntax (COL:L<n> / COL:R<n>) now also works on match
  columns (A and B). runDiffKey runs each match column token through keyColSql
  (bare / LEFT / RIGHT) before the match CONCAT_WS; label still shows raw tokens.
  For numeric matches the substring is applied first, then the slice is compared
  numerically (e.g. RIGHT(iban,4) as a number). Designer matches hint updated;
  fields stay free-text. Verified on real H2 (firstName:L1 vs initial text;
  iban:R4 vs last4 numeric -> expected mismatches). Note in
  `.claude/2026-07-15-diff-csvkey-match-substring.md`.

## diff cross-workflow picker fix (File A/B population)
* Picking File A/B from the "…or from a workflow" dropdowns could leave the
  fileA/fileB param unset (validation still asked for the boxes). Two fragilities
  fixed in the designer: diffFileChosen returned early if the File input element
  wasn't found (param never set) — now it sets the param unconditionally; and the
  absolute path was rebuilt from a data-dir attribute that a re-render could drop —
  now the absolute path is baked into each file option's value in diffWfChosen.
  Verified with a mock DOM (param set even when the input is absent). Note in
  `.claude/2026-07-15-diff-crossworkflow-picker-fix.md`.

## diff: TEXT_SET mode, match aggregates, attributes-checked total
* **TEXT_SET** mode: line membership (order-independent) — lines only in A (A->B)
  and only in B (B->A), via hash sets (scales, no LCS). Complements ordered TEXT.
* **CSV_KEY match aggregates**: per-match `agg` = value|sum|count|count_distinct.
  sum/count/count_distinct aggregate over the key group and compare A vs B
  numerically (sum via SUM(CAST(NULLIF(TRIM(expr),'') AS DECIMAL)); multi-occurrence
  inconsistent_key check bypassed for aggregates). Designer: Aggregate dropdown per
  match. (H2 2.1.214 has no TRY_CAST; sum needs numeric columns.)
* **Summary total attributes checked**: POSITIONAL & CSV_KEY reports show and
  expose `attributesChecked` = attributesCompared x (rowsA + rowsB), plus rows in
  A / rows in B. Verified on real H2/standalone. Note in
  `.claude/2026-07-15-diff-textset-aggregates-checked.md`.

## diff cancellable + query timeout (Stop works on diff)
* runDiff* was called without RunControl, so a running diff couldn't be stopped and
  (CSV_KEY) never registered its H2 Statement. Fix mirrors csvsql: run() passes
  control through runDiff to runDiffKey/runDiffText/runDiffTextSet; runDiffKey sets
  setQueryTimeout(qto) (qto via stepTimeoutSec, as csvsql) and registers
  control.statement = st (cleared in finally) so an operator Stop cancels the live
  query; positional checks control.aborted in the row loop; TEXT/TEXT_SET check
  aborted at start. A step timeout now also bounds the CSV_KEY query. Verified with
  a mock RunControl (normal run clears statement; aborted run returns -997). Note in
  `.claude/2026-07-15-diff-cancellable-query-timeout.md`.

## Operations grid: search-all + source/target descriptions + tags (req 1.1-1.3)
* Feed filter now matches every displayed field incl. dates: feedId, name, source
  id+description, target id+description, tags, status, failed step, last-run/
  last-success timestamps, and output-data labels/values. SOURCE/TARGET columns
  show the description under the id (data already returned). /api/overview/feeds
  now also returns `tags`; the FEED column shows "tags: …" and tags are searchable.
  CSV export includes descriptions + tags. Note in
  `.claude/2026-07-15-operations-grid-search-desc-tags.md`. (Batch A; remaining
  from the request: 1.4 all-runs output data; 1.5 workflow-level output data; 2.1/
  2.2 history-delete PROD+keep-last; 4 PROD in mass-edit; 5.x SKIP/ON HOLD.)

## Operations: all-runs output data (req 1.4)
* The OUTPUT DATA column now shows every run's output data (most recent first),
  not just the last run. /api/overview/feeds captures the run list once, builds the
  outputData var set (name+label) once, and emits `runsOutputData`
  [{runId,runTs,status,outputData}] (runs with no values skipped; test runs
  ignored); `outputData` (last run) kept for compat. overview.html odCell renders
  per-run lines "<runTs> [status] label=value; …" (falls back to last-run
  outputData); free search covers all runs; CSV adds allRunsOutputData. Verified in
  Node (render + search). Note in
  `.claude/2026-07-15-operations-all-runs-output-data.md`. (Batch B; remaining:
  1.5 workflow-level output data; 2.1/2.2; 4; 5.x.)

## Workflow-level output data (req 1.5)
* Output data can now be defined at the WORKFLOW level, not only per step.
  WorkflowDef gains Map<String,String> outputData; XML `<outputData><var name desc
  /></outputData>` (mirrors <variables>) round-trips through parser/writer/DTO/
  toDto. Variables page gets a "workflow output data" textarea (var = description,
  single + multi-feed, differ-aware); var-catalog exposes it; VarSaveReq.FeedEdit +
  applyEditsToDto handle it (full replace, like step-level). /api/overview/feeds
  folds the workflow-level defs into the output-data var set (feeds last-run and
  all-runs display). DOM round-trip verified standalone; UIs pass node --check.
  Note in `.claude/2026-07-15-workflow-level-output-data.md`. (Batch C; remaining:
  2.1/2.2 history delete; 4 PROD mass-edit; 5.x SKIP/ON HOLD.)

## Clear history: PROD (checkbox-gated) + keep-last (req 2.1/2.2)
* clear-history endpoint gains confirmProduction (PROD no longer hard-refused;
  cleared only when confirmed) and keepLast (delete every run except the most
  recent via store.delete, vs the full clearOneFeed wipe; audited
  FEED_HISTORY_CLEARED_KEEP_LAST). opConfirm extended with opts.checks
  [{id,label,required,checked}] (OK disabled until required boxes ticked; onYes gets
  the state). Operations drillBulkClear switched from native confirm to opConfirm,
  counts PROD in the selection and requires a "production included" checkbox when
  any; dashboard bulkClearHistory offers the PROD gate; both offer "keep most recent
  run". Verified node --check + gating logic in Node. Note in
  `.claude/2026-07-15-clear-history-prod-keeplast.md`. (Batch D; remaining: 4 PROD
  mass-edit; 5.x SKIP/ON HOLD.)

## PROD flag mass-editable (req 4)
* The production flag is now editable via the Variables page, including the
  multi-feed (mass) editor. VarSaveReq.FeedEdit gains Boolean production (null =
  unchanged); saveVariables applies it before applyEditsToDto (same as the import
  path). variables.html gains a valSelect helper and a "production flag" tri-state
  dropdown (leave unchanged / set PRODUCTION / clear PRODUCTION) in the single-feed
  and multi-feed editors (multi shows all-PROD/all-non/mixed); collect() threads
  production (Boolean, dirty-only). Verified node --check + mapping in Node. Note in
  `.claude/2026-07-15-prod-flag-mass-editable.md`. (Batch E; remaining: 5.x
  SKIP/ON HOLD.)

## Step SKIP passthrough (req 5.2, Batch F1)
* A step can be marked SKIP (StepDef.skip; XML skip="true", mirrors overwrite
  through parser/writer/NodeDto/toDto; designer "Step mode" dropdown normal/skip).
  Engine executeStep, before running: if step.skip, copyDirContents from the
  PREVIOUS step's output dir (or 00_landing_in if first) into this step's dir,
  marks StepStatus.SKIPPED, audits STEP_SKIPPED, continues; copy failure FAILs the
  step. copyDirContents helper added (recursive, overwrite). SKIP semantics =
  copy prev-step-output -> this-step-output (linear-chain); flagged for user
  confirmation. Verified copyDirContents + skip round-trip standalone; designer
  node --check. Note in `.claude/2026-07-15-step-skip-passthrough-F1.md`. (F1 of
  5.x; F2 = ON HOLD engine/suspension/resume; F3 = Operations ON HOLD column +
  PLAY.)

## ON HOLD engine + resume (req 5.3 core, Batch F2)
* Step onHold flag (StepDef.onHold; XML onHold="true"; round-trips like skip;
  designer Step mode = normal/skip/onHold via setStepMode). RunStatus.ON_HOLD
  (non-terminal). WorkflowRun.onHoldStepId + releasedHold. Engine loop suspends at
  an onHold step (status ON_HOLD, frees runningFeeds, RUN_ON_HOLD audit, currentIndex
  tracks position); resumeHold() sets releasedHold and re-enqueues loop(currentIndex)
  — mirrors decide(). ON_HOLD added to active queue/rank; Stop aborts an on-hold run.
  Endpoint POST /api/runs/{feedId}/{runId}/resume. Verified onHold round-trip + designer
  node --check; engine mirrors WAITING_APPROVAL path but NOT compiled in sandbox. Note
  in `.claude/2026-07-15-onhold-engine-F2.md`. (F2; F3 = run-page ON HOLD status/counts/
  outputs/PLAY + Operations ON HOLD column/PLAY.)

## ON HOLD UI: run page + Operations (req 5.3.1/5.3.2/5.3.3, Batch F3)
* Completes ON HOLD on top of F2. app.css: `.chip.ON_HOLD` blue (like RUNNING).
  Run page: on-hold panel with the held step name + partial "N of TOT steps
  successful" count (partial outputs = the run.vars already shown), a
  "Continue (resume)" button (resumeRun -> POST /resume -> refresh); ON_HOLD is
  treated as active so Stop stays available. Operations: bucketFor maps ON_HOLD ->
  new `onhold` bucket + an "On hold" rollup tile; the grid shows the blue chip and
  a "Continue" button on ON_HOLD rows (resumeRun(feedId,runId)). Verified node
  --check on both templates. Note in `.claude/2026-07-15-onhold-ui-F3.md`.
  **This completes the whole request (1.x, 2.x, 4, 5.x).** Maven build still to be
  run on deploy for the Java-touching batches (C, D, E, F1, F2, and this F3 line).

## Operations: weather icons per source (fancy)
* The per-source rollup table gains a "Sky" column: a weather emoji per source via
  weatherFor(s) — shining sun (all success), storm / heavy thunderstorm (some /
  all failed), white clouds (all to-run), sun+cloud (success+to-run mix), and
  (my choice) sun-behind-small-cloud (running), fog (on hold), sun-behind-rain
  (aborted). Any-failed takes priority. Title tooltips + a legend under the table.
  Emojis as \uXXXX escapes. overview.html only; node --check + mapping verified.
  Note in `.claude/2026-07-15-operations-weather-icons.md`.

## Light-theme polish
* Light theme had poor contrast between field placeholders and real values because
  only .field inputs had a light-theme placeholder colour; other inputs used the
  browser default (~text colour, dark). Fixed in app.css (light-theme-scoped): a
  global lighter italic ::placeholder (#98a4b3, opacity 1) for all inputs/textareas;
  all inputs read light (white bg/--ink/--line); real values at font-weight 500;
  clearer focus ring; dim/secondary text nudged for contrast. Dark theme untouched.
  Note in `.claude/2026-07-15-light-theme-polish.md`.

## Environment header badge (DEV/SIT/UAT/PROD)
* Distinguish installations from the header. AppProperties.environment (config
  `orchestrator.environment=PROD`, empty hides it). GET /api/env -> {environment,
  host}. theme.js (loaded on every page) fetches it and injects an .env-badge into
  .topbar (single point, no per-page edits); PROD adds .is-prod. CSS: PROD is
  white-on-red + red topbar accent line; DEV neutral, SIT blue, UAT amber; host on
  hover (covers the "obvious server name" alternative); light-theme variants.
  theme.js node --check clean (0 \n/\r); css balanced. Note in
  `.claude/2026-07-15-environment-header-badge.md`.

## Operations: source & target multi-select filters
* The feed grid gains Source and Target checkbox-dropdown filters (one/several/all).
  Empty selection = all; combine with AND plus the existing text search and rollup
  drill; "Show all" resets them; panels close on outside click. srcSel/tgtSel
  objects (empty=all), msfDistinct from FEEDS, renderDrill excludes on non-empty
  mismatch. .msf CSS added. overview.html only. Verified node --check + filter
  logic in Node. Note in `.claude/2026-07-15-operations-source-target-filters.md`.

## Standalone CSV viewer (csv-viewer.html at repo root)
* Self-contained HTML at repo root (file://, no server/CDN), for testers. Two tabs.
  Mirrors the internal viewer (CsvService parsing + displayschema titles). Data tab:
  virtualised grid + filter + sort + per-column AUTO-WIDTH (content-based, 54..420px) +
  drag-resize + horizontal scroll with sticky header. Aggregate tab: group-by +
  DISTINCT COUNT + SUM + pivot + substring COL=L4/R2 + TOTAL + CSV export. Dates
  formatted only on visible cells (filter/sort/agg on RAW). Note in
  `.claude/2026-07-15-standalone-csv-viewer.md`.

## Fixes (2026-07-20): clear-history + viewer widths
* Operations "Clear history" failed with `opConfirm is not defined`: overview.html did
  not load modal.js. Fixed by including modal.js + theme.js in overview.html.
* designer.html (EDIT) clearHistory() was the old dialog: rewritten to use a required
  PROD checkbox (when Production) + "keep the most recent run", passing
  confirmProduction/keepLast (matches dashboard/Operations).
* csv-viewer.html columns were fixed 160px; now auto-width from content (max of
  DisplayName/ColumnName/300-row sample, 54..420px) + horizontal scroll + sticky header
  + drag-resize. Note in `.claude/2026-07-20-clear-history-and-viewer-fixes.md`.

## Operations overview: resolve ${var} in feed tags
* The Operations grid showed feed tags raw (e.g. ${recordBusinessDate},
  ${originTableName}). overviewFeeds() now resolves each tag via VarResolver.resolve
  against a light var map (globalVars + feedId/sourceId/targetId + runDate/runTs +
  def.variables) — recordBusinessDate/originTableName are workflow vars so they
  resolve; unknown -> empty; literals untouched. Light map avoids feedVars()/provision
  to keep the poll cheap. Variables page keeps tags RAW (editable). Verified logic
  standalone; Java not compiled in sandbox. Note in
  `.claude/2026-07-20-overview-tags-resolve.md`.

## Operations overview: SOURCE filter on the summary/rollup
* A "Sources: all" checkbox dropdown in the "By source" panel header filters the whole
  summary by source: the status tiles and the by-source table recount only the
  selected sources; it also drives the drill grid (dashFeeds() feeds rollup +
  renderDrill), so drilling shows the same subset (in-drill src/tgt filters still AND
  on top). dashSrc {} (empty=all), dashFeeds() filters FEEDS; renderRollup uses
  rollup(dashFeeds()); renderDrill bases on dashFeeds().filter. overview.html only;
  node --check clean; logic verified in Node. Note in
  `.claude/2026-07-20-overview-summary-source-filter.md`.

## Operations overview: "On hold" column in the by-source table
* The by-source rollup table was missing an On hold column, so on-hold feeds counted in
  Total but showed in no column and the row didn't add up. Added an "On hold" column
  (after Aborted), cell('onhold', s.source, s.onhold, ...), clickable to drill. Now the
  per-source columns reconcile with Total. overview.html only; node --check clean. Note
  in `.claude/2026-07-20-overview-onhold-column.md`.

## Wrap ';'-lists (with sum) in OUTPUT DATA and run Variables
* Long ';'-separated values (csvRowCounts per-file counts, csvFiles path lists) were
  one-line and unreadable. Overview OUTPUT DATA (odItemsHtml): each variable on its own
  line; a ';'-list of 2+ tokens -> block with "Σ <total>" (numeric only, thousands) +
  "(N values)" and each value on its own line (scrollable). Run page Variables
  (run.html varValHtml): same treatment per ${var}; scalars unchanged; path lists wrap
  without a sum. overview.html + run.html; node --check clean; verified in Node. Note in
  `.claude/2026-07-21-outputdata-run-vars-multiline.md`.

## VarResolver: list indexing ${list[N]}
* csvRowCounts/csvFiles are single ';'-separated strings; added ${name[N]} to VarResolver
  = N-th element (1-based) of the ';'-list ${name}, trimmed. With indirection,
  ${csvRowCounts[${loopIndex}]} gives the current file's row count in a loop (loopIndex is
  1-based -> [1] is first). Out-of-range/missing -> empty. Splits on ';' (default list
  delimiter). Compiled + tested standalone. Note in
  `.claude/2026-07-21-varresolver-list-indexing.md`.

## Fixes: deleted-run rows, light zebra, standalone aggregate range
* #2 Run history (runs.html) is audit-based; deleting a run left its audit-grouped node.
  runs.html now flags runs with a RUN_DELETED event and hides them (audit kept); counter
  uses visible runs. #3 csv-viewer light theme: explicit zebra (white / #f2f5f9 / amber
  hover). #4 (standalone) csv-viewer rowsForAgg now also applies per-column RANGES when
  "respect Data filter" is on. Backend items (internal viewer aggregate range, duplicate
  workflow assets) follow separately. Note in `.claude/2026-07-21-fixes-runs-viewer.md`.

## #1 Duplicate copies uploaded files; #4 internal aggregate respects ranges
* Duplicate as new now copies uploaded files: AssetStore.copyFeedAssets(from,to), endpoint
  POST /api/workflows/{feedId}/copy-assets-from/{sourceFeedId}, and designer.html remembers
  window.DUP_FROM and calls it after save (save() + saveXmlDirect()). * Internal viewer
  aggregate now honours per-column range filters: CsvService.aggregate gains a List<Filter>
  overload (applied via matchesFilters, cache-key aware); csvAgg + wrappers take fc/ff/ft
  (built like csvPage); viewer.js buildAgg passes ranges via a getRanges callback. Java not
  compiled here; mirrors existing code. Note in
  `.claude/2026-07-21-duplicate-assets-and-internal-agg-range.md`.

## Internal CSV viewer: light-theme zebra fix
* .vgrid-row.odd used var(--bg-soft, #131a27) but --bg-soft is undefined, so odd rows were
  dark navy in light theme; totals row was hardcoded #1d2738. Added light overrides in
  app.css (odd #f2f5f9, hover #fbeecb, totals #eaf0fb). app.css only; rebuild WAR + Ctrl+F5
  (static CSS is browser-cached). Note in `.claude/2026-07-22-internal-viewer-light-zebra.md`.

## Variables page: mass-edit "Step mode" (skip / on hold)
* The mass-edit Variables page now has a "step mode" dropdown per step (same options as the
  designer: normal / skip (passthrough) / on hold (pause)) in both the single-feed and
  common-steps views, so skip/pause can be set massively. variables.html collect adds
  scope 'stepMode'; ApiController StepEdit.stepMode is applied in applyEditsToDto exactly
  like setStepMode (skip=true/onHold=null etc.); var-catalog returns skip/onHold per step
  for the "currently" hint. Note in `.claude/2026-07-22-variables-step-mode-massedit.md`.

## Tag badges (overview) + runtime currentDate variable
* Overview feed rows render resolved tags as pills (.tagb/.tag-badges in app.css, light
  override incl.) instead of a "tags: ..." line. * New runtime vars currentDate/currentTs:
  evaluated NOW and refreshed before every step in WorkflowEngine.loop() (yyyyMMdd /
  yyyyMMdd_HHmmss), so resumed ON-HOLD steps and their successors can use ${currentDate}
  (today) instead of the fixed ${runDate} (run start). feedVars + overview tag map also
  expose them (design-time = now). Note in `.claude/2026-07-22-tag-badges-and-currentDate.md`.

## Variables matrix (/matrix): spreadsheet editor
* New page matrix.html: feeds on rows, the union of all workflow variables on columns (plus
  optional tags + PROD meta columns), sticky header/first column. Type in cells; only dirty
  cells are saved (EDITS map keyed feedId+kind+name, survives filtering); an empty cell means
  the var is not defined and typing CREATES it. "+ Add column" for a new variable, ▾ header
  button fills a column down to all visible feeds, Excel TSV paste fills right/down, arrow
  keys/Enter navigate, filters for feeds/columns + "only columns that differ". Reuses POST
  /api/variables/save; var-catalog now also returns `production`. Route added in
  PageController; links from dashboard + variables. Note in
  `.claude/2026-07-23-variables-matrix-sheet.md`.

## Viewer line numbers + go-to; dequote blank lines; Waiting-approval column
* Viewer: the CSV grid has a sticky "#" row-number gutter (its width is folded into
  rowWidthPx() so header, rows and the column resizer stay aligned) plus a "go to row" box
  that scrolls and outlines the row; TXT/log keeps its gutter and gains "go to line";
  formatted JSON/XML now render through a line-numbered virtual list (renderLines) instead of
  a bare <pre>, so they get numbers, the line count and go-to too. Shared helpers
  gotoBox()/renderLines() in viewer.js; styles (.vnum, .vwr-goto, .hl) in app.css.
* dequote executor: blank lines (trim().isEmpty()) are skipped — this removes the stray line
  breaks left at the end of a CSV and any empty line in the middle — and counted in the new
  output variable `blankLinesRemoved` (also in the summary log). A line of just delimiters
  (";;;") is a valid row of empty fields and is kept.
* Operations overview: WAITING_APPROVAL is its own bucket ("waiting") instead of being folded
  into running, so runs paused on a MANUAL GATE are visible — new tile "Waiting approval" and
  by-source column "Waiting appr.", both right after Running and both clickable to drill; the
  Mix bar gained a waiting segment. The "Other" tile/column is hidden and reappears only if a
  feed really lands in an unmapped status (SKIPPED/REJECTED), so the per-source columns keep
  reconciling with Total.
* Note in `.claude/2026-07-23-viewer-linenumbers-dequote-waiting.md`. That note's "CLAUDE.md"
  paragraph (explaining why this was deferred) is now resolved by this entry.

## Weather icon for WAITING_APPROVAL
* weatherFor() did not know the new "waiting" bucket, so gate-paused feeds fell through to the
  generic "mixed" icon. Added 🌥️ (U+1F325, sun behind large cloud) with "N waiting for
  approval", after the on-hold check and before running: failed > all success > all not-run >
  done+to-run > on hold (🌫️) > waiting approval (🌥️) > running (🌤️) > aborted (🌦️) > mixed.
  Note in `.claude/2026-07-23-weather-waiting-approval.md`.

## dequote: records split by line breaks inside quoted fields
* The earlier "blank lines" change was the wrong cure: the real defect is a record split across
  physical lines because a quoted field contains a line break (the executor read one physical line
  at a time, so the record was never reassembled). Records are now read as LOGICAL rows via
  readCsvRecord(), which appends physical lines while quotes are unbalanced (oddQuotes; RFC ""
  escapes keep parity so they never join), with a 5000-line guard and a clean stop on an
  unterminated quote at EOF. New param `embeddedNewlines` = space (default) | strip | keep, new
  outVar `embeddedNewlinesRemoved`, plus field-level CR/LF sanitising. Note in
  `.claude/2026-07-23-dequote-embedded-newlines.md`.

## CR/LF inside values: extraction-side fix, all defaults conservative
* The right place is the JDBC extraction (column count known from ResultSetMetaData), not the
  dequote heuristic: SqlSupport.nlReplacement(mode) + an nlMode overload of
  exportResultSet/exportCsv normalise each value while writing, counted in
  ExportResult.newlinesSanitized; the `sql` and `csvsql` steps read `newlinesInValues` and publish
  ${newlinesSanitized}. * ALL new behaviour is OFF BY DEFAULT so production feeds are unchanged:
  extraction `newlinesInValues` defaults to keep; dequote `embeddedNewlines` now defaults to keep
  (it defaulted to space in the previous patch — a silent behaviour change, corrected) and the
  blank-line dropping is behind `dropBlankLines`, default no (it was unconditional — corrected).
  Note in `.claude/2026-07-24-extraction-newlines-conservative-defaults.md`.

## Build version badge (pom version + git commit)
* Nexus-safe, no new plugin: build-info.properties (build.version=@project.version@,
  build.commit=${git.commit}, build.time=${maven.build.timestamp}) filtered by Maven via an explicit
  <resources> block that filters ONLY that file. The git.commit default and timestamp format live in the
  EXISTING <properties> block (do not add a second one). ApiController.buildInfo() reads it once (cached,
  blanks raw placeholders) at GET /api/version and folded into /api/env; theme.js mountVersion() shows a
  .ver-badge ("v1.0.0 · <short>") in the topbar. The deploy .bat verifies, commits, then rebuilds with
  -Dgit.commit=<short HEAD> so the hash lands in the WAR. Note in
  `.claude/2026-07-25-build-version-badge.md`.

## Version fix + progressive + splash + claim
* CAUSE of the missing commit: spring-boot-starter-parent sets resource.delimiter=@ and
  useDefaultDelimiters=false, so ONLY `@token@` is substituted in filtered resources — `${git.commit}`
  was never replaced. build-info.properties now uses @...@ throughout. * Automatic progressive:
  build.number = `git rev-list --count HEAD`, passed by the deploy .bat as -Dbuild.number together with
  -Dgit.commit on the post-commit rebuild; the badge shows v<version>.<build> · <commit>, details in the
  tooltip, with a buildTime fallback to the resource mtime and blanks for unfiltered builds.
  * theme.js mountSplash(): full-screen splash once per tab session (sessionStorage `op-splash`),
  self-fading after 2s, closes on click/key, 6s hard safety net, honours prefers-reduced-motion; styles
  .op-splash* in app.css. * Claim in the dashboard topbar is now "Pipeline Workflow Orchestrator".
  * Remember: XML comments cannot contain `--`, and always validate pom.xml with an XML parser.
  Note in `.claude/2026-07-28-version-progressive-splash-claim.md`.

## scripts/build_openproteo.sh (bash build for TEST / PROD)
* Bash equivalent of the deploy .bat for the test and production boxes, stamping the version the same
  way. `-b` (build only) is the normal mode there: HEAD is already known so a single stamped build runs
  with `-Dgit.commit=<short HEAD> -Dbuild.number=<git rev-list --count HEAD>`. Without `-b` it does a
  verification build, stages with `git add -A -- . ':(exclude)*.patch'`, shows the diffstat and asks for
  confirmation (`-y` skips), commits `-F COMMIT_MSG.txt` (or `-m`), pushes (`-n` skips), then REBUILDS to
  stamp the new hash — the commit hash only exists after the commit. Guards: set -euo pipefail, git/mvn
  on PATH, must be inside the repo, non-empty COMMIT_MSG.txt, WAR checked after each build, commit and
  push skipped when nothing is staged, and it WARNS if a placeholder in build-info.properties was not
  substituted (that check would have caught the ${git.commit} delimiter bug at once). Note in
  `.claude/2026-07-28-build-script-bash.md`.

## Operations drill grid: sortable date columns
* `drillSort` defaults to `{key:'lastRunTs', dir:'desc'}`, so the feed list opens with the most recently
  executed feeds on top. "Last run" and "Last success" are clickable (`th.sortable` in app.css) with an
  accent triangle on the active column; clicking it toggles asc/desc, clicking the other starts from desc.
  `drillCmp` compares timestamps as text (chronological for yyyy-MM-dd HH:mm:ss), tie-breaks on feedId and
  always puts missing dates last in BOTH directions. The sort runs just before `drillDisplayed` is set, so
  CSV (displayed) and Copy follow the visible order. Note in `.claude/2026-07-30-drill-sort-by-date.md`.

## build_openproteo.sh: project resolution fix
* The script used to `cd` to the git top level, which broke when the repository root sits ABOVE the
  project (repo /projects/devpodtest, pom in /projects/devpodtest/openproteo). The project is now the
  directory containing pom.xml, searched as: parent of the script's directory (script lives in
  <project>/scripts/, symlinks resolved) -> $PWD -> git top level; it prints a note when the repo root
  differs. Staging happens after cd into the project, so in a parent repo only the project subtree is
  committed. It also re-execs under bash when started with `sh` (dash has no `set -o pipefail`), and git
  is now needed only for commit/push and version stamping: `-b` works in a plain source copy. Note in
  `.claude/2026-08-02-build-script-project-resolution.md`.

## Static cache-busting with the build id
* Recurring problem solved: a deploy appeared to change nothing because the browser served cached
  app.css/viewer.js/theme.js. `config/BuildInfo` is now the single source of build identity (map() for
  the API, id() for URLs; ApiController.buildInfo() delegates to it), `web/BuildIdAdvice`
  (@ControllerAdvice on PageController) publishes `${buildId}`, and every CSS/JS include became
  `@{/path(v=${buildId})}` (54 includes, 16 templates). docs.html passes it to the USAGE.md fetch via a
  `<meta name="op-build">` tag rather than inlining Thymeleaf in JS. id() = <buildNumber>-<shortCommit>,
  falling back to the build time and then to a per-JVM token. Note in
  `.claude/2026-08-02-static-cache-busting.md`.

## Log report — Batch 1 (indexer + search API)
* First slice of `.claude/LOG_REPORT.md`: read-only aggregation over the existing audit JSONL, no new
  engine instrumentation. `logreport/LogSeverity` (single OK|FAIL|WAIT|RUN|SKIP|INFO classifier),
  `logreport/LogIndexer` (in-memory H2 as a disposable SQL engine like csvsql — runtime-only driver, no
  new dependency; refresh is a byte-offset tail that leaves a trailing partial line for the next pass
  and reloads a feed whose file shrank), `logreport/LogQueryService` (bound parameters, whitelisted
  sort, size capped at 500, source/target/name/production joined live from registry.all() and never
  indexed), `web/LogReportController` (`/api/logs/search`, `/api/logs/status`, `/api/logs/reindex`).
  No UI, no timeseries/metrics/facets/export, no rolling window, no RBAC gating yet — TEST only until
  Phase 1 auth. H2 could not be exercised in the chat sandbox (Maven Central unreachable): the JDBC
  path runs first on the real build. Note in `.claude/2026-08-03-log-report-batch1.md`.

## Log report — Batch 2a (window, runs + output-data, facets)
* Decisions: rolling window 90 days (`openproteo.logreport.window-days`, 0 disables), refresh 10s.
  * SPEC CHANGE: `_runs/{runId}.json` is now indexed, because the audit line only carries
  exitCode/attempts/reason and the OUTPUT DATA shown in Operations and the run history lives in
  run.vars matched against the workflow's outputData declarations. New tables `run_entry` and
  `run_output`; run files are rewritten as a run progresses, so they are stamped by size:mtime and their
  rows replaced rather than tailed. * `GET /api/logs/runs` (filters + paging, each row carrying its
  outputData; free text also searches output values/labels/names via EXISTS) and `GET /api/logs/facets`.
  Outputs for a page are fetched in one query. Note in `.claude/2026-08-03-log-report-batch2a.md`.

## Log report — Batch 2b (/logs page)
* One page, one grid, with a source switch (Events -> /api/logs/search, Runs & output data ->
  /api/logs/runs) instead of two tabs. Filter bar fed by /api/logs/facets (source/target/feed/event/
  severity/status/user/free text/from-to); event+severity show for Events, run status for Runs, the rest
  is shared so switching keeps the selection. Runs rows render the declared output data as
  `description = value`. Feed and run link into the existing pages. Server-side paging 100/page, footer
  reports index contents (events/runs/outputs/window). Route in PageController, dashboard link, includes
  use the cache-busting `(v=${buildId})`. Note in `.claude/2026-08-03-log-report-batch2b.md`.

## Log report — Batch 3 (activity timeline)
* `GET /api/logs/timeseries` + SVG chart above the grid, sharing filters and source. Bucketing is done
  in Java over a bounded (ts, severity) projection (400k cap, `truncated` flag) instead of a
  dialect-specific SQL date function, so it does not depend on H2 syntax and the sizing is testable:
  `auto` keeps the chart under ~160 bars (1h->1m, 1d->15m, 30d->6h, 90d->1d, 1y->1w), minute|hour|day|
  week force a width. Run statuses map onto the event severity palette. Stacked bars with FAIL at the
  bottom, native <title> tooltips, no library/CDN; click a bar to zoom, drag to brush-select — both
  write from/to and re-run the search so chart, grid and filters are one state. Note in
  `.claude/2026-08-03-log-report-batch3.md`.

## Log report — Batch 4 (metric cards)
* `GET /api/logs/metrics` over the same filtered set as grid and chart; card row above the chart.
  Events: events/runs/feeds touched, failures, successes, avg step duration, top-5 feeds and top-5 steps
  by failure. Runs: runs, feeds, succeeded, failed (+failed steps), avg run duration, top-5 feeds.
  Counting is SQL; durations are paired in Java (STEP_STARTED -> STEP_COMPLETED|STEP_FAILED per
  feed/run/node, 300k cap) to avoid dialect-specific date arithmetic — unpaired starts contribute
  nothing, no pairs shows a dash. Cards are clickable filters (severity, feed, step+FAIL) routed through
  the same search(), so cards/chart/grid stay one state; the step filter is cleared by Reset. Note in
  `.claude/2026-08-03-log-report-batch4.md`.

## Log report — Batches 5 and 6 (export, reindex, cold scan)
* `GET /api/logs/export`: streamed CSV of the filtered set (paged through the index, UTF-8 BOM, 1M-row
  stop; runs flatten outputData as `name=value | ...`). RBAC is not on main, so it is UNGATED — kill
  switch `openproteo.logreport.export-enabled=false`, and it must be role-scoped as soon as Phase 1 auth
  merges. CSV + Reindex buttons added to /logs. * Cold scan: a query whose `from` predates the 90-day
  window is served by `LogIndexer.coldScan`, reading the audit files for the feeds in the filter only
  (20k cap, nothing written to the index), with the same predicates re-applied in Java; a cold query
  naming no feed/source/target is refused rather than scanning all 144 files. Responses carry
  `coldScan`/`truncated` and the page says which path answered. Note in
  `.claude/2026-08-03-log-report-batch5-6.md`.

## Version 1.1.0
* pom version bumped 1.0.0 -> 1.1.0 for the cross-feed log report (new feature, backward compatible;
  nothing existing changed behaviour). The badge becomes `v1.1.0.<commits> · <shortCommit>` and, since
  the cache-buster token is `<build>-<commit>`, the static assets invalidate themselves on this deploy
  as on any other. Patch releases stay 1.1.x.

## Operations in every topbar; standalone artifact
* Operations moved from a buried dashboard button to a filled button in the topbar of all 16 pages
  (overview excluded), using a dedicated `.btn.ops` class — NOT `.btn.primary`, which already styles
  other buttons app-wide. * The build now emits two artifacts: `openproteo.war` (plain, unchanged, for
  the external Tomcat) and `openproteo-standalone.war` (Boot repackage with
  `<classifier>standalone</classifier>` + `<attach>false</attach>`, embedded Tomcat, runs with
  `java -jar`). Works because spring-boot-starter-tomcat is `provided` → WEB-INF/lib-provided. Maven
  cannot emit `.jar` with war packaging; renaming the file works, a real jar would need a second module.
  Note in `.claude/2026-08-03-operations-nav-and-standalone-jar.md`.

## Standalone launch docs, build-script check, JDBC naming
* `.claude/2026-08-03-operations-nav-and-standalone-jar.md` now documents the standalone run in detail:
  JVM-only, the full command line, and the defaults table (port 8080; workflows/feeds/scripts/shared/
  datasources/logs all `./` RELATIVE TO THE WORKING DIRECTORY, so launching from an empty dir gives an
  empty instance and launching inside production operates on live data), plus the
  `application.properties`-next-to-the-artifact alternative and the context-path/IIS differences.
  * `build_openproteo.sh` now verifies BOTH artifacts and prints both paths; fixed a latent bug where the
  summary still used `$REPO`, removed by the project-resolution fix, which under `set -u` would have
  aborted the script on its last line. * The `sql` executor is labelled "sql (JDBC query)" instead of
  "DB2/AS400": it is plain JDBC and works with both datasource types (`as400` and `custom`). Only labels
  and docs changed — the executor id stays `sql`, so existing workflows are untouched.

## Log report in the topbar; output-data indexing fix
* "Log report" sits next to Operations in every topbar (16 pages); the duplicate dashboard button was
  removed. * FIX: the index reported `0 outputs` because `upsertRun` read only the workflow-level
  `<outputData>` block, while the designer writes declarations as `outputData.<var>` PARAMETERS ON THE
  STEPS — which is what Operations reads. The indexer now collects from the steps and lets the
  workflow-level block override. Requires a Reindex (or restart) after deploy. * Still open: the DETAILS
  column shows the audit payload; the step LOG lives in per-step files under the run dir and is not in
  the audit trail, so showing it needs either a link, a lazy tail fetch, or indexing a third source.
  Note in `.claude/2026-08-04-logs-nav-and-output-indexing-fix.md`.

## Step log: lazy peek in the log report
* Chosen over indexing the step logs: they are 1-3 orders of magnitude bigger than the audit and would
  have required a persistent index / search engine. Each event row with a run and a step shows a
  "≡ step log" chip in DETAILS; clicking expands the last 300 lines fetched on demand for that row only,
  reusing `/api/runs/{feedId}/{runId}/log/{stepId}?tail=300` (the run page's own endpoint), clicking
  again collapses. Nothing pre-fetched, cached or indexed; missing/unreadable logs degrade to a message
  in the panel. Note in `.claude/2026-08-04-step-log-lazy-peek.md`.

## Keyed variable lookup `${COL@key}`
* Decision taken for the sqlreport spec: keyed syntax over aligned lists. `VarResolver.keyed()` resolves
  `${COL@key}` by finding `key` in the companion `${COL.keys}` list and returning the value at the same
  position in `${COL}` (both ';'-separated, aligned). Positional `${COL[N]}` is unaffected. Absent key,
  or lists of different lengths, resolve to "" — never a neighbouring row, which in a reconciliation
  would be worse than nothing. Spec: `.claude/SQLREPORT_VERSIONING_VARIABLES.md`.

## Versioning decisions: `.v1` ids and `${parentId}`
* `tf0003819.v1` accepted as a feed id, so versioning is a naming convention and the registry is
  untouched. New built-in `${parentId}` = feed id with a trailing `.v<digits>` stripped, equal to
  `${feedId}` on unversioned feeds so it is never null. Published wherever `${feedId}` is. Note that a
  version inherits nothing (separate runs/audit/output — that is the point), and that `${feedId}` still
  names directories and files: anything that must keep the ORIGINAL naming across versions must use
  `${parentId}` explicitly. The ORIGINAL KEEPS ITS SCHEDULE: a version is created scheduling-inert and
  the operator retargets deliberately; the save dialog states which workflow is still scheduled. Spec: `.claude/SQLREPORT_VERSIONING_VARIABLES.md`.

## Operations bulk bar: CONTINUE / STOP, DELETE relabelled, delete-run guard
* The drill-grid bulk bar gains **▶ Continue** (resume the selected ON HOLD runs) and **⏹ Stop**
  (RUNNING/QUEUED/WAITING_APPROVAL/ON_HOLD -> ABORTED). No engine change was needed: an ON HOLD run
  only releases `runningFeeds`, it STAYS in `activeRuns`, and `stop()`/`resumeHold()` resolve by runId
  with a `store.load` fallback — so a hold is reachable both live and after a Tomcat restart. Putting
  ON_HOLD back into `runningFeeds` was rejected: it would re-occupy the engine slot for a deliberately
  parked run. * The bar targets `liveRunId || lastRunId`: new `engine.activeRunsByFeed()` (non-terminal
  runs still in `activeRuns`, incl. slot-released ones, newest per feed, test runs out) feeds new
  `liveRunId`/`liveStatus` fields in the LIVE part of `/api/overview/feeds`, because `lastRunId` is
  cached 10s and would miss a run started inside that window. Eligibility is filtered client-side; the
  endpoints stay authoritative, so a stale grid can never abort the wrong run. PROD confirmation is
  UI-level (`opConfirm` + required checkbox), deliberately asymmetric with `clear-history`, which
  enforces server-side — the per-run `/stop` and `/resume` contracts are shared with the run page and
  were left alone. * **DELETE relabelled `🗑 Delete feed`**: it posts to `/api/workflows/{id}/delete`
  and removes the WORKFLOW DEFINITION, so labelling it "Delete run" would have been a safety
  regression. A separate **🗑 Delete last run** was added for the run-level action. * FIX: `deleteRun`
  guarded on `activeRunId`, which is null for WAITING_APPROVAL and ON_HOLD runs (both release the
  slot) — a suspended-but-live run could be deleted from disk while still in `activeRuns`. It now
  refuses any non-terminal run (`WorkflowEngine.isTerminalStatus`). * FIX: `loadFeeds(force)` declared
  `force` and never used it; `/api/overview/feeds?refresh=1` now drops the cache so a bulk action is
  visible immediately. Note in `.claude/2026-08-04-DESIGN-operations-bulk-stop-continue.md`.

## sqlreport executor — Batch 1
* New internal executor `sqlreport`: a LIST of read-only queries against one JDBC datasource, no CSV,
  one Markdown evidence report. Registered in the four locations (parser whitelist + error message,
  parser `internal` set, `WorkflowEngine.internalKind()`, `InternalSteps` dispatch) plus the two
  designer ones (dropdown `<option>`, `clientValidate`). * **Storage is `<reportQuery>`, NOT
  `<query>`**: `<query>` is already the single-statement child of the `sql` executor
  (`textOrAttr(el,"query")`), so reusing it would have made the first report query silently become
  `StepDef.query`. One element per query, so a `;` inside SQL cannot corrupt the definition. New
  `model/def/ReportQuery`, `StepDef.reportQueries`, parser/writer round-trip, `ReportQueryDto` +
  `toDto`. * **Read-only, two levels, both before execution.** New `engine/SqlReportSupport` — no
  Spring, no JDBC, no project types, so the provable logic compiles and runs standalone —
  `readOnlyError()` strips comments and blanks quoted literals/identifiers, then requires: not empty,
  no second statement after `;`, leading keyword SELECT or WITH, and no DML/DDL keyword anywhere. The
  last check is STRICTER than the spec on purpose: `WITH x AS (...) DELETE FROM t` begins with WITH
  and is a single statement. Then `Connection.setReadOnly(true)` (effect read back with
  `isReadOnly()` and reported) + TIMEOUT SEC as query timeout. If any query is rejected NOTHING runs.
  The report, the docs and the designer all state this is a net against mistakes, not a guarantee —
  the guarantee is the database account's rights. * Report: run id, timestamp + zone, datasource /
  host / user / database, workflow + step; per query its own timestamp, duration, row count, the
  statement AS EXECUTED in a fenced block, and the table with `|` escaped and CR/LF folded to a
  space. **The row count is always real**: the ResultSet is consumed to the end and only `maxRows`
  (default 200) rows are kept, so `setMaxRows` is deliberately not used. **Never a password**:
  `redactJdbcUrl` masks `password/passwd/pwd` and the `user:pw@host` form. * Outputs `${reportFile}`,
  `${queriesExecuted}`, `${rowsTotal}`; `failOnEmpty` (default off) fails AFTER writing the report.
  The running statement is registered in `control.statement` so Stop can cancel it. * `keyColumn` and
  `collect` are parsed, stored and shown but UNUSED — variable collection is batch 2. Note in
  `.claude/2026-08-04-sqlreport-batch1.md`, spec in `.claude/SQLREPORT_VERSIONING_VARIABLES.md`.

## sqlreport — Batch 2 (collecting query results into run variables)
* Completes section 1.5 of the spec; `keyColumn`/`collect`, parsed and kept unused by batch 1, are now
  live. Per query: `collect=COL` on one row publishes the scalar `${COL}`, on several rows the
  `;`-separated list (so `${COL[N]}` works); with `keyColumn=K` every collected column also gets its
  companion `${COL.keys}` — the pair `VarResolver.keyed()` needs — plus `${K}` as the key list itself;
  a single-column result with no `collect` is published implicitly under its own label; no rows
  publishes the empty string. Names are the RESULT's labels, matched case-insensitively against what
  the author declared. * **The separator is `;`, always — `step.delimiter` is deliberately NOT
  honoured**: `keyed()` and `${list[N]}` both split on a hardcoded `;`, so another separator would
  produce lists that look right and fail every lookup. A collected value containing `;` or a line
  break is replaced by a space and COUNTED (report + log), because one such value would shift every
  later position and misalign the keys. * **Explicit intent fails hard, implicit degrades quietly**:
  an unknown collect/key column, a key column without collect, a label that is not a plain identifier,
  or a name in `SqlReportSupport.RESERVED_VARS` (`feedId`, `runId`, `stepDir`…) fails the step; the
  implicit single-column case just skips and logs. * **Overflow publishes NOTHING and fails**:
  collection is capped by `collectMaxRows` (default 5000, 0 = none), NOT by `maxRows` which caps only
  the table — a partial list is the worst outcome, since every missing key resolves to "" and looks
  like a legitimate absence. Duplicate keys warn but do not fail (`${COL@key}` takes the first match).
  * **Collected values are never echoed to the step log** (`##VAR name (collected, value not
  logged)`); the limit is stated: the ENGINE still audits every out var WITH its value and OUTPUT DATA
  shows it — shared behaviour, unchanged — so the docs say collect counts, sums, statuses and keys,
  not personal data. * Designer: labels lose "(batch 2)", new Collect max rows field, `clientValidate`
  refuses a key column with no collect and non-identifier names; `USAGE.md` gains a "sqlreport notes:
  collecting variables" paragraph. Note in `.claude/2026-08-04-sqlreport-batch2-collect.md`.

## Variables page: steps that only SOME selected feeds have (section 3, batch 1)
* The multi-feed editor showed only the INTERSECTION of step ids, which silently hid every
  difference: a step you cannot see is a difference you cannot act on. A new **Steps missing from
  some feeds** section lists the ids present in some but not all of the selection, each with an
  "in N of M feeds" badge, its executor, the feeds it is missing from (truncated at 6, full list in
  the title on hover) and a read-only preview of the common fields/params where it does exist.
  * **Executor conflict answered here**: when the same id uses a different executor across feeds it is
  flagged `conflict: sql / csvsql` rather than hidden — the fields do not mean the same thing in each
  feed, so it can never be mass-edited or mass-added. That resolves the first open question of the
  spec in the read-only batch, where it costs nothing. * **Deliberately read-only.** The preview
  inputs are `disabled` and carry NO `data-scope`, and `saveVariables()` collects only
  `.vval[data-dirty="1"]` with a scope — so a partial step can never reach the save payload. Editing
  one here would apply to a subset without saying so. * [Add to all selected] (insertion position,
  server-side creation, PROD confirmation) is the NEXT batch and is not in this one. * Verified by
  running the real `renderCommon` against a fake DOM: badges, conflict detection, missing-feed lists,
  and the invariant that every disabled input has no scope while every enabled one has one. Note in
  `.claude/2026-08-04-variables-partial-steps.md`.

## Variables page: [+ Add to N feeds] for a partial step (section 3, batch 2)
* Completes section 3. A non-conflicting partial step can now be CREATED in the feeds that lack it,
  copied from one that has it. The request never carries a step definition: the client sends
  `{stepId, fromFeedId, afterStepId}` and the server copies the node out of a fresh `toDto(src)`, so
  what is inserted is always an already-validated step, not client-supplied content. New
  `FeedEdit.addSteps` + `applyStepAdditions`, applied BEFORE `applyEditsToDto` so the field edits land
  on the freshly inserted step and the existing all-or-nothing staging covers both in one pass. * The
  two things that cannot be guessed are refused rather than guessed: the **insertion position** is a
  dropdown of the steps common to the whole selection, defaulted only when every source feed puts the
  step after the SAME common step (guessing is how a step ends up running after the send), and a field
  the source feeds **disagree** on is blanked and marked required instead of being copied from
  whichever feed came first. * Guards: a feed with a LIVE run is refused — via `activeRunsByFeed()`,
  NOT `activeRunId()`, since a gate/ON_HOLD run has released the slot and would look inactive — plus
  duplicate id, unknown source feed, step absent from the source, and an anchor absent from the
  TARGET (the server does not trust the UI's dropdown). `validateChecks` is deep-copied because
  `toDto` hands it over by reference from the registry's StepDef: without that, editing the inserted
  step would mutate the SOURCE workflow in memory. * An executor conflict keeps the step read-only
  with no button. PROD feeds need the Clear-History-style required checkbox. * The add form uses class
  `avval`, never `vval`, so `collect()` (`.vval[data-dirty="1"]`) cannot see it and it can never ride
  along with an ordinary Save — asserted directly in the tests. Note in
  `.claude/2026-08-04-variables-add-step.md`.

## `${parentId}` built-in (section 2, batch 1)
* `VarResolver.parentId(feedId)` strips ONE trailing `.v<digits>`, so `tf0003819.v2` -> `tf0003819`.
  Textual and TOTAL: on an unversioned feed it returns the feed id unchanged, so `${parentId}` is
  never empty and can be used unconditionally without the author knowing whether that feed is a
  version — that is why it is a variable and not a UI label. `x.v` (no digits) is not a version, `.v1`
  would strip to nothing so it is returned unchanged, null/blank give "". Companion `isVersioned()`.
  * Published in all SIX places `${feedId}` is: `buildRun` run vars, both design-time preview maps in
  ApiController (`feedVars` + csvsql preview), the Operations tag-resolution map, and in designer.html
  both the Builtin-vars cheat sheet and the path autocomplete. * VERIFIED rather than assumed: the
  spec's "`tf0003819.v1` is an acceptable feed id, the registry is untouched" holds — every feed-id
  validation (`WorkflowXmlParser:65`, `ApiController:1806`/`:2321`, `clientValidate` x2) uses
  `[A-Za-z0-9._-]+`, which already admits a dot. No change needed. * DECISION: **the versioning
  trigger will apply to the DESIGNER SAVE ONLY, not to the Variables-page bulk add.** A version is
  scheduling-inert and the original keeps its schedule, so triggering it on a 40-feed bulk add would
  produce 40 unscheduled workflows and change nothing about tonight's run — the operator's intended
  change simply would not happen. The bulk-add confirm text must say it modifies in place and creates
  no versions (fold into the next batch). * Remaining for section 2: structural-change detection on
  designer save, the version/overwrite dialog, `.v<n>` allocation, uploads copied like
  Duplicate-as-new, scheduling-inert creation, and Operations showing a version next to its parent.
  Note in `.claude/2026-08-04-parentid-builtin.md`.

## Workflow versioning on structural changes (section 2, batch 2)
* Saving from the designer a change that ADDS or REMOVES steps on a workflow that has ALREADY RUN is
  intercepted server-side: nothing is written, 409 with `{versionSuggested, nextVersionId, addedSteps,
  removedSteps, scheduled}`, and the designer offers a version. Rationale: the run history is audited
  against a definition; if the steps change under it, a past run no longer matches the workflow it
  says it executed. * **Only STEP nodes count** (decided): gates and LOOP/ENDLOOP added, removed or
  moved are ordinary edits, as is reordering steps without adding or removing any. Renaming a step id
  counts as one removed + one added, which is what it is for the history. All THREE conditions are
  required — structural step delta, feed exists, feed has >=1 run — and `structuralOverwrite=true`
  bypasses. * `nextVersionId` allocates in the family keyed on `parentId`, so editing `tf0003819.v2`
  gives `tf0003819.v3`, NOT `.v2.v1` — versions are a flat list, not a chain. max+1, gaps never
  reused; candidates checked against BOTH the registry and the workflows dir, because an unparseable
  file is absent from the registry while its id is still taken. * `hasRuns` fails SAFE: if the history
  cannot be read it returns true — suggesting a version needlessly is recoverable, silently
  overwriting a definition a run was audited against is not. * Client `saveAsVersion()` reuses the
  Duplicate-as-new machinery: `DUP_FROM` copies uploaded files, `EDIT_FEED_ID=null` makes it a create,
  and **`wf.cron=''` makes the version scheduling-inert** (`WorkflowScheduler` skips `cron==null`) so
  the ORIGINAL stays the scheduled one — dialog and banner both say which. Overwrite is a CHECKBOX in
  the version dialog, not a second button: it is the exception and its label states the consequence.
  * PROD, locked and tags are inherited unchanged; only the cron is cleared. Audit gains `parentId` on
  a versioned save. * The Variables-page bulk add still does NOT version (decided last batch); its
  confirm text now says so. Note in `.claude/2026-08-04-workflow-versioning.md`.

## Operations: version badge and family filter (section 2, batch 3 — closes section 2)
* `/api/overview/feeds` gains `parentId` and `version` per feed, derived server-side from
  `VarResolver.parentId` — the same function the engine uses for `${parentId}` — so Operations can
  never disagree with the runtime about what a feed descends from. `version` is the digits after
  `.v`, empty on an unversioned feed. * The Feed cell gains a badge: on a version `v1 of tf0003819`,
  on an original `2 versions`. Clicking either puts the family id in the existing search box and
  re-renders, so parent + versions appear together — the substring filter already matched them, the
  badge just makes it one click. * An ORPHANED version (parent deleted) shows only `v1` and explains
  on hover, instead of naming a workflow that no longer exists. * Nothing is grouped, merged or
  re-sorted: they stay separate feeds with separate runs and separate audit trails, which is the point
  of versioning. The badge is a signpost, not a relationship. Section 2 is now complete.
  Note in `.claude/2026-08-04-operations-version-badge.md`.

## audit_report.md: per-run evidence report
* New `engine/RunAuditReport` (no Spring, no IO — everything passed in, so it compiles and runs
  standalone) renders `{feedDir}/_logs/runs/{runId}/audit_report.md`. On request only, never on run
  completion: button beside "open" in Run history for one run, and "Audit report (last run)" in the
  Operations bulk bar for the last run of each selected feed. Only SUCCESS runs; `_test_` excluded.
  * **KEY FINDING that makes the backfill possible**: `run.vars` looks flat but `WorkflowEngine:947-951`
  writes BOTH `var` and `stepId.var` on every step output, so per-step attribution is already in the
  persisted run JSON — no audit-trail replay, no parsing of logs. A run with no `stepId.` key predates
  that and gets an explicit note instead of an empty section that would read as "produced nothing".
  * **Two different sets, kept apart**: the *Output data* section is the DECLARED list
  (`outputData.<var>` params + workflow-level), i.e. exactly the Operations column; the per-step
  tables are what each step actually published. `declaredOutputVars(def)` was EXTRACTED from the feeds
  endpoint and is now shared, so report and grid cannot disagree. A declared var the run never produced
  shows empty rather than being dropped. * Steps AND gates are merged chronologically — a report that
  drops the approval step is not evidence. Each step paragraph carries startTs/endTs + computed
  duration, status, exit code, attempts, checks, its variables, and its standard output. * Step logs:
  head 100 + tail 400 with the omission marked, NOT a pure tail like the run page — the query, the
  datasource and the parameters are printed at the START of a step log and a tail would cut exactly
  what the report is for. A ``` inside a log is neutralised so it cannot close the fence. * Caveat
  stated in the report and the docs: the declared list is the definition AS IT IS NOW, so for an old
  run a variable added since shows empty and one removed since is absent — which is what versions
  exist to avoid. Spec `.claude/2026-08-05-DESIGN-run-audit-report.md`.

## FIX: the version dialog never opened behind IIS, and the preview hid <reportQuery>
* **HTTP 409 is unusable in this deployment.** IIS replaces the body of an error response with its own
  page (`httpErrors existingResponse="Replace"`), so the client received "The page was not displayed
  because there was a conflict" as text/html instead of the JSON payload. The structural-change
  version suggestion returned 409 and therefore ALWAYS failed as soon as a feed with runs gained a
  step — reported from the field on tf0003868. All three save conflicts (`versionSuggested` and the
  two `exists` branches, in `/api/workflows/save` and `/api/workflows/save-xml`) now return **200 with
  `ok:false` in the body**, and the designer branches on `res.j.versionSuggested` / `res.j.exists`
  instead of `res.st === 409`. **Rule for this codebase: never carry an actionable outcome in a 4xx
  status — put it in the body.** * **The GENERATED XML panel is a CLIENT-side preview** (`buildXml()`
  in designer.html), not the server writer: `<reportQuery>` was added to `WorkflowXmlWriter` but not
  to `buildXml`, so a typed sqlreport query looked as if it were being lost. It was not — the save
  path was correct — but the panel is what an author checks. `buildXml` now emits `<reportQuery>` with
  title/keyColumn/collect/maxRows and the SQL as text, skipping a row that is entirely empty.
  * Lesson recorded: any new child element must be added in FIVE places, not four — parser, writer,
  DTO, toDto, **and `buildXml` in the designer** — or the preview silently disagrees with the file.

## setvar: chained integer arithmetic
* `evalArithmetic` evaluated exactly TWO operands: `74023 + 5164 - 12` matched `" + "`, then tried
  `Long.parseLong("5164 - 12")`, failed and returned the expression AS TEXT. Reported from the field.
  Now a left-to-right chain over whitespace-separated terms: `A + B - C + D`, no precedence, `+` and
  `-` only. * **The mandatory space around each operator was KEPT deliberately** — it is the guard,
  not a formatting rule. Accepting `A-B` would make a literal `2026-08-05` evaluate to 2013; with the
  space requirement any value without spaces (path, date, `;`-list) passes through untouched. * Only
  one shape changes besides genuine chains: `2026 - 08 - 05` WITH spaces now evaluates to 2013 where
  the old code returned it as text. Declared rather than hidden; nobody writes a date that way, and
  the space is the documented signal for arithmetic. * Overflow returns the input unchanged
  (`Math.addExact`/`subtractExact`) rather than a wrapped number. * Assignments within ONE step still
  cannot refer to each other — a step's params are all resolved before it runs — so a value computed
  from another assignment needs a second setvar step. Documented in USAGE.md and the designer label.

## Batch: future-date check, audit report naming + .docx, sqlreport .docx
* **validate `businessDateNotFuture`**: fails when a business date is later than today; optional
  `businessDateMax` param overrides the bound (empty = today), the bound itself passes, only strictly
  later fails. Mirrors `businessDateNotBefore` exactly (same bizIdx, same SKIP semantics when
  dateFormat is missing). **Preselected in the designer for NEW validate steps only.** Deliberately
  NOT added to the engine's implicit default list (the one used when a step has no `checks` attribute)
  — that would make existing feeds start failing on deploy, which the contract forbids. One line to
  change if that is ever wanted. * **New `engine/DocxWriter`: hand-rolled OOXML over `java.util.zip`,
  NOT POI/XWPF**, even though poi-ooxml is already a dependency. Reasons: `poi-ooxml-lite` carries
  only part of the wordprocessingml schemas and a missing one is a NoClassDefFoundError in
  PRODUCTION, not at build time; and — decisively — pure JDK means the class compiles and RUNS
  standalone here, so the .docx it produces was actually unzipped and validated before delivery. A
  POI version could not have been verified at all. It renders OUR Markdown (ATX headings, pipe tables
  with `\|` escapes, fenced code, rules, inline `**bold**` and backticks); anything unrecognised is
  written as literal text rather than dropped — an evidence document must not silently lose a line.
  * **Audit report is now `{feedId}_{runDate}_audit_report.{md|docx}`** — the old fixed name was
  identical in every feed and indistinguishable once attached to an email. runDate from the run,
  falling back to the date in startTs, then the runId. Both endpoints take `format=md|docx`; Run
  history and the Operations bulk bar each have a pair of buttons. * **sqlreport `reportFormat`** =
  `md` (default) | `docx` | `both`, publishing `${reportDocxFile}`. Both formats render the SAME
  Markdown, so they cannot disagree. * NOT in this batch (item 3.3): embedding the sqlreport content
  inside the audit report at the matching step.

## Step output: the datasource a step ran against
* `sql`, `sqlreport` and `ifscopy` now publish their datasource as a step output, so it resolves as
  `${<stepId>.dataSource}` through the existing engine namespacing — no new mechanism, no new
  variable machinery. * Published as the FIRST statement of the executor, BEFORE the datasource is
  looked up, so the value survives a step that then fails: "which database did this hit" matters most
  when something went wrong, and it is what lets the audit report be evidence rather than a summary.
  outVars are merged by the engine after every attempt regardless of exit code, so this works for a
  failed step too. * **Two names for one value**, `dataSource` and `datasource`: the XML attribute is
  all-lowercase while every other step output is camelCase, so whichever an author types would
  otherwise resolve to "" in silence — the exact failure mode that cost a session on RERCON vs RECON.
  Both are in sqlreport's STEP_OUTPUT_VARS so their value is echoed in the log rather than being
  treated as collected (possibly PII) data. Say the word and the alias goes. * Verified end to end by
  compiling the REAL VarResolver against a transcription of publishDataSource and of the engine's
  namespacing loop: the qualified form, the alias, the unqualified form, per-step stability across two
  SQL steps, last-writer-wins unqualified, nothing published when there is no datasource, an unknown
  step id resolving to "" and not to a neighbour, and trimming.

## FIX: ${dataSource} was empty inside the step that owns it
* Reported from the field on tf0003819.RECON: `${dataSource}` showed correctly in OUTPUT DATA but was
  EMPTY inside the sqlreport query of the same step. Cause: a step OUTPUT only reaches `run.vars`
  after the step finishes, so the step that publishes it cannot see it. Same class as "assignments in
  one setvar step cannot refer to each other". * Fix in `WorkflowEngine.executeStep`: the datasource
  is now SEEDED alongside `stepId`/`stepName`/`stepDir`, i.e. BEFORE params and queries are resolved.
  The two mechanisms are complementary and both kept — seeding serves the step itself (unqualified
  `${dataSource}`), the output serves later steps (`${<stepId>.dataSource}`) plus the audit trail and
  the audit report's per-step table. * A step WITHOUT a datasource **leaves the value alone** rather
  than clearing it. Clearing was written first and rejected: it would empty the OUTPUT DATA column of
  any workflow whose last step is not a SQL one, and it matches how every other step output behaves
  (last writer wins, value persists). The precise per-step form stays `${<stepId>.dataSource}`.

## XML tree view + standalone xml_viewer.html
* `viewer.js` gains a **Tree** tab for `.xml` next to the existing Code view: collapsible rows,
  Expand/Collapse all, and one search box matching tag names, attribute names, attribute VALUES and
  text content at once, with `<mark>` highlighting, non-matching branches hidden and the ancestors of
  every match auto-opened. Element headers and text rows have different backgrounds; every colour is
  an existing app.css variable so BOTH themes work — verified by checking each var resolves in the
  `:root[data-theme="light"]` block too, none invented. The tree is built lazily on first click of the
  tab, so opening an XML file costs nothing extra. A non-well-formed file says so and stays readable
  under Code. * **`xml_viewer.html` in the repo root**, mirroring `csv-viewer.html`: browse or
  drag-drop, same tree, same search, own theme toggle, zero external references (asserted). * The
  renderer is written ONCE and injected into both, so they cannot drift. * **jsdom was installed in
  the sandbox to run the real renderer against a real DOM** — 30 assertions on a realistic workflow
  XML. That caught a genuine bug: labels are written by `paint()`, which was only called from
  `search()`, so the tree rendered BLANK until the user typed. Fixed with an initial `paint('')`.
  Without a real DOM this would have shipped.

## XML viewer: TABLE view replaces the indented tree
* The first attempt rendered an indented tree with attributes inline as `name=value`. That was the
  wrong shape: the ask was a TABLE. Replaced, not extended. * Now: a single element is a titled block
  with an **Attribute | Value** table (name in one cell, value in the cell beside it); a run of
  CONSECUTIVE siblings sharing a tag becomes **one table with a header** whose columns are the union
  of their attribute names, one numbered row per element, with a `value` column when any of them has
  text; an attribute a row lacks is rendered with a hatched `xt-null` cell so "absent" is visibly
  different from "empty string". A row whose element has children gets a toggle opening that
  element's block in a full-width sub-row. Runs are CONSECUTIVE only, so document order is never
  rearranged. * **Architecture change**: the document is walked once into a light model (references +
  a lowercase haystack) and the DOM is rendered FROM the model on demand. His real file is 1.16 MB /
  4522 elements; building every cell up front does not scale. Search runs on the model, so it finds
  matches inside branches that have never been drawn — which a lazy DOM-only approach could not.
  * Verified with jsdom against a realistic workflow XML: 30 assertions covering run grouping, the
  attribute-name union in first-seen order, key/value as separate cells, header row contents, one row
  per element with the missing attribute blank, sub-row expansion, collapse/expand, and search on tag
  / attribute name / attribute value / text with path retention. An explicit assertion checks that no
  `name=value` inline text and no `xt-attr` class survives anywhere — the old shape cannot come back.

## XML viewer: stronger header backgrounds
* Reported: the element title bands and table headers were barely distinguishable from the body,
  especially in the light theme where both were near-white. * Element headers now sit on a solid
  tinted band with a **4px accent bar on the left**, so the start of every element is unmistakable
  even when several are stacked; table headers are darker still, uppercase, and carry a **2px accent
  underline**. * The colours were CHOSEN by computing WCAG relative-luminance ratios rather than by
  eye: element header vs body is 1.89 (dark) / 1.54 (light) where it used to be ~1.05, table header
  vs body 2.37 / 1.80, and the header text sits at 6.9-9.7 against its own background so legibility
  was not traded away for separation. Literal hex is used for these four surfaces because no existing
  theme variable was strong enough; everything else still comes from variables.

## Datasources: generic JDBC, presets, no bundled driver (item 2)
* **2.3 first, because it constrains everything else**: the stored `type` values `as400` and `custom`
  are UNCHANGED. Nothing migrates, every existing connection keeps working; only what the operator
  reads changed. * **2.1** wording: the page now talks about reusable JDBC connections; the type
  dropdown reads "JDBC — any database" and "IBM i — native (also carries the IFS file-copy
  credentials)"; the designer's executor list says "sql (JDBC query → CSV)" instead of "DB2/AS400
  query"; id/name placeholders are no longer AS400-flavoured. `ifscopy` still names IBM i because that
  is a fact about it, not branding — it genuinely needs that connection's credentials. * **2.2** a
  **Preset** dropdown fills the URL template and the driver class for Oracle, SQL Server, PostgreSQL,
  MySQL, MariaDB, DB2 LUW/zOS, IBM i, H2 and SQLite. A URL already typed is NEVER overwritten by
  choosing a preset — only the driver class is corrected. * **No driver is bundled and none will be**:
  `custom` already did `Class.forName` + `DriverManager`, so a JAR in `CATALINA_HOME/lib` is all that
  is needed — zero new Maven dependencies, which is the only acceptable answer given the Nexus
  constraint and the pom's existing warning about POI's transitive tree. `SqlSupport.loadDriver`
  rewrites ClassNotFoundException into an instruction naming the class and the directory, because
  "driver not found" is the most likely first-time failure and a bare class name tells an operator
  nothing. * Verified with jsdom against the real page: presets present, every one carrying a
  `jdbc:` template, a dotted driver class and a JAR name; the dropdown filled; a chosen preset filling
  both fields and the hint; and the "already typed URL survives" invariant.

## Audit report embeds sqlreport output (3.3); Markdown preview in the viewer (4)
* **3.3**: a step that produced its own Markdown report publishes `${<stepId>.reportFile}`, so
  `writeAuditReport` reads it back and `RunAuditReport.render` embeds it UNDER that step. New
  `demoteHeadings(md, 3)` pushes the embedded document's ATX headings down so it nests instead of
  competing with the audit report's own `#`/`##`/`###`; lines inside fenced blocks are left alone (a
  `#` there is a SQL comment) and levels cap at 6. Skipped with a named reason when the file is gone,
  over 2 MB, or `.docx` only. The old 5-arg `render` still compiles — the new map is an overload.
  * **4**: `.md` files get a **Preview** tab (default) beside **Source** in the viewer. New
  `renderMarkdown` handles headings, paragraphs, fenced code with language, pipe tables including the
  `\|` escape, bullet/numbered lists, blockquotes, rules and inline bold/italic/code/links.
  **It escapes first and adds markup second** — not theoretical: these reports carry raw database
  values, and `javascript:` links are dropped while the label is kept. * Both verified by execution,
  not inspection: `demoteHeadings` and the embedding with javac (20 assertions, including that the
  report lands under RERCON and not EXTRACT and that a `#` inside a fence survives); the Markdown
  renderer with jsdom (35 assertions, including that a `<script>` cannot survive and that the output
  parses to the expected DOM). * A literal `/\r/` regex slipped into the renderer and was caught by
  the escape scan — replaced with `String.fromCharCode(13)`. The proxy rule applies to regexes too.

## Audit report buttons download the file directly
* The per-run buttons previously wrote the file and showed its path in an alert, leaving the operator
  to go and find it. `POST /api/runs/{feedId}/{runId}/audit-report` now takes `download=1` and returns
  the bytes with `Content-Disposition` and an `X-Report-File` header; the client turns them into a
  blob and clicks a hidden anchor. * **One request, not two**: the report is still written under
  `_logs/runs/{runId}/` (it is evidence and belongs there) AND returned in the same response. A second
  GET would have to re-derive the file name and could race with a re-run. * `download=1` is opt-in, so
  the JSON contract is unchanged for the Operations bulk endpoint, which still needs `{ok:true}` to
  count successes. * Errors stay **JSON with 200** — a 4xx body would be replaced by the IIS error
  page — so the client branches on the content type, not on the status. * `URL.revokeObjectURL` runs
  on a timer rather than immediately: revoking straight away can cancel the download in some browsers.
  * Verified with jsdom by driving the real handler with stubbed responses: the happy path issues one
  POST carrying `download=1` and the format, triggers a download named from the server header, revokes
  the object URL and shows no alert; a JSON error is reported and saves nothing; a non-2xx proxy
  response is reported and saves nothing (rather than writing an HTML error page to disk as a .docx);
  and a missing header falls back to a sane name.

## Variables: Remove from N feeds, and designer dropdowns everywhere
* **Remove from N feed(s)** mirrors the add: new `StepRemove` + `applyStepRemovals`, applied in the
  same all-or-nothing save. Guards, stricter than the addition because this is the direction that
  breaks things: live run refused (via `activeRunsByFeed`), step must exist, **the last remaining step
  is refused**, and — the one that matters — **a step still referenced by another node is refused,
  naming the referrer**. `referencedBy` scans every node's string fields and param values for
  `${STEP.` and `${dir.STEP}`; a step referencing itself does not block its own removal and a
  similarly-named step is not a false positive. UI asks TWO required checkboxes (understand it
  deletes; PROD count) rather than one. * **Dropdown parity**: `PARAM_OPTIONS` in variables.html was
  EXTRACTED from designer.html by script, not retyped, so the two pages cannot offer different values
  for the same field. 20 params including the reported `deleteOnSuccessType`. Indexed params are
  matched by wildcarding **only the index** (`match.0.type` -> `match.*.type`); falling back to the
  last segment was tried and REJECTED — a name as generic as `type` would turn unrelated params into
  the wrong dropdown. * A stored value not in the list is kept as an extra option marked "current, not
  a standard value" instead of being snapped to the first entry, which on this page would change every
  selected feed silently. * `[[` in the generated table tripped the Thymeleaf scan — written as `[ [`.

## FIX: the date MASK is not a java.time pattern (validate step died)
* Reported from the field: after enabling `businessDateNotFuture`, the validate step failed with
  "Field DayOfYear cannot be printed as the value 222 exceeds the maximum print width of 2".
  * **Root cause, wider than the new check.** `dateFormat` in this product is a MASK — `YYYY/MM/DD`,
  read by `fmtToRegex` as year/month/day-of-month, the same shape the `sql` executor takes. But both
  business-date bound checks fed it straight to `DateTimeFormatter.ofPattern`, where `DD` is
  day-of-YEAR and `YYYY` is the week-based year. So the pre-existing `businessDateNotBefore` has
  ALWAYS been unable to parse a date with this mask (proved: parsing `2026/08/10` with `YYYY/MM/DD`
  throws on every JDK) — it just failed silently inside the row-level catch. The new check formatted
  first, for a cosmetic label, and that is what surfaced it. * **New `fmtToJavaPattern`** translates
  the mask (`YYYY`->`uuuu`, `DD`->`dd`, …) and quotes any other letter as a literal so a `T` in
  `YYYY-MM-DDTHH:mm` cannot be read as a pattern letter. `maskFormatter` never throws; `maskFormat`
  falls back to ISO — **a cosmetic label must never be able to fail a step**. * **The mask is a
  per-feed VALUE, usually a variable** (`${recordBusinessDateFormat}`): it is already resolved when the
  executor sees it (`resolvedParams`), but nothing guarantees every feed writes the same dialect. The
  translator therefore rewrites ONLY `Y`->`u` and `D`->`d` — the two letters where the dialects
  genuinely disagree — and passes through every other valid java.time letter, so a feed already
  writing `yyyy-MM-dd` keeps working exactly as before (it must: that form used to reach `ofPattern`
  intact). Letters are consumed in RUNS so `MMM` is not split into `MM` + a stray literal, quoted
  sections are preserved, and anything else is quoted. A mask still containing `${` is reported as an
  UNDEFINED VARIABLE rather than as a bad mask — a different problem needing a different fix. * **The sandbox could not
  reproduce the crash**: `ofPattern("DD")` is fixed-width on Java 8 and adaptive from JDK 9, so on the
  JDK here it printed `2026/08/222` instead of throwing. The deployment is Java 8. The mechanism is
  asserted explicitly with `appendValue(DAY_OF_YEAR, 2)`, which reproduces the exact message. Worth
  remembering: a sandbox on a newer JDK hides Java 8 formatting behaviour.

## FIX: stale text said the bulk add/remove "is not available yet"
* Reported: the Remove button could not be found. The code was on main and correct; the SECTION's
  intro text was still the one written for the read-only batch and ended with "Adding a missing step
  to the other feeds is a separate action, not available yet." Two later batches added the actions
  and neither updated that sentence, so the page was telling the operator the feature did not exist.
  * Lesson: when a batch turns a read-only view into an actionable one, the prose that explains WHY it
  is read-only is part of the change, not decoration. Grep the section text, not just the handlers.
  * Also corrected: the empty state now says the buttons appear only for a step some feeds have and
  others do not (so "nothing here" is explained rather than looking broken), and the executor-conflict
  note now says the step can be neither added NOR removed, instead of mentioning only adding.

## Operations: sort by feed id; Variables: search + status picker
* **Operations**: the Feed column header is now sortable. `drillCmp` gains a `feedId` branch using
  `localeCompare` — a name is not a timestamp, and the "blank goes last" rule below it means NEVER RUN
  and belongs only to the timestamp columns; a feed with no runs must not be pushed to the bottom of
  an alphabetical list. A new column starts `desc` (newest first) except `feedId`, which starts `asc`,
  because that is what clicking a name header asks for. * **Variables**: a **Last status** picker
  alongside Source/Target/Feed, narrowing the others exactly as they do, with `(never run)` as a
  selectable value. `lastStatus` added to `/api/var-catalog`, read LIVE rather than from the feeds
  cache — that catalog is loaded once when the page opens, not polled, so there is nothing to
  amortise and a stale status would be a filter that lies. * **Variables**: a search box over the Feed
  list matching id, name, source/target ids and descriptions, tags and status. It narrows what the
  list SHOWS and never touches the selection: a feed already selected stays visible even when it stops
  matching, otherwise typing would silently drop it from the selection. Clear selection resets it too.
  * A python edit block asserted its way out before the file write, so two of the three overview edits
  were silently lost; the jsdom test caught it. Assert-then-write means a later failed assert discards
  the earlier applied edits — worth splitting writes per edit.

## businessDateNotFuture is now ON for every feed, opt-out per step
* Requested: apply the check to all feeds, including ones already created and run. The earlier batch
  deliberately did the opposite — preselected for NEW steps only — and flagged it as his call; he has
  now made it. * **The checks list could not deliver this.** `checks="..."` is a POSITIVE list, so a
  workflow whose XML already carries one can never pick up a new id, and rewriting 144 definitions to
  add one is not a deploy anybody should do. The check is therefore governed by an OPT-OUT param
  instead: `sel_bizFut = !"false".equals(params.get("businessDateNotFuture"))`. Absent, empty or any
  other value means ON; only an explicit `false` turns it off. * The designer renders it as its own
  always-on chip ahead of the others: unticking writes `businessDateNotFuture=false`, ticking removes
  the param. It was REMOVED from `CHECK_IDS` and from the seeded list for new steps, so the two
  spellings cannot disagree; a step that still lists the id in `checks="..."` is simply already
  consistent with the new rule. * Verified with 13 assertions including the one that matters — a step
  carrying a typical existing `checks="..."` list would NOT have been reached by the old positive-list
  rule and IS reached by the new one — plus case and whitespace on the opt-out value, and that no
  value other than `false` can accidentally disable it.

## JSON table viewer, in-app and standalone
* `.json` now gets the same treatment as `.xml`: an object becomes a **Key | Value** table, an **array
  of objects becomes ONE table whose header is the union of the keys** across its elements (one
  numbered row each, a missing key rendered as the hatched `xt-null` cell), an array of scalars a
  numbered two-column table, and a nested value a summary (`{ 4 keys }`, `[ 12 items ]`) with a toggle
  opening it in a full-width sub-row. * **Opens ON the table**, unlike XML which opens on Code: a list
  of entities is what people come to a JSON file to read. Source one click away. * Same architecture
  as the XML view — a light model built once, tables rendered from it on demand, search running on the
  model so it finds matches in branches never drawn. The `.xt-*` stylesheet is REUSED rather than
  duplicated, so light/dark parity comes for free; only four type classes were added. * `null` renders
  as `null`, not as an empty cell — a different thing. Strings, numbers and booleans coloured apart.
  * **`json_viewer.html` in the repo root**, built from the `xml_viewer.html` shell with the renderer,
  the loader and the Code-tab printer swapped; zero external references asserted. * Verified with
  jsdom twice: 40 assertions on the renderer (including the key union in first-seen order, the ragged
  row, nested summaries, an array of scalars, search, and six degenerate documents that must not
  throw) and 20 more driving the standalone page through its REAL file input with a real File, so the
  load path, the tabs, the invalid-JSON banner and the search box are exercised as a user would.

## Linked entities in the standalone JSON viewer (batch 1 of 3)
* Spec `.claude/2026-08-11-DESIGN-linked-entity-viewers.md`. Batch 1 = standalone JSON; CSV and the
  in-app viewer follow. * **The cap is on MODEL RECORDS, not file bytes** — MEASURED, not guessed: a
  2.1 MB document shaped like his gave 168 329 records and 37 MB of heap, so ~18x, and a megabyte
  limit would be wrong by an order of magnitude. Counted from the parsed doc BEFORE building the
  model, so a file that will not fit is refused without being materialised and the open files are
  untouched. Files are read ONE AT A TIME: in parallel, two files could each fit alone and overflow
  together. * **The question is asked per file after parsing**, not at page open as the request said —
  before a file is parsed there are no fields for the dropdowns. Flagged and agreed. * Entity lists are
  arrays-of-objects MERGED BY PATH, so `$.records[].relationships` is one dropdown entry, not 5000.
  A cross-file link is the same object as a self-link with a different target file, which is why 2.3
  fell out of 2.2 for free. * The diagram is the EOR_viewer FOCUS/PARENT/CHILD fan, one hop, badges
  re-focus — the only shape that scales past a few dozen entities. * **Three defects the jsdom run
  caught before delivery**: the page did not start at all (`var WS` assignment is not hoisted, and
  `paintCap()` ran before the workspace block); a declared link matched no row (entity path
  `$.records[]` vs list path `$.records` — added `wsListPath`); and a link on a top-level array never
  matched (empty root path gave elements the path `[]`, unreducible — the root is now named `$`).
  Two of the three were invisible to `node --check`.

## FIX (same batch): swapped-sides detection, +/- toggles, diagram direction and owner context
* **Direction check.** A relationship declared backwards was accepted silently — the inverted
  direction resolved 3 315 of 5 000 and read as healthy, so a resolution rate alone cannot catch it.
  `wsLinkStats` now reports distinct/rows/uniqueness per side plus the forward AND reverse rate, and
  warns only when BOTH signals agree (parent not unique + child unique + reverse resolves better),
  offering **Turn this relationship round**; **⇆ Swap sides** does it before adding. A legitimate
  many-to-one key does not warn. * **Toggles are now `+` / `−` in a bordered box**, not a 10px
  triangle: they are the only way to open a node, and a small target is a bad target. * **The diagram
  side now follows the DIRECTION of the reference**: left = what points AT the focus, right = what the
  focus points at. The declaration calls the field holding the value the CHILD field (database
  convention), but on a picture the record doing the pointing reads as the one above — a relationship
  row naming a customer is that customer's parent. Reported from the field; the picture follows the
  direction and the neighbour counter was aligned with it too. * **Badges name the row they live
  inside** (`wsOwnerLabel`). Without it a customer whose own `relationships` list was empty looked as
  if the diagram had invented four relationships; they were real and belonged to other rows. That one
  line of context turns a correct answer that looks wrong into a correct answer that looks right.

## Standalone viewers: relationship cache, and one shared link core
* **Cache**: declared relationships are kept in localStorage under `op-viewer-links-v1` and restored
  when the same files are added again. **Only the declaration is stored** — file NAMES, list paths,
  field names. Never a value, never a row, never a parsed document; in a Legal Archive context that
  distinction is the point, and it is asserted in the tests. Files are matched by NAME (an id means
  nothing across sessions), so a renamed file is a forgotten file — deliberate, since looser matching
  would restore onto a file that may not be the same one. A saved link whose two files are not both
  open WAITS; one whose list/field is gone is **reported and skipped**, not silently dropped, because
  a file that changed shape under a saved declaration is exactly what an operator wants to hear.
  Closing a file drops the live link, keeps the saved one. Storage blocked (private mode) must not
  break the page — asserted. * **The link core is now format-agnostic**, behind a three-method file
  contract: `lists`, `iterField(path, field, cb(value, ref))`, `entityOf(path, ref)`. The index stores
  REFS, not materialised entities, so a CSV row index will cost integers rather than an object per row.
  One implementation serves both standalone pages instead of two copies drifting. * **A duplicate cache
  implementation nearly shipped**: `page.js` already had one and a second was injected. JS allows
  duplicate function declarations (last wins) so nothing failed — `node --check` and all three suites
  passed. Added a **duplicate top-level function scan** to the build checks; it is now part of the
  routine for these single-file pages.

## elarxml executor — Batch 0 (spec only)
* Spec committed at `.claude/ELAR_XML_EXECUTOR.md`, self-contained. No implementation this batch.
  * **Q1 (separator inside a value) is BLOCKING and cannot be answered from the sandbox** — it needs
  counts from `G:`. Three PowerShell commands are in §3.1, including one that reads the LEFTOVER
  legacy `out_*.csv` intermediates as the historical record of how many documents `CsvParser`
  discarded silently. That check is read-only and answers whether past deliveries are incomplete —
  an archiving finding, not a software one. * **Three design contradictions found while specifying**,
  each with a recommendation: (a) a content file changing between the digest and encode passes cannot
  fail the DOCUMENT, because HashValue and most of Content are already in the stream — it must fail
  the BATCH, which is the only outcome consistent with temp-then-rename; (b) `WRITE_ALONE` requires
  closing the current batch first, or the document is not alone; (c) **line wrapping cannot be a dumb
  Writer under XMLStreamWriter** — it cannot tell markup from text, which IS the legacy defect. Worth
  recording why legacy got away with blind chopping: at 25 000 chars and megabyte payloads essentially
  every break lands inside Base64 where whitespace is ignored, but a metadata value straddling a
  boundary would have been silently corrupted. Recommendation is a small emitter-driven
  `WrappingXmlOut` and no XMLStreamWriter; the alternative is stated with its trade. * Registration is
  FIVE places if exposed in the designer, not four — `buildXml` included, per the `reportQuery` lesson.
  * **Batching is ONE selected rule, not two racing triggers**: `batchBy=DOCUMENTS` (default, legacy
  behaviour) or `BYTES`; the unused limit is not read, and the equivalence run simply uses the
  default. Requested on the grounds that the `sql` executor's CSV split conditions are alternatives
  — **they are not**: `CsvWriter:73-76` ORs `maxRows` and `maxBytes`, both may be set, and
  `InternalSteps:2056` logs "N rows/part or M MB/part". The decision stands on its own (one trigger
  removes the "which fired?" question, and ELAR imposes no maximum INDX size) but it is a DIVERGENCE
  from `sql`, not a match, and §12b of the spec records that so the two read consistently later.
  * Under `BYTES`, `max_index_docs` stays in every family's properties file and silently stops
  mattering — the step must LOG the ignored key and its value at start. A setting that can be read but
  has no effect is worse than one that is absent. * **A malformed row FAILS the run** (`onMalformedRow=FAIL`,
  decided), which forces the check to run FIRST: every input file is scanned for field-count
  mismatches BEFORE any output is written. Failing mid-file would leave batches already renamed to
  final deliverable names beside an input with no `.done` — a partial set with no marker, which a
  re-run would then duplicate. Pre-scanning makes the refusal atomic at the STEP level, reports every
  offending line across every file in one message, and costs only a second read of the CSVs (nothing
  beside the Base64 about to be written). The scan MUST use the same charset and the same parse,
  `quoteChar` included — a pre-scan that disagreed with the reader after it would be worse than none.
  * Recorded as a **deliberate exception to conservative defaults**: today such a row is dropped in
  silence and the feed ships short; after this it stops. A feed carrying one today WILL fail on its
  first run — which is what §3.1's count exists to find out beforehand. Escape hatch per feed is
  `onMalformedRow=SKIP`, which restores today's behaviour except the loss is counted, not invisible.
* **Batch 0 answers received**: no value contains the separator (asserted, and the pre-scan makes the
  design safe if it is ever wrong — a stray separator now STOPS the run instead of losing the row);
  the writer is hand-rolled; a file changing between passes fails the BATCH; `WRITE_ALONE` closes the
  current batch first. * **Templates differ per family** (confirmed: each family has its own INDX with
  its own tags), so the prologue/block/epilogue model is DISCOVERED, never assumed — find the
  descriptors container by namespace + local name from the properties, require exactly ONE element
  child as the per-document block, and fail at step start naming the template and what was found
  otherwise. No family tag name in the code; per-family constants come through automatically because
  the block is emitted from the parsed template. * **The pre-scan also checks every referenced content
  file exists**, blocking (decided). Second deliberate exception to conservative defaults: today a
  missing file is a counted skip and the feed ships short. Cost is one `exists()` per document, worth
  it because the run is about to read all of them anyway and 300 missing of 5000 is better known at
  the start than twenty minutes in with INDX already delivered. * **`maxLineLength` fallback set to
  20000, not the prompt's 25000**: `CLICT@DT` sets 25000 explicitly so it is unaffected either way,
  but a family on the fallback would change its line breaks on deploy — and no existing feed may
  change output. One `Select-String` over the `config_*.properties` says whether any family is on the
  fallback at all; if none is, either value is safe.
* **Batch 0 CLOSED.** Final answers: `maxLineLength` default is **25000** (his call, overriding my
  20000 recommendation) and **no source data contains 0x80-0x9F**, so `ISO-8859-1` + `REPORT` is free
  today. * **THREE deliberate exceptions to conservative defaults are on record**, each changing a
  feed that works today: (1) a malformed row stops the run instead of being dropped silently; (2) a
  missing content file stops the run instead of the feed shipping short; (3) a family without
  `max.line.length` moves from 20000 to 25000, shifting its line breaks. 1 and 2 are the point of the
  rewrite; 3 is a convenience and one `Select-String` says whether it touches anything. * The 0x80-0x9F
  answer is a measurement of TODAY's data, not a property of the feed: a euro sign or curly quote
  arriving next year fails that document loudly. Intended — the alternative is a silent `?` inside a
  legally archived document — but it will read as a regression to whoever meets it, so `USAGE.md` must
  carry the message and the `outputCharset=windows-1252` escape hatch. * The charset mismatch being
  closed was latent, not harmless: delivered INDX declare ISO-8859-1 while written with the platform
  default (windows-1252). It never mattered only BECAUSE the answer here is "none".


## elarxml — Batch 1 delivered (flat CSV reader, config resolver, pre-scan)
* New package `com.legalarchive.orchestrator.elar`: `ElarConfig`, `FlatCsvReader`, `ElarPreScan`. No
  Spring, no orchestrator types — compiles and RUNS standalone, which is the whole reason this batch
  comes first. No executor and no registration yet; that is batch 6. * `ElarConfig` replaces the
  production NPE (a .bat copied between feeds with one argument unchanged) with a message naming the
  bad `familyType`, the properties file AND the families it does contain. `docIdTag()` does the
  column→tag translation the legacy `Validator` never did — `doc_id_reference` is a COLUMN while
  `not_duplicated_tags_list` is already ELAR TAG names, an asymmetry that is real in the properties
  file and handled rather than normalised away. `contentTag`/`dsakTag` configurable per family
  (templates differ), defaulting to this family's names. * `FlatCsvReader`: one explicit charset with
  `REPORT`, `split(sep, -1)` so trailing empties no longer look like a field-count mismatch, optional
  quoting, BOM stripped, and the decoder failure rewritten with file + line + APPROXIMATE byte offset
  — stated as approximate because the reader buffers, rather than printing a precise-looking number
  that is wrong by up to 64 KB. * `ElarPreScan` reuses `FlatCsvReader`, which makes "a file that passes
  the scan cannot fail the read" true by construction. Listing capped at 50 per category, counts
  uncapped. A malformed row is NOT then also counted as a missing file — its content path cannot be
  trusted. `resolveContentFile` takes the file name only, so a traversal cannot escape `documentPath`.
  * 62 assertions, `-source 8`. Rule scans clean with comments excluded — the naive version flagged
  `CLICT` and `FileReader` inside javadoc explaining the legacy defects, so the scan strips comments
  first; worth keeping in mind for the other build checks.

## elarxml — Batch 2 delivered (writer, template model, atomic output)
* `WrappingXmlOut` is **emitter-driven**, which is the point: a Writer counting characters underneath
  cannot tell markup from text, and that IS the legacy defect. Breaks legal between
  elements/attributes, inside Base64 at multiples of 4, inside other text NEVER — an over-long value
  fails naming the tag. Declaration GENERATED from the charset; `canEncode` checked per value so the
  failure names the tag and the code point instead of surfacing as a byte offset at flush.
  * `IndxTemplate` discovers the model (container by ns + localName from the properties, exactly one
  element child) and refuses any other shape at parse time. `unknownMappedTags()` catches a mapping
  naming a tag the template lacks — otherwise a silent no-op. * `AtomicOutput`: `.part` in the SAME
  directory so the rename stays on one volume; `close()` aborts unless `commit()` succeeded. * 46
  assertions, `--release 8`. The load-bearing ones: no line over the max, every payload line a whole
  number of quads, no line ending inside a tag, payload byte-identical once breaks are stripped, and
  end to end the accented byte on disk is **0xE9** — the declaration proven against the bytes, not
  asserted. * Rule scans now also assert the ABSENCE of `StringWriter`, `ByteArrayOutputStream` and
  `Transformer`/`DOMSource`. * **The sandbox had no JDK this session** (JRE only): installed
  `openjdk-21-jdk-headless` and compiled with `--release 8`, which is stronger than the earlier
  batches' `-source 8 -target 8` because it also checks the API surface. * **The container reset
  mid-turn** and took the working clone with it; recovery was to re-clone and re-apply from the
  delivered zip. Keep sources outside the clone while working, or deliver more often.
  `Transformer`/`DOMSource`. * **The sandbox had no JDK this session** (JRE only): installed
  `openjdk-21-jdk-headless` and compiled with `--release 8`, which is stronger than the earlier
  batches' `-source 8 -target 8` because it also checks the API surface. * **The container reset
  mid-turn** and took the working clone with it; recovery was to re-clone and re-apply from the
  delivered zip. Keep sources outside the clone while working, or deliver more often.

## elarxml — Batch 3 delivered (streaming Base64 + SHA-256, skip accounting)
* `ContentEmbedder`: digest pass then encode pass, neither holding the file. Chunks are a whole
  number of 3-BYTE groups so every full chunk is complete quads with no padding and no carried state;
  padding lands once, on the final partial group. Digest over RAW bytes — asserted to DIFFER from the
  digest of the Base64 text, which is the dead-code convention. `fill()` loops on the returned count:
  the prototype's single unchecked `read` left the tail as zeros and encoded them silently.
  * **The change-between-passes guard fails the BATCH** (§4.1), detected DURING the encode pass by
  byte count rather than after it, because by then the hash and most of the payload are already in the
  stream and cannot be retracted. Both directions tested — a file that grew and one that shrank.
  * `encodedLength` = `ceil(bytes/3)*4`, verified EXACT against the real encoder at nine sizes, not
  approximate; batch 4's byte budget depends on that. * `ElarCounters` states skip counts EVEN WHEN
  ZERO — a line that only appears on failure trains people not to look for it. * 28 assertions,
  `--release 8`, including all twelve size classes across the chunk boundary and both padding cases.
  * **`ByteArrayOutputStream` is now zero occurrences in the package**: the buffer that held each whole
  file before encoding is gone by construction. Added to the rule scan alongside `StringWriter` and
  `Transformer`/`DOMSource`.
  file before encoding is gone by construction. Added to the rule scan alongside `StringWriter` and
  `Transformer`/`DOMSource`.

## elarxml — Batch 4 delivered (naming, batching policy, PULL manifest)
* `BatchNaming`: the `C152100` segment is a **synthetic clock** — starts at `output.start_time` or
  wall-clock, advances exactly 60s per batch — not a timestamp and not a sequence. Two runs on the
  same day with the same explicit `start_time` therefore COLLIDE; with it unset they produce new names
  and duplicates accumulate silently, which is the real risk and why `countSameDayPairs` reports
  rather than prevents. Refusing a same-day re-run would break the case where a re-run is most needed.
  * Julian segment built with `getDayOfYear()` + `String.format`, so **`ofPattern` appears NOWHERE in
  the package** and the rule scan now asserts that. A control assertion records that `ofPattern("DD")`
  on 17 Aug gives 229 — the trap that killed the validate executor, kept visible in the test.
  * `BatchPolicy`: one rule, and `describe()` names the IGNORED limit WITH ITS VALUE since it sits in
  the properties file where anyone can read it. Constructed without its own limit → refused at once.
  `WRITE_ALONE` closes the open batch first (§4.2), `FAIL` explains why it can never be written and
  gives both ways out. `estimateDrifted` catches an estimator coming loose — a budget on a wrong
  estimate rolls in the wrong place silently. * `PullTemplate` substitutes in EVERY attribute (superset
  of legacy, cannot change any file it produced). The legacy hardcoded-namespace inconsistency between
  PULL and INDX templates is PRESERVED, not tidied: ELAR accepts it, and this is not the place to find
  out whether it accepts anything else. * 61 assertions, `--release 8`; batches 1-3 re-run.
  out whether it accepts anything else. * 61 assertions, `--release 8`; batches 1-3 re-run.

## elarxml — Batch 5 delivered (validate, off by default)
* **The third check from the spec is a TAUTOLOGY on a flat row and was NOT implemented.** The legacy
  reference check compared `doc_id` against the value of the tag it maps to; on a flat row those are
  the same value by construction, so it can never fail. A green check that cannot go red is worse than
  no check — it reports confidence it does not have. Replaced by "document id present and non-empty",
  which does fail on real data. Recorded in §10 of the spec. * Reporting: a duplicate **document id
  names the id** (a document identifier, same class as the missing-file name the pre-scan reports); a
  duplicate on **any other tag names the tag and the lines but never the value**, since an arbitrary
  tag can carry anything. * Findings are data, not errors — nothing throws, including on a null row.
  A clean file SAYS SO, and an unconfigured `not_duplicated_tags_list` says that too rather than
  looking as though it passed. * Memory: id set + one value set per configured tag, bounded by document
  count and not by content. Stated in the spec as the point that needs revisiting at tens of millions.
  * 30 assertions, `--release 8`; batches 1-4 re-run. **Two failures on the first run were both mine**:
  the helper put the id tag into the unique list, so two checks legitimately fired where the assertion
  expected one — isolated, with the two-checks case kept as its own deliberate assertion.
* `ElarRun` keeps the whole executor free of Spring, so it is tested END TO END against real files on
  disk rather than only on deploy. `runElarXml` in `InternalSteps` does nothing but translate
  parameters in and counters out. * `IndxTemplate` needed a STREAMING form: the one-call
  `write(out, docs)` would mean building the list of a batch's documents first, which is the very
  accumulation this rewrite removes. Prologue / document / epilogue are now separate calls.
  * **FIVE registration points**: parser whitelist, **the parser's error message**, parser `internal`
  set, `WorkflowEngine.internalKind()`, `InternalSteps` dispatch, designer dropdown. The message and
  the dropdown are the two that get forgotten — `reportQuery` was exactly a preview disagreeing with
  the writer. * Missing required parameters are ALL named in one message: nobody should run a step six
  times to be told six things. * 39 assertions across two harnesses; the load-bearing one is that the
  digest in the DELIVERED INDX matches the source file's raw bytes AND the embedded payload decodes
  back to that same digest. * **A test caught a behaviour I had not pinned**: with `output.start_time`
  set, a same-day re-run produces COLLIDING names and is refused on the first batch; with it unset the
  clock takes wall time, names differ, and duplicates accumulate. Both now pinned separately.
  * **NOT COMPILED**: `InternalSteps`, `WorkflowXmlParser`, `WorkflowEngine`, `designer.html` — they
  need the Spring tree from the internal Nexus, unreachable here. Checked structurally instead (brace
  balance, every helper/field verified against its real declaration, no helper-name collision, the
  designer line free of literal `\n` / `[[` / `[(`). `mvn clean package` is the gate before deploy.

## elarxml — Batch 7 delivered: USAGE.md + equivalence comparator. Executor COMPLETE.
* `USAGE.md` elarxml section written to the renderer's rules and **verified by running docs.html's own
  `render()` against the real file** — note `render()` returns `{html, toc}`, not a string, and the
  script needs `#doc`, `#toc` and `#clock` to bootstrap under jsdom. Result: no raw markdown leaking
  outside code blocks; of 32 paragraphs in the new section, ZERO are sentence fragments (the 118
  fragments elsewhere are pre-existing, not mine). * `ElarEquivalence` runs as a `main` against real
  directories. **Each payload is checked against the SOURCE FILE, not against the other side** —
  comparing the two outputs to each other would pass any mistake they share, which is the class a
  rewrite is most likely to inherit. * **A real defect was caught by that very test**: `docBlocks`
  descended to the element containing the id tag, which is the id tag ITSELF since every ancestor
  contains it, so every document looked like it carried one field and both comparisons silently
  passed. Now walks UP while the ancestor holds exactly one id, stopping at the container. A
  comparator reporting equivalence because it is looking at nothing is the worst failure this tool
  could have. * 24 assertions; all seven suites green. * **Remaining gate: `mvn clean package`**, which
  the sandbox cannot run, plus a first real comparison.

## elarxml follow-up: the hash tag was hardcoded (my defect, found on review)
* `ElarRun` held `static String HASH_TAG = "ELAR:HashValue"` while content and dsak tags were already
  configurable. It broke this spec's OWN rule — no family tag name in the code — and would have
  written the digest nowhere for any family whose template names that element differently, i.e. every
  family whose template has not been read. `output.hash_tag` now sits beside `output.content_tag` and
  `output.dsak_tag`, same default so no properties file needs editing. **No `ELAR:` literal remains in
  `ElarRun`** and the scan asserts it. * Verified by running the WHOLE executor for a family sharing
  **no tag name** with this one: digest in its own hash tag and equal to the source's raw-bytes SHA-256,
  extension in its own kind tag, payload decoding back to the file, its constant surviving, and the
  word `ELAR` absent from the output. * `PER_DOCUMENT_OVERHEAD = 2048` is now documented as **the one
  figure in this executor that is chosen rather than derived**. Unused under the default
  `batchBy=DOCUMENTS`; under `BYTES` it only shifts the rollover by a couple of KB per document, and
  `estimateDrifted` logs when estimate and reality part company — correct it from that log, not from
  another guess. * Lesson: a rule written into a spec is not self-enforcing. The scan for family
  literals covered the `elar` package but I had used a constant, not a literal in a comparison, so it
  read as legitimate code. Scans catch shapes, not intentions.

## Standalone CSV viewer: relationships (batch 2 of the linked-entity spec)
* Built on the **shared core**, not a second implementation: a file answers `lists`, `iterField`,
  `entityOf` and everything else is reused. For CSV the **ref is a row number**, so a 100k-row index
  is an array of integers and the row object exists only for entities a diagram shows. * The existing
  grid, filters, ranges, sort, aggregation and displayschema are untouched; only `drawRows` learned
  links, and the link map is computed **once per frame** — `drawRows` runs on every scroll frame, so
  anything per-cell there is paid thousands of times a second. * **Budget in CELLS** (~52 bytes each,
  4.6x the file) vs JSON's records (~18x): two units because one number would be wrong for one of them.
  * **Three defects the tests caught, two older than this batch**: (1) `linksPersist` only ADDED, so a
  removed relationship came back on reload — now it replaces the entries whose files are both open and
  leaves the ones waiting for a closed file; (2) the JSON viewer **never persisted on removal at all**;
  (3) **two `var WS` in one scope** — the page silently ran with the JSON record budget. The
  duplicate-FUNCTION scan could not see it, so the checks now scan **top-level `var`s** too. Second
  time a silent duplicate declaration has cost a round. * 59 assertions on the CSV page + a JSON
  regression suite, both green.
  regression suite, both green.

## elarcheck — Batch 0 (spec only)
* Spec at `.claude/ELAR_CHECK_EXECUTOR.md`. Read-only validation executor for delivered INDX files;
  built BEFORE the generator on purpose, since it is the acceptance harness — the decisive criterion
  for `elarxml` is that a regenerated file passes every check while the original does not.
  * **The five reference scripts live in `github.com/projectsrl-git/htmlviewers`**, not in this repo.
  Read, and they answered the three open points — two differently from what the descriptions implied.
  (a) Lines are 1-based including the declaration, but a line BREAK is reported at `lineNo - 1`, the
  line where it starts and which must be repaired, while `InvalidSpaceAfterAngle` is reported at
  `lineNo`. Reporting both at the current line would put every break one line late. (b) The record
  ordinal accumulates `<Doc` starts across the whole file, never restarts, and is 0 before the first
  document; the break checks run BEFORE the current line's starts are added and the bad-angle check
  AFTER, so two findings on one line can legitimately differ by one. (c) 25000 is the agreed target,
  **30000 is what the receiver enforces** — so the message must separate "over target" from "would be
  truncated". * **A distinction the prompt did not carry**: the script separates `MarkupLineBreak`
  from `TextLineBreak` by whether the previous line ended inside a tag, because the repairs differ —
  and its own comment records that repairing a markup break by inserting a space anywhere but between
  two attributes produces `< ELAR:TaxCode>`, the very invalid start that check 5.2 finds. Reported
  separately here for that reason. * Payload exclusion is by **local name carried across lines**, not
  by any heuristic on line content. * **Two design points decide the shape.**
  (a) TWO physical passes, not one: textual checks need physical lines and must continue after the
  document stops being well-formed — which is the whole point of reporting every `< Name` when StAX
  stops at the first — while structural checks need the parser. Sharing a Reader fails on both counts.
  Cost is I/O, not memory, and it is why progress reporting is a requirement. (b) **StAX coalescing
  must be OFF**: `IS_COALESCING=true` would materialise a payload of tens of MB as one String, the
  exact accumulation that killed the legacy generator. So 5.3 tests EACH `CHARACTERS` fragment for
  CR/LF instead of assembling the value — equally sensitive, no memory. * Read-only is made
  VERIFIABLE, not promised: no write API may appear in the package, asserted by a build scan; the
  findings file goes to the step directory, never to `inputDir`. * `CORRUPTED` (well-formed but wrong)
  is the verdict that matters — ELAR accepts the file and archives a wrong value, and nothing
  downstream ever flags it.

## elarcheck — DELIVERED (batches 1-7 in one pass)
* Package `com.legalarchive.orchestrator.elarcheck`, no Spring, so the whole checker runs against real
  files in tests. `ElarCheckReport` + `ElarCheckRun`; `runElarCheck` only adapts. Registered in all
  five places; findings file goes to the STEP directory. * **Read-only verified by scan**: no
  `FileOutputStream`/`Files.write`/`Files.delete`/`renameTo`/`createNewFile`/`FileWriter`/`.delete()`
  in the package, plus a test asserting the inspected dir's listing and mtimes are unchanged.
  * **Two passes**: textual (openers, line length) must outlive the first fatal parse error; structural
  (well-formedness, values, occurrence, hash) needs StAX. **Coalescing OFF** — else a payload of tens
  of MB becomes one String; the value-break check tests EACH `CHARACTERS` fragment instead, equally
  sensitive, no memory. * **Two defects the tests caught**: (1) `nextName` searched `.C` from the LEFT
  and matched `.CLICT` inside the family name, returning the name unchanged and silently — now scans
  from the right for `.C` + exactly six digits; found only because the test used the REAL family name.
  (2) The counter advances by **one second, not sixty** — sixty lands on the next batch's name, the one
  name guaranteed to be taken. * 63 + 15 assertions, `--release 8`; `USAGE.md` verified through
  `docs.html`'s own `render()` (20 paragraphs, zero fragments). * **Not verified**: `mvn clean package`,
  and nothing has run against a real INDX — matching ELAR's reported line/record numbers still needs
  the known-bad file. Designer has the dropdown but NO config panel, the gap `elarxml` had; use
  `+ param`, only `inputDir` is required. **Resolved** — see the elarcheck panel section below.

## elarxml: designer panel, and the `.done` rename per file
* **The `.done` rename ran once at the end of the run.** `ElarRun` renamed the inputs after the LAST
  batch of the LAST file, so a run of three CSVs whose third failed left the first two **delivered
  but not renamed** and the next run reprocessed them: colliding filenames with `output.start_time`
  set, silent duplicates without it, which is the live configuration. §5 already said "only after
  every batch that input produced" — the implementation read that as *every* batch, the correct
  reading is *its own*. Now two lists: the inputs contributing to the batch currently open, and the
  inputs read to the end whose last documents are still in it; closing a batch flushes the
  intersection, an input contributing to no open batch is renamed as soon as it is read. The
  contributor is recorded BEFORE the write, so `ROLL_THEN_ALONE` still attributes the batch. Every
  close goes through one `closeBatch` helper and the rename sits downstream of it, so `.done`
  continues to mean *delivered*. **Both directions are tested**: an input wholly inside a committed
  batch is renamed while later files are unread, and an input straddling two batches is NOT renamed
  when the second is aborted. 33 assertions running the real executor on real files; the mid-run
  failure is an unencodable metadata value, a WRITING failure and therefore invisible to the
  pre-scan.
* **The executor had no designer panel.** It was in the dropdown but had no branch, so it fell
  through to the generic external one — `＋ param` rows and a disabled Script field. Dedicated branch
  on the `sqlreport` model: six required parameters plus three subsections covering every optional
  one of §8, defaults as placeholders. `clientValidate` names all six missing in ONE message (as the
  executor does) and refuses a multi-character `separator`/`quoteChar`, both read with `charAt(0)`.
  `maxBytesPerBatch`/`oversizeDocumentPolicy` stay VISIBLE under `batchBy=DOCUMENTS` labelled "NOT in
  effect" — hiding them would contradict the rule the step log already follows. A field at its
  default writes no param at all, so defaults live in the executor and are not frozen into the XML.
  * **`buildXml` needed NO change** — every field is a `<param>`, which it already emits generically;
  not the `reportQuery` case. Checked, not assumed: those assertions pass against the unpatched file
  too. `variables.html` gained the matching `PARAM_OPTIONS` entries so a designer dropdown is not a
  free-text box in the mass editor. 88 jsdom assertions against the real template; the same harness
  fails 34 of them pre-patch.
* **Build check learned**: the duplicate top-level `function`/`var` scan must be **brace-depth
  aware**. The indentation-based version used on the standalone single-file pages flags every local
  variable in every function body of a Thymeleaf template, because there top level sits at four
  spaces.
* **OPEN, deliberately not changed**: `max.line.length` from the properties file is read, LOGGED and
  ignored. `ElarRun:104` resolves it, but `Batch.open` and the PULL writer recompute the width as
  `o.maxLineLength > 0 ? o.maxLineLength : 20000` without the config, so every INDX wraps at 20000
  whatever the family declares — measured: a family declaring 25000 logs 25000 and delivers 20000.
  The log states a width the bytes do not have. §8b's 25000 fallback never reached the code either.
  One line to fix, but it moves the line breaks of every delivered INDX on the first run after
  deploy, so it needs its own batch and an explicit decision.

## elarcheck: designer configuration panel
* Closes the gap its own commit message recorded. `inputDir` plus three collapsible subsections
  grouped by **what is decided together**, not by declaration order: line length (target beside
  receiver limit, with the reason they are two findings and not one written between them), element
  names (the three locals + the mandatory list), optional checks and reporting. Defaults as
  placeholders, reasoning in `title`. Built on the `elarxml` panel from the previous turn, now the
  model for both.
* **Three reasons that are easy to lose now sit beside their setting**: the charset is deliberately
  NOT the one the files declare; the findings cap caps the LIST and never the counters;
  `failOnFindings` is off so a gate can branch on the counters, because a step that always failed
  could not drive the check-then-repair shape. The panel also STATES the two properties that make the
  executor what it is — read-only by construction, and no field value in the findings file or any log
  — and **both are asserted**, so they cannot quietly fall out of the panel later.
* `clientValidate` requires `inputDir` and refuses a **target line length above the receiver limit**:
  the executor accepts it and the two findings then read backwards, which is worse than a refusal.
  **Equal values are allowed** — the receiver limit is a bound, not a strict outer one.
* **`inputCharset` is deliberately NOT in `PARAM_OPTIONS`.** That table is keyed by parameter name
  with no executor context, and the two ELAR executors share the name with DIFFERENT defaults
  (elarxml UTF-8, elarcheck windows-1252), so one dropdown would print the wrong default for one of
  them — on a mass-edit page, worse than a free-text box. The unambiguous enums (`checkPull`,
  `verifyHash`, `failOnFindings`) were added. Worth remembering before the next executor reuses a
  common parameter name.
* 67 jsdom assertions against the real template; the same suite fails 23 against the pre-patch file.
  **Three of them render an elarcheck and an elarxml step SIDE BY SIDE** and check each keeps its own
  panel: the branches are adjacent in the same chain and a collision would be invisible in either
  suite on its own. `buildXml` needed no change — every field is a `<param>`.

## ELAR: no file extension is expected, and the PULL had lost the counter
* **ELAR expects NO fixed extension.** A delivered file ends at its `.CHHMMSS` counter, which is how
  `output.index_name_pattern` is written. The generator already produced that name correctly — the
  whole name comes from the pattern and nothing adds, assumes or requires an extension.
* **What did not survive it is the name the PULL references.** `BatchNaming.stripExtension` removed
  the last dot-segment via `lastIndexOf('.')`, which on `x.INDX.C152100.xml` is indistinguishable
  from removing `.xml` and on `x.INDX.C152100` **eats the counter** — there the counter IS the last
  dot-segment. `[INDEX_NAME]` was substituted as `x.INDX`, so **every delivered PULL referenced a
  file that does not exist**. Both files still look right in a directory listing; only the manifest
  is wrong.
* **Legacy did `replace(".xml","")`** — a literal replace, therefore a NO-OP without an extension.
  Rewriting that crude line as the "obviously equivalent" `lastIndexOf('.')` is what introduced the
  defect: it is only equivalent while an extension is present. **Remember this before tidying the
  next legacy oddity.** Both now strip a literal `.xml` SUFFIX, case-insensitive; deliberate
  divergence from legacy, which substituted the sequence anywhere in the name.
* **`elarcheck` carried the SAME shortcut in its pair check**, so with no extension it looked for
  `...INDX` — a substring of almost any PULL for that family, including the PULL's own name — and
  **passed on a broken pair**. The two bugs agreed with each other, which is why neither could reveal
  the other: the failure direction that makes a checker worthless.
* 15 assertions on the generator + 6 on the checker, `--release 8`. The decisive one (a PULL naming
  the prefix without the counter) returns **zero findings** pre-patch. `.done` rename suite re-run
  unchanged; the elarcheck read-only scan re-run clean, since the change touches no file API.

## elarxml: the line break landed at the EDGES of a value; outputCharset now UTF-8
* **Found on the field, by the equivalence comparator over 1000 real documents**: 43 WHITESPACE
  findings plus 2 records reported as BOTH missing and extra, and in every one the break was on the
  CANDIDATE's side — ours — always immediately before or immediately after the value. The 2 split
  records had it inside `UniqueReportID`, so one document looked like two.
* **The boundary was got wrong, not the rule.** §5 already said "between elements legal, inside any
  other text node never". But the position right after the `>` of a start tag and right before the
  `</` of an end tag LOOK like element boundaries and are not — both are inside the character data.
  `text()` and `endElement()` each called `fit()`, so either could break there. It is the legacy
  defect moved from mid-value to the edges, and **worse**: a break in the middle of a value is
  visible, a leading or trailing one is not.
* **Fix: the unbreakable unit.** `WrappingXmlOut.textElement` measures the whole
  `<tag attrs>value</tag>` first, takes the break BEFORE the start tag where it is genuinely between
  elements, and writes through `raw` and never `fit`. `text()` is now **private** and non-breaking:
  a public breaking text writer is what produced the defect.
* **Refusal decided explicitly**: if the unit cannot fit, the document is refused naming the tag. The
  threshold now includes the TAGS, not just the value, so a value that used to pass by a few
  characters begins to fail — deliberate, because overflowing delivers a value the receiver truncates
  at 30000, and a truncated value in a legally archived document is worse than a named refusal.
* **The content tag is exempt BY CONSTRUCTION**, not by exception: Base64 never reaches
  `textElement`, it goes through `base64Chunk` and breaks at quad boundaries where whitespace is
  ignored by every decoder. Asserted, not assumed.
* **Mixed content is now refused at LOAD**, naming the element: it has no position its text could
  keep under the unit rule, and the previous emitter reordered it anyway (all text first, then every
  child). Whitespace-only text between children is formatting and still discarded, so indented
  templates pass.
* **The decisive test is exhaustive, not representative**: the same value written at EVERY starting
  column of a line, all must round-trip. The defect only showed at the few columns where the element
  straddled the limit — which is exactly why 43 in 1000 slipped through and why hand-picked cases
  would have missed it. **47 of 114 columns corrupt the value pre-patch.** 31 assertions.
* **DECLARED EXCEPTION to conservative defaults: `outputCharset` ISO-8859-1 -> UTF-8.** It changes
  the bytes of every family that does not set the parameter. Measured, not assumed: a byte probe over
  the INDX ELAR receives today (produced by the PowerShell scripts) found 294 non-ASCII bytes forming
  147 valid UTF-8 sequences, ZERO stray high bytes, declaration UTF-8. The old default came from
  `elar-file-maker.jar` — a DIFFERENT producer — and made the candidate differ on every accented
  character (147 VALUE findings, all `U+00C3 -> U+00E0`). **The file to be equivalent to is the one
  the PowerShell scripts produce.**
* **OPEN, and stated rather than left to be discovered**: `maxLineLength` counts CHARACTERS, not
  bytes. Under UTF-8 a 25000-character line can exceed 25000 bytes. Whether the receiver's 30000
  limit is bytes or characters is NOT confirmed with the receiving team. At 147 accented characters
  per 1000 documents the difference is far inside the margin, but the question is open.

## elarxml: the .skipped discards file, and onMissingFile split from onMalformedRow
* **Requested**: a referenced content file that is not on disk should skip the row and leave a record,
  not refuse the run. It DID refuse, and through the SAME switch as a malformed row. Two different
  problems: a malformed row means the input is broken and re-running will not help; a missing content
  file usually means staging has not finished, and the rows that DO have their files are deliverable.
  One switch made the second hostage to the first.
* **`onMissingFile`, separate from `onMalformedRow`.** SKIP is the default — a **DECLARED EXCEPTION**
  to conservative defaults, because until now a missing file refused the run: a family relying on that
  refusal must now set `onMissingFile=FAIL`.
* **`<input>.skipped`**, beside its input, the input's own header line plus each dropped row
  **VERBATIM** — re-serialising the split fields would rewrite quoting and separators, and this file
  exists to be re-read. Rename it to end in `.csv` and the next run picks it up (`listInputs` only
  accepts `.csv`, so it is never mistaken for an input while it ends in `.skipped`). The name APPENDS,
  matching `.done`: the original name stays legible and `a.csv`/`a.txt` cannot collide.
* **Published at the SAME moment the input becomes `.done`**, reusing the §5 machinery: temp until
  then, thrown away if the run fails. A discards file from a run that delivered nothing would read as
  a complete account of what was dropped and would be the opposite of one. `.skipped` means what
  `.done` means. Created lazily, so nothing to discard leaves no empty artefact to be mistaken for a
  report.
* **Empty-path rows go in it too.** Re-running will not rescue them, but a discards file listing only
  SOME of the dropped rows would misrepresent what was archived, and this is an archive.
* `writeSkippedRows=false` turns the file off, keeping the skip and the counters. New result var
  `skippedFilesWritten` so a gate can branch without parsing a log. 36 assertions; the decisive one is
  that a FAILED run publishes no discards file, no temp file, and an un-renamed input.
* **`onMissingFile` deliberately NOT in `PARAM_OPTIONS`**: `ifscopy` already uses that name with the
  OPPOSITE default, and the table is keyed by name with no executor context. Same rule as
  `inputCharset` — the second time this has come up, so treat a shared parameter name as the norm.
* **DEFECT found alongside, pre-existing: `rowsMalformed` counted TWICE** under
  `onMalformedRow=SKIP`. Assigned from the pre-scan, then incremented again in the write loop, so
  every malformed row was reported double in `run.vars` and in the cross-feed log report. The
  pre-scan's count is authoritative — taken before any output exists, over rows the loop may never
  reach — so the loop no longer counts. Caught by an assertion that expected 1 and got 2.

## ELAR: start tag atomic, formatOutput, and elarcheck's always-empty Content
* **`elarcheck` reported EVERY mandatory `Content` as empty.** The payload is streamed rather than
  assembled — deliberately, for memory — and the branch that records "this tag has content" sat on the
  other side of that `if`, so `nonEmpty` was never set for it. A thousand documents, a thousand
  identical false alarms, verdict CORRUPTED on files that were fine. **The worst shape a checker defect
  can take is the systematic one**: the noise buries whatever is real. Fixed by marking non-empty on
  the first non-whitespace character of the payload. **The complement is what keeps it honest**: a
  genuinely empty and a whitespace-only Content are still reported — silencing those too would have
  swapped a false positive for a false negative, which is worse in an archive.
* **A line could end INSIDE A START TAG.** Two `MarkupLineBreak` findings in 1000 delivered documents.
  Same shape as the value-edge defect, one level out: the start tag was written in three independent
  pieces (name, attributes, `>`), and `closeStartTag`'s `fit(1)` pushed the closing angle alone to the
  next line when the column landed EXACTLY on the limit. One column in every `maxLineLength` per
  element — fifteen elements a document, a thousand documents, two occurrences is the expected order.
* **Compare said the same file was fine, and was right.** `<ELAR:Doc` / `>` is VALID XML that a
  conformant parser forgives; only a byte-level reader sees it. The two tools were not contradicting
  each other, they were measuring different things — worth remembering before treating a disagreement
  as one tool being broken.
* **Fix: `startTag(qname, attrs)` writes the whole tag**, measured before it is begun, break before the
  `<`. `emptyTag`/`endTag` join it, and the piecewise `startElement`/`attribute`/`closeStartTag`/
  `selfClose`/`endElement` are **removed from the API**, not merely unused — leaving a way to place the
  pieces independently is what let this recur after the value fix. Verified exhaustively over every
  starting column, as with the values.
* **`formatOutput`, ON by default** (decided explicitly): one element per line, indented. Changes the
  bytes of every family on deploy. Content is untouched and it is ASSERTED, not argued: same 40
  documents run both ways, no value differs, none gains a line break, the payload decodes identically.
  **The payload stays attached to its own tags** (`endTagAttached`) — as requested, and as an
  unformatted file has it. Two bounding properties: formatting only ever makes lines SHORTER, so the
  receiver limit cannot be crossed by enabling it; and it never causes a refusal — when indent plus
  element would not fit, the INDENT is dropped, not the element rejected.
* **The two name patterns are now in the step log**, with `start_time` and the formatting flag. The
  filenames come ENTIRELY from those patterns — nothing adds an extension or a counter — so "where did
  this `.xml` come from" is a log lookup instead of a hunt through a properties file on a share. That
  question cost a round trip; this is the cheapest possible answer to it.

## elarcheck: a break at the HEAD of a value was invisible to BOTH tools
* **The fast path was wrong in one case.** A line ending in `>` was treated as safe, always. It is not
  when that `>` closed a **start tag**: the next character is the first of the element's content, so a
  break there gives the value a LEADING line feed.
* **Not hypothetical — it is the class the generator produced**: 43 documents in 1000 on
  `ClientAdvisor`, `RecordDescr`, `AccountID`, `ClientID`. elarcheck reported none of them, and
  neither did `Repair-ElarIndxLineBreaks.ps1`, which carries the SAME fast path and says so in its own
  description. Only `Compare-ElarIndx.ps1` saw it, because it compares values against a reference
  instead of reading bytes. **Running the repair script with `-Fix` over such a file would have
  rewritten the corruption unchanged and declared the file sound.**
* **The decision needs the NEXT line**, not the one that ends. Markup means the element has children
  and the break was between elements; anything else is character data. **A value can never begin with
  `<`** — it would be escaped — so the test is exact, not a heuristic. Leading whitespace is skipped
  because with `formatOutput` on an indented file is now the normal case. Content excluded: a break
  after `<ELAR:Content>` is payload.
* **Half the assertions are the false positives**, and that is the half that matters: a checker firing
  on every document is worse than one that misses. Indented file, unindented multi-line file, break
  after the content start tag / a self-closing tag / an end tag / a start tag whose child follows —
  none reported. Plus a cross-package run: 300 documents written by `elarxml` at a 300-char limit,
  checked with six mandatory tags and verifyHash, formatting on AND off — no findings either way.
* **The tool boundary, for the record**: elarcheck DETECTS everything the repair script detects, and
  since this change one class more. It REPAIRS nothing, by construction, asserted by scan. For a file
  already corrupt the script is still the only thing that fixes it. "Detects" and "repairs" are not
  the same question and the answer differs.

## elarxml: reading the Content payload from IFS — SPEC ONLY (batch 0)
* Spec at `.claude/ELAR_XML_IFS_CONTENT.md`. No code. Four decisions listed there are the author's, not
  mine, and batch 1 waits on 8a.
* **No Gate 0 on Nexus**: JTOpen is already declared (`net.sf.jt400:jt400:20.0.7`) and `IfsSupport`
  already uses `AS400`/`IFSFile`/`IFSFileInputStream`.
* **The constraint that shapes the whole design**: the `elar` package is Spring-free and compiles
  standalone with `--release 8`, which is what let every suite in this subsystem run the real executor
  in a sandbox — and it is what caught the `.done` rename, the value edges, the split start tag and the
  double count. `IfsSupport` is a Spring `@Component`. So: a JDK-only `ContentStore` interface INSIDE
  `elar`, `LocalContentStore` beside it, and `IfsContentStore` in the engine layer where Spring and
  JTOpen already are. `elar` stays standalone-testable; only the thin adapter is not, and that is
  declared rather than implied.
* **Two passes over a network file.** The template puts HashValue BEFORE Content, so the digest must be
  known before the payload is written: locally two sequential passes, neither holding the file. Over IFS
  that is two network transits. Buffering to get one pass is exactly the `ByteArrayOutputStream` that
  caused the OOM — rejected. **Recommended: stage each document to a local temp file once**, then digest
  and encode from local disk. One transit, invariant intact, peak disk one document — and the
  size+mtime stamp becomes MEANINGFUL, because a local temp cannot change underneath us while an IFS
  file can.
* **The pre-scan stays, but as ONE listing.** A per-row `IFSFile.exists()` is a round trip each.
  `IFSFile.listFiles()` on the base path is one round trip and yields name, size and mtime for every
  entry, so existence, length and DSAK all become local lookups. `IfsSupport.copyListToLocal`'s own
  javadoc already reasons about this and says why ELAR is the case where the pre-scan must be kept.
  Listing memory needs a declared cap, not a discovered OutOfMemoryError.
* **`buildXml` DOES need a change this time.** The datasource is a `<step>` ATTRIBUTE, not a `<param>` —
  `runIfsCopy` reads `step.datasource`. Every previous elarxml field was a param, which buildXml emits
  generically; this is the `reportQuery` case. The earlier "buildXml needed no change" does not extend
  to it. The parser must also carry `datasource` for `elarxml`.
* **Open question that changes the most code (8a)**: does the CSV column carry a bare file name or a
  full IFS path? Today the value is reduced to its last path segment. Locally that mirrors the legacy
  `updateFilePath`; over IFS a full path in the column is far more plausible, and if the feed carries
  one the current rule throws away the only part that matters and every row lands in `.skipped` with the
  file sitting right there. To be answered from a real feed, not assumed.
* **No AS/400 in the sandbox.** A fake store can prove the seam under slow reads, missing files and
  mid-stream failure; it cannot prove JTOpen. Honest sequence: batch 1 deployed and proved a no-op on a
  real feed first, then IFS on one family with a small input, then volume.

## elarxml IFS content: 8a answered (FULL PATH), spec revised, and batch 1 delivered
* **The CSV column carries a FULL IFS path.** That revises three sections of the spec, not one.
* **Resolution moves INTO the store, and the two stores deliberately disagree.** `LocalContentStore`
  keeps today's rule (last path segment, joined to `documentPath`); an IFS store takes the value as
  given. The asymmetry is right because **it is one CSV serving two topologies**: under LOCAL an
  `ifscopy` step has already flattened the tree into one directory, so only the file name can still be
  meaningful; under IFS the tree is still there and the path is the only thing that finds the file.
* **The trap the answer removed**: under a shared last-segment rule an IFS run would have reduced every
  full path to a name, looked for it under a base directory it is not in, and sent EVERY row to the
  discards file with the documents sitting untouched on the IFS — reporting itself as a clean skip of
  everything.
* **One base listing no longer works** either: with a path per row the documents can be spread over many
  directories. Revised to listing the distinct PARENT directories, derived from the CSV itself — a
  handful of round trips for thousands of rows. With a guard for the degenerate case: if parents
  outnumber half the rows, fall back to per-file `exists()`, and **log which strategy was chosen**, or a
  run that silently took the slow path is one nobody can explain afterwards.
* **Batch 1 delivered as a pure refactor, proved byte for byte**: same fixture before and after, SHA-256
  of the delivered INDX and PULL identical with formatting on and off. Assertions alone would not have
  been enough for a no-op claim; digests are.
* **A fake store proves the seam** — resolution honoured, full path kept whole, missing document skipped
  and recorded, length/mtime/two streams all asked of the store, and a mid-read failure landing in the
  same abort path as any write failure: no INDX, no temp, no `.done`, no discards file.
* **Defect the seam test found, not review: nothing closed the store.** Harmless locally, a connection
  leaked per run with an `AS400` inside. `ElarRun` closes it in the `finally` on every path, and the
  interface now documents that the executor owns the store for the run. **A `Closeable` in an interface
  is a claim; something has to call it.**

## elarxml IFS content batch 2: IfsContentStore, with JTOpen behind its own seam
* **A seam inside the seam.** All JTOpen contact is behind `IfsContentStore.Ifs` - four methods,
  implemented once in `Jt400Ifs`, no decisions in it. Everything WITH a decision (resolution, when to
  list, staging, the size check, the cap, the lifetime) is in `IfsContentStore` and driven in tests by a
  fake IFS that is a `Map`. **A fake cannot prove JTOpen; it proves everything built on top of it**,
  which is where a mistake would otherwise sit unseen until a field run.
* **The eager-parents plan was simplified while implementing, and the fallback deleted.** The spec had
  the store collect distinct parent directories up front plus a guard falling back to per-file `exists()`
  when parents outnumbered half the rows. Unnecessary: **a directory holding one document returns a
  listing of one entry, which costs exactly what the stat it replaces would have cost.** The shape of
  the feed decides by itself, no threshold has to be guessed, and the row count no longer has to be
  known before the pre-scan. Lazy per-parent listing, cached, plus a direct check for a file that
  appeared after its directory was listed. Measured: 80 docs in 2 dirs = 2 listings, 0 stats; 20 docs in
  20 dirs = 20 listings returning 20 entries total.
* **Staging: one transit per document.** Measured end to end — **30 documents, 30 transits, not 60** —
  and the staging directory clean afterwards. Peak local disk is one document. Buffering to avoid the
  second read would have been the `ByteArrayOutputStream` that caused the OOM.
* **The size guards; the modification time does not, and it is said rather than implied.**
  `lastModified` comes from the listing so it is stable for the run, which makes the embedder's timestamp
  comparison inert over IFS. **Length guards in two independent places**: staging compares downloaded
  bytes against the listing, and the embedder compares encoded bytes against the same figure. A document
  rewritten mid-run is caught by its length before anything reaches a deliverable name.
* **The proof that matters most**: the same 30 documents delivered from local disk and from the IFS store
  give a **byte-for-byte identical INDX**, same SHA-256. Where a document came from must not change what
  is archived, and now that is a measurement rather than an intention.
* **`Jt400Ifs` has never been executed** and will not compile without JTOpen on the classpath. Reviewed,
  not run. Its JTOpen surface is deliberately tiny and **every call in it is already used by
  `IfsSupport` in production** — the strongest evidence available without a machine.

## elarxml IFS content batch 3: the wiring, and two batch-0 predictions that were wrong
* `contentSource` (LOCAL default / IFS), `contentIfsPath`, `contentIfsMaxListing`; `runElarXml` builds an
  `IfsContentStore` over `Jt400Ifs` from the step's datasource; panel subsection; `clientValidate`
  refuses IFS without a datasource.
* **I predicted two changes in batch 0 that were not needed, both in the same direction.** `buildXml`
  already emits `datasource` in the generic run of step attributes beside `script` and `exec`; and
  `WorkflowXmlParser` reads `datasource` unconditionally for EVERY step, not per executor kind. **The
  `reportQuery` comparison was wrong**: that was a new child ELEMENT, which nothing emitted, whereas an
  attribute that already exists for other executors comes for free. The `<param>`-versus-attribute
  distinction was real; the conclusion drawn from it was not, and I drew it without reading the
  emission. **Read the emission before predicting work on it.**
* **A leak the batch 1 seam did not cover**: `ElarRun` closes the store on every path IT REACHES, but it
  validates the config before entering that try/finally, so an exception there returned without
  releasing the connection. `runElarXml` now closes it in a `finally` of its own; `close()` is
  idempotent so the two do not fight. **Second instance of the same lesson: a `Closeable` is a claim,
  and every path out has to honour it** — including the ones that leave before the owner's try.
* **A setting that cannot take effect is refused, not ignored**: `contentIfsPath` under LOCAL fails
  designer validation and is reported in the step log.
* 106 designer assertions (from 94): IFS fields absent under LOCAL and present when switched, datasource
  required, `buildXml` carrying it as an attribute, and a leftover base path refused after switching
  back. 144 store assertions including that closing twice neither logs nor disconnects twice.

## elarxml: the disk guard — stop before the INDX exists, and cut the input where it stopped
* **Asked for because it is not hypothetical**: the output disk fills regularly and the repair is done
  by hand. Filling it mid-INDX is not a clean failure — that batch aborts, but the batches before it are
  on the share while the input still carries its own name, so the next run delivers those documents a
  SECOND time unless somebody splits the CSV first.
* **Under `batchBy=BYTES`, before each new INDX**: free space on the output dir must exceed
  `2 x maxBytesPerBatch + 10%`. **Between batches is the only place the question "which rows are done"
  has one answer** — every INDX opened so far has reached its final name. A check inside a batch would
  answer it holding a half-written file.
* On failure the input becomes three: `.failed` (the original, untouched), `.done_before_failure` (the
  delivered rows, deliberately NOT a `.csv`), `.remaining.csv` (the rest, an ordinary input next run).
* **The invariant is checked, not assumed**: rows(.failed) = rows(.done_before_failure) +
  rows(.remaining.csv), exactly; a mismatch fails with NOTHING renamed. The rename comes last, so a
  failure while writing leaves the original under its own name. The discards file for the delivered part
  IS published — that part really was delivered, unlike an aborted run where it would account for nothing.
* **Re-read, not buffered.** Holding the rows as they went by would be a couple of hundred megabytes for
  a million-row CSV, held for a case that almost never happens, **on the exact path where the machine has
  just run out of resources.**
* Off under DOCUMENTS (no size to reason from) and off when the filesystem returns 0 for free space —
  refusing every run on a share that will not answer is worse than not checking.
* **The free-space figure is injected, and only that.** A disk cannot be filled on demand in a test, and
  a safeguard never seen to fire is not a safeguard; everything built on the figure is exercised for
  real. 29 assertions, the closing one being a cut-short run then a second run over the remainder with
  **20 documents delivered exactly once between the two**.
* **Defect caught by the panel suite, not by review**: my first insertion of the checkbox left an extra
  `</div>`, and the malformed HTML silently truncated everything after it in the panel — the whole
  content-source subsection vanished. Nine assertions fired. An unbalanced tag in a string-built panel
  does not throw; it just eats the rest.

## json2csv executor — Batch 0 (spec only)
* Specification at `.claude/JSON_TO_CSV_EXECUTOR.md`, self-contained. **No code in this commit.**
* **What it does**: reads the JSON files matching a wildcard mask in a directory and writes ONE flat
  CSV whose shape is the feed's **dataschema**, filling its columns from JSON attribute paths chosen
  against a **JSON schema** (an uploaded sample or a real JSON Schema). Nested objects and arrays are
  flattened: the row count follows the cardinality of the innermost **mapped** array, and everything
  outside it repeats on each row it covers.
* **No Gate 0 on Nexus, verified rather than assumed**: `jackson-databind` already arrives with
  `spring-boot-starter-web` and three classes already use `ObjectMapper`. No new dependency.
* **THE CONSTRAINT THAT SHAPES THE DESIGN.** `elar` is Spring-free and compiles with
  `javac --release 8`, which is what has let every suite in that subsystem run the real code in the
  sandbox. **Maven Central is unreachable from here (verified: 403)**, so a core importing Jackson
  could not be compiled here at all and the flattening rules — the easy part to get wrong — would ship
  unexercised. The split is therefore at the only place where it is free: the tree is `Map`/`List`/
  `BigDecimal`/`String`/`Boolean`/`null`, which is exactly what `readValue(f, Object.class)` already
  returns (`readSchemaColumnNames` reads the dataschema this way today). The core is pure over that
  and runs here; **Jackson appears in one class**, `JsonDocumentReader`, which is the only part that
  cannot be exercised in the sandbox and is declared as such.
* **Nothing new is written where something exists.** `CsvWriter` already does UTF-8 / CRLF / RFC-4180
  and split-by-rows / split-by-MB for `sql`, `csvsql`, `xlsx2csv`, so requirement 7 is a wiring job.
  `readSchemaColumnNames` already reads both dataschema dialects, and `columnsSchema` is reused as the
  parameter name because `sql` already calls it that.
* **The date format is a MASK.** `recordBusinessDateFormat` carries `YYYY/MM/DD`, where `DD` is
  day-of-month, and feeding it to `ofPattern` is the defect that silently broke `businessDateNotBefore`
  for the life of the product. Date columns go through `fmtToJavaPattern`, **both directions**;
  reimplementing it here would reintroduce the bug.
* **The flattening rule, stated so it can be argued with**: `S` = the array prefixes of the mapped
  paths *that have a `src`* — an array nobody reads from does not multiply rows, which is requirement
  4's "ovviamente se tali elementi sono mappati". If `S` is a chain, nested iteration; outer values
  repeat because reading at the current index is what repeating means. **Sibling arrays
  (`accounts[]` and `notes[]`) are refused by default** — the product is silent, turning 400 rows into
  4 000 with nothing saying which repetitions are real. `CROSS` is available per step, in the open.
  The check is static, on the mapping, so it fires before a file is opened.
* **An empty array yields ONE row, not none** (`onEmptyArray=ONE_ROW`), recursively at every level: a
  LEFT JOIN, not an INNER one. The default must not lose a document in silence.
* **`ObjectName` is a column TYPE, not a parameter naming a column** — the same dropdown as `Serial`,
  the other column whose value does not come from the JSON. Same mechanism, same place.
* **`Serial` restarts neither per input file nor per split part**: the parts are one delivery, and a
  Serial that restarted would give two rows the same number.
* **`<column>` reused, not a hundred params.** `ColumnSel` gains four optional fields written only when
  non-empty, so an `xlsx2csv` step round-trips byte-identically — **asserted in batch 2 by reading and
  rewriting every workflow XML in the repo and comparing SHA-256**, before anything else is touched.
* **Gate 0, six questions that gate batch 1**, all about the data and none answerable here: largest
  JSON file and count per run (decides tree vs streaming); one file = one document or an array of them;
  whether two independent arrays ever need mapping together; what dates look like *inside* the JSON;
  whether MIMEType wants `.json` or `application/json`, and of which file; whether the dataschema's
  `nullable:false` should be enforced (specified and off).

## json2csv — Batch 0 revised: Gate 0 answered, and it removed more than it added
* Spec updated at `.claude/JSON_TO_CSV_EXECUTOR.md`. **Still no code.** Six questions answered, four of
  them deleting a parameter each: `documentsPath`, `onSiblingArrays`, `onEmptyArray`, `checkNullable`
  / `onNullViolation` / `${nullViolations}`, `COLUMN_EXTENSION`, and epoch-millis from the date
  defaults. A parameter specified for a case that does not exist is a thing to misconfigure.
* **One JSON file is one document is one CSV row.** Each file is a serialised database row. Multi-row
  flattening — one row per array element, outer values repeated — is **deferred**, and `${filesRead}`
  must equal `${rowsWritten}`, said explicitly in the step log because it is the cheapest possible
  assertion that the executor did what it claims and the one number a gate can branch on.
* **The deferred design stays on the page, marked DEFERRED (§6.3–§6.5), not deleted.** The refusal is
  only defensible if what is being refused is written down, and the chain rule is the design for the
  day it returns. Same discipline as a struck-through wrong prediction: the reasoning is the artefact.
* **`[]` is refused at static validation, before a file is opened** — never read as `[0]`, never
  ignored. Reading it as `[0]` would deliver a feed short by every element after the first with
  nothing saying so; ignoring the column would deliver it empty. Both are found in ELAR months later.
  This is found when the step is saved. The catalogue still SHOWS array paths, marked unavailable with
  the reason — hiding them leaves an operator hunting for an attribute plainly in the sample.
* **`conti[0]` — an explicit index — is added, and it is the one place the design goes past Gate 0.**
  A file may carry arrays while one row comes out, and an explicit index reaches into one without
  asking for the deferred half. Flagged in the spec as veto-able at this gate.
* **Three date masks tried in order, and why order is safe HERE**: `YYYY/MM/DD`, `YYYYMMDD`,
  `YYYY-MM-DD` are disjoint by shape — eight digits, or ten with slashes, or ten with dashes — so no
  value parses as two of them. A format list tried in order is a dangerous idea in general
  (`DD/MM/YYYY` then `MM/DD/YYYY` reads 03/04 as two different days and never says so); it is safe
  when the shapes are disjoint. **A fourth mask added later is not automatically safe.**
* **Parsing is STRICT**, on the `uuuu` that `fmtToJavaPattern` already produces: `20260230` is refused
  rather than quietly resolved to the 28th. A Date column exists to validate as much as to reformat,
  and a resolver that repairs impossible dates gives the validation away. A JSON number needs no
  special case: `20260824` is written plain and parses under `YYYYMMDD`.
* **`maxFileMB` drops 64 -> 16**, deliberately close to reality. A guard set far above anything real
  cannot catch a whole export dropped into the input directory by mistake, which is most of what it
  is for.
* **`renameProcessed` can only ever fire at the end here**, unlike elarxml where it is per file as
  soon as that file's batches are named: every input feeds one output, so nothing is processed until
  the step is. Renaming earlier would be the elarxml `.done` defect reintroduced from the other side.

## json2csv — Batch 1 delivered: the core, Spring-free and Jackson-free
* Eleven classes in `com.legalarchive.orchestrator.json2csv`. **203 assertions, all green.** Nothing
  outside the new package is touched, so this batch cannot change any existing feed: there is no call
  site yet. The executor is batch 2.
* **`javac --release 8` here is STRONGER than the project's own build**, which sets
  `maven.compiler.source/target 1.8` — the form that does NOT check the API surface. Verified on the
  day rather than assumed: `List.of` fails to compile under the flag. `mvn clean package` remains the
  only final proof and Maven Central is unreachable from the sandbox.
* **ABSENT vs MISMATCH is the distinction the core turns on**, and implementing §6.6 made the rule
  wider than the spec had it. ABSENT = a missing key, an index past the end, an explicit null: the
  document does not have it, so empty and counted. MISMATCH = a key applied to a non-object, an index
  applied to a non-array, or a leaf that is an object or an array: **the document is not shaped the
  way the path assumes**, so `onNonScalar`, default FAIL. Folding them is the expensive mistake — a
  mapping typo would deliver an empty column for the whole feed and look exactly like a customer with
  no value. The mutation that folds them is caught by thirteen assertions.
* **The disjointness of the three date masks is MEASURED, not argued.** Every day of a full year in
  all three forms, 1 095 strings, each asserted to parse under exactly one mask. Overlaps: zero. That
  test is the guard on the warning that a fourth mask is not automatically safe — add one and it says
  at once whether it overlaps. STRICT is exercised for real: `2026-02-30`, `2026-13-01`, `20260230`
  and `2026-02-29` refused, `2024-02-29` accepted.
* **The mask translator is EXTRACTED from `InternalSteps.java` at build time, not retyped.** The suite
  lifts `fmtToJavaPattern` and `JT_PASSTHROUGH` verbatim, so "`YYYYMMDD` becomes `uuuuMMdd`" is a fact
  about the shipped translator and not about a copy that could drift. The core reaches it through a
  one-method `MaskTranslator` seam because `InternalSteps` is Spring-coupled and this package must
  compile with the JDK alone.
* **Eight mutations of the real source, all caught by named assertions**: the `[]` refusal removed;
  `[]` resolved as `[0]`; SMART instead of STRICT; Serial restarting per row; MISMATCH folded into
  ABSENT; Number through `double`; the output-mask probe removed; `describe()` leaking the value.
* **The first mutation run found a defect in the SUITE, not the core**: it died on `probs.get(0)` of
  an empty list and stopped reporting everything after — the opposite of what `MappingValidator` does
  on purpose. The runner now turns an unexpected throw into a failure, and that mutation is caught by
  eight assertions instead of a stack trace.
* **`onNonScalar` is now a slightly wrong name** for what it does — it covers "the value cannot be
  used as this column's type", including a number that will not parse. Kept because it is in the
  committed spec; the designer label in batch 3 will say what it means rather than repeat what it is
  called.
* The suite is NOT committed, as the `elar` suites are not. It can be, with a runner, on request.

## json2csv — Batch 2 delivered: the executor, and three things the spec got wrong
* `json2csv` registered in all six places and running. **267 assertions green** across two suites, plus
  the no-op proof and a compile check of the assembly code. Batch 3 is the mapper panel; until then a
  step is configured with `+ param` and hand-written `<column>` entries, as `elarxml` was.
* **THE NO-OP PROOF AS SPECIFIED PROVED NOTHING.** Batch 0 promised "every workflow XML in the repo
  read, written back and compared by SHA-256". Run, it returned the SHA of the empty string for every
  file: **no workflow in this repo contains a single `<column>` element**, so the test could not have
  failed. And comparing writer output to the hand-written original was never going to work either —
  the writer re-indents through a `Transformer`. What proves the claim is **pre-batch output against
  post-batch output on a workflow that uses the feature**: the real parser and writer compiled from a
  clean clone and from the patched tree, run over the 15 repo workflows *and* a synthetic `xlsx2csv`
  step with four `<column>` elements. Identical both ways. The same harness shows `json2csv` refused
  by the pre-batch parser and accepted by the patched one.
* **`JsonDocumentReader` could NOT live in the json2csv package**, where §1 put it. Maven Central is
  unreachable here, so one Jackson import would have made the package uncompilable in the sandbox —
  costing twelve classes their test bench for the sake of one. It moved to `engine`. It also carries
  its **own** `ObjectMapper`: `InternalSteps.jsonMapper` has four other call sites, and enabling
  `USE_BIG_DECIMAL_FOR_FLOATS` on the shared instance would silently change how each reads a number.
* **"Case-insensitive on Windows" was wrong.** It would make the same workflow select a different set
  of files on a developer's machine and on the server. `FileMask` is case-sensitive everywhere, which
  is also what `elarcheck` already does.
* **The run loop moved out of `InternalSteps`**, which is Spring-coupled and therefore untestable
  here. Reading and writing became seams — `DocumentReader` is Jackson in production and hand-built
  trees in the suite, `RowSink` is `CsvWriter` in production and a list in the suite — so the order
  files are visited, what the counters do when one fails, and when the rename may happen are all
  tested. `InternalSteps` keeps only the assembly.
* **`renameProcessed` is deliberately NOT an option on `Json2CsvRun`.** The rename belongs to the
  caller, after it closes the sink; a flag on the run object could not be honoured without renaming
  too early. A setting that cannot take effect is worse than one that does not exist — the same rule
  that refuses a fixed `value` on a `SOURCE_EXTENSION` column.
* **`FileMask` is a second implementation of something `elarcheck` has**, because json2csv must not
  depend on another executor's package. Two implementations that quietly disagree are worse than one,
  so the suite **lifts elarcheck's matcher from its source at build time** and compares them over 90
  name-and-pattern combinations. Zero disagreements — and making `FileMask` case-insensitive is caught
  by that comparison as well as by its own assertions.
* **The 183 new lines in `InternalSteps` were compiled, not just written.** They are lifted verbatim
  at build time and compiled against the real `StepDef`, `VarResolver`, `StepExecutor.Result`,
  `CsvWriter` and json2csv classes, all Spring-free; only `JsonDocumentReader` is stubbed. Syntax and
  types checked, behaviour not.
* **`ColumnSel`'s four new fields are written only when non-empty**, so an `xlsx2csv` `<column>`
  serialises exactly as before — proved, not assumed, by the comparison above.
* Six mutations, all caught: unsorted listing; rename inside the loop; a failed file counted as read;
  a skipped file marked processed; a case-insensitive mask; `?` matching nothing.
* NOT verified: `mvn clean package`; the executor against real files; the designer changes rendered
  (checked only for the two UBS rules); and `ApiController.toDto`, the one edit no harness reaches.

## json2csv — Batch 3 delivered: the mapper, and a panel that is RUN rather than inspected
* **104 assertions green** in two new suites (56 catalogue, 48 panel) on top of the 267 from batches 1
  and 2. Eight further mutations, all caught. **This feed is Transarch, not ELAR** — corrected in the
  spec and in `MappingValidator`; the executor is unaffected.
* **Three answers closed WITHOUT touching code**: RFC-4180 quoting is what `CsvWriter` already does;
  `ObjectName` already writes the file name; `recordBusinessDateFormat` is already the output mask.
  The real sample changed the CATALOGUE, not the executor.
* **KEYS CONTAIN DOTS.** `VM.CAP.DATE.CHARGE`, `VM.ALT.ACCT.TYPE` are single keys with dots inside,
  not nesting. Emitted bare, `VM.CAP.DATE.CHARGE` parses as **four nested keys and resolves to
  nothing** — on a path the dropdown itself handed over. The catalogue emits `['VM.CAP.DATE.CHARGE']`,
  and the suite asserts both halves: that the quoted form is produced, and that the bare form parses
  to four segments and resolves to nothing.
* **Arrays hold exactly one object**, so the catalogue lists TWO things per array: the unbounded `[]`,
  shown and **disabled** with its reason, and the first element's members under `[0]`, selectable.
  Listing only `[]` would leave those values unreachable; offering `[0]` as if it were the whole array
  is the mistake the refusal exists to prevent. This is what `[0]` was added for at Gate 0.
* **The dataschema and the JSON share one vocabulary** over ~100 columns, hence "map by exact name"
  and the declared type preselecting Number. Both are suggestions and stay editable, and
  **map-by-name never overwrites a column already mapped**: the ones a person set deliberately are
  exactly the ones a bulk action must not touch. Matching is exact and case-sensitive, because JSON
  keys are and because a near-match offered as a match would be accepted without being read.
* **`seenIn` against `scanned` is shown on every path.** Seen in 3 of 20 is not the same as seen in 20
  of 20, and only the person mapping the column can say which is expected. The catalogue is held per
  node in the browser and **never in the workflow**: it is a picture of what some sample files
  happened to contain, and persisting it would let a stale snapshot decide a mapping months later.
* **THE PANEL IS EXECUTED, NOT INSPECTED.** `tests/panel.js` extracts the branch from `designer.html`,
  wraps it in its helpers and runs it to produce real HTML — the technique that verified the
  `USAGE.md` renderer. It asserts tag balance, that unavailable paths render disabled rather than
  hidden, that a quoted dotted path round-trips verbatim through the attribute, and that **every
  handler the panel emits names a function that exists**. A regex would not have caught the mutation
  that matters: an extra `</div>`, the panel defect recorded above, caught here by four assertions.
* **An assertion of mine was wrong, not the code.** X8 claimed apostrophes in a path must be escaped
  inside an attribute. They need not be — inside a double-quoted attribute an apostrophe is legal HTML
  and the value round-trips verbatim. Replaced with the assertion that matters, plus a new one proving
  a DOUBLE quote *is* escaped, since that one would end the attribute.
* **TWO MUTATIONS CAME BACK GREEN AND NEITHER WAS A PASS**: one `sed` matched nothing, one had shell
  quoting that never applied the edit. Rewritten in Python with an anchor assertion, both are caught —
  the first by seven assertions. **A mutation that stays green is a claim about the mutation before it
  is a claim about the suite**, and the only way to tell is to look.
* NOT verified: `mvn clean package`; the executor on real files; the panel's `fetch` calls (rendering
  is exercised, the network is not); the two endpoints themselves, which are Spring controllers — the
  logic they wrap is tested, the wiring is not; and the real `dataschema.json`, so the exact-match
  test is a shape test, not a data test.

## json2csv — Batch 4 delivered: USAGE.md, rendered rather than proof-read
* `USAGE.md` gains a `json2csv` section and an executor-list entry. **23 assertions green**, total
  across four batches **394**. The feature is complete as specified; the one thing outstanding is a
  run on real files, which the sandbox cannot do.
* **`docs.html` turns EVERY SOURCE LINE into its own paragraph** — no soft-wrap merging. A paragraph
  wrapped at 100 columns renders as five `<p>` blocks: invisible in an editor, obvious on the page.
  So `tests/docs.js` extracts `render()` from `docs.html` at build time and runs it over the real
  `USAGE.md`, asserting no Markdown leaks outside code blocks and **no paragraph in the added section
  is a wrapped continuation of the one above it**.
* **89 wrapped paragraphs already exist elsewhere in `USAGE.md`.** Reported by the harness and **not
  fixed**: rewrapping 700 lines of prose inside a patch about an executor is the unrelated churn that
  makes a diff unreviewable. The assertion is scoped to what this batch added, plus one confirming the
  batch did not make the count worse. Same pattern as the duplicate `CLAUDE.md` lines left alone.
* **A GREEN MUTATION FOUND A REAL GAP, for the second time in this feature.** Two `USAGE.md` mutations
  came back green; both were opened up rather than filed. One was a bad mutation (`###` to `####` is
  not a defect). The other was a genuine hole: the wrap check keyed on what the NEXT line started
  with and skipped it when that was `-`, reading it as a new bullet — so a paragraph split whose
  continuation happened to begin with a dash slipped past both the source and the rendered check.
  **The check now keys on the line that ENDS**: a source line that does not end a sentence, followed
  by more text, is a wrapped paragraph whatever the continuation starts with. Realistic wraps of a
  paragraph and of a bullet are each caught by three assertions.
* NOT verified, and it is the same gap as every batch: `mvn clean package`, and a run against real
  files. Nothing in four batches has been through Spring, a WAR, Tomcat, or one real document. On the
  first run, in order: that the log prints `one row per file (filesRead = rowsWritten = N)`; that
  `${valuesMissing}` is not a multiple of the row count, which would mean a path mapped one level off;
  and that the CSV header is the dataschema in dataschema order, including the unmapped columns.

## elarxml: per-document trace, and in-flight files that no permissive pattern can match
* **`logDocuments`, on by default**: one line per document with the INDX it went into, the id from
  `input.doc_id_reference` and the content file name; then a total per INDX with the PULL it is paired
  with. **ELAR validates an INDX in full and rejects it in full**, so the first question after a
  rejection is always which documents were in that file — and answering it used to mean reopening and
  parsing it.
* **Identifiers only, never a field value.** Same line the pre-scan and the findings file already draw:
  a document id and a file name identify a DOCUMENT, the metadata beside them identifies a CUSTOMER.
  200k documents = 200k lines, so the switch exists.
* **The in-flight name changes its TOKEN, not just its suffix**: `x.INDX.C152100` is written as
  `x.I_PART.C152100.part`, PULL likewise `P_PART`. **`.part` alone was not enough** — everything
  downstream matches on the token, not the extension: the reference PowerShell scripts default to
  `*INDX*` and elarcheck's `filePattern` does too, so a half-written `x.INDX.C152100.part` matched both.
  Replacing the token is a stronger guarantee than asking every consumer to be careful.
* The **last** occurrence is replaced, so a family whose own name contains the token keeps it; a name
  with no token still gets the suffix — the protection degrades rather than disappearing.
* **The naming test watches the output directory WHILE the run is in progress** and checks every file
  seen in flight against `*INDX*` and `*PULL*`. Asserting the final names only would have proved
  nothing about the window this change exists to close. 33 assertions.

## Designer: a select that stored the value without redrawing, and the test that hid it
* **The elarxml content-source select called `setNodeParam` alone.** The panel shows different fields
  for LOCAL and IFS, so the page showed **IFS selected with the LOCAL fields still under it** — no base
  path, no listing cap, no datasource selector. The step could not be configured for IFS through the
  interface at all. The pattern was already in the file: `diffSetMode` and `ifsSetListSource` both set
  the param and then call `renderNodes()`. Now `elarSetContentSource`.
* **The jsdom suite hid it, and that is the more useful half.** It did `setNodeParam(...)` then
  `renderNodes()` — **doing by hand the step the page never did** — so every assertion about the IFS
  fields passed. **A test that supplies the missing step cannot fail on it.**
* The suite now finds the select in the rendered panel, asserts its handler is NOT a bare
  `setNodeParam`, and **executes the `onchange` attribute** as a browser would — jsdom does not run
  inline handlers, so dispatching an event does nothing and the attribute has to be evaluated. Then it
  asserts the panel actually changed. 6 assertions fail against the pre-fix template.
* **Rule: drive a control through the handler the page wires to it, never around it.** Any step the
  harness performs on the page's behalf is a step the page is no longer tested for. Worth checking the
  other conditional panels the same way.

## Designer: the same defect in `batchBy`, and an advisory scan for the class
* **Audited the other conditional panels after the contentSource defect, with a scan rather than by
  eye.** Thirteen hits, twelve of them fine: text inputs with `oninput` **cannot** redraw — doing it on
  every keystroke takes the focus away mid-word, so there the redraw would BE the defect — and most
  branches only set a `checked` or `selected` attribute, already correct when the value is written.
* **One real case, milder than the first: `batchBy`.** It hides nothing (`maxBytesPerBatch` and
  `oversizeDocumentPolicy` stay visible on purpose) but it decides whether three labels carry
  "NOT in effect", so switching to BYTES left three fields wrongly labelled until something else
  redrew. Same class: **a control that changes what the panel SAYS must not leave the old wording under
  the new choice.** Now `elarSetBatchBy`.
* **`tools/scan_panel_redraw.js`, ADVISORY, exit code always 0.** Deliberate: the first version flagged
  13 of which 12 were fine, and **a check that cries wolf twelve times out of thirteen is switched off
  within a week, at which point it protects nothing.** It stays diagnostic until calibrated against
  panels written after it. Calibrated: 13 -> 0 with the two real defects fixed.
* **The scan's own first run was a reassuring lie**: parameter names sit inside ESCAPED quotes in the
  emitted strings (`setNodeParam(' + i + ',\'x\',...)`), so matching plain quotes found 3 parameters
  instead of 118 and reported all-clear. **A scan that finds nothing must be checked for whether it can
  find anything.**
* **Proved it bites**: each defect re-introduced one at a time (helper deleted, handler reverted) and the
  scan reports that one and only that one. The panel suite covers `batchBy` through its own handler and
  fails 2 assertions against the reverted template.

## elarxml IFS: the first real feed inverted the lookup default
* **Measured: `/Proteo/DOC/PDF` and `/Proteo/DOC/TIFF` hold THREE MILLION files between them**, and a run
  references a few thousand. The listing design would have transferred 3M entries to reach a few
  thousand, held ~300 MB doing it, and tripped the 500000 cap on the first real run.
* **§4 reasoned from the wrong axis.** It asked how many DIRECTORIES the paths spread over and concluded
  that few directories meant few round trips — true and irrelevant, because **the cost of a listing is
  not the round trip, it is everything in the directory**. The axis that matters is the RATIO: how much
  of a directory a feed actually uses. STAT is bounded by the FEED; LISTING by the STORE behind it.
* `contentIfsLookup`: **STAT is now the default** (one round trip and one cached entry per referenced
  document — right whenever a feed touches a small fraction of a large store, which is what a document
  archive is); LISTING stays for the opposite shape, a small directory used densely.
* **Changing this default is NOT a conservative-defaults exception**, and the distinction is worth
  keeping: IFS reading has never run in the field, so no delivered feed changes behaviour. **A default
  that fails on the only real feed we have is simply the wrong way round.**
* **The cap message now names the strategy**, because the same number means two different things: under
  LISTING it counts every file in the store and points at STAT; under STAT it counts the feed's own
  documents, so it means the feed is bigger than expected.
* 162 assertions (from 144). The decisive one reproduces the reported shape and asserts 0 listings / 6
  stats by default against 1 listing pulling 3000 entries to reach one. End to end, both strategies
  deliver a **byte-identical INDX**.

## elarxml: the drift check was blind, and the staging file reused one name
* **The drift warning fired on EVERY document, at a fixed ratio of 1.330 — 4/3, the Base64 expansion.**
  Three quantities, only two comparable: the estimate is ENCODED, `writeDocument` returned the RAW
  payload, and what the INDX actually grew by was never measured. **A warning that always fires reports
  nothing** — and this is the one that exists to police `PER_DOCUMENT_OVERHEAD`, the only number in the
  executor not derived from something else. Blind since it was written.
* **CORRECTION, on the record**: I first told the field that the BUDGET counted raw bytes, so a 1.9 GB
  budget would produce ~2.5 GB INDX files. **That was wrong.** `policy.appended(estimate)` accumulates
  the encoded estimate; the budget was in the right units and the files were the right size. I drew the
  conclusion from the warning instead of reading the line that accumulates. **Second time this session**
  — after `buildXml` — that a plausible inference replaced reading the deciding line.
* Fix: the batch measures its own growth with `charsWritten()` before and after, and that feeds both the
  budget and the check. Measured, not derived, so it survives a formatting change. `bytesEmbedded` keeps
  the raw payload, which is what it means. Verified on the real log's sizes: **5 warnings of 5 before,
  0 of 5 after, delivered INDX byte-identical** — what is written did not change, only what is compared.
* **`Access is denied` after 27 668 documents and 9.3 GB, disk not full (confirmed).** Every document
  staged to the SAME path, delete-then-create. On Windows that is the shape that races a virus scanner
  or the indexer still holding the previous file — not a permissions problem, which is why it took tens
  of thousands of iterations to show. Now a unique name per document, 3 create attempts with back-off,
  and a once-per-run sweep because unique names never overwrite leftovers. **The lock itself cannot be
  reproduced here** — it needs Windows and a scanner — so this is a mechanism removed, not a cure shown.
* **The elarcheck panel not opening could NOT be reproduced**: four-step workflow with elarxml in IFS,
  last card's `.nc-body` present with 26 fields, div-balanced, inside its own card, CSS and `toggleNode`
  both correct. Three hypotheses ruled out by measurement, none left. **The panel suite was weak by
  construction** — it asserted on `innerHTML` as a string, which passes on malformed HTML — and now
  counts elements INSIDE the card.

## elarxml: deleting the staged copy, at the moment the INDX is delivered
* **`deleteContentAfterEmbed`, OFF by default**: each embedded document is removed from the family's
  `documentPath` once the INDX carrying it has reached its final name. The staged copies are what an
  `ifscopy` step put there and the archive still holds the originals; the first real run left **9.3 GB**
  of them behind after the feed had gone out.
* **Both questions were asked, not inferred.** No two rows reference the same content file (so a
  per-batch delete needs no seen-set), and that directory holds **only copies** (so deleting is safe
  there and nowhere else).
* **The moment is the whole design.** `writeDocument` writes into a batch under the in-flight
  `I_PART`/`P_PART` names; the final name arrives only in `Batch.close`. Three paths discard everything
  in between - an exception aborts, the disk guard cuts the input into `.remaining.csv`, an oversize
  document rolls the batch - so deletion is anchored to `closeBatch`, **the same event as the `.done`
  rename and the `.skipped` commit**. Deleting at write time would look identical on a good run and
  leave `.remaining.csv` pointing at files that no longer exist, which the default `onMissingFile=SKIP`
  would then drop **without failing the run**.
* **`DeletableContentStore` is a separate interface, implemented only by `LocalContentStore`.** The ban
  on deleting over IFS is in the TYPE: there is no method to call, so no later edit can move or invert a
  condition and reach the archive. `IFS` + the flag is **refused** (`exitCode=2`) rather than warned
  about - unlike `contentIfsPath` under LOCAL, which is merely inert. An operator who turns this on to
  reclaim disk and gets silence is told nothing about the one thing they wanted.
* A delete that fails is counted (`documentsDeleteFailed`), named in the log and **never fatal**: the
  INDX is already delivered. One line per batch, ten names at most. `summary()` mentions deletion only
  when the option is on, so the line that says a file was removed is not buried under a permanent
  `deleted 0`.
* **Chosen, not derived**: an INDX that commits while its PULL cannot be published deletes **nothing**,
  including the documents already inside the delivered INDX. The staging stays full rather than being
  reclaimed against half a pair.
* **32 assertions standalone (INDX byte-identical with and without the option), 11 in jsdom.** Two
  mutations fail the Java suite, two fail the panel suite.
* **Two of my own scenarios were blind and are recorded as such.** One forced a mid-batch failure by
  replacing a content file with a directory - the **pre-scan** catches that, so no batch was ever open
  and a disjunctive assertion passed on nothing. The other occupied the second INDX name - `AtomicOutput`
  refuses an existing final name in its **constructor**, so those documents were never written. What
  discriminates is the **PULL**, built inside `Batch.close` after the INDX has been committed. **Stated
  plainly: the disk-guard scenario cannot tell the two implementations apart**, because the guard only
  fires between batches, when nothing is in flight.

## tiffcompress batch 1: the scanner, and a scan that could not see the panel it was pointed at
* **The measurement comes before the compressor, because the measurement decides what the compressor
  is.** Bilevel and uncompressed means CCITT T.6 and a Nexus dependency; colour means T.6 cannot
  apply at all and Deflate from `java.util.zip` can, with no dependency; mostly-already-G4 means there
  is nothing to build. Writing the compressor first would have meant choosing one of the three blind.
* **Two packages, one executor.** `orchestrator.tiff` has no write API and `tools/scan_tiff_readonly.js`
  asserts it, keeping the elarcheck property that makes a scan safe against a live directory.
  Everything that writes will live in `orchestrator.tiffpack`. The report is rendered by the package
  and **written by `InternalSteps`**, which is how elarcheck keeps the same property.
* **Reported by count AND by bytes, always.** A million small G4 files and ten thousand large
  uncompressed ones read as 99% compressed by count and as the opposite by bytes, and the byte column
  is the one that decides.
* **The sample is the deliverable.** `RESERVOIR` by default: one lazy pass, unbiased, and it opens no
  more files than taking the first N does. `DIRECTORY` samples a **prefix of the enumeration**, which
  is a corner of the store; its only advantage is that the walk stops early.
* **Measured, against a proxy that lied.** The first bias test assumed the walk would hand back the
  first-named files first. `Files.newDirectoryStream` on ext4 returns **hash order** - 15 of the first
  100, not 100 - so the scenario passed for the wrong reason and proved nothing. Node's `readdir` had
  said name order because **libuv sorts with `alphasort` and Java does not**. A proxy measurement
  instead of the deciding one, caught by the test rather than by production. The suite now demonstrates
  the bias through the walk's own FIFO ordering, which this code owns.
* **`scan_panel_redraw.js` is now calibrated against a panel written after it — item 5 — and the
  calibration found two defects in the SCAN, not in the panel.** It recognised only
  `var x = nodeParam(n,'p') ===` and not the `|| 'DEFAULT'` idiom, so it never saw the new controls at
  all; and it recognised a redraw only inside a named helper, not one written into the attribute. A
  mutation removing a redraw was caught by the jsdom suite and **not** by the scan. Both extended;
  the inline pattern had to be bounded to its own attribute, because unbounded it ran forward and
  credited `directory` and `maxFilesScanned` with a redraw they do not have.
* **Green mutations investigated, twice usefully.** Removing IFD-loop detection changed nothing because
  the page cap also returns `IFD_LOOP` - the test now asserts *which* guard fired. Making
  `outcomesSumToFilesOpened()` return `true` broke nothing because no test ever built a state where it
  fails - it does now. And no fixture exercised the out-of-line tag branch, which is where a real RGB
  page keeps `BitsPerSample`; a reader that took the offset for a value read **86** as a bit depth.

## elarxml log document index: which INDX carried which document, from the history
* **`tools/Get-ElarDocumentIndex.ps1`**, read-only, no build and no deploy: it reads every elarxml
  step log of the named workflows and writes one `;`-separated CSV of `indx_file;original_file`.
  Spec and decisions in `.claude/ELAR_LOG_DOCUMENT_INDEX.md`.
* **PowerShell rather than an internal executor, deliberately.** It is a cross-feed analysis of
  history, not a step in a pipeline; the question it answers is raised BY an ELAR rejection and has
  to be answerable the same day, not after a WAR and a Tomcat restart; and **indexing step logs was
  already considered and rejected for the product** when the log report was built - they are one to
  three orders of magnitude bigger than the audit trail. An executor would also add a run, and a log,
  to the history it reads.
* **The trace line is written BEFORE the document, and the INDX only gets its deliverable name in
  `Batch.close`.** So an aborted batch leaves trace lines naming a file that was never delivered, and
  the obvious implementation - collect every `<- id=` line - produces a CSV **asserting deliveries
  that did not happen**. A pair is emitted only when the log also shows that INDX reaching its final
  name, through `delivered with N document(s)` or the ungated `wrote X (N document(s))`; the two are
  both accepted because their blind spots differ (the first is absent with `logDocuments` off, the
  second when the run failed before its summary). Traces without a delivery are counted and reported,
  never emitted and never dropped, and **the traced count is reconciled against the declared one**.
* **What the logs cannot give is stated, not discovered later**: runs before `7fa7298` (2026-08-24)
  and runs with `logDocuments=false` carry the delivery lines but no per-document trace, so the tool
  reports those INDX files as unmapped instead of letting them look empty; and `file=` is the bare
  name for BOTH stores, so even under `contentSource=IFS` the full path is not in the log and no tool
  can recover it from history.
* **Test runs are INCLUDED by default** (a `_test_` run that wrote a real INDX delivered it, and a
  missing row is worse than an extra one) and **duplicates are not removed** - the same file in two
  INDX files is the shape a re-run without `output.start_time` produces, both rows are true, and the
  count is reported because in an archive that is a finding.
* **The fixture logs are written by the REAL `ElarRun`**, compiled standalone with `--release 8`, so
  the parser is tested against the emitter rather than a transcription: five runs including one whose
  third batch is traced and then aborted by an unencodable value, one with `logDocuments` off, and one
  carrying a document id that itself contains `" file="`. **61 assertions, 11 mutations all caught.**
* **A green mutation was opened and was a bad one** - the `EndsWith(" document(s))")` guard is
  covered by the `LastIndexOf(" (")` beside it - but reading that line to find out turned up a real
  off-by-one next to it: the count substring was one character too wide and only parsed because
  `int.TryParse` ignores a trailing space. **And an assertion of mine could not fail**: on Linux
  .NET's current directory follows the process, so a relative output path resolves correctly with or
  without the fix. The real function is now lifted out of the script and run against a deliberately
  diverging `[System.IO.Directory]::SetCurrentDirectory`, which is the Windows behaviour by hand.
* **NOT verified: Windows PowerShell 5.1.** The sandbox has PowerShell 7 for Linux. The dialect is
  asserted by six scans (no `??`, ternary, `&&`/`||`, `-Parallel`, three-argument `Join-Path`,
  literal backslash-n), each with a positive control proving the scan can fire - a syntax argument,
  not a run. Nor has it seen a real log: the first run on the real history is also the measurement of
  how much of that history predates the trace.

## Feed schema index — Batch 0 (spec only)
* Spec at `.claude/FEED_SCHEMA_INDEX.md`, self-contained. **No code in this commit.** Two deliverables:
  `tools/Get-FeedSchemaIndex.ps1` writing one CSV census of every feed's record layout, and
  `feed_index_viewer.html` at the repository root navigating it as a SOURCE -> feed -> fields tree.
* **PowerShell, not an internal executor** - the choice was left open and it goes the way
  `Get-ElarDocumentIndex.ps1` went, for the same reasons plus one that is stronger here: an executor
  means new code inside `InternalSteps`, on the deploy path of 144 live feeds, in exchange for a
  report. **What would reverse the decision is written down**: if the index has to be visible inside
  the application, the reader belongs in Java behind an endpoint and the viewer becomes a template.
  That is a different feature, and saying so now makes it a decision later instead of a discovery.
* **ONE ROW PER FIELD.** Fields-as-columns would make the header the union of every field name in the
  estate; fields packed into one cell cannot be filtered, which is the only reason to write a CSV.
  Per field: source/target ids and descriptions, feed identity, the dataschema position/type/nullable,
  and the displayschema `DisplayName`/`DataType`/`DisplaySequenceNr`/`Viewable`/`anonType`.
  `field_seq` is the DATASCHEMA order - that order is the record layout, and `DisplaySequenceNr` is a
  presentation choice carried in its own column rather than allowed to reorder the file.
* **The displayschema join must be the PRODUCT's join, and it is a second implementation of one that
  already exists in Java.** Exact, case-sensitive, trimmed, with the `name`/`ColumnName`/`COLUMN_NAME`
  and `DisplayName`/`displayName`/`display_name` alias chains and both container dialects, copied from
  `ApiController.displayNameMap` and `InternalSteps.readSchemaColumnNames`. The suite **lifts those
  alias lists out of the Java at build time** and asserts agreement - the technique that kept json2csv's
  `FileMask` honest against elarcheck's matcher. A case-insensitive join was rejected: it would report a
  description the application does not use.
* **A displayschema entry naming a column the dataschema lacks is an ORPHAN**, emitted with
  `in_dataschema=no` and counted, never dropped: a description pointing at a column that does not exist
  is the defect an index exists to surface. Same for a feed with a dataschema and no displayschema - it
  is IN, with empty description columns, because a feed nobody has described is a finding.
* **Two schemas for one feed are reported, never merged.** The feed root wins over a step's
  `dataschema`/`displayschema`/`columnsSchema` param; a conflict names both paths in the summary. A
  schema path still carrying an unresolved `${...}` is reported as unresolved rather than read as a
  literal - there is no run to resolve it against.
* **Workflow variables as columns (`name:description`) are RESOLVED** — Gate 0 answered, reversing this
  spec's own recommendation of the raw declaration. Resolved against the map **Operations already uses
  for feed tags** (globals + `feedId`/`parentId`/`sourceId`/`targetId` + the workflow's `<variables>`),
  innermost-first, so the index and the Operations grid cannot disagree about the same feed. Header is
  the description, `-VariableHeaders Name` flips it.
* **The divergence that makes resolving safe: an unresolvable token STAYS a token.**
  `VarResolver.resolve` gives an unknown name the empty string — right at runtime, wrong here, where
  `${runDate}` and `${extract.rowCount}` do not exist and an empty cell would be indistinguishable from
  *this feed does not define the variable*. The cell keeps the literal `${name}` instead, so it either
  carries a design-time truth or visible evidence that it is computed at run time, and never a lie;
  `variables_unresolved` counts it. `${list[N]}` and `${COL@key}` are left literal — they only mean
  something against a run's published lists. **The RAW recommendation is left standing in the spec
  rather than deleted**: the token rule only reads as a decision beside what it overruled.
* Cost stated: a **third** implementation of `${}` resolution after `VarResolver` and the Operations tag
  map, contained by scope (`${name}` with nesting and a depth cap) and by a suite that lifts
  `VarResolver`'s patterns from the Java at build time and asserts agreement — same technique as the
  displayschema aliases. And **global vars become an INPUT** (`-GlobalVarsFile`): unreadable, every
  `${someGlobal}` degrades to a token, so the summary reports the count loaded and says `0` explicitly.
* Viewer: **zero external references, asserted** - the three viewers it is modelled on load PapaParse
  and Chart.js from a CDN and this one reuses `csv-viewer.html`'s own parser. Leaf is a TABLE, not more
  tree rows (a record layout is a table, and a `+` in front of 15 000 childless items is noise); search
  runs on the model so it matches inside feeds never drawn; `+`/`-` in a bordered box, prev/next match
  with an `n of m` counter, and per-node counts that show `n of m` under a filter so a node showing 3 of
  97 fields cannot look like a feed with 3 fields.
* **NOT verifiable here and said now**: Windows PowerShell 5.1 (the sandbox has PS7 for Linux; the
  dialect is six scans with positive controls, a syntax argument and not a run), and the real estate -
  no real workflow XML or `dataschema.json` is available, so the first run on the real directory is also
  the first measurement of the conflict and orphan counters. `mvn clean package` is untouched: neither
  deliverable is in the WAR, which is the point of the executor decision.

## Feed schema index — Batch 1 delivered: `tools/Get-FeedSchemaIndex.ps1`
* One CSV census of the record layout of every feed, one row per FIELD, joining the workflow XML, the
  `dataschema.json` and the `displayschema.json`. Read-only: nothing is written but the output, and
  every input is opened `FileShare.ReadWrite` so the tool cannot block a feed that is running while it
  reads. **150 assertions, 14 mutations all caught**; the suite is not committed, as the `elar` suites
  are not.
* **`$root.Name` on `<workflow name="Feed A">` returns `Feed A`.** PowerShell's XmlNode adapter exposes
  attributes and child elements AS properties, shadowing the node's own. Every workflow in the fixture
  was rejected as "expected root element <workflow>, found <Feed A>" and the index came out EMPTY,
  behind a message that read like a malformed file. Now through the .NET getters (`get_Name`,
  `get_Attributes`, `get_Value`, `get_InnerText`, `get_ParentNode`), which a document's own content
  cannot shadow. **Remember before the next PowerShell tool touches XML.**
* **The first real run found a usage defect, not a logic one.** Under `pwsh -File` every argument is a
  plain string and a comma is NOT an array separator, so `-Variables 'a:x','b:y','c:z'` collapsed into
  one column whose header was the rest of the command line — silent, the only symptom being a header
  nobody reads twice. `Split-VariableSpecs` splits such an entry **only when the split is
  unambiguous**: every part must look like a variable name and the parts must AGREE about carrying a
  description, so `total:somme, moyennes` is left alone. Splitting that would be the quiet corruption
  the rule exists to prevent; both directions are mutations and both are caught.
* **`display_nullable` beside `field_nullable`, and `nullable_disagreements`.** The mandatory-field
  rule is `InternalSteps`' with its asymmetry intact — mandatory ONLY for the boolean `false` or the
  string `"false"`, so absent, `0` and a typo all mean nullable. The displayschema carries its own
  `Nullable` and **the two disagree on 3 of 10 columns in this repo's OWN shipped samples**, so both
  are written and the disagreement is counted. The dataschema is the authority because it is what the
  `validate` step acts on; reporting one column alone would give a clean answer to a question the files
  do not agree about. An absent attribute is `''`, not `true`.
* **Two harness defects the run exposed, both of the class that reports success.** My fixture used
  `param([string]$DataJson)`, and `[string]$null` is `''`, so "this feed has no displayschema" wrote an
  EMPTY one — the estate a suite builds is part of the suite. And the alias-agreement lift matched the
  FIRST occurrence of the chain shape in `InternalSteps`, which is the mask executor's ending in
  `DisplayName`, not `readSchemaColumnNames`' ending in `COLUMN_NAME`: it was comparing the reader
  against the wrong Java and reporting agreement. Anchored on the method now.
* **`ConvertFrom-Json` refuses an object whose keys differ only by case**, identically on 5.1 and 7, so
  such a schema lands in `feeds_skipped_unparseable` rather than half-read. Pinned by assertion.
* **NOT verified**: Windows PowerShell 5.1 — the sandbox has 7.4.6 for Linux and the dialect is six
  scans, each with a positive control proving it can fire, which is a syntax argument and not a run.
  And the real estate: the smoke run is this repository's 15 workflow XML files with `samples/` copied
  into three feed directories. `mvn clean package` is untouched — nothing here is in the WAR.

## Feed schema index 1.1: a skip that did not say where it looked
* **Reported from the field: every feed skipped as `no dataschema` with both schema files plainly
  present in the designer.** The location was not the doubt - `AssetStore.scopeDir` returns
  `FeedLayout.feedDir`, so the tool looks in the right kind of place - but the message could not
  distinguish *the file is missing* from *the DIRECTORY is missing*, and those have opposite remedies.
* **The defect was the message.** `no dataschema` reads as a statement about the estate and is almost
  always a statement about `-BaseDir`. Every skip now NAMES the path it looked for; a missing feed
  directory is its own cause with its own counter (`feed_dir_missing`), pointing at `-BaseDir` and at
  the workflow's own `baseDir` attribute; and the run states both directories it was given before it
  reads the first feed. Same principle as `ElarConfig` replacing the production NPE with a message
  naming the bad family, the file and the families it does contain.
* **A workflow's `baseDir` attribute overrides `-BaseDir`**, as `FeedLayout` has it - the single most
  likely way for a whole estate to be skipped, and nothing previously said which value had won.
* 156 assertions, 16 mutations all caught, the last two being the reverts of this change.

## Feed schema index — Batch 2 delivered: `feed_index_viewer.html`
* Standalone SOURCE -> feed -> fields tree over the index CSV, at the repository root beside
  `csv-viewer.html`. **94 jsdom assertions + 9 end to end, 14 mutations all caught.** Zero external
  references asserted by scan; the CSV splitter is `csv-viewer.html`'s **lifted verbatim**, so the two
  cannot disagree about RFC-4180 quoting. No literal `\n`/`\r` in the JS (positive control included),
  plus the `[[`/`[(` and duplicate top-level `function`/`var` scans.
* **Columns are found BY NAME, never by position** — the file gains four columns under `-Detailed` and
  one per `-Variables` entry, so a positional reader shifts silently, and it LOOKS right on the default
  file. That is exactly why the first mutation of this rule came back green until a `-Detailed` fixture
  existed.
* **Tables on demand, search on the model.** Sources open with their feeds listed and no field table
  drawn; search runs over the model so it matches inside feeds never rendered. **A hit must never look
  empty**: a matching source shows all its feeds, a matching feed all its fields, and counts read
  `1 of 3 fields` so a filtered node cannot be mistaken for a small one.
* **The three findings are DRAWN, not only counted**: orphan rows marked and named, mandatory fields
  saying YES, and a nullable disagreement carrying a badge whose tooltip names BOTH values. The
  end-to-end check asserts the page marks exactly the three disagreements the tool counted on the same
  file. The unresolved `${runDate}` token is coloured apart from an empty cell, so the distinction the
  tool preserves survives to the last step instead of dying in the picture.
* **Three green mutations opened: one bad, two real gaps.** Removing the BOM strip from `parseCsv`
  changed nothing because `buildModel` indexes on `columns[i].trim()` and **JavaScript's `trim` removes
  U+FEFF as whitespace** — the protection exists twice and either alone suffices, so the parser's own
  contract is now asserted. The real gaps: no fixture exercised `-Detailed`, and the step-navigation
  test searched and stepped without collapsing anything in between, so `applyQuery` had already done
  `step`'s work. **A test that supplies the missing step cannot fail on it** — the second time that
  exact shape has appeared.
* **Two assertions of mine were wrong rather than the code**: one confused feed ROWS with field TABLES;
  one took the LAST chip matching a name across three feeds instead of the first, so it asserted
  against the feed whose values are empty. Same class as the harness that lifted the wrong Java.
* **NOT verified**: the real index (the end-to-end run is `samples/` through the real tool and the real
  page, not 144 feeds), and the UBS browser — flex-with-wrap throughout and no `grid`, which is an
  argument and not a rendering. `mvn clean package` untouched: the page is not in the WAR.
