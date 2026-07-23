# Viewer line numbers + go-to, dequote drops blank lines, Waiting-approval column

## 1. Viewer: line/row numbers and "go to"
- CSV grid: a sticky "#" gutter column shows the 1-based row number, and a "go to row" box in
  the toolbar scrolls to a row and outlines it. The gutter width is folded into rowWidthPx()
  so the header, rows and the column resizer stay aligned.
- TXT/log: the line-number gutter already existed; added the same "go to line" box, with the
  target line highlighted.
- JSON/XML: the formatted output was a bare <pre> with no numbers. It is now rendered through
  the same line-numbered virtual list (renderLines), so XML/JSON get numbers, the line count in
  the meta line, and "go to line" too.
Shared helpers gotoBox() and renderLines(); no literal \n/\r in the source.

## 2. dequote executor: drop stray blank lines
The CSV de-quoting executor copied every physical line, so the stray line breaks left at the
end of a file (and any empty line in the middle) survived as empty records. Blank lines
(trim().isEmpty()) are now skipped and counted in the new output variable
`blankLinesRemoved` (also in the summary log). A line of just delimiters (";;;") is a valid
row of empty fields and is NOT dropped.

## 3. Operations overview: Waiting approval instead of Other
bucketFor mapped WAITING_APPROVAL to "running", so runs paused on a MANUAL GATE were
indistinguishable. It now has its own bucket, "waiting":
- new tile "Waiting approval" and a new by-source column "Waiting appr.", both right after
  Running, both clickable to drill;
- the "Other" tile/column is no longer shown; it is kept as a fallback and re-appears only if
  some feed really ends in an unmapped status (SKIPPED/REJECTED), so the row still reconciles
  with Total instead of silently losing feeds;
- the Mix bar gained a waiting segment; bucketLabel now labels waiting/on hold.

Verify: viewer.js and overview.html pass node --check, zero literal \n/\r, no unsafe
Thymeleaf; InternalSteps/ApiController brace-balanced (the pre-existing paren delta from
string literals is unchanged). Java not compiled here -> Maven build on deploy; app.css is
static so hard-refresh after deploy.

## CLAUDE.md
Intentionally NOT touched by this patch: the pending "Variables matrix" patch also appends at
the end of CLAUDE.md, and two EOF-append hunks conflict with each other. This note is the
record for these three changes; fold it into CLAUDE.md later if wanted.
