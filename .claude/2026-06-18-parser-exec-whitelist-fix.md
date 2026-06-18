# 2026-06-18 — fix HTTP 400 on save: parser exec whitelist missing new execs

## Root cause (confirmed via Network tab)
POST /api/workflows/save re-validates by generating XML (WorkflowXmlWriter) and re-parsing it
(WorkflowXmlParser.parse). The parser's exec whitelist (and its "internal" set that decides
whether a `script` attribute is required) did NOT include the recently added executors split,
safecopy, dequote. So parsing a workflow containing exec="safecopy" threw
IllegalArgumentException -> save() returned 400 ("Validation failed: ... exec must be ...").
The corporate proxy replaced the 400 body with its own HTML page, which is why the client
showed the generic "text/html / Bad Request / intercepted by a proxy" message.

## Fix
WorkflowXmlParser: added "split", "safecopy", "dequote" to (1) the allowed-exec whitelist and
(2) the `internal` set (so script is not required for them). Engine internalExecutor() and the
designer already knew these execs; only the parser was stale.

## Verified
Parsing a workflow with exec=safecopy (pattern "*.md5, *.tar"), exec=dequote and exec=split now
succeeds (3 steps). 76 classes compile. Save will no longer 400 for these steps.
