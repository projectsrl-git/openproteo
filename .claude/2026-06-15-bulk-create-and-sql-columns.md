# 2026-06-15 — Bulk create da CSV + SELECT generata dal dataschema

## Cosa

1. **Step `sql`: espansione `{{columns}}`.** Nella query si può usare il segnaposto
   `{{columns}}`, espanso a runtime con i `name` del dataschema JSON indicato dal param
   `columnsSchema`. Identificatori nudi di default (`SELECT NDG, NOMINATIVO FROM ...`),
   `columnQuote=double` per virgolettarli. Retrocompatibile: senza `{{columns}}` la query
   è usata invariata. La lista campi si aggiorna da sola al cambio del dataschema.
2. **Bulk create da CSV** (pagina `/bulk` + `POST /api/workflows/bulk`). Da un workflow
   template + un CSV genera N workflow XML nella workflows-dir. Colonne riservate del CSV:
   `feedId` (obbligatoria, nome file), `name`, `sourceId`, `description`; ogni altra colonna
   diventa una variabile di workflow (`originTableName` è quindi solo una colonna). File
   esistenti saltati salvo overwrite; XML validato col parser reale prima della scrittura;
   reload del registro a fine generazione.

## Perché

Servono ~100 feed quasi-identici, che differiscono solo per feedId/name/source/description
e per la tabella di origine. La coppia "SELECT dal dataschema" + "bulk da CSV" rende il
template totalmente generico e la creazione massiva ripetibile dall'interfaccia, senza
editare a mano 100 XML.

## File toccati

* `engine/InternalSteps.java` — runSql: espansione `{{columns}}`; helper statici
  `buildColumnList` (testabile) e `readSchemaColumnNames` (Jackson).
* `parser/BulkWorkflowGenerator.java` — NEW: genera XML per riga CSV via DOM
  (clona il template, setta attributi root + `<description>` + `<variables>`), CSV
  quote-aware. JDK puro, niente Jackson.
* `web/ApiController.java` — `POST /api/workflows/bulk` (template feedId + csv +
  overwrite + delimiter); valida ogni XML con il parser reale (file tmp) e fa reload.
* `web/PageController.java` — route `/bulk` con elenco feed per la tendina template.
* `templates/bulk.html` — NEW: pagina con select template, textarea CSV, opzioni,
  tabella risultati. JS senza `\n`/`\r` letterali, Thymeleaf-safe.
* `templates/dashboard.html` — link "Bulk create" nel toolbar.
* `workflows/_TEMPLATE-extract.xml`, `samples/bulk_feeds_example.csv` — NEW esempi.
* `README.md`, `CLAUDE.md` — documentazione.

## Verifiche

* 73 classi compilate; 14 workflow parsano (incluso il nuovo template).
* `TestBulk`: buildColumnList (plain e double-quote), generazione 3 feed da CSV,
  feedId/name/sourceId/description settati, `originTableName` var aggiornata, virgola
  dentro campo quotato preservata, `{{columns}}` lasciato per l'espansione a runtime,
  XML generato pulito e ben indentato.
* `bulk.html`: JS validato (node --check), nessun `\n`/`\r` letterale, Thymeleaf-safe.

## Non testabile in sandbox (verifica su UBS)

* `readSchemaColumnNames` usa Jackson (lettura dataschema): come per validate/mask, va
  verificato col deploy reale. La logica di costruzione lista campi (`buildColumnList`)
  è testata.
* L'endpoint `/api/workflows/bulk` scrive file e fa reload: provato a livello di
  generazione (TestBulk); il giro HTTP completo va provato sul deploy.

## Decisioni confermate con l'utente

* Approccio: funzione built-in "Bulk create da CSV" (XML reali), non template "vivo".
* Dataschema **per feed** (colonne diverse) -> `columnsSchema=${feedDir}/dataschema.json`.
* Identificatori DB2 **nudi in maiuscolo** (quoting opzionale via `columnQuote`).

## Da fare al deploy

* Ogni feed generato si aspetta il proprio `dataschema.json` nel rispettivo `feedDir`
  (path referenziato da `columnsSchema`). Vanno messi lì (non li crea il bulk).
* Redeploy WAR + Ctrl+F5 (nuova pagina /bulk, dashboard, backend).

## Follow-up possibili

* Far caricare al bulk anche i dataschema per feed (oggi vanno posati a mano).
* Anteprima a secco ("dry-run") che mostra i feedId generati senza scrivere i file.
