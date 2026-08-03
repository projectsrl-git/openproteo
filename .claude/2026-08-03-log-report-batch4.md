# Log report — Batch 4: metric cards, clickable as filters

`GET /api/logs/metrics` over exactly the same filtered set as the grid and the chart, plus a card row
that doubles as a filter surface.

## Numbers
Events source: events, runs touched, feeds touched, failures, successes, average step duration, top 5
feeds by failure, top 5 steps by failure. Runs source: runs, feeds touched, succeeded, failed (with the
failed-step total), average run duration, top 5 feeds by failure.

Counting is SQL. Durations are paired in Java — STEP_STARTED to STEP_COMPLETED|STEP_FAILED for the same
feed/run/node, ordered so the pairing is deterministic — because date arithmetic is dialect-specific and
H2 cannot be exercised here; the projection is capped at 300k rows. Runs use end minus start. An
unpaired start (a run still going, or a step that never completed) simply contributes nothing instead of
skewing the average, and with no pairs at all the card shows a dash rather than zero.

## Click-to-filter
Failures/Successes select that severity; a feed in "top feeds" selects that feed; a step in "top steps"
sets the step filter and severity FAIL. Every click goes through the same `search()` used by the filter
bar, so cards, chart and grid stay one state — a card click also redraws the chart and recomputes the
cards themselves. The step filter is part of the query state and is cleared by Reset.

## Verify
Every metric statement (the five counters, both top-N queries) and the duration pairing were executed
against a real SQL engine on a fixture with two feeds, two runs and three step pairs: counters
6/2/2/2/1, top step DEQUOTE with 2 failures, average step 23.3s over the expected 20s/10s/40s pairs.
logs.html passes node --check with zero literal \n/\r and no unsafe Thymeleaf. Brace balance checked
with the string/comment-aware counter.
