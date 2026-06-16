# 2026-06-15 — Bulk create: due CSV, mappatura nomi colonna, schema inline

## Cosa (evoluzione del bulk del turno precedente)

1. **Nomi colonna configurabili.** Il CSV di caricamento può avere colonne in più e con
   nomi diversi: per ogni campo necessario c'è una casella in cui indicare come si chiama
   la colonna nel CSV. Le colonne non mappate vengono ignorate (prima ogni colonna extra
   diventava una variabile — ora no).
2. **dataschema / displayschema inline nel CSV.** Se mappate e con contenuto JSON ben
   formato (tra doppi apici nel CSV, `"` raddoppiati), vengono scritte come
   `feedDir/dataschema.json` / `feedDir/displayschema.json`. JSON non valido -> workflow
   creato comunque, schema saltato con avviso.
3. **Secondo CSV (tables).** Mappa `feedId` -> `tableName`, joinato al primo per feedId;
   il valore diventa la variabile di workflow `originTableName` (nome configurabile).

## File toccati

* `parser/BulkWorkflowGenerator.java` — riscritto: `Mapping` (nomi colonna), `joinTable`
  (CSV#2 -> feedId/tableName), `Item` ora porta dataschemaJson/displayschemaJson/tableName.
  Solo le colonne mappate sono consumate. DOM+CSV, no Jackson.
* `web/ApiController.java` — `/api/workflows/bulk` con i nuovi parametri (mappatura, csv2,
  delimiter2, tableVar). Valida i JSON schema con Jackson (`mapper.readTree`) e li scrive
  nel feedDir (via `registry.layout(feedId).provision()`) dopo il reload.
* `templates/bulk.html` — due sezioni (Feeds CSV / Tables CSV) con le caselle di mappatura
  e il nome variabile tabella; risultati con colonna Table.
* `samples/bulk_tables_example.csv` — NEW; `bulk_feeds_example.csv` resta.
* `README.md`, `CLAUDE.md` — documentazione aggiornata.

## Verifiche

* 74 classi compilate.
* `TestBulk` v2: mappatura con nomi colonna custom (FEED/FRIENDLY/SRC/DESCR), colonna
  spazzatura ignorata, join tabelle da CSV#2 con nomi custom, `originTableName` iniettata,
  **dataschema JSON un-escaped correttamente** dai doppi apici (`""`->`"`),
  displayschema assente gestito, errore se la colonna feedId non esiste.
* `bulk.html` validato (node --check), no `\n`/`\r` letterali, Thymeleaf-safe.

## Non testabile in sandbox (verifica su UBS)

* Validazione JSON via Jackson (`mapper.readTree`) e scrittura schema nel feedDir: la
  logica di parsing/unescape CSV è testata; il giro completo (validazione + provision +
  scrittura file) va provato sul deploy.

## Decisioni confermate con l'utente

* Due CSV che contengono tutto; nomi colonna mappabili via caselle di testo.
* dataschema/displayschema inline come JSON nel CSV#1 (doppi apici + escape).
* Tabella SQL nel CSV#2, joinata per feedId -> variabile originTableName.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5 (pagina /bulk, backend, generatore).
