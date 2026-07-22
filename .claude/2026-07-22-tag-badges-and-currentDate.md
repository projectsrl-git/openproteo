# #1 Tag badges in Operations overview; #2 runtime currentDate variable

## #1 Tags as badges in the overview feed list
The feed rows showed tags as a plain "tags: a, b, c" line. overview.html now renders each
resolved tag as a small pill (.tagb inside .tag-badges); app.css styles them (dark + a
light-theme override). Split is on the exact ", " join separator.

## #2 New runtime variable currentDate (and currentTs)
runDate/runTs are fixed at run start. currentDate/currentTs are evaluated NOW, refreshed
before every step execution in WorkflowEngine.loop() (java.time.LocalDateTime.now(),
yyyyMMdd / yyyyMMdd_HHmmss). So when an ON-HOLD step is resumed days later, that step and its
successors can use ${currentDate} to get today's date instead of the original ${runDate}.
feedVars and the overview tag map also expose currentDate/currentTs (design-time = now) so
${currentDate} resolves in previews and tags.

Use: ${currentDate} where you want the execution day (e.g. output paths/filenames on resumed
steps); ${runDate} still gives the day the run started.

Verify: overview.html passes node --check (no literal \n/\r, no unsafe); WorkflowEngine and
ApiController brace/paren balanced. Java not compiled here -> Maven build on deploy; app.css
is a static asset (hard-refresh after deploy).
