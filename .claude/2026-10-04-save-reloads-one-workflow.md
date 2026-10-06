# A save reloads one workflow, not all of them

2026-10-04, written on `8aded1a`, rebased onto `f34e507` (objunpack progress lines: no file in common but `USAGE.md`, another section; suite re-run after the rebase). Mode B. Request: "when I edit and save a workflow it reloads all of
them. If it is not necessary I would like to avoid it and load only the one I saved."

## Was it necessary?

No. Every save called `WorkflowRegistry.reload()`, which for EVERY `*.xml` in the workflows
directory parsed the file, built its `FeedLayout`, created its missing directories, wrote a log
line and appended a `WORKFLOW_LOADED` record to that feed's hash-chained audit file. With N
workflows one save cost N parses and N audit appends, and every feed's audit trail grew by one
record for each save of any other feed. Then `WorkflowScheduler.reschedule()` cancelled and
re-created the timer of every scheduled workflow.

Nothing reads those side effects: the engine provisions a feed's directories itself when a run
starts (`WorkflowEngine` :217, :275), and a `FeedLayout` depends only on its own workflow and on
`default-base-dir`. Three things the full reload did give for free, and they are kept:

1. a file copied / edited / deleted in the workflows directory by hand was picked up by the
   next save;
2. duplicate feedIds were re-decided (first file in directory order wins);
3. a workflow whose set-up had failed (feed directory not creatable) was retried.

## What changed

`WorkflowRegistry.refresh(Collection<String> written)` - new. It reads the files named by the
caller (always) and every other `*.xml` whose modification time or length differs from when it
was last read, or that appeared or disappeared. **Contract: after `refresh` the registry holds
exactly what `reload` would have put there - same workflows, same order, same load errors.**
Where it cannot guarantee that it calls `reload()` and logs the reason:

| Case | Why reload |
|---|---|
| a feedId declared by two files, in any role (a new file claims a held feedId; the winner of a standing duplicate is deleted or stops parsing) | the winner is decided by the order of the whole directory |
| a file now declares a different feedId | same |
| a load error that came after parsing (directories, audit) | it depends on the machine, not the file: retried, as reload would |
| the directory was not listed at the last load | nothing to compare with |
| set-up of a changed workflow throws | what reload leaves depends on how far it got |

A file that stops parsing is handled locally (workflow removed, error listed): a broken XML
sitting in the directory must not turn every later save back into a full reload.

`reload()` itself: same behaviour, same log lines, same messages. It now records each file's
stamp, and its single `try` is split in two so an error is known to be a parse error, a duplicate
or a set-up error. The body that admits a parsed workflow is shared with `refresh` (`admit`).

`WorkflowScheduler.reschedule(Refresh)` - new. Re-plans the workflows the refresh loaded or
removed; everything when the refresh reloaded everything. `reschedule()` unchanged in effect.

`ApiController`, five callers moved from `reload()` to `refresh(names)`: designer save,
save-xml, Variables mass save (the files it wrote), maintenance lock, delete. **Not moved**:
`/api/reload`, start-up, Bulk creation, Import - explicit mass operations, and natural points
at which everything is re-read.

Order: `reload` sorts with `Arrays.sort(File[])`, i.e. `File.compareTo`, which ignores case on
Windows and not on Linux. `refresh` re-sorts with the same comparison on the same kind of
`File`, so it reproduces whatever order the host gives; the host difference itself is older than
this change and is not touched.

## What an operator sees that is different

- The application log shows one `caricato` line per saved workflow, not N, then
  `Workflow refresh: 1 file(s) read, 0 removed, N-1 untouched`.
- The audit trail of a feed no longer receives `WORKFLOW_LOADED` when ANOTHER feed is saved.
  The saved feed's own records (`WORKFLOW_LOADED`, `WORKFLOW_SAVED`) are as before.
- Directories of the other feeds are not re-created by a save (a run still creates them).
- Timers of the other scheduled workflows are not cancelled and re-created.

No feed output changes. No parameter was added, so there is nothing to switch on or off
(checklist 10: no new runtime parameter). Judgement call, stated: «Default conservativi» asks
that new behaviour is born switched off; a switch here would be a new `orchestrator.*` key,
which today could only be file-only, i.e. new debt under «Configurabilità integrale da GUI».
The change keeps the registry's observable state identical by contract and by test, so it was
delivered without a switch. If the loss of the `WORKFLOW_LOADED` records on untouched feeds
matters to anyone reading audit trails, that is the one thing to object to.

## Verification

Kit committed in `tools/registry-refresh-test/` (needs a JDK only):

    tools/registry-refresh-test/run.sh <JDK_HOME> [seed] [rounds]
    python3 tools/registry-refresh-test/mutate.py <JDK_HOME> [seed ...]

It compiles the REAL `WorkflowRegistry`, `WorkflowScheduler`, `WorkflowXmlParser`, `FeedLayout`,
`AppProperties` and `model/def`, against stubs for Spring's annotations and scheduler, slf4j and
`AuditLogger` (a recorder). Every step does a `refresh` on a live registry and then builds a
brand-new registry that `reload`s the same directory: all workflows (feedId, file, name, cron,
node count, feed directory, step directories), their order and the load errors must be equal;
same for the scheduler (which feeds are scheduled, cron errors and their order). Eight groups,
the last a random walk (saves, deletions, broken files, feedIds reused across files, two thirds
told to `refresh` and one third done "by hand") - 600 rounds x 6 seeds.

- 1115 checks at the default 400 rounds, 1636-1661 at 600; 0 failures.
- Positive control: a `reload` of 30 workflows writes 30 `WORKFLOW_LOADED`; the save of one
  writes 1, and an untouched workflow is the same object after the save.
- 19 mutations, on copies, anchors verified, all killed, none by a compile error. A 20th
  survived and was investigated: `contested.contains(feedId)` next to `held.contains(feedId)` was
  unreachable (a contested feedId is always held, or the refresh has already gone to reload), so
  the clause was removed rather than left untested.
- The kit's own first mutation run reported 19 "kills" that were compile failures (output
  directory missing). Found because the run prints the first failure of each kill; `run.sh` now
  exits 3 for "does not compile" and `mutate.py` counts that as not killed.
- `ApiController` / `PageController` (not compilable here): differential compile, errors before
  and after without line numbers - no new error. Two positive controls: an unknown variable and
  an unknown method / wrong arity in the modified lines both show up.
- `USAGE.md` through the real `render()` of `docs.html`: the new section renders, no raw
  Markdown left.

Linux: all of the above, sandbox, ext4. Temurin 1.8.0_432 (compile and run) and OpenJDK
21.0.12 (run of the Java 8 classes); as root and as uid 65534; `LANG` unset and `C.UTF-8`.

Windows: nothing was run. Read, not run: `File.compareTo` ignoring case (the order `refresh`
reproduces), NTFS modification times, and a file name on disk differing in case from the name a
caller builds (covered by matching the caller's names ignoring case; exercised on Linux only as
logic).

Neither: the Maven build; Spring wiring of the two beans; the five endpoints through HTTP; the
real `AuditLogger` (the test uses a recorder, so the hash chain was not exercised - `refresh`
calls it through the same `admit` body `reload` uses).

## Limits, stated

- A file changed by hand that keeps BOTH its modification time and its length is not noticed
  by a save. Before, any save noticed it. Reload XML definitions does.
- If set-up of a changed workflow throws half-way, `refresh` falls back to `reload` and the
  workflows already admitted in that refresh get a second `WORKFLOW_LOADED`.
- `refresh` holds the registry lock while it parses the changed files, as `reload` does for all.

## Found, not touched

- `POST /api/workflows/{feedId}/delete` never rescheduled, before or after this change: the
  timer of a deleted scheduled workflow stays until the next reschedule and each firing logs
  `avvio da scheduler fallito: Unknown workflow`. One line to fix
  (`scheduler.reschedule(registry.refresh(...))`); left out because it changes what Delete does.
- Variables mass save: when a write fails half-way the endpoint returns without reloading, so
  files already written are on disk but not in the registry. With `refresh` they are picked up
  by the next save (their stamp changed); before, by the next reload.
- `README.md` not touched (diverged from `USAGE.md`, separate work).

## Files

- `registry/WorkflowRegistry.java` - `refresh`, `Refresh`, `LoadError`, `admit`, stamps
- `engine/WorkflowScheduler.java` - `reschedule(Refresh)`, `schedule`, `unschedule`
- `web/ApiController.java` - five callers
- `src/main/resources/static/USAGE.md` - "What a save reloads"
- `CLAUDE.md` - the rule for callers, under «Workflow di sviluppo»; the Delete bullet corrected
- `tools/registry-refresh-test/` - the kit
