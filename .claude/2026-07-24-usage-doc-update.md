# USAGE.md: document the features added since the last doc pass

The online help is `src/main/resources/static/USAGE.md`, rendered by templates/docs.html
(route /docs), which contains a small hand-written markdown renderer (no library, no CDN) that
also builds the side TOC from the headings.

Added sections (all previously undocumented): step mode skip / on hold and how it differs from a
manual gate; mass-editing step mode from the Variables page; the Variables matrix (/matrix);
currentDate/currentTs; list indexing ${list[N]}; output data and run variables rendered one value
per line with a Σ total; Operations filtering and the current column set (Waiting appr., On hold,
Other only when needed) plus the weather legend and tag badges; viewer line numbers and go-to;
the Aggregate tab honouring the active filters; the standalone csv-viewer.html for testers;
duplicating a workflow now copying the uploaded files; what deleting a run does (and that the
audit trail is kept). Updated in place: the dequote executor bullet (logical records,
embeddedNewlines, blankLinesRemoved/embeddedNewlinesRemoved) and the Clear History section (PROD
confirmation checkbox, keep-the-last-run, and that it also runs from Operations on the selection).

RENDERER CONSTRAINT worth knowing for future edits: docs.html turns every source line into its
own paragraph, so a hard-wrapped paragraph renders as several <p>. Prose must therefore be
written as ONE long line per paragraph (and one line per bullet); fenced code blocks are fine.
The added text follows this.

Verify: the real render() from docs.html was extracted and run over the updated file — it
completes, produces 40 TOC entries and leaves no raw markdown outside code blocks; paragraphs
render as single <p> after the reflow.
