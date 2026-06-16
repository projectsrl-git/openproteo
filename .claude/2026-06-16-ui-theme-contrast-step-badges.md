# 2026-06-16 — UI: contrasto label, tema chiaro/scuro, badge step (batch 1/2)

Primo dei due batch sulle richieste UI. Qui solo il "look"; il batch successivo
copre cancellazione workflow, modali e multi-selezione (azioni interconnesse).

## Cosa

1. **Contrasto label.** Le label uppercase (sottotitolo topbar, label dei campi,
   sub-label di sezione, intestazioni colonna/cheat-sheet) usavano `--ink-faint`
   (#5a677c, troppo basso). Introdotta `--label` ad alto contrasto e ripuntate.
2. **Tema chiaro/scuro** con toggle nella topbar. Default scuro; preferenza
   persistita in localStorage (`op-theme`); `data-theme="light"` su <html> con
   palette chiara dedicata. Snippet anti-flash nel <head> + `static/js/theme.js`
   (bottone iniettato nella topbar) inclusi in tutte le 10 pagine.
3. **Badge degli step** nel designer: ogni nodo ha un badge numerato circolare
   (ambra per gli step, blu per i gate), header con barra colorata e nome dello
   step, per separarli e ritrovarli facilmente.

## File toccati

* `static/css/app.css` — `--label` (dark+light), blocco `:root[data-theme=light]`,
  `.theme-toggle`, `.node-card .nc-head/.step-badge/.nc-name`.
* `static/js/theme.js` — NEW: anti-flash + toggle + localStorage (con guardia).
* `templates/designer.html` — header nodo ristrutturato col badge.
* tutte le pagine con topbar — incluso theme.js + snippet anti-flash nel <head>.
* `CLAUDE.md` — vincolo tema/label.

## Verifiche

* 74 classi compilate; theme.js validato (node --check); nessun `\n`/`\r` letterale
  negli inline script; tutti i template Thymeleaf-safe.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5 (app.css, theme.js, tutti i template cambiati).

## Prossimo batch (2/2)

* #2 tasto cancellazione workflow, #3 modali al posto di alert/confirm, #4
  multi-selezione con Delete/Run massivi (questi tre insieme).
