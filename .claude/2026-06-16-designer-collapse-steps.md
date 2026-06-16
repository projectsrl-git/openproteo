# 2026-06-16 — Designer: collapse/expand del dettaglio step

## Cosa

Ogni node-card del designer ha un chevron nell'header per collassare/espandere il dettaglio
(i campi). Da collassata resta visibile header con badge numerato, tipo e nome dello step,
così la lista è scorribile e navigabile con molti step. Pulsanti "Collapse all / Expand all"
nella toolbar dei nodi. Lo stato è per-nodo (chiave = id del nodo, fallback indice) e
sopravvive ai re-render (modifiche campi, riordino, duplica).

## Come

* `templates/designer.html`: corpo della card avvolto in `.nc-body`; chevron `.nc-toggle`
  nell'header; `collapsedNodes` map + `nodeKey`/`toggleNode` (senza closest: risalita DOM
  manuale, toggle classe) + `collapseAllNodes`/`expandAllNodes`.
* `static/css/app.css`: `.nc-toggle` (rotazione chevron), `.node-card.collapsed .nc-body
  {display:none}`, header tratteggiato da collassato.

## Verifiche

* designer inline JS validato (node --check); nessun `\n`/`\r` letterale; Thymeleaf-safe.
  Struttura aperture/chiusure .nc-body/.node-card bilanciata. Nessuna modifica Java.

## Non testabile in sandbox

* Toggle reale e persistenza dello stato durante editing/riordino: comportamento client,
  da provare sul deploy.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5 (designer.html, app.css).
