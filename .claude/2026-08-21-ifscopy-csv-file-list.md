# ifscopy: copy the files listed in a CSV column

Base commit: `70da085`.

## What was asked

On the IFS copy executor, add an option to copy a list of files enumerated in a CSV: the name of
the file (which may come from an earlier step) and the field holding the file name, plus an optional
path to prepend when the name is not a full path.

## The shape of the option

One param, `listSource`, selects between two shapes:

* absent or `pattern` — **exactly today's behaviour**, including which variables the step publishes.
  The code path was moved but not touched.
* `csv` — copy exactly the files named in one column of a CSV.

**An unrecognised value fails the step** rather than falling back to `pattern`. Falling back would
answer a typo (`cvs`, `CSV_LIST`) with a directory copy that has no pattern set — i.e. everything in
the directory. That is the one degradation this executor must not have.

Params, all in the new shape only: `listFile`, `listColumn`, `listPathPrefix`, `listDelimiter`,
`listCharset`, `hasHeader`, `onMissingFile`, `onNameCollision`. Nothing was added to `StepDef`, the
parser, the writer, the DTO or `buildXml`: params round-trip already, so the five-place rule for new
child elements does not apply here and the diff stays inside the executor and its designer branch.

## Decisions worth the words

**The prefix has a fallback, and it is the field already on the step.** `listPathPrefix` wins; when
it is empty the existing **IFS source path** field is used as the base. A list of bare file names
therefore needs no second copy of the directory it came from, and the field keeps meaning something
in both shapes rather than sitting there greyed out. Which base was used is stated in the log, so
the fallback is never something you have to infer. A name starting with `/` is taken as a full IFS
path and the prefix is **not** applied — that is what lets one step read a column that sometimes
holds a full path and sometimes a bare name, which is the realistic case for a list produced by a
query.

A backslash inside a name is left alone. It is legal in an IFS name, and rewriting it would corrupt
a genuine name in order to accommodate a Windows-flavoured list that this source system does not
produce.

**No existence pre-scan, deliberately, and unlike `elarxml`.** There the pre-scan exists because a
partial set of batches already renamed to final deliverable names, beside an input with no `.done`,
is unrecoverable. Here the destination is a step working directory and the run stops; a pre-scan
would cost one round trip per listed file — for a list of thousands, the whole transfer twice over —
to buy nothing the failure message does not already say. `copyListToLocal` therefore **returns**
rather than throws when a file is missing, so the caller can still publish how many were copied
before the stop, which is the first thing anyone asks about a half-done transfer.

**A missing file fails by default** (`onMissingFile=fail`). This is a new shape, so there is no
existing feed to protect: the choice is between the two behaviours on their merits, and a list is an
explicit request in a way a glob is not — a name in the list that is not on the IFS is a fact about
the data, not about the pattern. `skip` counts them in `${missingFiles}` and names them in the log.

**A local-name collision fails by default** (`onNameCollision=fail`), before anything is copied.
`/one/x.pdf` and `/two/x.pdf` both land on `x.pdf`; without the check the step would report success
with fewer files in the destination than it copied, which is the silent-short-delivery failure this
project keeps meeting. The same path listed twice is a **duplicate** (collapsed, counted), not a
collision — the tests pin the distinction, because conflating them would make an ordinary duplicate
fail the step.

**Nothing is lost in silence.** Rows read, files to copy, duplicates collapsed and rows with no file
name are all logged before the transfer starts, with the first fifty offending line numbers named
and the counts uncapped. A row that is too short to have the column is treated like an empty cell
and counted; a physically empty line is not a row at all.

The `pattern` field is ignored in this shape and the step **says so with its value** when one is
set, following the `elarxml` rule that a setting which can be read but has no effect is worse than
one that is absent.

## Where the code lives

New `engine/IfsListSupport` — no Spring, no JTOpen, no orchestrator types — holds everything of
substance: column resolution, path building, dedup, blank and collision accounting. That is what
makes the feature testable here rather than only on deploy, the same reason `SqlReportSupport` and
the `elar` package are shaped that way. `InternalSteps.runIfsCopyList` translates params in and
counters out; `IfsSupport.copyListToLocal` does the transfer over a single AS400 connection.

It carries its own field splitter rather than calling `InternalSteps.parseCsvLine`, which is private
and would drag Spring into the harness — the same trade `FlatCsvReader` made. The **delimiter is
not** sniffed there: the caller passes the character it got from `InternalSteps.detectDelimiter`, so
detection stays single-sourced with `csvsql`.

Column resolution tries the **header name first** (case-insensitive) and a 1-based index only when
no column carries that name, so a column genuinely called `2` still wins. Without a header a name is
refused with an explanation rather than being read as index 0.

## Verified

* `IfsListSupport` compiled `--release 8` and **run**: 73 assertions — path building with and
  without a base, absolute names ignoring the prefix, root and trailing-slash bases, column by name
  (case-insensitive) and by index, index past the end and unknown name both naming the available
  columns, no-header mode refusing a name, BOM stripped from the header *and* from the first data
  row, ISO-8859-1 and an unknown charset, RFC-4180 quoting with the separator and doubled quotes
  inside a name, duplicates vs collisions, short rows, six degenerate files (empty, header-only,
  missing), the reporting cap counting everything while listing fifty, and list order preserved.
* `IfsSupport.java` **compiled** against minimal stubs of `AS400`/`IFSFile`/`IFSFileInputStream`/
  `DataSourceDef`, so the new method is known to compile even though the transfer needs a real IBM i.
* `designer.html` driven under **jsdom against the real script**: 44 assertions — the pattern shape
  renders exactly as before (IFS path still starred, Pattern shown, old outputs line), the CSV shape
  hides Pattern and relabels the IFS path, the switch's default option writes **no param at all** so
  switching back leaves the step byte-identical, an unrecognised stored value does not open the CSV
  panel, the three policy dropdowns default to the safe answers, stored values are preselected, the
  XML preview carries the params, and `clientValidate` requires `ifsPath` only in the pattern shape
  while reporting *both* missing CSV fields rather than the first.
* `USAGE.md` rendered with **docs.html's own `render()`**: the section is in the TOC, no raw markdown
  leaks outside code blocks, and none of its 8 paragraphs is a wrapped fragment. Two escapes were
  caught this way: `inline()` is `\*\*([^*]+)\*\*`, so `**… *local* …**` never renders and the
  renderer has no italics at all — both rewritten.
* Brace/paren balance ignoring strings and comments on all three Java files; `node --check` on both
  templates; **zero literal `\n`/`\r`**; no uncommented `[[` / `[(`; duplicate top-level function and
  `var` scan clean.

**Not compiled: `InternalSteps.java`** — it needs the Spring tree from the internal Nexus, which the
sandbox cannot reach. Checked structurally instead: every helper it calls (`xStr`, `blankToNull`,
`rebaseRel`, `detectDelim`) verified against its real declaration, and every `CopyResult` field
against the compiled class. `mvn clean package` is the gate.

## Follow-up

`PER_FILE` progress: a list of thousands logs one line per file, like the pattern path. If that
proves too noisy on a real list, the fix is a summary every N files, not a silent copy.
