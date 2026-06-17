# 2026-06-17 — Loop index 1-based + loopIndexString (LPAD)

## Change
* ${indexVar} (default loopIndex) is now 1-based (was 0-based).
* New ${indexStringVar} (default loopIndexString): the 1-based index left-padded with '0'
  to indexPad characters (default 3). E.g. pad 3 -> 001, pad 5 -> 00005. Useful for ordered
  output file names.

## Where
* LoopDef: + indexStringVar (default loopIndexString), + int indexPad (default 3); doc updated.
* WorkflowEngine: setLoopIndexVars(run, lp, zeroBased) sets indexVar = zeroBased+1 and
  indexStringVar = LPAD. Called at loop start (0) and on each re-entry. Internal counter
  __loop.<id>.i stays 0-based (drives items.get(i)).
* Parser/Writer/NodeDto/ApiController /definition: indexStringVar + indexPad round-trip.
* Designer: Index var labelled (1-based); new Index string var + Pad width + Count var fields;
  addLoop defaults; preview XML; help text with live pad sample (zpadPreview).

## Tested
* TestLoopIdx: writer emits indexStringVar/indexPad; parser reads them back (idxStr, pad 5).
* setLoopIndexVars: zeroBased 0 -> idx=1, idxStr=00001; zeroBased 4 -> idx=5, idxStr=00005.
* 76 classes compile; designer JS OK; no \n/\r; Thymeleaf-safe.

## Note
Existing workflows that relied on a 0-based index will now see 1-based values (intended).
