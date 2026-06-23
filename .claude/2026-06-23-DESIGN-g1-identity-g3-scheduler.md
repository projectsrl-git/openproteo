# DESIGN PROPOSAL (not yet implemented): G1 composite identity, G3 adaptive scheduler

These two are structural and production-critical; documenting the plan so they can be done in
dedicated turns with review, rather than blind.

## G1 — uniqueness = feedId + targetId (two workflows may share a feedId)
Today feedId IS the unique key everywhere: the registry map, the feed directory
(baseDir/<feedId>), every URL (/workflow/{feedId}, /run/{feedId}/{runId}), every API path variable,
the audit file and _runs. Making (feedId, targetId) the identity touches all of these.

Proposed approach:
- Introduce a single composite key helper, e.g. wfKey = feedId + "__" + targetId (filesystem- and
  URL-safe; reject "__" inside feedId/targetId on save).
- Registry keyed by wfKey; `registry.get(feedId)` -> `registry.get(wfKey)` (or get(feedId,targetId)).
  De-dup on load by wfKey, not feedId (today a duplicate feedId silently overwrites).
- FeedLayout root becomes baseDir/<feedId>/<targetId> (keeps same-feed targets grouped) or
  baseDir/<wfKey>. Pick one and migrate.
- URLs / controllers: path becomes /workflow/{feedId}/{targetId} (cleanest) or /workflow/{wfKey}.
  All @PathVariable feedId endpoints get a targetId companion or a wfKey.
- Bulk: uniqueness check on feedId+targetId; schema files already per-feed-dir.
- Migration (one-off, scripted): for each existing feed, derive targetId from its WorkflowDef and
  move baseDir/<feedId> -> the new path; rewrite any stored runId prefixes only if we change them
  (prefer keeping runId scheme but storing under the new dir).
Risk: high (touch count is large; URLs change; needs a migration + a backward-compat redirect for old
links). Best as its own turn with a migration utility and a dry-run report.

## G3 — adaptive parallel execution from the queue
Today the runner is a single-thread executor: only one run at a time, others queue. Per-feed
concurrency is already prevented by the runningFeeds lock, so different feeds CAN run in parallel
safely once the executor allows it.

Proposed approach (incremental, opt-in):
1. Config `orchestrator.max-parallel-runs` (default 1 = today's behaviour). Replace the single-thread
   executor with a bounded pool of that size. (Done carefully: buildRun/loop/finish already use
   concurrent maps + per-feed lock.)
2. Resource-gated admission: a scheduler tick (every ~30-60s, plus on each run completion) admits
   queued runs up to max-parallel AND only while free heap >= `min-headroom-mb-per-run` and load
   average is acceptable. Otherwise the run stays queued and is retried next tick. This is the
   "decide in the next minute to start another" behaviour.
3. A real pending queue (FIFO with optional priority) instead of submitting straight to the executor;
   the scheduler is the only thing that promotes queued -> running.
4. Observability already in place: the Operations Resources panel shows heap used/available, load,
   and running/queued/waiting counts. Add max-parallel + last admission decision once implemented.
Memory sizing: a precise per-run estimate is hard (depends on step types: csvsql in-mem vs on-disk,
masking, POI). Start with the global headroom rule + the max-parallel cap; later refine with a
per-feed historical peak-heap hint recorded at run end.
Risk: changes core execution concurrency. Default max-parallel=1 preserves current behaviour exactly;
raising it is an explicit, observable opt-in to validate on UBS.
