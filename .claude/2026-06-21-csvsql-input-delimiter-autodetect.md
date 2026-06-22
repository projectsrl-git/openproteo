# Fix: csvsql 'Column not found' on WHERE — input delimiter was forced to output delimiter

## Symptom
`SELECT * FROM eorfull` previews fine, but `SELECT * FROM eorfull WHERE NDG = '0000596'` fails:
```
ERROR: Column "NDG" not found; SQL statement:
SELECT * FROM (SELECT * FROM eorfull WHERE NDG = '0000596') LIMIT 50 [42122-214]
```
In the preview the whole header showed as a single column literally named
`CODCLI,NDG,DESCR,PARENT,...`.

## Cause
The input was a **comma**-separated CSV, but the staging read it with `fieldSeparator=;` — the
step's **output** delimiter (default `;`) was being reused for reading the inputs. So H2 parsed each
line as ONE column whose name was the entire header; `SELECT *` returned that single column (looked
like it "worked"), but no real column `NDG` existed, hence the error on `WHERE`.

## Fix
Decouple the input separator from the output one and auto-detect it.
- New optional per-input `delimiter` (`<input csv table delimiter>`); blank = auto-detect.
- `engine/InternalSteps.detectDelim(file, default)` sniffs the header line and counts comma /
  semicolon / tab / pipe outside quotes, returning the most frequent (ignores a leading BOM; falls
  back to the output delimiter for a true single-column file). Each input is now staged with its own
  detected/overridden separator; the staging log prints the separator used.
- `web/ApiController` csvsql preview uses the same detection (`InternalSteps.detectDelimiter`).
- Model/parser/writer/DTO carry the per-input `delimiter`.
- Designer: each input row gets a small **Sep** field (placeholder `auto`); a hint explains input is
  auto-detected and output uses the **Delimiter** field. XML serialises `delimiter` only when set.

## Result
A comma EORFULL extract now parses into its real columns (CODCLI, NDG, DESCR, …), so `WHERE NDG = …`
works. Output delimiter is unchanged and independent.

## Verification
- Compiles (96 classes). Designer JS: `node --check` OK, no literal `\n`/`\r`, no unsafe `[[`/`[(`.
- Live H2 still not runnable in the sandbox (jar unreachable); confirm on UBS by re-running the
  WHERE query.
