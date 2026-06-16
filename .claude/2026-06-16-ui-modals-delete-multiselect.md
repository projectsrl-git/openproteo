# 2026-06-16 — UI batch 2/2: delete workflow, modali, multi-selezione

## Cosa

2. **Cancellazione workflow.** Endpoint `POST /api/workflows/{feedId}/delete`: cancella
   il file XML dalla workflows-dir e fa reload del registro; rifiuta (409) se c'e' un run
   attivo per il feed. Storia run e dati su disco restano. Bottone "Delete workflow" nel
   designer (in edit mode).
3. **Modali al posto di alert/confirm.** `static/js/modal.js` (incluso in tutte le pagine)
   espone `opConfirm(msg,onYes,opts)` e `opAlert(msg,opts)` con div modali a tema
   (overlay, ESC/Enter, bottone danger). Convertiti TUTTI i confirm() (14 punti) e gli
   alert() dei template.
4. **Multi-selezione dashboard.** Checkbox per riga + select-all (solo righe visibili) +
   barra azioni che compare con >=1 selezione: Run massivo e Delete massivo, via loop
   client-side sugli endpoint per-feed. Conferme tramite opConfirm.

## File toccati

* `web/ApiController.java` — endpoint delete workflow.
* `static/js/modal.js` — NEW: opConfirm/opAlert.
* `static/css/app.css` — stile modali (.op-modal*), .btn.danger, .bulk-bar, .cbcol.
* `templates/dashboard.html` — checkbox col (+colspan 7->8), barra bulk, JS
  multiselect/bulkRun/bulkDelete (senza closest, risalita DOM manuale), stopRun->opConfirm.
* `templates/designer.html` — bottone Delete workflow + deleteWorkflow(); run/save/
  overwrite/duplicate -> opConfirm.
* `templates/run.html`, `runs.html`, `workflow.html`, `datasources.html`, `bulk.html` —
  confirm()/alert() -> opConfirm/opAlert; modal.js incluso.

## Verifiche

* 74 classi compilate; tutti gli inline JS validati (node --check); modal.js/theme.js OK;
  nessun confirm()/`\n`/`\r` residuo negli inline script; tutti i template Thymeleaf-safe.

## Non testabile in sandbox (verifica su UBS)

* Giro HTTP di delete workflow e bulk run/delete (endpoint scrivono/cancellano file e
  avviano run): logica client + endpoint compilati; il flusso completo va provato sul deploy.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5 (nuovi JS, CSS, tutti i template cambiati).

## Vincolo nuovo

* Niente confirm()/alert() nativi nei template: usare opConfirm/opAlert.
* Niente Element.closest (browser UBS tipo IE): risalita DOM manuale.
