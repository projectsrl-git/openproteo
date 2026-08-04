# DESIGN — Operations bulk bar: CONTINUE / STOP, and the DELETE label

Status: **CONFIRMED and implemented in a single batch.**
Scope: `templates/overview.html` (drill grid bulk bar), `web/ApiController`,
`engine/WorkflowEngine`.

## 1. Problem

The Operations drill grid has a multi-select bulk bar (Run / Lock / Unlock /
Clear history / Delete). Feeds paused **ON HOLD** can only be resumed one at a
time, either from the run page or from the per-row `▶ Continue` button. There is
no way to stop a set of runs at once, and no bulk way to resume them.

Two requests:

1. Add **CONTINUE** and **STOP** to the bulk bar. STOP must leave the selected
   runs **ABORTED**; CONTINUE must resume the ON HOLD ones. STOP must reach a run
   that is ON HOLD (which has already released the engine slot), otherwise such a
   run stays pending forever. Explicit confirmation for PRODUCTION feeds, as for
   Clear history.
2. The **DELETE** button label in that bar is ambiguous — it reads as if it
   deleted the feed.

## 2. Findings (verified against `main` @ 5e09fb8)

### F1 — STOP on an ON HOLD run already works; no engine change is required

`WorkflowEngine.loop()` suspends an ON HOLD run with
`runningFeeds.remove(run.feedId)` only — the run **stays in `activeRuns`**
(`WorkflowEngine.java:562-568`). `stop()` resolves its run as
`activeRuns.get(runId)`, falling back to `store.load(layout, runId)`
(`WorkflowEngine.java:431-432`); neither path consults `runningFeeds`. It then
handles `ON_HOLD` explicitly, clearing `onHoldStepId` and calling
`finish(..., ABORTED, ...)` (`WorkflowEngine.java:441-446`).

So an ON HOLD run is reachable by runId in both situations:

* while the JVM is up — found in `activeRuns`;
* after a Tomcat restart — `activeRuns` is empty, but `store.load` reads the run
  file from disk and the same ABORTED path runs. `resumeHold()` has the same
  fallback, so CONTINUE also recovers orphaned holds.

**The gap is purely in the UI**: the bulk bar has no button, and the client has
to know *which* runId to send.

### F2 — the bulk DELETE button does delete the feed, not the run

`drillBulkDelete()` (`overview.html:329`) posts to
`/api/workflows/{feedId}/delete`, which deletes the **workflow XML definition**
and reloads the registry (`ApiController.java:1021-1045`). The confirm text
already says so ("Definition files are removed"), but the button label does not.

Relabelling it **DELETE RUN** would therefore be a safety regression: it would
present a feed deletion as a run deletion. See decision D5.

### F3 — `deleteRun` guard is weaker than assumed, and not only for ON_HOLD

`deleteRun` refuses only when `runId.equals(engine.activeRunId(feedId))`
(`ApiController.java:1100`), and `activeRunId` is just `runningFeeds.get(feedId)`
(`WorkflowEngine.java:394`). Both the **ON_HOLD** path and the
**WAITING_APPROVAL** gate path remove the feed from `runningFeeds`
(`WorkflowEngine.java:567` and `:648`), so for a suspended-but-live run
`activeRunId` returns `null` and the deletion goes through unguarded: the run
file is removed while the run object is still in `activeRuns`, and a later
resume/decide/stop operates on a run with no file on disk.

### F4 — `lastStatus` / `lastRunId` in `/api/overview/feeds` are cached for 10 s

Only `running` and `bucket` are recomputed live; the last-run fields come from
`feedsCache` with `FEEDS_TTL_MS = 10000` (`ApiController.java:1471, 1613-1622`).
A run started in the last 10 s therefore has a stale or absent `lastRunId`.

### F5 — `loadFeeds(force)` ignores `force`

`overview.html:331` declares the parameter and never uses it, and
`/api/overview/feeds` has no cache-bypass. After a bulk action the grid can show
the pre-action status for up to 10 s.

## 3. Decisions

**D1 — STOP reaches an ON HOLD run by runId, with no new engine semantics.**
No change to `stop()`, no "treat ON_HOLD as active in `runningFeeds`". Putting an
ON HOLD run back into `runningFeeds` would re-occupy the engine slot for a run
that is deliberately parked, which is the opposite of what F2 (the ON HOLD batch)
was designed to do.

**D2 — the bulk action targets `liveRunId || lastRunId`, looping client-side over
the existing per-run endpoints.**
Consistent with the documented bulk pattern ("loop client-side sugli endpoint
per-feed", CLAUDE.md). No new endpoint. Because of F4, a purely cache-based
`lastRunId` would silently skip a run started in the last 10 s, so the live part
of `/api/overview/feeds` gains two fields:

* new `WorkflowEngine.activeRunsByFeed()` → snapshot `Map<feedId, WorkflowRun>`
  of the non-terminal runs still held in `activeRuns` (covers RUNNING, QUEUED,
  WAITING_APPROVAL, ON_HOLD; excludes `_test_` runs, as `running` already does);
* per feed, `liveRunId` and `liveStatus` written in the existing non-cached loop —
  one map built per request, not per feed. `null` when the engine holds nothing.

The client resolves `runId = f.liveRunId || f.lastRunId` and
`status = f.liveStatus || f.lastStatus`. `liveRunId` covers the fresh-run blind
spot; `lastRunId` covers holds orphaned by a restart. Nothing existing changes:
the two fields are additive and unused by the current UI.

**D3 — eligibility is filtered client-side, and the server stays authoritative.**
STOP is sent only for feeds whose effective status is `RUNNING`, `QUEUED`,
`WAITING_APPROVAL` or `ON_HOLD`; CONTINUE only for `ON_HOLD`. Feeds with no
actionable run are not contacted and are reported as skipped. If the status moved
under the UI, the endpoints refuse (`stop()` returns false on terminal,
`resumeHold()` returns false unless ON_HOLD) and the request is counted as
skipped — a stale grid can never abort the wrong run, because the runId is pinned.

**D4 — PRODUCTION confirmation is at the UI level, mirroring Clear history.**
`opConfirm` with `danger:true` and, when the selection contains PROD feeds, a
**required** checkbox "I understand N PRODUCTION feed(s) are included". The
per-run `/stop` and `/resume` endpoints are left unchanged: they are also used by
the run page, which has no PROD gate today, and adding a server-side
`confirmProduction` there would change an existing contract for no gain in this
batch. Noted as a deliberate asymmetry with `clear-history`, which does enforce
server-side.

**D5 — DELETE is relabelled `🗑 Delete feed`, and a separate
`🗑 Delete last run` is added.**
The existing button deletes the workflow definition (F2), so it is relabelled and
its confirm text sharpened; relabelling it "DELETE RUN" would have presented a
feed deletion as a run deletion. The run-level bulk action requested is added
alongside it as a **new, distinct** action posting to
`/api/runs/{feedId}/{runId}/delete` with the feed's most recent run. It targets
only feeds whose last run is finished **and** which have no live run at all
(`lastRunId && !liveRunId && terminal(lastStatus)`): rather than silently falling
back to an older run when the newest is alive, the feed is skipped and counted.
The D6 guard is its server-side backstop.

**D6 — `deleteRun` refuses any non-terminal run.**
Replace the `activeRunId` comparison with: resolve the run
(`engine.activeRun(runId)`, else `store.load`) and refuse when its status is not
terminal (`SUCCESS|FAILED|SKIPPED|REJECTED|ABORTED`), with the message
"Run is not finished — stop it before deleting". Strictly stronger than today and
conservative: it only *adds* refusals, and exactly in the cases that currently
corrupt state. Deleting a finished run is unaffected. The new bulk STOP makes the
refusal actionable.

**D7 — `?refresh=1` on `/api/overview/feeds`, and `loadFeeds(force)` uses it.**
Optional parameter; when true the feed cache is invalidated before the response
is built. Default unchanged, so nothing else is affected. Fixes F5 so the grid
reflects a bulk STOP/CONTINUE immediately instead of up to 10 s later.

**D8 — visual hierarchy.** `▶ Continue` uses `.btn.sm.ok` (green) and `⏹ Stop`
plain `.btn.sm`, because a stopped run is recoverable — a new run can be started.
The two irreversible actions, `🗑 Delete last run` and `🗑 Delete feed`, are the
only `.btn.sm.danger` ones. Only CSS classes already defined in `app.css` are
used. The per-row `▶ Continue` also moves to `.btn.sm.ok` for consistency.

**D9 — every bulk action in this bar now goes through `opConfirm`/`opAlert`.**
`drillBulkDelete` still used a native `confirm()`, which CLAUDE.md forbids in
templates; since its text was being rewritten anyway it moves to `opConfirm` with
`danger:true` and the same PROD checkbox. Pure added friction on the single most
destructive action — nothing is loosened. The older Run/Lock/Unlock handlers are
left alone to keep the patch to the point.

## 4. Implementation

Delivered as one batch.

| File | Change |
|---|---|
| `engine/WorkflowEngine.java` | new `activeRunsByFeed()` snapshot + `startedLater()` (D2); `isTerminalStatus()` made public, private `isTerminal()` delegates (D6) |
| `web/ApiController.java` | `liveRunId` / `liveStatus` in the live loop of `/api/overview/feeds` (D2); optional `refresh` param (D7); `deleteRun` non-terminal guard (D6) |
| `templates/overview.html` | `drillBulkContinue()` / `drillBulkStop()` / `drillBulkDeleteRun()` + buttons (D2/D3/D4/D5/D8); `drillBulkDelete` relabelled and moved to `opConfirm` (D5/D9); `loadFeeds(force)` passes `refresh=1` (D7); per-row Continue uses the live run |

New UI helpers: `drillRunOf` / `drillStatusOf` (live-then-cached resolution),
`drillIsTerminal`, `drillPickRuns` (eligibility + skip count), `drillPostSeq`
(sequencer over prebuilt URLs), `drillDone` (reports `ok / failed / skipped`),
`drillProdChecks` (the PROD checkbox). The existing `drillRunSeq` / `drillAfter`
are untouched and still serve Run / Lock / Unlock / Clear history.

### Non-goals

* No change to `stop()`, `resumeHold()`, `finish()` or the ON HOLD suspend path.
* No change to the per-run `/stop`, `/resume`, `/delete` endpoint signatures
  (only `deleteRun`'s internal guard).
* No server-side PROD enforcement on stop/resume.
* No per-row Stop button in the drill grid.

## 5. Verification plan (chat mode — Modalità B)

* `node --check` on the extracted `overview.html` inline script.
* Scan for literal `\n` / `\r` in JS and for uncommented `[[` / `[(`.
* String/comment-aware brace balance + import check on the two Java files.
* `git apply --check` on a second fresh clone.
* **Not verifiable in the sandbox**: Maven build, and the live behaviour of a
  bulk STOP over real ON HOLD runs — to be confirmed on the UBS deploy.

### Verification actually performed

* `node --check` on the extracted `overview.html` inline script: OK.
  Zero literal `\n` / `\r`; zero `[[` / `[(` anywhere in the template.
* String/comment-aware brace and paren balance on both Java files: 0 / 0.
* `activeRunsByFeed()` + `isTerminalStatus()` extracted verbatim, compiled with
  `-source 8` and run against synthetic runs: newest-run-wins on a feed holding
  both a parked ON_HOLD and a newer RUNNING run, ON_HOLD and WAITING_APPROVAL
  both reported, terminal and `_test_` runs excluded, startTs tie broken by
  runId.
* The real `drillPickRuns` / `drillIsTerminal` / `drillUrls` / `drillProdChecks`
  extracted from the template and run in node against synthetic grid rows:
  CONTINUE selects only ON_HOLD; STOP selects RUNNING/QUEUED/WAITING_APPROVAL/
  ON_HOLD and picks `liveRunId` for a run started inside the cache window;
  a hold orphaned by a restart is reached via `lastRunId`; DELETE LAST RUN
  selects only the feed whose last run is finished with no live run; skip counts
  and the PROD checkbox are correct.
* **Not run**: `mvn clean package`, and any live engine behaviour.
