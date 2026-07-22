# #1 Duplicate workflow copies uploaded files; #4 internal viewer aggregate respects ranges

## #1 Duplicate as new -> copy uploaded files into the new feed dir
Duplicating a workflow only recreated the definition; the uploaded files
(dataschema/displayschema/scripts) were not copied, so the new feed's directory was
incomplete. Now:
- AssetStore.copyFeedAssets(from,to) copies every uploaded file + its metadata to the
  target feed dir (writeMeta once).
- New endpoint POST /api/workflows/{feedId}/copy-assets-from/{sourceFeedId}.
- designer.html duplicateWorkflow() remembers the source feed (window.DUP_FROM); after a
  successful save (both save() and saveXmlDirect()) it calls copy-assets-from for the new
  feed, then remounts/reloads. The new workflow is a faithful copy, files included.

## #4 (internal viewer) Range filters ignored by the aggregate
csvAgg took only group/distinct/q, so CsvService.aggregate ignored the per-column range
filters. Now:
- CsvService.aggregate has an overload taking List<Filter> (cache key includes them; the
  scan applies matchesFilters just like page()); the 4-arg form delegates with null.
- csvAgg + both @GetMapping wrappers accept fc/ff/ft and build filters exactly like
  csvPage.
- viewer.js buildAgg receives a getRanges callback and appends &fc/&ff/&ft to the
  aggregate URL, so the Aggregate tab reflects the active range filters.

Verify: viewer.js + designer.html pass node --check (no literal \n/\r, no unsafe);
CsvService/ApiController/AssetStore brace/paren balanced and mirror existing csvPage /
AssetStore code. Java not compiled in the chat sandbox -> full Maven build on deploy.
