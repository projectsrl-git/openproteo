# ELAR: the start tag made atomic, `formatOutput`, and elarcheck's always-empty Content

Two defects reported from the field on the same delivered file, plus the formatting option and a log
line that would have saved a round trip.

## 1. `elarcheck` reported every mandatory `Content` as empty

With `Content` in `mandatoryTags`, **every document of every file** came back `TagEmpty`, on a file
whose payloads are megabytes — and the verdict was `CORRUPTED` on files that were fine.

The payload is streamed rather than assembled, deliberately, so a half-gigabyte INDX costs no memory.
The branch that records *this tag has content* sat on the other side of that `if`, so `nonEmpty` was
never set for the content element and `checkMandatory` concluded it was empty every time.

This is the worst shape a checker defect can take, because it is **systematic**. A thousand documents
produce a thousand identical false alarms, and the noise buries whatever is real — which is the exact
opposite of what the executor exists to do.

Fixed by marking the element non-empty on the first non-whitespace character of the payload: one scan
of the first fragment, nothing after it.

**The complement is what keeps the fix honest.** A payload of 400 characters, and the same payload
wrapped over many lines, are no longer reported. A genuinely empty and a whitespace-only content
element **still are**. A fix that silenced those too would have swapped a false positive for a false
negative, which in an archive is the worse trade.

## 2. A line could end inside a start tag

Two `MarkupLineBreak` findings — *line ends inside a tag* — in 1 000 delivered documents.

Same shape as the value-edge defect, one level further out. A start tag was written in three
independent pieces — the name, each attribute, then the `>` — and each could break on its own.
`closeStartTag` called `fit(1)`, so when the column landed **exactly** on the limit the closing angle
alone was pushed to the next line. One column in every `maxLineLength`, per element: fifteen elements
a document, a thousand documents, two occurrences is the expected order of magnitude.

**The equivalence comparator called the same file fully equivalent, and was right.** `<ELAR:Doc` / `>`
is valid XML that any conformant parser forgives; only a byte-level reader sees it. The two tools were
not contradicting each other, they were measuring different things — worth remembering before treating
a disagreement as one tool being broken. It also bounds the severity: to a conformant parser this is
nothing. But we do not know ELAR's ingest is one, and the legacy produced the same class of break, so
it gets fixed.

`startTag(qname, attrs)` now writes the whole tag, measured before it is begun, with the break taken
before the `<`. `emptyTag` and `endTag` join it, and the piecewise `startElement` / `attribute` /
`closeStartTag` / `selfClose` / `endElement` are **removed from the API** rather than merely left
unused. Leaving a way to place the pieces independently is what let this recur after the value fix.

## 3. `formatOutput`, on by default

One element per line, indented by depth. **On by default**, decided explicitly: it changes the bytes of
every family on the first run after deploy, and buys a file an operator can check by eye.

Nothing about the content changes, and that is asserted rather than argued. Whitespace between elements
is insignificant in XML, every element carrying a value is written as one unbreakable unit, and the
payload stays **attached to its own tags** — `<ELAR:Content>` immediately followed by the first quad,
the end tag immediately after the last, exactly as an unformatted file has it. `endTagAttached` exists
for that one case.

Two properties that bound the risk:

- **Formatting only ever makes lines shorter**, so it cannot push a line past the receiver's limit.
  Checked by running the same feed both ways and comparing the longest line.
- **Formatting never causes a refusal.** When the indent and an element together would not fit a line,
  the indent is dropped rather than the element rejected, so the refusal threshold still depends on the
  element alone.

## 4. The name patterns are in the step log

`output.index_name_pattern` and `output.pull_name_pattern`, with `output.start_time` and whether
formatting is on. The filenames come **entirely** from those two patterns — nothing in the executor
adds an extension, a counter or a suffix — so *where did this `.xml` come from* is now a log lookup
instead of a hunt through a properties file on a share. That question cost a round trip; this is the
cheapest possible answer to it.

## Verified

- **19 assertions** for the tag unit and formatting, `--release 8`. The decisive one is exhaustive
  again: a start tag written at **every** starting column of a line, and no column ends a line inside a
  tag. Plus the exact column that used to split it; the indent shape line by line; the payload's
  attachment checked on the characters either side of its tags; and the same 40 documents run with the
  option on and off, where no value differs, none gains a line break, and the payload decodes to the
  same bytes.
- **5 assertions** for the checker, including the complement that a genuinely empty and a
  whitespace-only content element are still reported.
- The value-edge suite (31), the naming suite (15), the discards suite (36), the `.done` rename suite
  (33) and the elarcheck pair suite (6) re-run unchanged. Designer panels green at 94 and 67.
- `elar` and `elarcheck` rule scans clean, `elarcheck` still read-only by scan, template scans clean,
  and the new `USAGE.md` section written one paragraph per line for the `docs.html` renderer.

**Not compiled here**: `mvn clean package` needs the internal Nexus. Both packages compile and run
standalone in full; `InternalSteps` and `designer.html` were checked structurally and under jsdom.
