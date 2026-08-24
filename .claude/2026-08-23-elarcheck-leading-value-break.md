# elarcheck: a break at the head of a value was invisible to both tools

Asked whether `elarcheck` replaces `Repair-ElarIndxLineBreaks.ps1`, since the script takes half an hour
against a few seconds. Checking the answer properly turned up a defect in both.

## The boundary first

`elarcheck` **detects** everything the script detects — `TextLineBreak`, `MarkupLineBreak`,
`InvalidSpaceAfterAngle` — plus well-formedness, both line-length thresholds, mandatory tags, name
reuse, PULL pairing and the digest. It **repairs** nothing, by construction, and a build scan asserts
there is no write API anywhere in the package. For a file that is already corrupt the script remains
the only thing that fixes it. Detection and repair are different questions and the answers differ.

## The defect the question exposed

Probing every class of break the script handles turned up one that `elarcheck` reported nothing for:

```
break at the START of a value   (<tag><LF>value) -> (nothing)
```

A line ending in `>` was treated as safe, always — the fast path that makes a half-gigabyte scan cheap.
It is not safe when that `>` closed a **start tag**: the next character is the first of the element's
content, so the break gives the value a **leading** line feed.

This is exactly the class the generator itself produced — 43 documents in 1 000, on `ClientAdvisor`,
`RecordDescr`, `AccountID` and `ClientID`. Only `Compare-ElarIndx.ps1` saw them, because it compares
values against a reference rather than reading bytes.

**And the repair script has the same blind spot**, stated in its own description: *a line ending in '>'
ends between elements and is classified with no further analysis*. Run with `-Fix` over such a file it
would have rewritten the corruption unchanged and declared the file sound. So the half hour was not
buying that coverage — it was a gap in both tools, not a scruple worth keeping.

## The fix

The decision cannot be taken on the line that ends; it needs the one that follows. Markup means the
element has children and the break was genuinely between elements; anything else is character data,
and the value is wrong.

**A value can never begin with `<`** — it would be escaped — so the test is exact rather than a
heuristic. Leading whitespace is skipped, because with `formatOutput` on an indented file is now the
normal case rather than the exception. The content element is excluded: a break after
`<ELAR:Content>` is inside the payload, where whitespace is ignored by every decoder.

## Verified

- **11 assertions.** Half are the defect - the break itself, and the element named in the finding.
  The other half are the **false positives**, and that half matters more, because a checker that fires
  on every document is worse than one that misses: an indented file, an unindented multi-line file, a
  break after the content start tag, after a self-closing tag, after an end tag, and after a start tag
  whose child follows - none reported.
- **Cross-package, end to end**: 300 documents written by `elarxml` at a 300-character line limit, run
  through `elarcheck` with six mandatory tags including `Content` and `verifyHash` on, **with
  formatting on and off**. No findings of any kind either way.
- The mandatory-Content suite (5) and the pair suite (6) re-run unchanged. Package rule scans clean and
  the read-only scan still clean - the change reads two lines instead of one and writes nothing.

**Not compiled here**: `mvn clean package` needs the internal Nexus. The `elarcheck` package compiles
and runs standalone in full.

## Still open, and worth doing once

`TextLineBreak` detection has still never been run against a genuinely corrupt legacy file - only
against files this project generated. `MarkupLineBreak` has been proven in the field, since it found
the two breaks `Compare-ElarIndx.ps1` could not see. Running both tools once over a known-bad INDX and
comparing the counts would settle it permanently, and cost half an hour once rather than at every run.
