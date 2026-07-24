# dequote executor: join records split by line breaks inside quoted fields

## The real problem
The previous change dropped BLANK lines, which was the wrong cure. The actual defect is a record
split across physical lines because a quoted field contains a real line break, e.g.

    970  0325-00121981:521122;NOTARCOLA UMBERTO;C...
    971  "L'ORIGINE DEL RAPPORTO RISALE ALLA DATA...

The executor read ONE PHYSICAL LINE at a time (r.readLine() + parseCsv), so such a record was
never reassembled: the parser saw a fragment with an unterminated quote and wrote it out split.

## Fix
Records are now read as LOGICAL rows: readCsvRecord() keeps appending physical lines while the
double quotes on the record are unbalanced (oddQuotes(); RFC "" escapes keep the parity even, so
they never trigger a join), replacing the embedded break. Guards: at most 5000 continuation lines
per record, and an unterminated quote at EOF returns what has been read instead of hanging or
swallowing the file.

New step parameter `embeddedNewlines` (dropdown "Line breaks inside quoted fields"):
- `space` (default) — join the record, the break becomes a single space;
- `strip` — join the record, the break is removed;
- `keep` — legacy behaviour, the record stays split.
Field values are also sanitised so no CR/LF can survive inside a written field. Header and body
are read the same way. New output variable `embeddedNewlinesRemoved` (physical lines joined),
reported in the summary log next to blankLinesRemoved.

## Verify
oddQuotes/readCsvRecord were extracted from the source, compiled and executed: the 970/971 case
collapses into a single record in `space` and `strip`, stays split in `keep`, `""` escapes do not
join, and an unterminated quote at EOF terminates cleanly (next read = null). designer.html passes
node --check with no literal \n/\r and no unsafe Thymeleaf. Java -> Maven build on deploy.
