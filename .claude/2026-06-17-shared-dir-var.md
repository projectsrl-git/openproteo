# 2026-06-17 — ${sharedDir} variable for steps

A step can now create an app-level shared file by targeting the new ${sharedDir} variable
(the orchestrator.shared-dir folder, same one the Shared files page manages). Seeded in
WorkflowEngine run.vars from assets.sharedDir() (overridable by a workflow variable of the
same name). Added to the designer cheat sheet + path suggestions and to USAGE.md.
Example: sql csvFile=${sharedDir}/report.csv, or filecopy dest=${sharedDir}.
76 classes compile; designer JS OK.
