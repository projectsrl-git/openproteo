# 2026-06-16 — Fix: nome file spezzato per carattere nel pannello file

## Problema (da screenshot designer)

I nomi file senza spazi nella colonna FILE di WORKFLOW FILES andavano a capo
un carattere per riga (colonna verticale illeggibile).

## Causa

app.css: `.wf-files-box td.mono.small { word-break: break-all; }` + colonna File
senza min-width -> la colonna collassava e break-all spezzava il token per carattere.

## Fix (solo CSS)

* break-all -> break-word + overflow-wrap: break-word.
* min-width: 180px su th/td:first-child di .wf-files-box (colonna File).

Scope .wf-files-box (designer + workflow). Pagine shared/pools non interessate.
Nessuna modifica Java/JS; 76 classi invariate.
