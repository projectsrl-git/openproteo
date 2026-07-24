# CR/LF inside values: normalise at the JDBC extraction, opt-in everywhere

## Where the fix belongs
Reassembling a split record in `dequote` needs a quote-parity heuristic. At the JDBC extraction the
column count comes from ResultSetMetaData, so the record shape is known exactly and a value
containing CR/LF is the only thing that can break "one record = one physical line". SqlSupport
therefore normalises the value while writing it — one funnel, total coverage.

- `nlReplacement(mode)`: `space` -> " ", `strip` -> "", anything else (including null) -> keep.
- `exportResultSet/exportCsv` gained an nlMode overload; the pre-existing signatures delegate with
  "keep", so nothing changes for callers that do not pass a mode.
- `ExportResult.newlinesSanitized` counts the cells touched; the `sql` and `csvsql` steps read the
  step parameter `newlinesInValues` and publish `${newlinesSanitized}` (sql also logs when > 0).

## Conservative defaults (deliberate)
Production feeds must not change behaviour on deploy, so EVERY new behaviour is off by default:
- extraction `newlinesInValues` defaults to **keep** — values are written exactly as the database
  returns them, byte-for-byte as before;
- dequote `embeddedNewlines` defaults to **keep** — records are NOT joined (the previous patch
  defaulted to `space`, which was a silent behaviour change: corrected here);
- dequote blank-line dropping is now behind `dropBlankLines`, default **no** (it was
  unconditional in the previous patch: corrected here).
Opt in per step from the designer, or massively from the Variables page / matrix.

## Verify
nlReplacement was extracted from the source, compiled and run: null/empty/keep leave CR+LF intact,
space and strip transform as documented. designer.html passes node --check with no literal \n/\r
and no unsafe Thymeleaf; SqlSupport and InternalSteps are brace-balanced. Java not compiled as a
whole here -> Maven build on deploy.
