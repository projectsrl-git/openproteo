# G3 — adaptive, resource-gated parallel run scheduler

## Goal
Run more than one workflow at a time when resources allow (e.g. a slow SQL extract leaves the box
idle), instead of the strict single-thread FIFO. Same feed stays serialised; only DIFFERENT feeds run
in parallel, up to a configured cap, and an ADDITIONAL run is admitted only while enough JVM heap is
free. Default preserves the old behaviour exactly.

## Config (AppProperties / external application.properties)
- orchestrator.max-parallel-runs (default 1) — 1 = legacy sequential. Different feeds only.
- orchestrator.run-admission-headroom-mb (default 256) — admit an ADDITIONAL run only while at least
  this much heap is still allocatable. The first run is always admitted (progress guaranteed).
- orchestrator.scheduler-tick-sec (default 20) — how often the scheduler re-checks resources to admit
  deferred queued runs ("decide in the next minute").

## Engine changes
- The single-thread executor is replaced by a cached daemon pool (`wf-runner`); concurrency is bounded
  by admission, not by pool size.
- A pending FIFO + `active` counter + synchronized `schedule()`:
  `start()` and gate-resume (`decide`) now `enqueue(runId, task)` instead of submitting directly.
  `schedule()` admits while `active < max-parallel` AND (active == 0 OR free heap >= headroom),
  incrementing `active` and submitting; each task decrements `active` and calls `schedule()` again in a
  finally, so a freed slot (run finished OR paused at a manual gate) immediately admits the next.
- A single-thread `wf-scheduler` ScheduledExecutorService calls `schedule()` every tick, so runs
  deferred for lack of memory get admitted as soon as resources free up.
- A QUEUED run that is not yet admitted simply shows QUEUED in Operations (correct).
- Per-feed safety unchanged: the runningFeeds lock is taken at start() and prevents two runs of the
  same feed, so the pending queue never holds two runs for one feed. Test runs use their own executor
  and do not count against max-parallel.

## Operations
The Resources panel now shows "Parallelism (admitted / max)" plus any runs waiting for a slot, next to
heap, processors, load and running/queued/waiting counts.

## Safety / validation
- Default max-parallel-runs = 1 reproduces the exact previous single-run behaviour.
- The admission algorithm was simulated standalone (50 tasks): peak concurrency == max for max in
  {1,3,5} and all tasks completed — no deadlock, no lost wakeup.
- Raising max-parallel-runs on UBS is an explicit, observable opt-in; watch the Resources panel and
  heap while increasing it. A precise per-run memory estimate is future work; today's guard is the
  global heap-headroom rule plus the cap.

Compiles (99 classes).
