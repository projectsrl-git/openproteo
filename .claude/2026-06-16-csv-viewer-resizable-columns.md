# 2026-06-16 — Viewer CSV: colonne ridimensionabili

## Cosa

Nel viewer CSV (griglia virtualizzata server-paged) le colonne ora si possono allargare:
maniglia di resize sul bordo destro di ogni intestazione, trascinabile (min 40px). Le
larghezze sono per-colonna e persistono durante lo scroll virtuale (le celle del body
vengono ricreate ma rileggono l'array delle larghezze).

## Come

* `static/js/viewer.js`, build(): array `colW` per colonna + `rowWidthPx()`; header e celle
  body usano `colW[i]`; `addResizer(headCell, idx)` gestisce il drag (mousemove/mouseup in
  capture), aggiorna la larghezza header live e fa `vl.redraw()` (throttle via rAF) per il body.
* `static/css/app.css`: `.vgrid-cell.head{position:relative}`, `.vgrid-resizer` (col-resize).

## Verifiche

* viewer.js validato (node --check); nessun `\n`/`\r` letterale. Nessuna modifica Java.

## Non testabile in sandbox

* Drag reale del mouse e redraw della griglia: comportamento client, da provare sul deploy
  con un CSV vero (es. EORFULL).

## Da fare al deploy

* Redeploy WAR + Ctrl+F5 (viewer.js, app.css).
