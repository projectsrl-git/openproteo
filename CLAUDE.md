# CLAUDE.md

Orientamento per Claude (e altri tool AI in VSC) che vengono ricollegati a
questo repository. Tieni questo file aggiornato a mano: è la mappa del progetto
e dei vincoli che NON sono ovvi dal codice.

## Cos'è OpenProteo

Web app **Java 8 / Spring Boot 2.7 / Thymeleaf** che orchestra ed esegue in
modo programmato gli script PowerShell e gli step built-in di preparazione e
spedizione dei feed Legal Archive in un ambiente corporate UBS (Credit Suisse →
UBS decommissioning).

~~Pacchettizzata come **WAR per un Tomcat esterno**, senza embedded server.~~
Corretto 2026-10-04 (falso dal 2026-08-03): la stessa build produce **due
artefatti**. `openproteo.war`, per un **Tomcat esterno**; e
`openproteo-standalone.war`, con **Tomcat embedded**, che si avvia con
`java -jar`. Le regole di questo file valgono per entrambi.

## Come e' organizzata la documentazione di sviluppo (dal 2026-10-04)

Questo file e' **solo il contratto**: regole, convenzioni, checklist. Si legge
per intero all'inizio di ogni sessione, e adesso si puo' fare in pochi minuti.

| Dove | Cosa | Quando si legge |
|---|---|---|
| `CLAUDE.md` | il contratto | sempre, per intero |
| `.claude/HISTORY.md` | le voci di consegna accumulate in coda a questo file fino al 2026-10-04, spostate li' **senza modificarne un byte**. Archivio chiuso: non ci si appende | quando si tocca un'area: cercare il nome dell'executor o della pagina prima di progettare. Li' stanno i fatti misurati e i difetti gia' pagati |
| `.claude/MAIUSCOLO.md` | le spec (una per feature o programma), con le decisioni e le intersezioni | quelle pertinenti al task |
| `.claude/AAAA-MM-GG-slug.md` | la nota di una consegna: cosa, perche', file, verifica, follow-up | quelle pertinenti al task |

**Una consegna NON appende piu' nulla in coda a questo file.** Lo faceva per
prassi, non per regola, e aveva due costi: il contratto era diventato il 12%
di un file di quasi 4000 righe, e due chat in parallelo andavano in conflitto
sulla stessa ultima riga. Da ora:

* quello che una consegna ha fatto e imparato sta nella **sua nota**
  (`.claude/AAAA-MM-GG-slug.md`), che e' un file nuovo e non collide con niente;
* una lezione che deve **vincolare il lavoro futuro** diventa una regola, scritta
  nella sezione di questo file a cui appartiene, nella stessa consegna. Se non
  vale la pena di scriverla come regola, non era una regola;
* un fatto misurato che serve a chi tocchera' la stessa area sta nella spec di
  quell'area.

## Stack e regole irrinunciabili

* **Java 8** — niente API ≥ 9 nemmeno per zucchero sintattico.
* **Spring Boot 2.7**, Thymeleaf, Maven, Tomcat 8.5/9 esterno, oppure embedded
  nell'artefatto standalone (aggiunto 2026-10-04).
* **Zero CDN, zero dipendenze a runtime di rete**: ogni risorsa è bundled
  (font, JS, CSS, pool di masking) o vive in path locali ~~configurati nella
  `application.properties` esterna~~ configurati **dalla GUI** (emendato
  2026-10-04 dalla regola «Configurabilità integrale da GUI» qui sotto; fino a
  quando quella regola non è soddisfatta vale lo stato di transizione scritto in
  «Configurazione esterna»).
* **Niente database server**: lo stato vive su **file** (JSON / JSONL pretty,
  audit hash-chained, run logs).
* **ARX non disponibile**: la dipendenza ARX NON è nel `pom.xml`. Lo step
  `anonymize` è un placeholder (Batch 2a free-text funziona, Batch 2b
  k-anonymity rinviato). Lo step **`mask`** è l'executor attivo per il masking.
* **Compatibilità Linux obbligatoria.** L'applicazione e ogni executor interno
  devono funzionare in modo ~~identico~~ **equivalente** su Windows e su Linux,
  nel rispetto del contesto e delle specificità del sistema operativo ospite:
  OpenProteo è un simbionte che si adatta e cresce in funzione delle
  caratteristiche dell'OS ospite (corretto 2026-10-04: «identico» era la parola
  sbagliata, e avrebbe reso `unarchive` una violazione). Dove l'host stesso
  differisce il comportamento lo segue, e il run dichiara quali regole ha usato.
  Su un valore già salvato vincono i «Default conservativi» (vedi «Principi non
  negoziabili»). Nessun path,
  separatore, encoding, fine riga, interprete o trust store può essere assunto
  dalla piattaforma: o è neutro, o è rilevato a runtime, o è configurabile. I
  runner esterni (powershell, cmd, bash) sono per natura legati a un interprete:
  per loro la regola è che la disponibilità è rilevata e mostrata in GUI, mai
  scoperta al primo run fallito. Ogni consegna dichiara separatamente cosa è stato
  verificato su Linux e cosa su Windows.
* **Configurabilità integrale da GUI.** Ogni parametro di runtime deve essere
  impostabile dalla GUI, senza shell e senza modificare file sulla macchina:
  proprietà orchestrator.*, livelli di log, datasource, driver JDBC, target FTPS,
  truststore e certificati, pool di masking, script, interpreti. Fuori scope, e
  dichiarato tale: installazione e aggiornamento dell'artefatto, parametri della
  JVM (-Xmx, JAVA_HOME), configurazione del container (connector Tomcat). Ogni
  parametro dichiara se si applica subito, al run successivo o al riavvio, e la
  GUI lo mostra. Una feature che introduce un parametro configurabile solo da file
  non è completa.

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
9. **Operazioni sui file che cambiano con l'host** (aggiunto 2026-10-04, audit
   Linux): quattro operazioni del JDK danno risultati diversi su Windows e su
   Linux **senza alcun errore**, e passano tutte da `platform/HostFiles`.
   `File.renameTo` su un nome che esiste rifiuta su Windows e **sostituisce**
   su Linux: usare `renameNoReplace`. L'ordine di `listFiles()` /
   `newDirectoryStream` e' per nome su NTFS e casuale su Linux: se l'ordine
   esce dallo step (una variabile, un indice, un report), usare `inHostOrder`.
   Il glob di `newDirectoryStream(dir, glob)` ignora il maiuscolo/minuscolo su
   Windows e non su Linux: lo step lo dice nel log. Una cancellazione
   ricorsiva fatta con `File.listFiles()` **entra nei link simbolici**: usare
   `deleteTreeNoFollow` o `Files.walkFileTree`. Dettagli e misure in
   `.claude/LINUX_AUDIT.md`.

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

**Emendato 2026-10-04** dalla regola «Configurabilità integrale da GUI» (sezione
«Stack e regole irrinunciabili»). Il testo precedente e' barrato, non cancellato:

~~`application.properties` esterno sotto `CATALINA_HOME/config/`, attivato via
`-Dspring.config.additional-location=file:...`. Chiavi principali:~~

**Regola.** La sede della configurazione di runtime e' la GUI. Il file
`application.properties` (default bundled, piu' l'eventuale file esterno) resta
come strato di base e come fallback: e' legittimo per il provisioning, non puo'
essere l'unico modo di impostare un parametro. I valori salvati dalla GUI vivono
in un file di override gestito dall'applicazione, con precedenza su
`application.properties`; posizione del file e modo in cui l'applicazione lo
trova al bootstrap si decidono nella spec del batch "settings da GUI", non qui.

**Stato a `712db45` (verificato sul codice): la regola NON e' ancora soddisfatta.**
Questa sezione descrive il debito, non lo nasconde.

* Gia' in GUI: datasource, target FTPS, variabili globali su file, pool di
  masking (**solo se** `orchestrator.mask-pools-dir` e' impostata, e quella
  chiave oggi e' solo su file), file condivisi, workflow, variabili, upload di
  script.
* Solo su file, quindi **debito dichiarato**: tutte le chiavi `orchestrator.*`
  di `config/AppProperties` (elenco sotto, non esaustivo: la lista autorevole e'
  la classe), le due chiavi `openproteo.logreport.*` lette con `@Value`
  (`window-days`, `export-enabled`), `logging.*`, gli interpreti
  (`powershell-exe`, `cmd-exe`, `java-exe`, e dal 2026-10-04 `bash-exe`,
  **nata solo-su-file** e dichiarata tale nella sua consegna),
  `windows-tree-kill` (2026-10-04, anch'essa nata solo-su-file: sperimentale,
  spenta di default), i file di truststore e certificato
  dei target FTPS (in GUI si scrive il path, il file va messo a mano sulla
  macchina), i limiti di upload `spring.servlet.multipart.*`, e i driver JDBC
  (`CATALINA_HOME/lib` piu' riavvio).
* Fuori scope per regola: parametri della JVM e configurazione del container.
  ~~**Caso aperto, non deciso qui**~~ **Deciso 2026-10-04**: `server.*` e'
  configurazione del container sotto il Tomcat esterno (dove e' ignorata), ma
  nell'artefatto standalone il Tomcat embedded non ha altro posto da cui prendere
  la porta. Regola conservativa, per ora: **`server.port` e' impostabile dalla
  GUI e vale dal prossimo avvio dell'applicazione** (classe "al riavvio"). Vale
  per `server.port` soltanto; le altre chiavi `server.*` restano fuori scope.
  ~~Lo decide per nome la spec del batch "settings da GUI".~~ Alla spec di quel
  batch resta il come, compreso cosa mostra la GUI sotto il Tomcat esterno, dove
  il valore non ha effetto.

**Caso di intersezione, deciso per nome.** La regola dice che «una feature che
introduce un parametro configurabile solo da file non è completa»; le chiavi
qui sopra sono configurabili solo da file. Sul debito **esistente** vince lo
stato: quelle feature restano in produzione come sono e il debito si chiude con
i batch "settings da GUI" e "driver JDBC da GUI", non rendendole retroattivamente
incomplete. Su ogni chiave **nuova** vince la regola, da questo commit: una
consegna che aggiunge una chiave leggibile solo da file la dichiara incompleta
nel `COMMIT_MSG.txt` e la aggiunge all'elenco del debito qui sopra. Esempio: un
executor nuovo che ha bisogno di una directory temporanea di istanza non puo'
uscire "completo" con la sola `orchestrator.x-tmp-dir`; un parametro di step
(`<param>`, impostabile dal designer) invece soddisfa gia' la regola.

Finche' il debito non e' chiuso, il file esterno si attiva come prima: sotto
`CATALINA_HOME/config/`, via `-Dspring.config.additional-location=file:...`.
Chiavi principali:

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
  ~~reload~~ `refresh` (2026-10-04, vedi il punto sotto) (rifiuta se c'e' un run attivo;
  storia/dati su disco restano). Bottone nel designer.
* **Registro dei workflow: `reload()` e `refresh(nomi)`** (aggiunto 2026-10-04). `reload()`
  rilegge tutti i file: avvio, bottone Reload, Bulk, Import. Chi scrive o cancella **un** file
  di workflow chiama `registry.refresh(nomi)` passando i nomi dei file toccati, e poi
  `scheduler.reschedule(esito)`: vengono riletti solo quei file e quelli cambiati su disco
  (nome, data di modifica, dimensione). Il contratto e' che dopo `refresh` il registro contiene
  **esattamente** cio' che `reload` ci avrebbe messo - stessi workflow, stesso ordine, stessi
  errori; dove non puo' garantirlo (feedId dichiarato da due file o che cambia file, setup
  fallito, directory non elencata) `refresh` chiama `reload` e lo scrive nel log. Chi tocca
  `refresh` rilancia il confronto contro un `reload` fresco su una directory vera (nota
  `.claude/2026-10-04-save-reloads-one-workflow.md`). I nomi vanno passati **sempre**: due
  salvataggi della stessa lunghezza nello stesso tick dell'orologio del file system hanno lo
  stesso timbro, e senza il nome il secondo non viene riletto.
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
  **La pagina /bulk invia JSON** (`bulkCreateJson`, stesso path con
  `consumes=application/json`), non form-urlencoded: Tomcat applica `maxPostSize`
  (2 MB) ai parametri di form e, oltre il limite, li scarta TUTTI senza errore
  (-> "Required request parameter 'csv' is not present", 400 "Bad Request" in UI).
  Il body JSON e' letto come stream e non ha quel limite. L'endpoint form resta per
  compatibilita'. Stessa regola per ogni nuovo POST che porti CSV/JSON grandi.

## Convenzioni di commit / changelog

Ogni turno di sviluppo (= ogni "consegna" di Claude) produce:

1. uno o più commit con messaggio strutturato
   (riga 1 ≤ 72 char, riga 2 vuota, corpo wrap a 78);
2. un file `.claude/YYYY-MM-DD-slug.md` con il **riepilogo della modifica**
   (cosa, perché, file toccati, follow-up). Si committano insieme alle modifiche.
3. **nessuna voce in coda a `CLAUDE.md`** (dal 2026-10-04, vedi «Come e'
   organizzata la documentazione di sviluppo»). `CLAUDE.md` si modifica solo
   per cambiare una regola, nella sezione della regola.

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
* **Un patch per intervento.** ~~Due patch che appendono entrambi in coda a
  `CLAUDE.md` vanno in conflitto~~ Dal 2026-10-04 nessuno appende piu' in coda
  a `CLAUDE.md`, che era la causa abituale. Resta il caso generale: due patch
  che toccano **le stesse righe** (la stessa regola di questo file, la stessa
  sezione di una spec, lo stesso punto di `USAGE.md`) vanno in conflitto:
  dichiarare l'ordine o rigenerare il secondo sopra il primo.
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
* **Meglio della logica isolata: la classe vera** (aggiunto 2026-10-04). Una
  classe che usa solo il JDK si compila dal repo cosi' com'e'. Una che tocca
  Spring si compila contro **stub** di poche righe (le annotazioni,
  `ResponseEntity`, `Environment`) e contro le classi vere del progetto, e poi
  **si esegue**: e' stato fatto per `PlatformController` e per `StepExecutor`.
  Quello che cosi' NON si prova - il wiring di Spring, il JSON di Jackson, la
  build Maven - va scritto tra le cose non verificate.
* **Compilazione differenziale per le classi che qui non si compilano**
  (aggiunto 2026-10-04). `InternalSteps`, `WorkflowEngine`, `ApiController` e
  le altre classi che usano Spring, Jackson o POI non si compilano nel sandbox,
  ma `javac` le analizza lo stesso: si raccolgono gli errori **prima** e
  **dopo** la modifica, senza numeri di riga, e si confrontano. Ogni errore
  nuovo e' della modifica - ha trovato un `LinkedHashMap` non importato alla
  prima applicazione. Il controllo ha il suo controllo positivo: un errore
  messo apposta in una riga modificata deve comparire nel confronto. Dove si
  puo', il corpo vero del metodo si **estrae per posizione** e si esegue accanto
  a quello pre-patch estratto allo stesso modo, sugli stessi file.
* **Un runtime Java 8 vero si puo' avere nel sandbox** (aggiunto 2026-10-04):
  Temurin 8 si scarica dalle release GitHub di `adoptium/temurin8-binaries`
  (`github.com` e' raggiungibile; l'API di GitHub ha un limite di richieste, gli
  URL diretti delle release no). `--release 8` su un JDK nuovo compila per
  Java 8 ma **non lo esegue**: quando il comportamento a runtime puo' differire
  (riflessione, `Process`, formattazione di date, charset) la suite gira su
  Java 8 **e** sul JDK nuovo, e la consegna dice su quale. Allo stesso modo si
  ottiene PowerShell per Linux (`PowerShell/PowerShell`, tar.gz `linux-x64`).
* **Il sandbox gira come root e senza locale** (aggiunto 2026-10-04), e
  entrambe le cose falsano un test. Root passa ogni controllo di permessi: ogni
  verifica che dipende da un permesso si esegue **anche da utente non-root**
  (`setpriv --reuid=65534 --regid=65534 --clear-groups`), ed e' successo due
  volte che una mutazione fosse rilevata solo cosi'. Senza locale
  `sun.jnu.encoding` e' ASCII: i test che dipendono dall'encoding si eseguono
  **dichiarando** `LANG` (vuoto e `C.UTF-8`), ricordando che Python passa
  `LC_CTYPE=C.UTF-8` ai propri figli e quindi un harness Python cambia il locale
  di cio' che lancia.
* **Un test su processi, righe di comando o locale e' esso stesso un processo**
  con una riga di comando e un locale (aggiunto 2026-10-04). `pkill -f pattern`
  uccide la shell che lo ha digitato; un controllo "nessun processo ha X sulla
  riga di comando" conta l'harness che contiene X. Ogni controllo del genere
  ha un **controllo positivo** eseguito nello stesso modo.
* **Linux e Windows si dichiarano separatamente** (checklist, punto 9): cosa e'
  stato eseguito su Linux e con quale JDK, cosa su Windows, cosa su nessuno dei
  due. Una logica Windows esercitata su Linux con `os.name` iniettato prova la
  logica, non la piattaforma, e va scritto cosi'.
* Nel `COMMIT_MSG.txt` **dichiarare cosa e' stato verificato e cosa no**.

## Principi non negoziabili (entrambe le modalita')

* **Default conservativi**: ogni nuovo comportamento nasce spento. Un deploy non
  deve cambiare l'output dei feed in produzione; l'attivazione e' per step o di
  massa da Variables / matrice. **Intersezione con «Compatibilità Linux
  obbligatoria», decisa per nome (2026-10-04)**: su qualunque valore gia' salvato
  vince questa regola. Un valore memorizzato non si riscrive e non si migra per
  renderlo portabile; un default sensibile alla piattaforma vale solo per istanze
  nuove e oggetti nuovi. Esempio: un target FTPS salvato con `trustMode=WINDOWS`
  si carica, si salva e gira come WINDOWS su qualunque host (e su una JVM senza
  SunMSCAPI fallisce con il messaggio esistente); solo a un target NUOVO viene
  proposto un modo in base alla piattaforma.
* **Spec-first**: per una feature non banale, prima una `.md` in `.claude/` con
  le decisioni, poi implementazione a batch con conferma tra uno e l'altro.
* **Regole che si sovrappongono: la spec decide il caso di intersezione, per
  nome.** Quando due affermazioni di una spec possono applicarsi allo stesso
  caso, la spec deve dire *quale vince su quel caso*, con un esempio concreto.
  Una spec che dice due cose non resta ambigua: il codice ne sceglie una, e
  nessuno si accorge quale finche' non arriva il dato reale. Successo **due
  volte** nello stesso executor (`objpack`, settembre 2026), ogni volta con il
  codice che segue la meta' peggiore:
  - default di `objectSource`: la prosa diceva "order non e' il default", la
    tabella parametri diceva il contrario. Il codice ha seguito la tabella: 7
    accoppiamenti riga/oggetto sbagliati su 7 su un feed reale, fermati solo per
    caso da un controllo sul mime type. Con estensioni tutte uguali, 7 documenti
    sarebbero stati archiviati sotto i metadati del vicino.
  - `object_id`: "un ruolo non mappato ricade sul nome Transarch" contro
    "object_id non mappato = assegna 1..N". Il codice ha seguito la prima, e
    l'opzione di scarto righe e' risultata inutilizzabile su ogni metadata CSV
    reale, che ha sempre una colonna `object_id`.

  Come applicarla:
  1. Per ogni regola generale, cercare nella spec le eccezioni gia' dichiarate
     altrove e scrivere il caso di intersezione **accanto a entrambe**, non in
     una sola.
  2. Prosa e tabella dei parametri devono dire la stessa cosa sui default. Chi
     ne cambia una cambia l'altra nello stesso commit.
  3. Se esiste un contratto di riferimento (lo script che si sostituisce, una
     spec esterna), la spec dice cosa fa quel contratto sul caso di
     intersezione e se lo si segue. Nel caso `object_id` lo script PowerShell
     lo diceva in chiaro ("Se object_id e' presente nel sorgente lo
     ignoriamo") e la spec non l'aveva riportato.
  4. Una contraddizione scoperta si corregge **dove e' nata**, nella spec
     (barrata, non cancellata), oltre che nel codice.
* **Corollario: il test riproduce la forma del dato reale, non quella comoda.**
  Un test che percorre una strada diversa da quella della produzione non
  protegge la produzione. La guardia per "scarto con `object_id` mappato"
  controllava il *parametro* `map.objectId`; in produzione l'id arrivava dal
  nome della colonna, il parametro era vuoto e la guardia non e' mai scattata,
  mostrando proprio il messaggio confuso che doveva sostituire. Il suo test
  usava il mapping esplicito; nessun test della suite aveva una colonna
  `object_id` non mappata, cioe' la forma di ogni file reale. Per ogni guardia:
  il test deve presentare il caso **nel modo in cui arriva davvero**, e deve
  fallire sul codice senza la guardia (mutazione) - e quel fallimento deve
  distinguere il messaggio nuovo dal vecchio, non cercare una frase che hanno
  in comune.
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

**Corretto 2026-09-29, verificato sul codice di `objpack`:** ~~4 punti~~ — sono **8**:
`WorkflowXmlParser` whitelist, messaggio d'errore e set `internal` (tre punti, righe 89/91/94 a
`0997815`), `WorkflowEngine.internalKind()`, dispatch in `InternalSteps.run()`, e nel designer
`<option>`, branch del pannello e `clientValidate`. Più `variables.html` `PARAM_OPTIONS` per gli
enum e `buildXml` solo se l'executor introduce un elemento figlio nuovo. La tabella sopra resta
com'era: la lista autorevole e' il codice dell'ultimo executor registrato, non questa tabella.

### Runner esterni: un percorso diverso (aggiunto 2026-10-04)

La regola qui sopra vale per gli executor **interni**. Un runner **esterno**
(powershell, cmd, jar, bash) non passa da `InternalSteps` ne' da
`internalKind()`. Ricavato dal codice registrando `bash`; anche qui la lista
autorevole e' il codice dell'ultimo runner aggiunto:

1. `engine/StepExecutor`: l'enum `Kind`, `resolveKind` (nome dell'`exec` ed
   estensione del file), `buildCommand`, e il costruttore che riceve l'interprete.
2. `config/AppProperties` (campo, getter, setter) e il punto di
   `WorkflowEngine` che costruisce lo `StepExecutor`.
3. `parser/WorkflowXmlParser`: i valori ammessi di `exec` **e** il testo
   dell'errore accanto. **Non** il set `internal`, cosi' `script` resta obbligatorio.
4. `templates/designer.html`: `isExternal`, la `<option>`, il placeholder dello
   script, l'hint dei parametri, la riga di aiuto. `buildXml` scrive `exec` in
   modo generico.
5. `static/js/filespanel.js`: la regex delle estensioni apribili nell'editor e
   l'etichetta dell'upload.
6. `web/PlatformController` e `templates/platform.html` (`EXE_LABELS`): la riga
   dell'interprete; `tools/scan_platform_whitelist.js`: il getter ammesso.
7. `USAGE.md`, «External scripts».

Ogni processo esterno si avvia con `ProcessTree.launch`, mai con un
`ProcessBuilder` proprio: e' li' che vivono il kill dell'albero e lo Stop.

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
8. **Spec con regole sovrapposte** — se la consegna tocca una spec in
   `.claude/`, ogni regola generale che ha un'eccezione dichiarata altrove ha
   il caso di intersezione scritto accanto a entrambe, e prosa e tabella
   parametri concordano sui default (vedi «Principi non negoziabili»).
9. **Linux / Windows** — `COMMIT_MSG.txt` e nota di sessione dichiarano su tre
   righe separate cosa e' stato verificato su Linux (nominando il JDK), cosa su
   Windows, e cosa su nessuno dei due.
10. **Nessun parametro nuovo solo-su-file** — ogni parametro di runtime
    introdotto dalla consegna e' impostabile dalla GUI e dichiara quando si
    applica (subito / al run successivo / al riavvio). Altrimenti la consegna si
    dichiara incompleta e aggiunge la chiave al debito in «Configurazione
    esterna».
