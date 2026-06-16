# OpenProteo — orchestratore feed Legal Archive

*Come Proteo, il dio marino che cambia forma: lo stesso flusso di dati attraversa
forme diverse — landing, preparazione, pacchetto — fino alla spedizione.*

OpenProteo orchestra ed esegue in modo programmato gli script PowerShell di
preparazione e spedizione dei feed Legal Archive. Applicazione **Java 8 + Spring Boot 2.7 + Thymeleaf**,
impacchettata come **WAR e deployata su un Tomcat esterno**, **zero dipendenze esterne a runtime**
(nessuna CDN, nessun database server: tutto su file leggibili).

## I tre pilastri di ogni workflow

1. **feedId come chiave di tutto** — Ogni workflow è identificato dal *feed ID* del
   Legal Archive (es. `LA-EOR-001`) più un nome friendly. Il feedId compare nel run id,
   nella struttura directory, nei file di log e di stato, ed è disponibile in ogni step
   come variabile `${feedId}` da inserire in parametri e nomi file.

2. **Struttura directory provisionata automaticamente** — Al caricamento del workflow:

   ```
   {baseDir}/{feedId}/
     00_landing_in/            ← dati grezzi in arrivo (AS400)
     10_check_landing/         ← una directory per ogni step, in ordine
     20_prepare/
     30_send/
     99_landing_out/           ← pacchetti pronti per la spedizione FTPS
     _logs/
       audit_{feedId}.jsonl    ← audit log append-only con hash chain
       runs/{runId}/{step}.log ← output PowerShell di ogni step
     _runs/
       {runId}.json            ← stato di ogni esecuzione (pretty JSON)
   ```

3. **Audit log "audit proof"** — Un file JSON Lines per feed, append-only, dove ogni
   record contiene `prevHash` e `hash = SHA-256(seq|ts|feedId|runId|node|event|user|detailsJson|prevHash)`.
   Manomettere, cancellare o inserire una riga rompe la catena: la UI (e una verifica
   manuale) lo evidenziano. È testo semplice, consultabile con Notepad o PowerShell
   anche senza applicazione.

Nessun DB binario: lo "stato" è interamente in `_runs/*.json` (pretty-printed) e
`_logs/*.jsonl`.

## Build

Il progetto produce un **WAR** da deployare su un **Tomcat esterno** (vedi la sezione
dedicata più sotto). Il repackage eseguibile di Spring Boot è disattivato, quindi il WAR è
leggero e non contiene un server applicativo embedded.

Richiede Maven e un JDK 8+ (target bytecode 1.8):

```
mvn clean package
```

Produce `target/openproteo.war`. *(Il progetto è consegnato come sorgente non
compilato: fare una prima build e un giro di test sul proprio ambiente, applicando poi
eventuali modifiche in modo incrementale.)*

## Log applicativo

Il log dell'applicazione va in `./logs/openproteo.log` (rotazione automatica a 20 MB,
14 file di storico). In sviluppo il livello è volutamente verboso
(`com.legalarchive=DEBUG`, `org.springframework.web=DEBUG`): per la produzione
abbassare in `application.properties`:

```properties
logging.level.com.legalarchive=INFO
logging.level.org.springframework.web=INFO
```

Nota: il log applicativo è cosa diversa dall'**audit log** per feed
(`_logs/audit_{feedId}.jsonl`, hash-chained) e dai **log PowerShell** per step
(`_logs/runs/{runId}/{stepId}.log`), che restano nella struttura del feed.


> L'interfaccia dell'applicazione è in **inglese**; questo README resta in italiano
> come documentazione interna.

## Deploy su Tomcat esterno (WAR)

### Versione di Tomcat

| Tomcat | esito |
|---|---|
| 7 | **NO** — Servlet 3.0; Spring Boot 2.7 richiede Servlet 3.1+ |
| 8.5 | funziona, ma è fuori supporto (EOL marzo 2024) |
| **9.x** | **consigliato** — Servlet 4.0 namespace `javax.*`, gira su Java 8 |
| 10.x / 11 | **NO** — namespace `jakarta.*`, incompatibile con Spring Boot 2.x |

### Passi

1. `mvn clean package -DskipTests` → `target/openproteo.war`
2. Copiare il WAR in `CATALINA_HOME/webapps/openproteo.war`
   (l'app risponderà su `http://server:8080/openproteo/`; come `ROOT.war` risponde su `/`).
   La UI è già **context-path aware**: funziona sia sotto `/openproteo` sia come ROOT.
3. **Configurazione esterna obbligatoria**: su Tomcat la working directory non è
   la cartella dell'app, quindi i path relativi di default (`./workflows`, `./feeds`,
   `./datasources.json`, `./logs`) finirebbero in `CATALINA_HOME/bin`. Creare
   `D:/openproteo/application.properties` con **path assoluti**:

   ```properties
   orchestrator.workflows-dir=D:/openproteo/workflows
   orchestrator.scripts-dir=D:/openproteo/scripts
   orchestrator.default-base-dir=D:/feeds
   orchestrator.datasources-file=D:/openproteo/datasources.json
   orchestrator.shared-dir=D:/openproteo/shared
   logging.file.name=D:/openproteo/logs/openproteo.log
   ```

   e agganciarlo in `CATALINA_HOME/bin/setenv.bat`:

   ```bat
   set "JAVA_OPTS=%JAVA_OPTS% -Dspring.config.additional-location=file:D:/openproteo/application.properties"
   ```

4. La porta diventa quella di Tomcat (il `server.port` interno è ignorato nel WAR).
5. L'utente del servizio Tomcat deve avere i permessi su scripts/feeds/logs e
   visibilità di rete verso AS400 e FTPS.

Note:

* Lo **scheduler cron** parte con il deploy del WAR e si ferma all'undeploy; se su
  quel Tomcat girano altre webapp, valutare un'istanza dedicata per evitare che un
  restart di altre app interrompa run in corso.
* Per il runner PowerShell vale tutto quanto detto nella sezione execution policy:
  l'esecuzione avviene dal processo Tomcat, quindi è il **suo** utente a contare.

## Creazione massiva di workflow (Bulk create)

Per generare molti feed quasi-identici (es. un centinaio) senza scrivere a mano gli XML:
pagina **Bulk create** (link in dashboard). Si sceglie un workflow **template**, si incollano
**due CSV** che contengono tutto, e si generano gli XML nella `workflows-dir` (più reload).

I **nomi delle colonne sono configurabili**: puoi incollare i CSV così come sono, anche con
colonne in più (quelle non mappate vengono ignorate).

**CSV #1 — feeds.** Campi mappabili: `feedId` (obbligatorio, anche nome file), `name`,
`sourceId`, `description`, e — opzionali — `dataschema` / `displayschema` con il **contenuto
JSON inline** (tra doppi apici nel CSV, con `"` raddoppiati secondo RFC4180). Se il JSON è
ben formato, viene scritto come `feedDir/dataschema.json` / `feedDir/displayschema.json` del
feed; se non è valido, il workflow viene comunque creato e lo schema è saltato con avviso.

Il workflow ha anche un attributo **`targetId`** (destinazione), accanto a `sourceId`,
configurabile nel designer e nel bulk; nel bulk puoi indicare **più destinazioni separate
da virgola** (es. `{Dest A},{Dest B}`). I campi **`name`, `sourceId`, `targetId` e `description`** accettano un **template**: i token
`{Nome Colonna}` (i nomi colonna possono contenere spazi) vengono sostituiti col valore
della colonna, il resto è testo letterale. Così puoi **concatenare più colonne**, es.
`{Banca} - {Codice ICTO}`. Un valore senza graffe resta un singolo nome di colonna
(come prima).

**CSV #2 — tables.** Mappa `feedId` → `tableName`; il valore viene iniettato come variabile
di workflow (nome configurabile, default `originTableName`), che il template usa nella SELECT.

File esistenti **saltati** salvo overwrite; il `feedId` uguale al template è saltato; ogni XML
è **validato col parser reale** prima della scrittura (errori per riga). Endpoint:
`POST /api/workflows/bulk`. Esempi: `workflows/_TEMPLATE-extract.xml`,
`samples/bulk_feeds_example.csv`, `samples/bulk_tables_example.csv`.

### Step `sql` — SELECT generata dal dataschema

Lo step `sql` può costruire la lista campi della SELECT dal **dataschema JSON** del feed,
così il template resta generico. Nella query usa il segnaposto `{{columns}}` e indica il
dataschema con il param `columnsSchema`:

```xml
<step id="extract" exec="sql" datasource="AS400-PROD"
      csvFile="${landingOut}/${feedId}_${runDate}.csv" delimiter=";">
    <param name="columnsSchema" value="${feedDir}/dataschema.json"/>
    <query>SELECT {{columns}} FROM ${originTableName}</query>
</step>
```

A runtime `{{columns}}` viene espanso con i `name` del dataschema (es. `NDG, NOMINATIVO,
DATA_KYC`), poi `${originTableName}` e le altre variabili vengono risolte normalmente.
Identificatori **nudi** di default; param opzionale `columnQuote=double` per virgolettarli
(`"NDG", "NOMINATIVO"`). Se la query non contiene `{{columns}}`, è usata tale e quale
(retrocompatibile). La lista campi si aggiorna da sola se cambia il dataschema, senza
rigenerare i workflow.

## Lista file (workflow page e shared)

Il pannello file ora ha: **ricerca per nome file** (filtro live), colonna **Modified**
(data/ora di modifica) e **ordinamento per colonna** (click sull'intestazione: File, Type,
Alias, Size, Modified; secondo click inverte l'ordine).

Nel **viewer CSV** le colonne sono **ridimensionabili**: trascina il bordo destro
dell'intestazione di colonna per allargarla/restringerla (min 40px).

## Loop sui file (blocco LOOP / ENDLOOP)

Quando l'estrazione produce **più file** (split per righe/MB → `${csvFiles}` lista,
`${csvParts}` conteggio), puoi ripetere una **catena di step** una volta per file con un
blocco **LOOP … ENDLOOP**:

```xml
<step id="extract" exec="sql" ... csvSplitRows="100000"> ... </step>
<loop id="perFile" over="${csvFiles}" delimiter=";" itemVar="file" indexVar="fileIdx"/>
    <step id="mask" exec="mask" csvFile="${file}"/>
    <step id="send" exec="powershell" script="send.ps1"/>
<endloop id="endPerFile"/>
```

Gli step tra `LOOP` e il suo `ENDLOOP` girano **in sequenza, una volta per elemento**,
con `${file}` (elemento corrente), `${fileIdx}` (indice 0-based) e `${loopCount}`. Lista
vuota → blocco saltato. I blocchi si possono annidare. Nel designer: pulsante **↻ Add loop**
(inserisce la coppia LOOP+ENDLOOP). Lo stato del loop è persistito, quindi sopravvive a una
pausa su gate manuale dentro il blocco.

Nota: il motore ha un limite di sicurezza `orchestrator.max-transitions` (default **500**)
contro i loop infiniti dei gate; per loop su molti file alza questo valore
(transizioni ≈ numero file × step nel blocco).

## Dashboard

La home elenca i workflow registrati (uno per feed) con un **riepilogo in alto** e una
tabella paginata.

* **Paginazione**: selettore *Show* 5 (default) / 10 / 25 / 50 / 100 con Prev/Next, così la
  lista non si appesantisce quando i feed sono molti. Funziona insieme a ricerca e filtro
  sorgente.
* **Riepilogo Sources**: distribuzione dei workflow per `sourceId` con conteggio, percentuale
  e barra proporzionale (stile EOR_viewer). Ogni voce è **cliccabile** e filtra la tabella per
  quella sorgente; "All sources" azzera il filtro.
* **Recent runs**: ultima esecuzione per workflow, le 5 più recenti (una riga per workflow,
  anche se ne ha decine), con stato e link al run.
* **Execution queue (FIFO)**: evidenza live di ciò che è in esecuzione e dei prossimi in coda
  (polling ogni 4s su `/api/queue`). La coda può essere vuota. Riflette il motore single-thread
  FIFO: un run alla volta, gli altri attendono in ordine di arrivo.

## Workflow Designer (visuale)

Dalla dashboard, **＋ New workflow** apre il designer (`/designer`); il pulsante
**Edit** su ogni workflow apre `/designer/{feedId}` con la definizione precaricata.

Nel designer si compongono visivamente:

* intestazione: feedId, nome friendly, cron, baseDir, descrizione, variabili;
* la sequenza di **step** (script, timeout, retry, parametri con `${var}`, output attesi);
* i **gate** decisionali: `auto` con condizione, oppure `manual` (approvazione umana),
  con routing **On true / On false** scelto da menu: un nodo qualsiasi, `(next node)`
  o `END:SUCCESS|SKIPPED|REJECTED|FAILED`;
* i nodi si riordinano con ▲▼; a destra anteprima live della pipeline e dell'XML generato.

**Validate & save**: l'XML viene rigenerato lato server, **rivalidato con lo stesso
parser usato a runtime** (id duplicati, target inesistenti, gate senza condizione...),
salvato in `workflows/{feedId}.xml` (o nel file originale se il feed esisteva già,
previa conferma di sovrascrittura), e registry + scheduler vengono ricaricati.
Il salvataggio è tracciato nell'audit del feed come `WORKFLOW_SAVED`.

API del designer: `GET /api/workflows/{feedId}/definition` (modello JSON),
`POST /api/workflows/save?overwrite=true|false` (salvataggio validato).

## Definizione di un workflow (XML)

Il designer è il modo più comodo, ma i file XML in `workflows/` restano la fonte di
verità e si possono scrivere anche a mano (un file per feed). Vedere
`workflows/LA-EOR-001.xml` commentato. Sintesi:

```xml
<workflow feedId="LA-EOR-001" name="EORFULL verso Legal Archive"
          cron="0 30 6 * * MON-FRI" baseDir="D:/feeds">
  <description>...</description>
  <variables>
    <var name="minFiles" value="1"/>
  </variables>
  <steps>
    <step id="check_landing" name="Verifica landing IN" script="Check-LandingIn.ps1" timeoutSec="300">
      <param name="Path" value="${landingIn}"/>
      <param name="FeedId" value="${feedId}"/>
      <output var="fileCount"/>
    </step>
    <gate id="files_present" name="File presenti?"
          condition="${fileCount} &gt;= ${minFiles}"
          onTrue="prepare" onFalse="END:SKIPPED"/>
    <step id="prepare" ... retry="1" retryDelaySec="60"> ... </step>
    <gate id="approval" name="Approvazione spedizione" type="manual"
          onTrue="send" onFalse="END:REJECTED"/>
    <step id="send" ...> ... </step>
  </steps>
</workflow>
```

* **cron**: espressione Spring a 6 campi (`sec min ora giorno mese giornoSettimana`).
  Senza attributo `cron` il workflow è solo manuale.
* **gate auto**: `condition` con clausole `&&` o `||` (non misti) e operatori
  `== != >= <= > < contains notcontains matches exists notexists`.
  Confronto numerico se entrambi i lati sono numeri.
* **gate manual**: il run si sospende in `WAITING_APPROVAL`; un operatore approva o
  rifiuta dalla pagina del run (decisione, utente e nota finiscono nell'audit).
* **target dei gate**: id di un nodo, oppure `END:SUCCESS` / `END:SKIPPED` /
  `END:REJECTED` / `END:FAILED` per terminare il run con quello stato.
  Target omesso = prosegue col nodo successivo.

### Variabili builtin negli step

`${feedId}` `${feedName}` `${runId}` `${runDate}` `${runTs}`
`${feedDir}` `${landingIn}` `${landingOut}` `${logDir}`
`${stepDir}` (dir dello step corrente) `${dir.<stepId>}` (dir di uno step specifico)

### Tipi di step (runner)

Uno step può eseguire diversi tipi di eseguibile. Il runner è dedotto dall'estensione
del campo `script`, oppure forzato con l'attributo `exec`. Valori ammessi:
`auto` | `powershell` | `cmd` | `jar` (step **a processo**) e
`sql` | `ifscopy` | `filecopy` | `setvar` (step **built-in**, eseguiti in-process
dalla JVM, senza lanciare script esterni).

| script | runner | invocazione | parametri |
|---|---|---|---|
| `*.ps1` | powershell | script block in memoria (vedi sotto) | nominali: `-Nome 'Valore'` |
| `*.bat` / `*.cmd` | cmd | `cmd.exe /c <script> <arg...>` | posizionali, nell'ordine |
| `*.jar` | jar | `java -Dfile.encoding=UTF-8 -jar <jar> <arg...>` | posizionali, nell'ordine |

Per `cmd` e `jar` i **nomi** dei parametri sono solo etichette: conta l'**ordine**, e
ogni parametro diventa un singolo argomento (gli spazi nel valore sono gestiti
correttamente, non serve quotare a mano). Esempio nel designer per un jar:

```
exec = jar    script = legalarchive-tool.jar
param 1: in   = ${landingIn}
param 2: out  = ${landingOut}
param 3: feed = ${feedId}
  ->  java -Dfile.encoding=UTF-8 -jar legalarchive-tool.jar "<landingIn>" "<landingOut>" "<feedId>"
```

`java` deve essere nel PATH del servizio, oppure impostare un path assoluto in
`application.properties` (`orchestrator.java-exe`, `orchestrator.cmd-exe`).
Working directory del processo = directory dello step. Le variabili di output
(`##VAR nome=valore` su stdout) e l'exit code funzionano per tutti i runner.

#### Step built-in (senza script)

| exec | cosa fa | attributi principali | output `##VAR` |
|---|---|---|---|
| `sql` | esegue una query su DB2 for i / AS400 (o qualunque JDBC) usando un datasource | `datasource`, `<query>...</query>`, `outputVar`, `delimiter`, `csvFile` | `rowCount`, `col_<NOME>`, `firstValue`, `outputVar`, `csvFile` |
| `ifscopy` | copia **nativa** e veloce di file dall'IFS dell'AS400 al filesystem Windows (driver IBM JTOpen) | `datasource`, `ifsPath`, `dest`, `pattern`, `overwrite` | `filesCopied`, `bytesCopied`, `matchedFiles` |
| `filecopy` | copia/spostamento/elenco di file tra directory locali, senza script | `source`, `dest`, `pattern`, `mode` (`copy`/`move`/`list`) | `matchedCount`, `matchedFiles`, `bytesCopied` |
| `setvar` | assegna/calcola variabili di run | `param` come `nome = espressione` (supporta `${var}` e `A + B` / `A - B` interi) | le variabili assegnate |

Per esportare il **risultato di una query in CSV**, valorizza l'attributo `csvFile`
dello step `sql`: l'intero result set viene **scritto in streaming** sul file (UTF-8
con BOM per Excel, separatore = `delimiter` o `;`), senza caricarlo in memoria — adatto
anche a tabelle molto grandi. Lo step espone poi `##VAR csvFile` (path assoluto) e
`rowCount`, riusabili dagli step successivi (es. un `filecopy`/`ifscopy`/send). Esempio:

```xml
<step id="export_eor" name="Export EOR to CSV" exec="sql"
      datasource="AS400-PROD" csvFile="${landingOut}/eor_${runDate}.csv" delimiter=";">
    <query>SELECT NDG, DESCR, DT_EOR FROM MYLIB.EORFULL WHERE DT_EOR = '${runDate}'</query>
</step>
<step id="deliver" name="Send CSV" script="Send-FTPS.ps1">
    <param name="PackageFile" value="${csvFile}"/>
    <param name="FtpsHost" value="ftps.example.com"/>
    <param name="FeedId" value="${feedId}"/>
</step>
```

**Trim dei campi.** Le colonne `CHAR` di DB2/AS400 arrivano riempite di spazi
(blank-padded): l'export CSV, la query preview e le variabili esposte dallo step
(`col_<NAME>`, `firstValue`, `outputVar`) sono **trimmate di default**. Per
disattivare (caso raro in cui il padding è significativo):
`<param name="trim" value="false"/>` sullo step `sql`. Gli spazi *interni* ai valori
sono preservati.

**Split dell'export CSV.** Sullo step `sql` con `csvFile` puoi spezzare l'output in più
file impostando uno dei due attributi (mutuamente alternativi):

* `csvSplitRows="100000"` — un nuovo file ogni N righe dati;
* `csvSplitMb="50"` — un nuovo file quando la prossima riga supererebbe N MB (stima
  UTF-8; l'header è ripetuto in ogni parte).

I file sono numerati `eor_001.csv`, `eor_002.csv`, … Lo step espone `##VAR csvParts`
(numero di parti), `csvFile` (prima parte) e `csvFiles` (lista dei path, separati dal
`delimiter`). Per consegnare tutte le parti, usa uno step successivo in fan-out, es.
`filecopy`/send con `forEach="${csvFiles}"`. Esempio:

```xml
<step id="export" name="Export EOR (split 100k)" exec="sql" datasource="AS400-PROD"
      csvFile="${landingOut}/eor_${runDate}.csv" csvSplitRows="100000" delimiter=";">
    <query>SELECT NDG, DESCR, DT_EOR FROM MYLIB.EORFULL</query>
</step>
<step id="send" name="Send parts" script="Send-FTPS.ps1" forEach="${csvFiles}">
    <param name="PackageFile" value="${item}"/>
    <param name="FeedId" value="${feedId}"/>
</step>
```

`ifscopy` usa lo **stesso** datasource AS400 della query SQL: la copia avviene via
host server JTOpen (stream diretto a 64 KB), molto più rapida di una `copy` DOS su
unità mappata.

### Datasource (connessioni riusabili)

Le connessioni si configurano una volta sola nella pagina **Data sources** (stile
DBeaver) e si richiamano per id dagli step `sql` e `ifscopy`. Sono salvate nel file
`orchestrator.datasources-file` (default `./datasources.json`).

* **Tipo `as400`**: host, user, password (+ proprietà JDBC opzionali tipo
  `naming=system;libraries=MYLIB`). Usato sia per le query DB2 sia per `ifscopy`.
* **Tipo `custom`**: `jdbcUrl` + `driverClass` per qualunque altro database.
* Pulsante **⚡ Test connection** (esegue una select di test, es.
  `SELECT 1 FROM SYSIBM.SYSDUMMY1`).
* Nel designer, lo step `sql` ha una textbox per la query e un pulsante
  **▶ Preview query** che esegue e mostra le prime righe in anteprima.

> Il driver IBM **JTOpen / jt400** è incluso come dipendenza Maven: nessun jar da
> installare a mano. Le **password sono salvate in chiaro** in `datasources.json`:
> proteggere il file con ACL del filesystem.

### Step a thread paralleli (fan-out / join)

Uno step a processo può girare **in parallelo** su una lista: impostare
`forEach="${unaListaDiItem}"` e (opzionale) `concurrency` (default 4). Il motore
spezza la lista, esegue lo step una volta per item esponendo `${item}` e
`${itemIndex}`, attende il completamento di tutti i worker, poi prosegue. Lo step
fallisce se almeno un item fallisce; produce `##VAR itemsTotal/itemsOk/itemsFailed`.
I log dei singoli item sono in `_logs/.../<stepId>__<n>.log`. Vedi `SAMPLE-03-parallel`.

### Loop di uno step (contatore + gate)

Un loop si costruisce con un **back-edge**: un gate di decisione che punta `onTrue`
a uno step precedente. Il contatore si mantiene con uno step `setvar`. Esempio in
`SAMPLE-04-loop`: `init (counter=0) -> work -> increment (counter=${counter} + 1) ->
gate ${counter} < ${maxIterations} [onTrue=work / onFalse=END:SUCCESS]`. La guardia
`orchestrator.max-transitions` (default 500) impedisce loop infiniti.

### Workflow di esempio (modelli da copiare)

Nella cartella `workflows/` trovi modelli pronti da duplicare (dal designer:
**⧉ Duplicate as new** per il workflow, **⧉** sulla card per un singolo step):

| file | mostra |
|---|---|
| `SAMPLE-01-sql-ifs.xml` | sql + auto gate + ifscopy nativo + prepare (ps1) + approvazione manuale + send (jar) |
| `SAMPLE-02-listener.xml` | listener `cron="0 * * * * *"` (ogni minuto): scan directory + conteggio + step con i nomi file rilevati |
| `SAMPLE-03-parallel.xml` | step a thread paralleli (`forEach`/`concurrency`) con join e step successivo |
| `SAMPLE-04-loop.xml` | loop di uno step guidato da contatore e gate di decisione |
| `SAMPLE-05-filecopy.xml` | copia e spostamento semplice di file tra directory, senza script |

> I sample `SAMPLE-01` usano il datasource id **`AS400-PROD`**: crealo nella pagina
> Data sources prima di eseguirli.

### Contratto degli script PowerShell

* Parametri ricevuti come `-Nome 'Valore'` (i `${...}` sono già risolti).
* Working directory = directory dello step.
* **Exit code 0 = successo**; qualunque altro valore = fallimento (con retry se configurato).
* Variabili di output per gli step/gate successivi: righe su stdout nel formato

  ```
  ##VAR nome=valore
  ```

* Tutto lo stdout/stderr finisce timestampato in `_logs/runs/{runId}/{stepId}.log`.
* Non usare `$Host` come nome di parametro (variabile riservata PowerShell).

## File: upload, classificazione, download (feed e applicazione)

Ogni workflow ha il pannello **Feed files** (in `/workflow/{feedId}`); a livello
applicazione c'è la pagina **Shared files** (`/files`), i cui file sono usabili da
**tutti** i workflow. In entrambi puoi caricare con **selezione o drag&drop**, oppure **creare un file di testo** incollando il contenuto (pulsante *✎ create text file*: nome, tipo, e per gli eseguibili l'alias).

Al momento dell'upload si sceglie il **tipo**:

* **document** — file di riferimento (txt, md, JSON dataschema/displayschema, …).
  Mantiene il nome originale, nessun alias.
* **executable step** — script eseguibile (ps1, jar, bat, cmd). Richiede un **alias
  univoco** che diventa una variabile: l'alias risolve al **path assoluto** del file,
  così puoi referenziarlo in uno step con `script="${alias}"` (o come parametro
  `${alias}`). L'alias è **proposto in automatico** dal nome file (senza spazi né
  caratteri speciali) e la **univocità è verificata** lato server su tutto lo scope
  applicazione + il feed corrente; se è già in uso, l'upload viene rifiutato.

Gli alias degli script (app + feed) sono iniettati come variabili di ogni run, quindi
sono disponibili ovunque nel workflow. Esempio: carichi `Validate.ps1` come executable
con alias `validate`, poi in uno step usi `script="${validate}"`.

**Download:** ogni file presente nella directory del feed è scaricabile dal pannello
(inclusi gli output prodotti dai run, es. CSV in `99_landing_out`); idem per i file
condivisi. I file interni di audit/stato (`_logs`, `_runs`) non sono elencati.

Upload e cancellazioni sono tracciati nell'audit (`FILE_UPLOADED` / `FILE_DELETED`).
Limite dimensione configurabile (`spring.servlet.multipart.max-file-size` / `max-request-size`, default 1GB; gli upload grandi vengono temporaneamente scritti in `java.io.tmpdir`);
directory dei file condivisi: `orchestrator.shared-dir` (default `./shared`).

## Viewer online (server-side) ed editor

> Per collaudare il viewer puoi caricare un CSV (anche grande) come file **shared**
> scegliendo tipo *document*: comparirà il pulsante **👁 View**.

I file ASCII/CSV (caricati o prodotti dai run) hanno nel pannello il pulsante **👁 View**
oltre al download. Il viewer ora lavora **lato server** (paginazione/streaming), quindi
il browser non carica più milioni di righe:

* **testo**: righe con numero di riga, caricate **a pagine** durante lo scroll
  (virtualizzazione), e un pulsante **✎ Edit** per modificare e **salvare** il file.
* **CSV/TSV**: tabella **virtualizzata** con pagine richieste al server, filtro su tutte
  le colonne (server-side), e scheda **Aggregate** che calcola lato server:
  - **Group by** → COUNT delle righe per combinazione di valori;
  - **Distinct count of** → conteggio dei valori distinti per le colonne scelte; si può
    usare **anche da solo, senza group by**: in tal caso calcola i distinct globali per
    ciascuna colonna selezionata (con eventuale filtro applicato);
  - **riga TOTAL fissata** sotto gli header: count complessivo e distinct globali per
    colonna, sempre visibile durante lo scroll;
  - **Pivot by** (opzionale, solo conteggi): scegli una colonna pivot e ottieni una
    tabella incrociata — righe = combinazioni del group by (o 'ALL' se nessuno),
    colonne = valori distinti della colonna pivot (top 30 per frequenza + OTHER),
    celle = COUNT, con colonna TOTAL per riga e riga TOTAL per colonna. Il pivot riusa
    l'aggregazione server (group by + colonna pivot) e rimodella lato client: costo =
    una sola aggregazione.
  Risultato ordinato per frequenza, con **export CSV** (include la riga TOTAL; il pivot
  esporta la matrice incrociata). È lo strumento per le metriche di riconciliazione
  (es. per `SOURCE`: count righe e distinct NDG; o pivot `SOURCE` × `CS_APP`).

Endpoint server: `csv/meta|page|aggregate`, `text/meta|page`, `files/save` (per scope
feed e app). L'aggregazione fa un solo passaggio in streaming sul file.

## Ottimizzazione memoria (server 8GB, Tomcat condiviso)

**Diagnosi.** Un `PrivateMemoryMB` negativo nel monitoraggio è un overflow a 32 bit del
contatore: il valore reale è `4096 − |valore|` MB (es. −1910 ⇒ ~2.19GB committati).
Misura sempre con i contatori a 64 bit:

```powershell
Get-Process tomcat8 | Select-Object @{n='WS_MB';e={[math]::Round($_.WorkingSet64/1MB)}},
                                    @{n='Priv_MB';e={[math]::Round($_.PrivateMemorySize64/1MB)}}
```

**Causa tipica.** Senza `-Xmx` esplicito, Java 8 assegna max heap = ¼ della RAM fisica
(2GB su 8GB) e l'heap tende a riempirsi anche se le webapp ne usano una frazione.

**Dove configurare.** Il Tomcat gira come **servizio Windows**, quindi `setenv.bat` è
ignorato: usa `tomcat8w.exe //ES//<NomeServizio>` → tab *Java*:

* *Initial memory pool*: `256` — *Maximum memory pool*: `1024` (parti da qui; vedi sotto)
* *Java Options* (una per riga):

```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UseStringDeduplication
-XX:MaxMetaspaceSize=256m
-Xss512k
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=G:/Phoenix/logs
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xloggc:G:/Phoenix/logs/gc.log
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=5
-XX:GCLogFileSize=10M
```

Poi riavvia il servizio. Con due webapp leggere l'occupazione attesa scende a
~1.2–1.4GB di working set. **Prima di stringere ulteriormente**, osserva nel
`gc.log` l'occupazione dell'old-gen dopo i full GC: se resta sotto ~400–500MB puoi
scendere a `-Xmx768m`; se l'altra applicazione è esosa, resta a 1024.
`UseStringDeduplication` (solo G1) aiuta molto con i CSV (valori ripetuti).
In `server.xml`, se il connettore ha il default `maxThreads="200"`, per due app
interne bastano 50: ogni thread costa fino a `-Xss` di stack.

**Lato OpenProteo** (già incluso in questa versione):

* gli endpoint di log (`/log/{stepId}`, `/tail`) e l'audit ora sono in **streaming con
  tetto** (ultime N righe), non caricano più l'intero file a ogni poll della console;
* paginazione CSV/testo via **indice di offset** (vedi sotto): niente più scansioni
  complete per pagina;
* aggregazioni con **cache LRU** e **tetto globale ai valori distinct** (2M) per
  limitare l'heap: se il tetto scatta, il viewer segnala che i distinct sono lower
  bound (`truncatedDistinct`);
* gli upload multipart vengono scritti su disco temporaneo, non in heap.

## Diagnostica risposte non-JSON ("Unexpected token ... is not valid JSON")

Se una chiamata del designer riceve **HTML invece di JSON** (tipica pagina di blocco di
un proxy/web-filter aziendale, un redirect a login per sessione/SSO scaduta, o un 404),
il browser falliva con il criptico `Unexpected token 'T', "The page w"... is not valid
JSON`. Ora un helper `readJson` controlla status e content-type e mostra un messaggio
chiaro: *"Server returned HTTP <code> as text/html, not JSON … First bytes received:
<snippet>"*, così è evidente che la risposta **non viene da OpenProteo** e che si tratta
di un'intercettazione di rete/sessione. Vale per save, load definizione, datasources,
files e preview. Nota: l'helper costruisce le regex a runtime (`String.fromCharCode`)
per non incorrere nella normalizzazione `\n`/`\r` del proxy.

Suggerimento di indagine: aprire DevTools → Network → la richiesta fallita (es. `save`)
→ Response, per leggere la pagina HTML restituita. Se contiene un blocco del proxy e nel
workflow ci sono URL interni nella description, provare a rimuoverli per verificare se è
il filtro a bloccare il POST.

## Fix: errore Thymeleaf nel Designer

Negli script `th:inline="javascript"` Thymeleaf interpreta le sequenze `[[...]]` come
espressioni di inlining: un array di array JavaScript scritto come `[[...],[...]]`
manda in errore il **rendering del template** (TemplateOutputException) — era il caso
della checklist dello step validate nel designer, che rendeva la pagina inaccessibile
sia in modifica sia in creazione. Corretto (lista id + mappa label, nessun `[[`).
**Regola permanente**: nei template, mai sequenze letterali `[[` o `[(` fuori dal
pattern commentato `/*[[${...}]]*/`; il controllo è parte della QA insieme a
`node --check`.

## Note di revisione (Fable 5)

* **Indice offset di riga** (`CsvService`): `meta()` fa un'unica passata a livello di
  byte che conta le righe **e** registra un checkpoint ogni 1024 righe (~12KB per 1.5M
  righe; il byte `0x0A` in UTF-8 non appare mai dentro sequenze multibyte, quindi i
  checkpoint cadono sempre a inizio riga valida). `page()`/`lines()` fanno seek al
  checkpoint più vicino: lo scroll profondo è O(1) (testato: offset 49.990 in 2ms).
* **Cache aggregazioni**: LRU di 6 risultati per (file, mtime, colonne, filtro);
  ricalcolo solo se il file cambia (testato: hit 0ms, invalidazione su append).
* **Split CSV per MB ora esatto**: il conteggio UTF-8 gestisce le coppie surrogate
  (4 byte), quindi il taglio è esatto al byte per qualunque contenuto.
* **Timestamp dei log al millisecondo** (`yyyy-MM-dd HH:mm:ss.SSS`): l'interlacciamento
  dei log `forEach` nella console live è ora fedele all'ordine reale.
* **Robustezza**: `validate` dentro un `forEach` non aggancia più la checklist
  condivisa (gli item avrebbero scritto sullo stesso `StepExec` da thread concorrenti);
  `RunStore.save` era già synchronized con scrittura atomica.
* **Checklist nel BPMN**: i sotto-step di `validate` sono mostrati in due modi sul task:
  (a) **segmenti colorati compatti dentro il rettangolo** (grigio=PENDING, blu=RUNNING,
  verde=PASS, rosso=FAIL, tenue=SKIP) per il colpo d'occhio, e (b) una **pila di
  sotto-nodi etichettati sotto il task** — veri sotto-step con spina di collegamento,
  pallino di stato, icona (✓/✗/▶/⊘) e label del controllo, con tooltip che riporta il
  dettaglio. L'altezza del diagramma si espande automaticamente per contenerli. Tutto si
  aggiorna live insieme alla checklist sotto il diagramma.

## Step di validazione CSV (checklist)

Nuovo executor **`validate`**: esegue una **checklist di controlli** selezionabili sul
CSV indicato (`source`), pensata per la qualità dei feed verso il Legal Archive. I
controlli disponibili (attributo `checks`, valori separati da virgola; nel designer
sono **checkbox**):

* `rowCount` — numero righe dati = `expectedRows` (header escluso se presente);
* `colCount` — numero colonne coerente in tutte le righe (vs `dataschema`, o vs header);
* `jsonSchema` — il/i descrittore/i JSON (`dataschema`/`displayschema`) sono ben formati;
* `noQuotes` — nessun doppio apice `"` nei campi (romperebbe il parsing in archiviazione);
* `colNames` — header coerente con i `name` del `dataschema`;
* `notNull` — i campi `nullable:false` del `dataschema` sono presenti e valorizzati;
* `businessDate` — la colonna indicata (`businessDateColumn`) è sempre valorizzata e nel
  formato `dateFormat` (es. `YYYY/MM/DD` o `YYYYMMDD`);
* `displayDates` — se passi il `displayschema`, le colonne `DataType:date` sono nel `dateFormat`.

Parametri (via `<param>`): `hasHeader`, `expectedRows`, `dataschema`, `displayschema`,
`businessDateColumn`, `dateFormat`. `dataschema`/`displayschema` sono **path** (puoi usare
l'`${alias}` di un JSON caricato tra i file). La validazione fa **una sola passata in
streaming** sul CSV (regge file grandi).

**Report delle violazioni.** Quando un controllo per-riga fallisce (noQuotes, colCount,
notNull, businessDate, displayDates), lo step scrive accanto al CSV validato un file
`<nome>_validation_report.csv` con **una riga per violazione**:
`CHECK;LINE;COLUMN;DETAIL` — quindi per i doppi apici vedi **riga e campo** incriminati
con uno snippet del valore. Il file è in streaming (tetto 100.000 righe, segnalato se
troncato), compare nel pannello file del feed (scaricabile e apribile con 👁 nel
viewer, dove puoi **aggregarlo per COLUMN o CHECK** per la distribuzione delle
violazioni). Il path è esposto come `##VAR validationReport`. Gli esempi nella
checklist mostrano la colonna: `line 3 [TESTO_SMS]`.

Ogni controllo è un **sotto-step**: nel run la pagina mostra una **checklist con
avanzamento live** (⏳→▶→✓/✗/⊘) e un badge `ok/totale` sul nodo BPMN; ogni esito è anche
tracciato nella console live. Lo step **fallisce** se almeno un controllo è ✗
(`##VAR checksTotal/checksPassed/checksFailed`), così puoi diramare con un gate
(`condition="${checksFailed} == 0"`). Vedi `SAMPLE-06-validate.xml` e gli esempi in
`samples/` (dataschema.json, displayschema.json, eor_sample.csv).

## Console live durante l'esecuzione

La pagina del run mostra una **console live** che segue lo step in esecuzione, come da
shell: lo **stdout** è bianco, lo **stderr** è rosso, le righe di sistema
(abort/timeout) in ambra; l'ordine è quello di arrivo. C'è un toggle
**⏸ Pause / ▶ Follow** per fermare/riprendere l'autoscroll. Per gli step in
**forEach** (parallelo) le righe dei vari item sono unite cronologicamente e
prefissate con `[#N]` (indice item).

Tecnicamente: `StepExecutor` ora tiene **stdout e stderr separati** (niente più
`redirectErrorStream`) e li scrive nel log marcando lo stream; un endpoint
`…/log/{stepId}/tail?offset=N` restituisce in modo **incrementale** le nuove righe
(JSON con tag di stream), che la console accoda con polling ~2s. È quindi
*near-real-time* (latenza = intervallo di polling + flush del processo), non uno
streaming byte-per-byte: script che bufferizzano molto l'output possono comparire a
blocchi. Resta disponibile il visualizzatore "Step log" per ispezionare qualunque step.

## Step csvreplace (sostituzioni di stringa)

Nuovo executor **`csvreplace`**: sostituisce letteralmente una stringa con un'altra nei
campi di un CSV. Per ogni step puoi configurare **più sostituzioni**, e ogni
sostituzione può agire **su tutti i campi** o **solo su colonne specifiche** (per nome,
lista separata da virgola). Le regole si applicano in ordine.

* input: `source` (path del CSV, tipicamente `${stepId.outputFile}` dello step di
  estrazione); `delimiter`; `hasHeader`;
* output: parametro `outFile` opzionale — se vuoto scrive `<nome>_replaced.<ext>`
  accanto all'input; se valorizzato scrive lì (può coincidere con l'input per
  sovrascrivere, gestito via file temporaneo + move); espone `##VAR outputFile`,
  `rowCount`, `replacements`;
* regole: elementi `<replace from="..." to="..." columns="A,B"/>` (`columns` vuoto = tutti
  i campi; `to` vuoto = cancella la stringa). Header e BOM preservati.

Esempio (pulizia testo SMS + rimozione apici), vedi `SAMPLE-07-replace.xml`:
```
<step id="clean" exec="csvreplace" source="${extract.outputFile}" delimiter=";">
    <param name="hasHeader" value="true"/>
    <replace from="CS informa: " to="" columns="TESTO_SMS"/>
    <replace from="&quot;" to=""/>
</step>
```

## Variabile di output per-step: `${stepId.outputFile}`

Ogni step espone le sue variabili anche **namespacing** con l'id dello step
(`${stepId.var}`), e in più una variabile canonica **`${stepId.outputFile}`** con il
full-path del file prodotto (export CSV dello step `sql`, output di `csvreplace`, ...).
Così l'output di un'estrazione si passa con un clic all'input di un `validate` o
`csvreplace` successivo: nel designer i campi *input* hanno il suggerimento a tendina
`${<stepId>.outputFile}` per ogni step del workflow.

## Aggregazione: sottostringa LEFT/RIGHT e pivot con distinct

* **Sottostringa in group/distinct**: nel viewer, il campo *Substring* accetta mappe
  `COLONNA=L4, ALTRA=R2` (L = primi N caratteri, R = ultimi N). Permette di raggruppare
  o contare per una porzione del valore — es. **anno** da una data `DT_EOR=L4`, **mese**
  `DT_EOR=R5`/`L7`, o un **prefisso** di codice `CODE=L2`. Server-side lo spec di colonna
  diventa `idx:L4`/`idx:R2` e l'etichetta colonna riporta il marcatore (`DT_EOR[L4]`).
* **Pivot con distinct per cella**: in modalità pivot, se spunti **esattamente una**
  colonna in *Distinct count of*, le celle contengono il **conteggio dei valori distinti**
  di quella colonna invece del numero di righe (es. `SOURCE × CS_APP` con **NDG distinti**
  per cella). Poiché i distinct non sono additivi, la colonna OTHER e le righe/colonne
  TOTAL in questa modalità mostrano `–` (la colonna ROWS resta il conteggio righe).

## Performance dashboard (centinaia di workflow)

La home elencava i workflow chiamando, per ciascuno, `RunStore.list(layout, 1)` per il
"last run" — ma quel metodo **leggeva e faceva il parse JSON di TUTTI i run** del feed
prima di tenerne uno: con centinaia di feed e molti run, migliaia di parse a ogni
apertura. Ora `list` sfrutta il fatto che i nomi dei file di run contengono un timestamp
zero-padded (ordine per nome = ordine cronologico): ordina i nomi e fa il **parse solo
degli ultimi `max` file**. La dashboard passa così da migliaia di parse a **uno per
feed**; la pagina workflow (history 50) parsa al più 50 file invece di tutti. Nessuna
modifica al formato dei run.

## Step `mask` — mappatura colonne tramite liste (displayschema senza anonType)

Dato che il **displayschema è codificato dal sistema di destinazione** e non può ospitare
`anonType`, la mappatura colonna→strategia si fa con **un parametro per ogni anonType**,
valorizzato con i nomi-colonna separati da virgola. **Le colonne non incluse in nessuna
lista vengono lasciate invariate** (non mascherate). Il displayschema diventa **opzionale**
e serve solo a riconoscere le colonne `DataType=date` (passthrough); in alternativa si usa
`dateColumns`.

Parametri lista disponibili: `cidColumns`, `dateColumns`, `numericFixedColumns`,
`alphanumericFixedColumns`, `firstNameColumns`, `lastNameColumns`, `fullNameColumns`,
`cityColumns`, `addressColumns`, `companyColumns`, `customerDescriptionColumns`,
`freeTextColumns`. Esempio: `cidColumns=CID,NDG` e `firstNameColumns=NOME`.

* Se una colonna compare in **più liste**, vince la prima per precedenza e il conflitto
  viene segnalato nella checklist.
* I nomi elencati ma **assenti nell'header** (refusi) vengono segnalati come
  "listed-but-absent".
* In modalità liste, `unmappedColumnPolicy` ha **default `passthrough`** (le non mappate
  restano invariate, come richiesto); puoi comunque forzare `fail`/`redact`.
* La vecchia modalità con `anonType` nel JSON resta supportata: se non passi nessuna lista,
  lo step legge l'`anonType` dal displayschema (default `unmappedColumnPolicy=fail`).

Verificato end-to-end sul motore reale (questa modalità non richiede Jackson): mappatura via
liste, colonna non mappata lasciata invariata, CID consistente cross-riga, nomi coerenti tra
varianti maiuscole/minuscole, free-text a lunghezza esatta, **vuoti preservati** per tutte le
strategie (corretto un caso in cui le strategie a pool generavano un valore su campo vuoto).

## Step `mask` — Batch 2/3 (pool, aziende, CID multi-modo, testo libero)

Completate le strategie basate su pool e testo, oltre alle modalità CID:

* **Pool bundlati nel WAR** (`/maskdata`, sovrascrivibili senza rebuild via
  `orchestrator.mask-pools-dir`): nomi IT (curati) + internazionali, cognomi IT,
  città/CAP (ISTAT-derived, comuni-json), vie, componenti azienda.
* **`firstName` / `lastName` / `fullName`**: pescati dai pool in modo deterministico;
  **nome × cognome incrociati** (combinatoria moltiplicata). Mix nazione via
  `localePercent` (% pool italiano, default 100; il resto internazionale).
* **`city`**: città italiana dai comuni. **`address`**: "Via &lt;nome&gt; &lt;civico&gt;",
  struttura plausibile (CAP/città indipendenti, per scelta).
* **`company`**: nome sintetico Animale + Colore + Azione + suffisso societario (es.
  "Canguro Ceruleo Volante S.p.A.") — zero problemi di licenza.
* **`customerDescription`**: persona (nome+cognome) **oppure** azienda inventata, in
  percentuale configurabile (`personVsCompanyPercent`, default 70% persona).
* **`freeText`**: lorem ipsum a **lunghezza di carattere esatta**.
* **`cid` — tre modalità** (`cidMode`): `formatPreserving` (per-classe, default),
  `partial` (maschera il **centro** con `-`, testa/coda visibili, `cidMaskPercent` default
  60%), `hash` (HMAC deterministico troncato a `cidHashLen`, default 12).

Tutto **deterministico** (stesso valore → stesso output, i join sopravvivono) e in
**streaming** (memoria costante). Testato sul motore reale: pool, cross-join, mix nazione,
modalità CID, persona/azienda, lorem length-aware, varietà (200/200 fullName distinti).

I **pool sono file di testo** (una voce per riga) sostituibili: per avvicinarsi ai 1000
nomi IptL richiesti basta ampliare `firstnames_it.txt` con una lista ISTAT più estesa, senza
ricompilare (dir esterna). Fonti: comuni-json (MIT, ISTAT-derived), liste nomi pubbliche,
nomi IT curati, aziende sintetiche.

## Step `mask` — masking CSV deterministico in streaming (Batch 1)

Nuovo executor **`mask`**, alternativo ad ARX per i casi di **masking / format-preserving**
(non anonimizzazione statistica). Single-pass in **streaming a memoria costante**: regge
file da ~1GB senza caricarli. ARX/`anonymize` resta separato e invariato.

* **Driver**: displayschema JSON (param `displayschema`, path) con un `anonType` per
  colonna accanto a `DataType`. Si accetta sia un array top-level sia `{ "columns": [...] }`;
  per colonna si leggono `name`/`ColumnName`, `DataType`, `anonType`.
* **`DataType=date` ha priorità assoluta**: passthrough verbatim qualunque sia l'anonType.
* **Colonne non mappate**: `unmappedColumnPolicy` = `fail` (default, fallisce elencandole),
  `redact` (mascheramento class-preserving) o `passthrough`.
* **Determinismo con chiave**: `seed = HMAC-SHA256(secret, anonType + ":" + valoreNormalizzato)`,
  con RNG deterministico in counter-mode sui byte dell'HMAC. **Stesso valore → stesso
  output** ovunque, quindi i join (CID, codici ripetuti) sopravvivono. Il segreto sta in
  `application.properties` esterno (`orchestrator.masking-secret`), **mai** nel repo.
  Normalizzazione del valore per il seed configurabile (`orchestrator.mask-normalize` =
  none|trim|trimUpper, default trimUpper) per collassare le varianti. HMAC è **a senso
  unico**: nessuna re-identificazione (nessuna mapping table).
* **Strategie Batch 1** (format-preserving, lunghezza esatta, **vuoto resta vuoto**):
  `date` (passthrough), `numericFixed` (ogni cifra→cifra random; separatori `-/.`
  preservati di default, `numericPreserveSeparators`), `alphanumericFixed` e `cid`
  (sostituzione per classe: cifra→cifra, A–Z→A–Z, a–z→a–z; layout/punteggiatura
  invariati).
* **Streaming I/O quote-aware** in lettura e scrittura, **EOL preservato** (CRLF/LF
  rilevato dall'input), quoting per-campo preservato, verifica **righe in == righe out**.
* Sotto-step BPMN + checklist live: schema → preflight → init → mask (avanzamento %) →
  output. **Nessun valore PII nei log.** outputs: `##VAR outputFile, dataRows, columns`.

Testato sul motore reale: determinismo e consistenza dei join cross-riga, preservazione
di lunghezza/classe/separatori, vuoti invariati, date passthrough, EOL LF preservato,
conteggio righe. Il **parsing JSON del displayschema** usa Jackson reale e — come per lo
step `validate` — è verificabile solo a runtime su UBS, non nel sandbox.

**Batch 2** (pool: firstName/lastName/fullName/address/city con liste bundlate nel WAR) e
**Batch 3** (customerDescription/freeText, lorem length-aware) seguiranno dopo la verifica.

## Step `anonymize` — Batch 2a (free-text + ruoli colonna)

Sul Batch 1 (preflight, guardia risorse, rilevamento date) si innesta ora la parte
**deterministica e già operativa** dell'anonimizzazione:

* **Ruoli colonna** (param, liste comma-separate; le liste esplicite vincono sull'auto):
  `identifyingColumns` (soppresse → valore vuoto), `quasiColumns` (generalizzate da ARX in
  Batch 2b), `sensitiveColumns` (l-diversity in Batch 2b), `freeTextColumns` (forza
  editing), più i `forcePassthrough/forceAnonymize` del Batch 1.
* **Auto free-text**: una colonna i cui valori superano una **soglia di caratteri**
  (`freeTextThreshold`, default **50**, modificabile) è trattata come testo libero (a meno
  che non sia data o abbia un ruolo esplicito).
* **Editing free-text a lunghezza di carattere preservata** (82 char → 82 char), due
  strategie per-step (`freeTextStrategy`):
  * **`redact`**: maschera i pattern riconoscibili (codice fiscale, IBAN, email, date,
    telefoni, importi) sostituendo ogni match con un carattere ripetuto (`maskChar`,
    default `█`). **Non** cattura i nomi propri liberi (limite intrinseco delle regex).
  * **`lorem`**: rimpiazza l'intero campo con lorem-ipsum troncato/riempito alla lunghezza
    esatta — sicuro anche per i nomi.
* **k-anonymity**: parametro `k` (default 5) **registrato** ma applicato in Batch 2b (ARX).
  Su file troppo grandi resta la **guardia che blocca** (scelta confermata: nessun
  percorso 1GB).
* Scrittura **streaming quote-aware** che preserva lo stile di quoting per campo (un campo
  originariamente fra apici resta fra apici), così i dati non toccati non cambiano forma.
* outputs: `##VAR outputFile, dataRows, columns, cells, dateColumns, freeTextColumns`.

Tutto testato sul motore reale (redazione, lorem, lunghezza preservata, soglia
modificabile, override colonna, soppressione identifying, passthrough date/quoting).
**ARX (k-anonymity) resta in attesa del Batch 2b**, dopo la conferma del jar via sonda.

## Step `anonymize` (ARX) — Batch 1 (scheletro)

Nuovo executor **`anonymize`** per l'anonimizzazione CSV con ARX, consegnato **a lotti**.
Questo è il **Batch 1** (scheletro verificabile, **senza** la trasformazione ARX vera):

* **Preflight** in una passata streaming **quote-aware** (i newline dentro campi quotati
  non contano come fine riga): righe, colonne, celle, delimiter (sniffato se assente),
  byte, BOM. Espone `##VAR dataRows/columns/cells`.
* **Guardia risorse fail-fast**: stima conservativa `celle × bytesPerCell` confrontata con
  `Runtime.maxMemory()` + headroom; se eccede (o supera maxRows/maxCells) **interrompe
  PRIMA di ARX** con messaggio chiaro, evitando OutOfMemory a metà run (ARX gira nello
  stesso heap del Tomcat). Soglie in `application.properties` (`orchestrator.anonymize*`),
  override per-step via param.
* **Rilevamento colonne data (passthrough)**: campiona i primi N valori non vuoti per
  colonna; se ≥ soglia (default 95%) matchano `YYYY/MM/DD`, `YYYY-MM-DD` o `YYYYMMDD` **e**
  l'anno è plausibile (default 1900–2099), la colonna è passthrough. Il caso critico
  `YYYYMMDD` (collide con NDG/conti a 8 cifre) è mitigato dalla plausibilità **e** dagli
  **override espliciti** `forcePassthroughColumns` / `forceAnonymizeColumns` (priorità sul
  rilevamento). Espone `##VAR dateColumns`.
* **Sotto-step BPMN + checklist live** (stile validate): preflight → guard → dates →
  config → anonymize → output, con avanzamento e SKIP coerenti.
* **Output**: in Batch 1 è una **copia passthrough** (nessuna trasformazione). `##VAR
  outputFile`.

**§1 — dipendenza ARX (priorità)**: **non verificabile in questa sandbox** (Maven Central
non raggiungibile, com'è su UBS). In `arx/` trovi `ArxProbe.java` + `README_ARX.md` con i
passi esatti (ricerca su Nexus, `mvn install:install-file` per il vendoring, prova di
link) da eseguire lato UBS per rispondere definitivamente a "ARX è sul Nexus?". Il Batch 1
**non** richiede ARX a build-time, quindi compila e gira senza.

**Batch 2 (da progettare dopo verifica)**: ruoli colonna (identifying/quasi/sensitive/
insensitive), modello di privacy (k-anonymity ecc.), gerarchie di generalizzazione,
esecuzione ARX reale. Restano da chiarire le 3 domande del documento (garanzie
statistiche vs masking, mappatura PII dei feed, ordini di grandezza).

## Step `encoding` — modalità directory (batch)

Oltre al file singolo, `encoding` può convertire **tutti i file di una directory** in
un'altra. Parametri batch: `inputDir` (cartella sorgente; in alternativa basta puntare
`source` a una cartella), `outputDir` (**obbligatoria** in batch), `filter` (lista comma di
glob/estensioni, es. `*.csv,*.txt` oppure `csv`; vuoto = tutti), `recursive` (true/false:
se true mantiene la struttura delle sottocartelle in output). I file conservano il nome;
`from`/`bom` valgono per tutti. Scrittura via tmp+move per file. outputs: `##VAR
outputDir, filesConverted, filesFailed`. Il caso a file singolo resta invariato.

## Step `encoding` — normalizzazione a UTF-8

Nuovo executor **`encoding`** che porta un file di testo a UTF-8:

* se il file è **già UTF-8 valido** (con o senza BOM), tocca **solo il BOM** secondo il
  parametro `bom`;
* altrimenti **decodifica dall'encoding sorgente** e ri-codifica in UTF-8;
* gestisce **UTF-16 con BOM** (LE/BE) riconoscendolo automaticamente;
* il **BOM in uscita** è controllato da `bom` (default `false` = UTF-8 *senza* BOM →
  copre il caso "UTF-8 con BOM → senza BOM").

Parametri: `source` (file in ingresso), `from` (encoding sorgente se non UTF-8; vuoto =
auto: prova UTF-8, altrimenti assume Windows-1252), `bom` (true/false, default false),
`outFile` (vuoto = sovrascrive l'input). Espone `##VAR outputFile` e `sourceEncoding`.
Vedi `SAMPLE-07-encoding.xml`.

**Limite dichiarato onestamente**: il rilevamento dell'encoding di un file **non**-UTF-8
è euristico e non affidabile al 100% (es. ISO-8859-1 e Windows-1252 sono di fatto
indistinguibili dai soli byte). Quando conosci l'encoding di partenza, **dichiaralo** in
`from`; l'auto-detect assume Windows-1252 (il caso tipico dei feed Windows) ed è pensato
come ripiego, non come garanzia.

## Correzioni validate e stato run

* **Nomi colonna fra apici (header)**: il parser dello step `validate` è ora quote-aware
  (RFC): toglie i doppi apici che racchiudono nomi e valori e de-escapa `""`→`"`. Così il
  controllo `colNames` confronta `CONTO_TITOLI_SMIT2` e non `"CONTO_TITOLI_SMIT2"`, e i
  campi con il delimitatore racchiuso fra apici vengono contati correttamente.
* **`noQuotes` ora rileva solo gli apici INTERNI** ai campi (un `"` rimasto dopo il
  parsing RFC), non quelli di wrapping: niente più falsi positivi su CSV interamente
  quotati.
* **`colCount` senza dataschema = SKIP** (non più PASS): il controllo è definito rispetto
  al dataschema, quindi senza schema è "non eseguibile" come gli altri (prima ripiegava
  sul conteggio dell'header e risultava verde a sproposito).
* **Stato run nella Run history**: i run riusciti restavano "RUNNING" perché l'evento di
  audit terminale è `RUN_COMPLETED` (non `RUN_SUCCESS`) e la pagina non lo riconosceva.
  Ora l'engine include lo stato autoritativo nei dettagli dell'evento e la pagina lo legge
  (con fallback `RUN_COMPLETED`→SUCCESS).

## Filtro stdout / stderr / system

La **live console** e lo **step log** hanno tre checkbox (stdout / stderr / system) per
mostrare/nascondere al volo i flussi: utile per isolare gli errori nel rumore. Il filtro
agisce via CSS sulle righe già classificate (bianco=stdout, rosso=stderr, ambra=system),
senza ricaricare.

## Conferme sulle azioni e rimozione run

Le azioni che **modificano lo stato o sono distruttive** ora chiedono conferma con un
popup prima di eseguire: avvio run (Run now / Re-run), Stop, Approve/Reject di un gate,
Validate & save, Duplicate, eliminazione file ed eliminazione run. La semplice
navigazione e visualizzazione (aprire un log, cambiare tab, seguire i link) resta senza
conferma, per non rendere l'uso pesante.

**Rimozione run**: dalla pagina **Run history** ogni run ha un pulsante **🗑 Delete**, e
la pagina del run ha **🗑 Delete run** nella toolbar. L'eliminazione rimuove il JSON del
run e i relativi log di step, registra un evento `RUN_DELETED` nell'audit, ed è
**vietata su un run attivo** (va prima fermato). Endpoint: `POST
/api/runs/{feedId}/{runId}/delete`.

## Aggregazioni: selezione colonne a tendina ricercabile

I selettori **Group by** e **Distinct count of** del viewer non sono più checkbox in
linea (scomode con molte colonne) ma **menu a tendina ricercabili a selezione multipla**:
digiti per filtrare le colonne, clicchi per selezionarle, le scelte appaiono come chip
removibili. Nessuna libreria esterna (compatibile con il vincolo no-CDN).

## Pagina Run: toolbar di navigazione completa

La pagina del run ha ora una toolbar con **✎ Edit** (designer), **Workflow page**,
**⟲ Run history**, **Audit log** e **← Dashboard**, oltre ai già presenti **↻ Re-run** e
**■ Stop**. Così da un run si raggiunge tutto il resto senza passare dalla home.

## Designer: pulsanti Run / Run history / Audit

Per evitare passaggi dalla home, la toolbar del Designer (quando si modifica un feed
esistente) include ora **▶ Run now**, **⟲ Run history**, **Audit log** e **Workflow
page**. Il Run rispetta il blocco run-in-corso (polling `/active`, etichetta
"▶ Running…" e link **▷ Active run**), identico alla pagina workflow.

## Messaggi in cima alla pagina

`save()`/validazione: il banner di esito (errore o conferma) compare in cima e la pagina
**scrolla automaticamente in alto** ad ogni messaggio, così non resti a fondo pagina
senza accorgerti dell'esito.

## Blocco del Run durante un'esecuzione

Il bottone **▶ Run now** della pagina workflow si **disabilita** quando un run è già
attivo per quel feed (etichetta "▶ Running…") e mostra un link **▷ Open active run**;
torna attivo a fine run. Lo stato è verificato all'apertura e in polling ogni 3s tramite
`GET /api/workflows/{feedId}/active`. Il click disabilita subito il bottone (anti
doppio-invio). Resta comunque il presidio lato server: l'engine **rifiuta** un secondo
run sullo stesso feed finché il precedente non termina.

## Fix: caricamento workflow nel Designer (regressione readJson)

L'introduzione di `readJson` aveva lasciato in `load()` un passaggio che trattava il
risultato come `Response` (`if (!r.ok)…`), mentre `readJson` restituisce già il JSON
parsato: ogni apertura di un workflow esistente falliva con "Could not load workflow".
Corretto: la catena ora è `fetch → readJson → usa il DTO`. Il parser sui workflow delle
versioni precedenti era ed è OK (verificato sul workflow segnalato).

## File del workflow nel Designer

Nella pagina di **edit** del workflow (Designer) c'è ora una sezione **Workflow files**:
puoi **caricare o creare** file strettamente legati al feed — tipicamente
`dataschema.json` e `displayschema.json` — che ricevono un **alias** e diventano
riferibili negli step come `${alias}` (es. nei campi dataschema/displayschema dello step
validate, o come input di csvreplace). Il pannello è attivo quando si modifica un feed
esistente; per un workflow nuovo si abilita **subito dopo il primo salvataggio** (la
cartella del feed viene creata in quel momento). La sezione **Workflow files** del designer è **comprimibile** (header con freccia) ed è
larga quanto l'area dei campi del workflow. Mostra **solo i file presenti nella root del
feed** — cioè quelli che carichi o crei in fase di design (`?root=true`) — **non** i file
generati dai run (output sotto `00_landing_in/`, `_runs`, `_logs`): così resta una lista
pulita di ciò che serve come properties. È lo stesso pannello file (upload, creazione da
testo, alias, download, viewer 👁), in versione root-only per il designer.

I **document e gli output del feed** (tutto tranne gli script, che si usano via
`${alias}`) compaiono automaticamente nel **datalist dei suggerimenti** dei campi path
del designer, già nella forma **`${feedDir}/<nome>`**: così, dove serve un JSON come
proprietà (es. `dataschema`/`displayschema` dello step validate, o l'input di
csvreplace), lo **selezioni a tendina** invece di digitarlo. L'elenco si aggiorna in
tempo reale quando carichi, crei o elimini un file dal pannello (callback `onChange` del
files panel).

## Run history (pagina dedicata)

Lo storico dei run ha una pagina propria: **`/runs/{feedId}`**, raggiungibile con un
click da dashboard (pulsante *⟲ Runs* su ogni riga), dalla pagina del workflow
(*⟲ Run history*, e il rimando "full history" sulla tabella *Recent runs*), dalla
pagina del run (link *history*) e dall'audit. Mostra l'**albero dei run** espandibile:
per ogni run lo stato, il timestamp e la sequenza di eventi (step, gate, file), con
link "open ▷" alla pagina live del run e link **👁** per visualizzare i file degli
eventi `FILE_*`. Si **auto-aggiorna ogni 5s** preservando i nodi espansi. La pagina
**Audit** resta dedicata alla verifica della catena e ai record grezzi.

## Suggerimenti path nei parametri/variabili (designer)

I campi *value* di parametri, variabili e `setvar` hanno una **tendina** di suggerimenti
(builtin come `${feedDir}`, `${stepDir}`, `${landingIn}`, `${landingOut}`, `${dir.<step>}`,
più le variabili del workflow). Digitando, premendo **freccia destra** o **Tab** il
suggerimento viene **completato** nel campo (diventa valore reale).


## Variabili a livello di workflow

Oltre alle variabili builtin, ogni workflow può definire **variabili proprie**, valide per
tutta l'esecuzione, dal blocco `<variables>` dell'XML o dalla sezione *Workflow variables*
del designer. Si usano come `${nome}` in parametri, query, path e condizioni dei gate.

```xml
<variables>
    <var name="dateFormat" value="YYYY/MM/DD"/>
    <var name="library" value="MYLIB"/>
</variables>
```

## Source ID

Oltre al **feed id**, ogni workflow può avere un **source id** (l'applicazione di
origine, espressa in modo generico): attributo `sourceId` sul tag `<workflow>` o campo
*Source ID* nel designer. È mostrato in dashboard e nella pagina del workflow, è incluso
nel filtro di ricerca, ed è disponibile a runtime come variabile `${sourceId}`.

## Dashboard: filtro e re-run

* In dashboard c'è un **campo di ricerca** che filtra i workflow per feed id, nome,
  descrizione o schedulazione (filtro client-side, istantaneo).
* Nella pagina di **esito di un run** il pulsante **↻ Re-run** rilancia lo stesso feed
  (compare quando il run è terminato; durante l'esecuzione c'è invece **■ Stop**).
* Nel diagramma BPMN il nodo finale implicito è etichettato **END** e, a fine run,
  diventa **SUCCESS** o **FAILED** in base all'esito.

## API (usate dalla UI, utilizzabili anche da script)

| Metodo | Path | Descrizione |
|---|---|---|
| POST | `/api/workflows/{feedId}/run` | avvio manuale |
| GET | `/api/runs/{feedId}/{runId}` | stato del run (JSON) |
| POST | `/api/runs/{feedId}/{runId}/decision?approve=true&note=...` | decisione gate manuale |
| POST | `/api/runs/{feedId}/{runId}/stop` | stop di un run attivo o in attesa |
| GET | `/api/runs/{feedId}/{runId}/log/{stepId}?tail=500` | log step (testo) |
| GET | `/api/audit/{feedId}/verify` | verifica catena audit |
| POST | `/api/reload` | ricarica XML e ripianifica i cron |

L'utente registrato nell'audit è preso dall'header `X-User` se presente (comodo dietro
reverse proxy con autenticazione), altrimenti `operator@<ip>`.

## Verifica manuale dell'audit (senza applicazione)

Il file `_logs/audit_{feedId}.jsonl` è verificabile a mano: per ogni riga,
`hash` deve essere lo SHA-256 della stringa
`seq|ts|feedId|runId|node|event|user|detailsJson|prevHash`
e `prevHash` deve coincidere con l'`hash` della riga precedente (prima riga: `GENESIS`).
Esempio di lettura rapida in PowerShell:

```powershell
Get-Content audit_LA-EOR-001.jsonl | ForEach-Object { $_ | ConvertFrom-Json } |
  Format-Table seq, ts, event, node, user
```

## Esecuzione script e execution policy

Gli step **non** vengono lanciati come file `.ps1`. Un piccolo bootstrap (poche righe,
dimensione fissa) legge il contenuto dello script **come dato**
(`[IO.File]::ReadAllText(...)` in UTF-8) e lo esegue con `[ScriptBlock]::Create(...)`.

La execution policy di PowerShell (AllSigned/RemoteSigned) richiede la firma solo per i
**file** `.ps1` *eseguiti come script*; uno script block creato a runtime da una stringa
ha "command provenance" e quindi non viene bloccato. Questo evita l'errore
*"file is not digitally signed"* anche quando la policy è imposta da **Group Policy**
(caso in cui `-ExecutionPolicy Bypass` da riga di comando viene ignorato). Il blocco
`param()` continua a funzionare perché lo script block viene invocato con i parametri
in coda: `& $sb -Nome 'Valore'`.

Poiché il contenuto arriva da disco e **non** dalla riga di comando, **non c'è limite di
lunghezza**: funziona anche con script da migliaia di righe.

Caveat: lo script gira come script block, quindi `$PSScriptRoot` e
`$MyInvocation.MyCommand.Path` non sono valorizzati (non c'è un file "in esecuzione").
Se uno script ha bisogno del proprio path/cartella, passarlo come parametro
(es. `-ScriptHome ${dir.<stepId>}` o un path esplicito) invece di derivarlo da
`$PSScriptRoot`. La working directory del processo è comunque la directory dello step.

## Modello di esecuzione (coda FIFO)

Le esecuzioni dei workflow sono **serializzate** da una coda FIFO globale a singolo
worker: un workflow che parte prima di un altro **termina completamente** prima che il
successivo inizi. Questo protegge le risorse del server (niente run concorrenti che si
contendono CPU/IO). L'unica concorrenza è il **fan-out `forEach`** all'interno di uno
step (parallelismo *esplicito*): gira dentro al run che occupa il worker, senza
sovrapporsi ad altri run.

Conseguenze:

* Un run appena lanciato è in stato **QUEUED** finché il worker non lo preleva, poi
  passa a **RUNNING**. La dashboard e la pagina run mostrano lo stato; il pulsante Stop
  funziona anche su un run in coda (lo chiude come ABORTED senza farlo mai partire).
* La guardia "un run alla volta per feed" resta attiva: se un trigger cron scatta mentre
  un run dello stesso feed è ancora QUEUED/RUNNING, il nuovo avvio viene rifiutato
  (`RUN_REJECTED`) — utile per i listener al minuto, che così non accodano duplicati.
* I **gate manuali** sono l'eccezione alla contiguità: quando un run si sospende in
  attesa di approvazione libera il worker (non avrebbe senso bloccare l'intera coda per
  ore); la sua ripresa, dopo `decide()`, rientra in fondo alla coda FIFO. Quindi tra la
  sospensione e la ripresa un altro run può girare, ma due run non eseguono mai
  contemporaneamente.

## Stop di un job/workflow

Dalla pagina del run il pulsante **■ Stop** (e l'azione Stop in dashboard sull'ultimo
run attivo) interrompe l'esecuzione:

* se uno step è in corso, il processo PowerShell viene terminato e il run si chiude
  come `ABORTED`;
* se il run è fermo su un gate manuale (`WAITING_APPROVAL`), viene chiuso subito come
  `ABORTED`.

Lo stop è tracciato nell'audit come `RUN_STOP_REQUESTED` / `STEP_ABORTED` /
`RUN_ABORTED`. API: `POST /api/runs/{feedId}/{runId}/stop`.

## Note operative

* **Un run alla volta per feed**: avvii concorrenti vengono rifiutati e tracciati in audit.
* Durante un gate manuale il feed non occupa il motore: il run resta sospeso su disco e
  riprende alla decisione, anche dopo riavvio dell'applicazione.
* Anti-loop: guardia configurabile `orchestrator.max-transitions` (default 500).
* Sviluppo consigliato: partire dalla base verificata e applicare modifiche una alla
  volta, testando sull'ambiente di rete aziendale dopo ogni passo.
