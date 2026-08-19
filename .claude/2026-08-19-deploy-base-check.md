# 2026-08-19 — Deploy: il patch dichiara la sua base, e lo script la verifica

## Cosa

`deploy_openproteo_patch.bat` prende **lo zip piu' recente** in `D:\downloads` e non ha modo di sapere su quale commit quel patch sia stato generato. Due volte in questo progetto e' arrivato un patch costruito su un `main` piu' vecchio: `git apply` lo ha rifiutato correttamente, ma solo **dopo** il tentativo.

Da ora il nome porta la base — `openproteo-<argomento>-<commit>.zip`, patch `<argomento>-<commit>.patch` — e lo script la confronta con `git rev-parse --short HEAD` prima di applicare.

## Il blocco

`tools/deploy_patch_base_check.bat`, versionato nel repo. Va incollato in `deploy_openproteo_patch.bat` (che vive fuori dal repo) **dopo** l'estrazione del patch, quando `PATCHFILE` e' noto, e **prima** di `git apply --check`.

## Tre esiti, non due

Questa e' la parte progettata piu' che scritta:

* **base uguale a HEAD** → prosegue in silenzio;
* **base diversa** → si ferma e distingue i due casi possibili, perche' il rimedio e' opposto: o manca un `git pull` (il repo e' indietro), oppure il patch e' vecchio e va rigenerato;
* **nessuna base nel nome** → **avvisa e prosegue**. Tutti gli zip consegnati prima di oggi non hanno il suffisso, e rifiutarli trasformerebbe una rete di sicurezza in un ostacolo. Il criterio e' rigido: sette caratteri esadecimali dopo l'ultimo trattino, altrimenti non e' un hash.

## Niente label, niente `goto` — di proposito

Il repo memorizza tutto con terminazioni LF (`.gitattributes`: `* text=auto eol=lf`) e `cmd.exe` legge un `.bat` **per offset di byte**: un `goto` in un file LF-only puo' atterrare nel punto sbagliato. Gli altri `.bat` qui dentro sono LF e funzionano perche' non saltano mai.

La prima stesura di questo blocco usava quattro label e due loop con `goto` — per estrarre l'ultimo segmento del nome e per contarne i caratteri. Riscritto senza:

* l'ultimo segmento si ottiene sostituendo ogni `-` con uno spazio e lasciando che un `for` assegni ogni token a turno: **vince l'ultima assegnazione**;
* «esattamente sette caratteri» si esprime con `!S:~6,1!` non vuoto e `!S:~7!` vuoto, senza contare nulla.

Il file non contiene piu' un solo `goto`, quindi la questione non si pone.

## Limite noto

Sette cifre decimali sono esadecimali valide, quindi un nome come `qualcosa-1234567` verrebbe letto come base e bloccherebbe. E' un falso positivo che **ferma** invece di lasciar passare, e il messaggio spiega cosa fare; l'alternativa — pretendere almeno una lettera — lascerebbe passare hash legittimi tutti numerici, che sono altrettanto possibili. Meglio fermarsi di piu' che di meno, su uno script che scrive nel repo.

## Verifica

Il `.bat` **non e' stato eseguito**: il sandbox non ha `cmd.exe`. E' stata simulata la **decisione** che codifica — stessa estrazione del suffisso (sostituzione dei trattini e ultimo token), stesso test esadecimale, stessi tre esiti — su undici casi, incluso quello realmente accaduto (`elarxml-batch6-7-0f712c8` contro HEAD `4bce645` → blocco) e i nomi senza suffisso delle consegne precedenti (→ avviso, non blocco). Questo prova la regola, **non la sintassi batch**: quella va provata alla prima consegna.

## File toccati

* `tools/deploy_patch_base_check.bat` — nuovo, nel repo.
* `deploy_openproteo_patch.bat` — **non** nel repo: il blocco va incollato a mano.
* `CLAUDE.md` — la convenzione di naming era gia' entrata col commit precedente; qui si aggiunge il rimando al blocco.
