# 2026-06-16 — Bulk: template multi-colonna per name/sourceId/description

## Cosa

In bulk create, i campi mappati **name**, **sourceId**, **description** ora accettano un
**template**: i token `{Nome Colonna}` vengono sostituiti col valore della colonna del CSV,
il resto è testo letterale. Permette di **concatenare più colonne**, es.
`{Banca} - {Codice ICTO}`. I nomi colonna possono contenere **spazi** (vengono presi
interi tra le graffe). Un valore senza graffe resta un singolo nome di colonna
(retrocompatibile). feedId, dataschema, displayschema restano colonne singole.

## File toccati

* `parser/BulkWorkflowGenerator.java` — helper `resolveField(expr,header,row)` (template
  vs singola colonna); usato per name/sourceId/description in generate().
* `templates/bulk.html` — nota esplicativa sulla sintassi template.
* `README.md`, `CLAUDE.md` — doc.

## Verifiche

* 74 classi compilate.
* `TestField`: `{Banca} - {Codice ICTO}` -> "Intesa - IC0099" (header con spazio),
  template per name/description, retrocompat con nome singolo, token mancante -> vuoto.
* bulk.html validato (node --check), no \n/\r, Thymeleaf-safe.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5.
