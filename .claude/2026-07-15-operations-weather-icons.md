# Operations: weather icons per source row (fancy)

Adds a "Sky" column to the per-source rollup table in the Operations overview: a
weather emoji summarising each source's feeds.

## Mapping (weatherFor)
- all successful               -> shining sun          (U+1F31E)
- some failed                  -> storm                (U+1F329)
- all failed                   -> heavy thunderstorm   (U+26C8)  (meaner)
- all still to run             -> white clouds         (U+2601)
- success + to-run only        -> sun behind cloud     (U+26C5)
- (my choice) running          -> sun behind small cloud (U+1F324)
- (my choice) on hold          -> fog                  (U+1F32B)
- (my choice) aborted / mixed  -> sun behind rain / sun behind cloud

Priority: any failed wins (storm); then all-success; then all-to-run; then the
success+to-run mix; then running / on-hold / aborted / mixed. Each icon has a
title tooltip with the counts, and a compact legend sits under the table.

Emojis are written as \uXXXX escapes (proxy-safe, consistent with the codebase).

## Verify
overview.html passes node --check; no literal \n/\r; no unsafe Thymeleaf. The
weatherFor mapping was exercised in Node across all the cases above. Frontend only.
