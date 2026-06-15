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
4. **CSS variables**: usare solo le variabili effettivamente definite in
   `app.css` (`--bg`, `--bg-raise`, `--bg-panel`, `--line`, `--line-soft`,
   `--ink`, `--ink-dim`, `--ink-faint`, `--accent` ambra `#f5a623`,
   `--accent-dim`, `--ok`, `--ok-bg`, `--run`, `--run-bg`, `--fail`).
   **Mai inventare** `--panel`, `--bg-hover`, `--fg-mute` ecc.: appaiono OK in
   un browser tollerante e si rompono altrove.
5. **Log applicativo**: timestamp **a precisione di millisecondo**.
6. **PII**: **MAI loggare** valori originali né mascherati di campi PII.
7. **Deploy WAR**: a Tomcat fermo, cancellare **sia** `webapps/openproteo.war`
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

## Convenzioni di commit / changelog

Ogni turno di sviluppo (= ogni "consegna" di Claude) produce:

1. uno o più commit con messaggio strutturato
   (riga 1 ≤ 72 char, riga 2 vuota, corpo wrap a 78);
2. un file `.claude/YYYY-MM-DD-slug.md` con il **riepilogo della modifica**
   (cosa, perché, file toccati, follow-up). Si committano insieme alle modifiche.
