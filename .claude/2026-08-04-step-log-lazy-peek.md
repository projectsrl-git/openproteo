# Step log: lazy peek from the log report grid

The DETAILS column shows the audit payload, which is all the audit line carries. The step log - the
script output - lives in per-step files under the run directory and is deliberately NOT indexed: it is
one to three orders of magnitude larger than the audit (a single step can emit hundreds of lines, much
of it CLIXML noise), and indexing it would have turned the disposable in-memory index into something
that needs a real search engine and persistent state.

Instead, each event row that has both a run and a step gets a "≡ step log" chip in the DETAILS cell.
Clicking it expands a panel under the row with the **last 300 lines**, fetched **on demand for that row
only**, reusing the endpoint the run page already uses
(`/api/runs/{feedId}/{runId}/log/{stepId}?tail=300`). Clicking again collapses it. Nothing is
pre-fetched, nothing is cached, nothing is indexed: cost is one request per peek, and the log files stay
the only copy of that data.

Failures degrade politely: a missing or unreadable log shows "step log not available" with the HTTP
status inside the panel, instead of breaking the row; an empty file says so explicitly.

Rows without a run or a step (workflow saved, feed locked, ...) get no chip, because there is no step log
to show.

## Verify
logs.html passes node --check with zero literal \n/\r and no unsafe Thymeleaf. The endpoint is the one
already in use by the run page, so no backend change was needed. The interaction could not be exercised
here (no browser, no app): after deploying, open /logs on the Events source and click the chip on a row
that has a step.
