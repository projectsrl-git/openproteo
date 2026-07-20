# Operations overview: resolve ${var} placeholders in feed tags

The Operations grid showed feed tags raw, e.g. "${recordBusinessDate},
${originTableName}, INVIATI T1 ..." instead of the resolved values.

## Fix
overviewFeeds() now resolves each tag's ${var} placeholders before joining, using a
light variable map (globalVars + feedId/sourceId/targetId + runDate/runTs +
def.variables) and VarResolver.resolve. recordBusinessDate and originTableName are
workflow variables (BulkWorkflowGenerator sets them), so they resolve to their
values. Unknown placeholders resolve to empty (VarResolver semantics), literals are
left as-is.

The light map deliberately avoids feedVars() (which calls layout.provision() and
builds dir vars) so the overview poll stays cheap across many feeds. The Variables
page (feedView) intentionally keeps tags RAW so they remain editable.

## Verify
Resolution logic checked standalone (placeholders -> values; literal untouched;
missing -> empty). Java change mirrors feedVars; not compiled in the chat sandbox —
build on deploy.
