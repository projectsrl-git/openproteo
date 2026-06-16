# 2026-06-16 — Deploy: COMMIT_MSG nello zip + commit/push automatici

## Cosa

1. **COMMIT_MSG.txt nel pacchetto.** Da ora il file viaggia dentro lo zip (openproteo/),
   così ogni zip porta il proprio messaggio di commit (versionato).
2. **deploy_openproteo.bat: commit + push automatici.** Tolto `/XF COMMIT_MSG.txt` dal
   robocopy (il messaggio ora arriva dallo zip e sincronizza nel working dir). Dopo una
   build OK, una subroutine `:commit_push` esegue `git add -A`, e se ci sono modifiche
   `git commit -F COMMIT_MSG.txt` + `git push`. Best-effort: push fallito stampa un avviso
   ma non blocca il deploy locale; nessuna modifica -> nessun commit.

## File toccati

* deploy_openproteo.bat (consegnato a parte, NON nel repo): robocopy senza /XF; blocco info
  git sostituito da `call :commit_push` dopo il controllo del WAR; subroutine in fondo.
* CLAUDE.md, .claude/ — convenzione documentata.
* COMMIT_MSG.txt — ora presente nel repo/zip.

## Note

* Il commit/push avviene SOLO dopo `mvn package` OK, per non pushare codice che non compila.
* Richiede credenziali git gia' memorizzate (Credential Manager) per push non interattivo.
* Questo turno ha toccato solo il .bat e i doc: il codice runtime e' invariato dal turno
  del loop a blocco.

## Da fare al deploy

* Sostituire deploy_openproteo.bat. Da qui in poi il bat committa e pusha da solo.
