# 2026-06-15 — Dashboard: top summary, paginazione, coda live + fix UBS

## Cosa

1. Tabella feed in dashboard ora **paginata**: selettore *Show* 5 (default) /
   10 / 25 / 50 / 100 con Prev/Next e label "1–5 of 12 · page 1/3". Resetta
   alla pagina 1 a ogni cambio di ricerca, filtro o pageSize.
2. Nuovo **top summary** in stile `EOR_viewer.html`, con tre card:
   * **Sources** — distribuzione dei workflow per `sourceId` con conteggio,
     percentuale e barra proporzionale. Ogni voce è cliccabile e diventa un
     **filtro per source** della tabella (chip "source: X ✕" nel toolbar);
     "All sources" azzera.
   * **Recent runs** — ultima run **per workflow**, top 5 più recenti (una
     riga per workflow anche se ne ha decine), con stato e link al run.
   * **Execution queue (FIFO)** — live (polling ogni 4s su `/api/queue`):
     ciò che è in esecuzione (▶) e i prossimi in coda (1, 2, …). Mostra
     "Queue empty" quando non c'è nulla.

## Perché

Sopra una certa soglia di feed la lista diventa illeggibile; serviva
paginazione + un colpo d'occhio in alto su volumetria per sorgente, ultime
attività e occupazione delle (poche) risorse di esecuzione.

## File toccati

* `src/main/java/com/legalarchive/orchestrator/engine/WorkflowEngine.java`
  → metodo `queueSnapshot()`: RUNNING / WAITING_APPROVAL prima, QUEUED dopo,
  ordinati per `startTs` (ms-precision).
* `src/main/java/com/legalarchive/orchestrator/web/ApiController.java`
  → endpoint `GET /api/queue` che restituisce lo snapshot in JSON.
* `src/main/java/com/legalarchive/orchestrator/web/PageController.java`
  → attributi `sourceStats` (con percentuali) e `recentRuns` aggiunti al
  model della dashboard.
* `src/main/resources/templates/dashboard.html`
  → tre card riepilogo, toolbar con `Show` e chip filtro sorgente, pager,
  blocco JS per paginazione + filtro sorgente + polling coda.
* `src/main/resources/static/css/app.css`
  → stili `.summary-grid`, `.sum-card`, `.kv`, `.bar`, `.run-item`,
  `.q-item`, `.pager`.
* `README.md`
  → nuova sezione "Dashboard".

## Trabocchetto sistemato in corsa (visibile su UBS)

Il top summary in locale appariva corretto, su UBS si vedeva con le tre card
**impilate verticalmente** e colori sbagliati. Due cause:

1. avevo usato `display: grid` con `repeat(auto-fit, minmax(280px, 1fr))`,
   non supportato dal browser corporate UBS (cade nel default → stacking
   verticale);
2. il blocco CSS della summary usava variabili **inesistenti** nel tema
   (`--panel`, `--bg-hover`, `--fg-mute`, `--accent: #3b82f6`).

Riscritto con **`display: flex` + `flex-wrap: wrap` + `flex: 1 1 280px`** e
prefissi `-webkit-`, e ogni `var(--…)` ora referenzia solo le variabili
realmente presenti in `app.css` (`--bg-panel`, `--line`, `--line-soft`,
`--ink`, `--ink-dim`, `--ink-faint`, `--accent` ambra `#f5a623`, `--run-bg`).

Annotato in `CLAUDE.md` come vincolo permanente per evitare di ripeterlo.

## Verifiche

* Build sandbox: 71 classi compilate, zero warning.
* JS dashboard validato (`node --check`).
* Template Thymeleaf-safe (nessun `[[` o `[(` fuori dal pattern di inlining).
* Nessun `\n` / `\r` letterale nel JS servito al browser.
* Test end-to-end via jsdom della logica dashboard: pageSize 5/10, ultima
  pagina, label corretta, filtro sorgente (kv attiva + chip), clear,
  search testuale, render coda con run attiva, stato coda vuota.

## Da fare al deploy

* Redeploy del WAR su Tomcat (stop, cancella `webapps/openproteo.war` e
  `webapps/openproteo/`, copia il nuovo, start).
* **Ctrl+F5** dopo il redeploy: `app.css` e `dashboard.html` sono cachati
  pesantemente.

## Follow-up possibili (non ora)

* Rendere live anche "Recent runs" e la distribuzione sorgenti (oggi si
  aggiornano al reload pagina).
* Aggiungere ▶ / ■ direttamente sulle righe della coda.
* Salvare il `pageSize` scelto in `localStorage` per persistere la preferenza
  tra ricariche.
