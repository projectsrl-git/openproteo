# Step-by-step testing off the main queue (#5)

## Problem
Runs execute on a single-threaded executor (`wf-runner`), so anything launched while a run is in
progress queues behind it. The user needs to configure/test a workflow step-by-step even while other
feeds are running.

## Solution
A dedicated **test executor** (`wf-test-runner`, fixed pool of 2 daemon threads) separate from the
main queue, plus `WorkflowEngine.testStep(feedId, stepId, user)`:
- Builds an ephemeral run via the existing `buildRun` (full var seeding incl. stepTimeoutMins,
  globalVars, dir vars, script aliases), then overrides the runId to `<feedId>_test_<ts>_<seq>` and
  marks every step except the chosen one SKIPPED.
- Registers it in `activeRuns` + `controls` and persists it, so the **existing run page**
  (`/run/{feedId}/{runId}`) shows live output and the existing **Stop** works (incl. cancelling a
  csvsql query, from the earlier Stop fix).
- Submits `executeStep(def, layout, run, step)` to the test executor, then `finish(...)` with
  SUCCESS/FAILED. Writes to the feed's **normal step folders**, so outputs persist and step N can read
  step N-1's output — a real step-by-step run.
- Does **not** take the per-feed lock, so it runs concurrently with other feeds. Refuses only if the
  **same feed** has a normal run active (avoids clobbering its working files).

## Race safety
`finish()` now clears the per-feed lock conditionally — `runningFeeds.remove(feedId, runId)` — so a
test run finishing can never release a normal run's lock (test runs never hold it).

## API + UI
- `POST /api/workflows/{feedId}/test-step/{stepId}` -> `{ok, runId, url}`; maps engine errors
  (same-feed run active, unknown step) to a clear message.
- Designer: a **\u25B6 Test** button on every STEP node (not gates/loops). It confirms, posts, and
  opens the run page in a new tab. It uses the SAVED workflow, so save first.

## Caveats
- Tests the last SAVED workflow (not unsaved editor state).
- A step is run once; `forEach` on the step is honored, but a step that depends on LOOP-node iteration
  context (`${item}`) has no item when tested alone.
- Don't start a normal run on a feed while you're testing its steps (the test path doesn't lock the
  feed; starting a normal run mid-test could interleave writes). Testing during OTHER feeds' runs is
  the supported case.

## Verification
- Compiles (97 classes). designer JS `node --check` OK; no literal `\n`/`\r`; no unsafe `[[`/`[(`.
- Live concurrency/streaming is UBS-side.
