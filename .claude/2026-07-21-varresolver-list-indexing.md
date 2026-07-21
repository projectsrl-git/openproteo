# VarResolver: list indexing ${list[N]}

Values like csvRowCounts and csvFiles are single ';'-separated strings, so there was no
way to reference the element for the current loop iteration.

Added indexing to VarResolver: ${name[N]} resolves to the N-th element (1-based) of the
';'-separated value ${name}, trimmed. Combined with the existing innermost-first
indirection, ${csvRowCounts[${loopIndex}]} yields the row count of the current file in a
loop (loopIndex is 1-based, so [1] is the first element). Out-of-range or missing base ->
empty string. Splits on ';' (the default list delimiter used by csvRowCounts/csvFiles/
matchedFiles); lists written with a custom delimiter won't index.

Examples:
  ${csvRowCounts[${loopIndex}]}     -> rows of the current part
  ${csvFiles[${loopIndex}]}         -> path of the current part
  ${csvRowCounts[1]}                -> first part's rows

Verify: VarResolver compiles standalone and the indexing was exercised (loopIndex=2 ->
2nd element; [1]/[5]; csvFiles; out-of-range -> empty; normal vars unaffected). Java --
rebuild the WAR on deploy.
