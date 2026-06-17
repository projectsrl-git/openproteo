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
* **Niente Nexus blockers**: ARX è solo un placeholder, da abilitare quando il
  jar è disponibile sul Nexus corporate.

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
* `anonymize` (ARX) — Batch 2a (free-text + ruoli colonne). Batch 2b
  (k-anonymity) **in attesa del jar ARX su Nexus**.
* `mask` — **NEW**, streaming deterministico (HMAC-SHA256), memoria costante,
  format-preserving + pool + free-text + 3 modalità CID. Mappa colonne via
  **liste per anonType** (il displayschema è opzionale, serve solo per
  `DataType=date`).

## Workflow di sviluppo

* **Ritmo**: incrementale, **confirmation-gated**. Una feature/batch alla
  volta, verifica reale sul deploy UBS, poi si prosegue.
* **GitHub**: `https://github.com/projectsrl-git/openproteo` (pubblico). Lo
  zip che si scarica con "Code → Download ZIP" deve corrispondere ai sorgenti
  consegnati (parità garantita dal `.gitignore` minimale).
* **Deploy locale (`deploy_openproteo.bat`)**: estrae lo zip in
  `D:\SVILUPPO\openproteo`, `mvn clean package`, ferma Tomcat, rimuove WAR +
  esploso + work cache, copia il nuovo WAR, riavvia. Il batch **non** scrive
  l'`application.properties` esterno: in locale Spring usa i default bundled
  (e quindi `./workflows` = `CATALINA_HOME/bin/workflows`).
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

## Deploy & commit (deploy_openproteo.bat)

* `COMMIT_MSG.txt` viaggia DENTRO lo zip (in openproteo/), così ogni pacchetto porta il
  proprio messaggio di commit. E' versionato (committato come file).
* Il `.bat` (esterno al repo) sincronizza lo zip nel working dir con
  `robocopy /MIR /XD .git` (NON piu' /XF COMMIT_MSG.txt: ora il messaggio arriva dallo zip),
  poi DOPO una build OK fa `git add -A && git commit -F COMMIT_MSG.txt && git push`
  (best-effort: push fallito non blocca il deploy locale; nessuna modifica -> nessun commit).
* Quindi ogni turno: genero COMMIT_MSG.txt nel repo (entra nello zip) descrivendo le
  modifiche di quel turno.

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
