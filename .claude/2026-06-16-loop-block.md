# 2026-06-16 — Loop a blocco LOOP/ENDLOOP (catena di step per file)

## Cosa

Aggiunto un costrutto di loop sequenziale a blocco per ripetere una CATENA di step una
volta per ogni elemento di una lista (caso tipico: i file multipli prodotti
dall'estrazione con split, ${csvFiles}).

Nodi LOOP e ENDLOOP (marker piatti, coerenti con la lista nodi). Il LOOP risolve `over`
(split per `delimiter`, default ;), esegue i nodi fino al suo ENDLOOP una volta per item in
sequenza, esponendo ${itemVar}/${indexVar}/${countVar} (default item/loopIndex/loopCount).
Lista vuota -> blocco saltato. Annidamento supportato (matching per profondita').

Robustezza: lo stato del loop (items/n/i) e' persistito in run.vars, quindi sopravvive a una
pausa su gate manuale dentro il blocco. Il salto di fine-blocco torna al primo nodo del
corpo (non al nodo LOOP), quindi l'inizializzazione avviene una sola volta.

## File toccati

* model/def/LoopDef.java, LoopEndDef.java — NEW.
* parser/WorkflowXmlParser.java — <loop>/<endloop>.
* parser/WorkflowXmlWriter.java — emit <loop>/<endloop> da NodeDto.
* web/dto/WorkflowDto.java — campi loop su NodeDto.
* web/ApiController.java — /definition converte LoopDef/LoopEndDef -> NodeDto.
* engine/WorkflowEngine.java — handling LOOP/ENDLOOP nel loop principale; helper
  loopEndIndex/loopStartIndex (static, nesting), splitList/join/parseIntSafe.
* templates/designer.html — render LOOP/ENDLOOP, pulsante "↻ Add loop" (coppia),
  modello, preview XML, clientValidate (over richiesto + bilanciamento LOOP/ENDLOOP).
* workflows/SAMPLE-10-loop.xml — NEW esempio.

## Verifiche

* 76 classi compilate; 15 workflow parsano (incl. sample loop).
* TestLoop: writer emette <loop over=... itemVar=...>/<endloop>; parser round-trip
  kinds STEP LOOP STEP STEP ENDLOOP, campi over/itemVar/delimiter; matching annidato
  loopEndIndex(l1)=7, loopEndIndex(l3)=5.
* designer JS validato; no \n/\r; Thymeleaf-safe.

## Non testabile in sandbox (verifica su UBS)

* Esecuzione reale del loop in un run (iterazione, ${file}, ripresa dopo gate): richiede
  il motore completo con step reali. Logica di matching/serializzazione testata.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5. Per loop su molti file alzare orchestrator.max-transitions
  (default 500) nell'application.properties esterno.

## Decisioni confermate con l'utente

* Catena di step (non singolo) -> serve loop. Costrutto: loop a blocco (scelto come il
  piu' robusto). Elaborazione sequenziale, ordine garantito.
