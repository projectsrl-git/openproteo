# 2026-06-16 — File list: ricerca/data/ordinamento + barra azioni in cima al designer

## Cosa

1. **Pannello file (workflow page + shared)**: ricerca per nome file (filtro live),
   colonna **Modified** (da `f.modified` gia' restituito dal backend), e ordinamento
   per colonna (File/Type/Alias/Size/Modified; click intestazione, secondo click inverte).
   Convertito anche il `confirm()` rimasto in filespanel.js a opConfirm (con fallback).
2. **Designer**: barra azioni in cima (solo edit mode), sticky, con Run now / Active run /
   Validate & save / Run history / Audit / Workflow page / Duplicate / Delete. Cosi' dopo
   il salvataggio (che riporta in cima) i comandi sono subito a portata senza scorrere in
   fondo. `dSetRunning` ora aggiorna TUTTI i pulsanti Run via classe (.js-drun/.js-drunlink),
   evitando ID duplicati.

## File toccati

* `static/js/filespanel.js` — load/render riscritti: stato {q,key,dir}, ensureShell con
  search box persistente, renderTable con filtro+sort+colonna data; del() -> opConfirm.
* `static/css/app.css` — .file-toolbar/.file-search-input/th.sortable; .top-actions sticky.
* `templates/designer.html` — barra .top-actions dopo i banner; classi js-drun sui run
  controls in fondo; dSetRunning multi-elemento.

## Verifiche

* filespanel.js + designer inline JS validati (node --check); nessun `\n`/`\r` letterale;
  tutti i template Thymeleaf-safe. Nessuna modifica Java (74 classi invariate).

## Non testabile in sandbox (verifica su UBS)

* Rendering reale del pannello file con dati veri (ricerca/sort/data) e la barra sticky in
  cima: comportamento client, da provare sul deploy.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5 (filespanel.js, app.css, designer.html).
