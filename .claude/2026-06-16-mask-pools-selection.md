# 2026-06-16 — Mask pools: dati fake, selezione per-file, pagina di gestione

## Cosa (4 punti richiesti)

1. **Nomi/cognomi fake** (it + intl): lettere interne invertite (Marco->Macro). Generazione
   one-off (zero costo a runtime). firstnames_it (Giusppee...), firstnames_international
   (Aaidl...), lastnames_it (Abtae...), lastnames_international (Smtih...).
2. **company_* in it + internazionale**: company_animals/colors/actions/suffixes presenti
   sia *_it sia *_international.
3. **Selezione it/intl per-file via tendina nello step mask** (non application.properties).
   8 categorie combinabili liberamente (es. animali it + colori intl).
4. **File pool visualizzabili/sostituibili** dalla pagina Pool files.

## Backend

* MaskGenerators: gia' parametrico (firstNameFile/lastNameFile/cityFile/streetFile/company*
  File, default _it); pools.get(field).
* InternalSteps.runMask: legge i param *File via NUOVO helper poolFile() (hardened a bare
  filename) -- FIX: il refactor precedente chiamava poolFile senza definirlo -> build rotta,
  ora risolto.
* MaskPools: BUNDLED[15], isBundled, hasExternal, getExternalDir, readRaw (override esterno
  else bundled).
* ApiController: GET /api/mask/pools/files (catalogo bundled∪esterni con source/managed),
  GET /api/mask/pools/download?path=, POST /api/mask/pools/files (upload/replace su
  mask-pools-dir), POST .../files/create, POST .../files/delete, GET .../alias-suggest.
  Forma identica al pannello shared per riusare filespanel.js.

## Frontend

* designer.html: fetch catalogo + helper poolOptions/poolSel; 8 tendine nella sezione mask
  (prefissi: firstnames/lastnames/cities|caps/streets/company_*). "Italian pool %" -> legacy.
* pools.html (NEW) su /pools (PageController), riusa filespanel.js su api/mask/pools/;
  mostra la dir esterna o avvisa di settare orchestrator.mask-pools-dir. Link in dashboard.

## Verifiche

* 76 classi compilate. TestPools: BUNDLED=15, firstnames_it=182 (primo "Giusppee" = scramble),
  readRaw international OK, fallback null per inesistenti.
* designer/pools/dashboard JS validati; no \n/\r; Thymeleaf-safe.

## Non testabile in sandbox (verifica su UBS)

* runMask reale con i file selezionati; upload/replace su mask-pools-dir; rendering tendine
  e pagina Pool files nel browser corporate. Logica catalogo/lettura testata.

## Deploy

* Per sostituire i pool, impostare orchestrator.mask-pools-dir (path assoluto) nell'
  application.properties esterno; senza, i pool restano quelli bundled (read-only).
  Redeploy WAR + Ctrl+F5.
