# Operations: OUTPUT DATA of all runs in history (Batch B, req 1.4)

## Change
The OUTPUT DATA column showed only the last run's values. It now shows the output
data of every run in the feed's history (most recent first).

### Backend (/api/overview/feeds)
The run list (store.list(layout, 25)) is captured once and reused. The output-data
variable set (varName + label) is built once from the steps' outputData.* params;
then for each run a `runsOutputData` entry is produced: {runId, runTs, status,
outputData:[{label,value from that run's vars}]}. Runs with no non-empty output
value are skipped; test runs are ignored. The existing `outputData` (last run) is
kept for backward compatibility.

### Frontend (overview.html)
odCell renders each run as a compact line "<runTs> [<status>] label=value; …"
(falls back to the single last-run outputData when runsOutputData is empty). The
free search now also covers every run's timestamp, status and output labels/values
(extends 1.1). CSV export gains an `allRunsOutputData` column.

## Verify
overview.html passes node --check; no literal \n/\r; no unsafe Thymeleaf. odCell
and the search hay were exercised in Node: both runs render, and search matches an
older run's date, a FAILED status and a value/label from any run. Backend mirrors
the existing feed-loop patterns (no stub/Maven compile this turn). Full render to
be confirmed on deploy.
