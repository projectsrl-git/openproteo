# 2026-06-16 — Campo targetId sul workflow (+ bulk multi-valore)

## Cosa

Aggiunto l'attributo **targetId** al workflow (destinazione), accanto a sourceId.
End-to-end: modello, parser, writer, DTO, designer (campo + preview + load + cheat
${targetId}), seeding run var (${targetId}), e mappatura bulk. Nel bulk usa la stessa
sintassi template degli altri campi, quindi si possono indicare più destinazioni separate
da virgola, es. {Dest A},{Dest B} -> targetId="ARCHA,ARCHB".

## File toccati

* model/def/WorkflowDef.java, web/dto/WorkflowDto.java — campo targetId.
* parser/WorkflowXmlParser.java — lettura attributo targetId.
* parser/WorkflowXmlWriter.java — scrittura attributo targetId.
* engine/WorkflowEngine.java — run.vars ${targetId}.
* parser/BulkWorkflowGenerator.java — Mapping.targetId + resolveField.
* web/ApiController.java — param mapTargetId.
* templates/designer.html — campo f_targetId (modello/save/preview/load/cheat).
* templates/bulk.html — input mapTargetId + nota multi-valore.

## Verifiche

* 74 classi compilate; TestTarget: {Dest A},{Dest B} -> targetId="ARCHA,ARCHB".
* designer/bulk JS validati; no \n/\r; Thymeleaf-safe.

## Da fare al deploy

* Redeploy WAR + Ctrl+F5.
