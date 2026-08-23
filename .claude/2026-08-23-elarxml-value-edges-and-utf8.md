# elarxml: the line break landed at the edges of a value, and outputCharset is now UTF-8

Both found by running the equivalence comparator over 1 000 real documents — reference produced by the
PowerShell scripts, candidate by `elarxml`. Neither was visible in the sandbox until the field run
pointed at where to look.

## 1. The break landed at the edges of a value

43 `WHITESPACE` findings plus 2 records reported as **both** missing and extra. In every one of them
the line break was on the **candidate's** side — ours — and always at one of two positions:
immediately before the value or immediately after it. `AccountID`, `ClientID`, `ClientAdvisor`,
`RecordDescr`; and for the 2 split records `UniqueReportID`, where a break inside the key made one
document look like two.

§5 already stated the rule correctly: *between elements legal, inside any other text node never*. The
implementation got the **boundary** wrong. The position immediately after the `>` of a start tag and
the one immediately before the `</` of an end tag **look** like element boundaries and are not — both
are inside the character data. `text()` and `endElement()` each called `fit()`, so either could break
there.

It is the legacy defect moved from mid-value to the edges, and arguably worse: a break in the middle
of a value is visible on sight, a leading or trailing one is not.

### The fix

An element carrying a value is written by `WrappingXmlOut.textElement` as **one unbreakable unit**:
the whole `<tag attrs>value</tag>` is measured first, the break — if any — is taken **before** the
start tag where it is genuinely between elements, and nothing inside can break, which is why it
writes through `raw` and never through `fit`. `text()` is now private and non-breaking; a public
breaking text writer is what produced the defect in the first place.

Three consequences worth recording:

- **The refusal now counts the tags.** If the unit cannot fit a line the document is refused naming
  the tag, and the threshold includes the tags rather than only the value, so a value that used to
  pass by a few characters begins to fail. Deliberate: overflowing the line delivers a value the
  receiver truncates at 30 000, and a truncated value inside a legally archived document with nothing
  to flag it is worse than a refusal that names the field.
- **The content tag is exempt by construction, not by exception.** A Base64 payload never reaches
  `textElement` — it goes through `base64Chunk`, which breaks at quad boundaries where whitespace is
  ignored by every decoder. Asserted rather than assumed.
- **Mixed content is refused at load**, naming the element. It has no position its text could keep
  under the unit rule, and the previous emitter reordered it anyway — all the text first, then every
  child — while also being free to break between the text and the first child. Whitespace-only text
  between children is formatting and is still discarded, so an indented template passes.

## 2. `outputCharset` now defaults to UTF-8

A **declared exception to the conservative-default rule**, taken deliberately: it changes the bytes of
every family that does not set the parameter, on the first run after deploy.

Measured, not assumed. A byte probe over the INDX ELAR receives today found 294 non-ASCII bytes
forming 147 valid UTF-8 multibyte sequences, **zero stray high bytes**, and a declaration of UTF-8.
That file is UTF-8 and says so. The candidate under the old default was ISO-8859-1, also coherent with
its own declaration, and the two therefore differed on every accented character: 147 `VALUE` findings,
all `U+00C3 -> U+00E0` at the same position of the same field.

The old default came from `elar-file-maker.jar`, whose declared/actual mismatch §2 recorded as the
reason for choosing ISO-8859-1. That reasoning was right about the JAR and wrong about the target:
**the file to be equivalent to is the one the PowerShell scripts produce.**

Stated now rather than discovered later: `maxLineLength` counts **characters**, not bytes. Under UTF-8
a line of 25 000 characters can exceed 25 000 bytes. Whether the receiver's 30 000 limit is counted in
bytes or characters has **not** been confirmed with the receiving team. At the density measured — 147
accented characters across 1 000 documents — the difference is far inside the 5 000 margin, but the
question is open, not closed.

## Verified

- **31 assertions**, `--release 8`. The decisive one is **exhaustive rather than representative**: the
  same value written at *every* starting column of a line, all of which must round-trip. The defect
  only appeared at the few columns where the element straddled the limit, which is precisely why 43
  documents in 1 000 slipped through and why a handful of hand-picked cases would not have caught it.
- **Against the previous code, 47 of 114 columns corrupt the value**, and end to end two values per
  run come back carrying a line break. The same suite, reduced to what the old API can express, fails
  6 assertions.
- Also asserted: the break lands before a start tag and at neither edge; the refusal names the tag and
  says why it is not simply broken; the tag overhead counts toward the threshold and an element that
  fits exactly is written whole; attributes and escaping survive the unit; a 5 000-byte payload under
  a 100-character limit wraps across more than fifty lines and decodes byte-for-byte; end to end over
  200 documents at a 300-character limit **not one value carries a line break** and every value is
  exactly what went in; the accented character survives; the PULL still names its INDX with no break
  inside it; and the declaration says UTF-8 with `à` written as the two bytes `C3 A0`.
- The `.done` rename suite (33) and the naming suite (15) re-run unchanged. Both designer panel
  suites re-run green (88 + 67) after the default label moved to UTF-8. `elar` rule scans clean.

**Not compiled here**: `mvn clean package` needs the internal Nexus. The `elar` package compiles and
runs standalone in full; `InternalSteps` and `designer.html` were checked structurally and under
jsdom. The build on the target machine remains the gate.

## What this closes

With both changes, the 43 whitespace findings, the 2 split records and the 147 value findings all have
a cause and a fix. A re-run of the comparator with `-ReferenceEncoding UTF-8 -CandidateEncoding UTF-8`
should now report equivalence — and at that point the comparison is a proof of equivalence rather than
a list of excuses.
