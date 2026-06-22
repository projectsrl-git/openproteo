# csvsql performance: in-memory engine + per-input indexes

## Problem
csvsql was correct but extremely slow on ~1M-row inputs, especially with joins/filters — effectively
unusable for non-trivial queries.

## Root causes
1. **On-disk H2 (file engine)**: every input was materialised into an MVStore database on disk
   (`${stepDir}/_h2`), so a 1M-row input meant a large disk write then read back.
2. **No indexes**: all-VARCHAR tables with no indexes, so every join/filter was a full table scan
   (and joins built large hash structures).
3. Default H2 settings (small cache, locking) for what is a single-threaded throwaway DB.

## Changes
- **Engine selection** (`engine` step param, default from `orchestrator.csvsql-engine` = `auto`):
  - `auto`: in-memory H2 when total input size < `orchestrator.csvsql-mem-max-mb` (default 512 MB),
    on-disk above it.
  - `mem`: always in-memory (`jdbc:h2:mem:`), fastest, no disk I/O.
  - `file`: always on-disk (for inputs too big for the heap).
  The log prints the chosen engine and the total input size.
- **Per-input indexes** (`<input ... index="NDG,CODCLI">`): after staging each table, a
  `CREATE INDEX IF NOT EXISTS` is built on each listed column (regex-validated; bad/unknown columns
  are warned, not fatal). This is the big win for complex queries.
- **H2 tuning** on the connection URL: `LOCK_MODE=0` (single-threaded transient DB) and
  `CACHE_SIZE=262144` (256 MB; helps the on-disk engine).
- In-memory DBs use a unique name per run with `DB_CLOSE_DELAY=0`, so they are freed when the
  connection closes; on-disk temp files are deleted in `finally` as before (now null-safe for mem).
- Designer: each csvsql input row gains an **Index cols** field; the output row gains an **Engine**
  selector (auto / in-memory / on-disk). The preview already uses in-memory H2 capped at 1000 rows,
  so it is unaffected.
- Config: `AppProperties.csvsqlEngine` (`auto`) and `csvsqlMemMaxMb` (`512`), settable as
  `orchestrator.csvsql-engine` / `orchestrator.csvsql-mem-max-mb`.

## Guidance
For a 1M-row join: keep Engine = auto and list the join key(s) in Index cols on both sides. If a run
hits OutOfMemory (very large inputs in `mem`), set that step's Engine = file (it still benefits from
the indexes) or raise the Tomcat `-Xmx`.

## Verification
- Compiles (96 classes). Round-trip test: per-input `delimiter` + `index` survive writer↔parser.
- Designer JS: `node --check` OK; no literal `\n`/`\r`; no unsafe `[[`/`[(`.
- Live H2 timing/heap is UBS-side (the H2 jar is unreachable in the sandbox).
