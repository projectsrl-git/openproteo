# elarcheck: the designer configuration panel

Closes the gap `elarcheck`'s own commit message and spec both recorded. The executor was in the
dropdown of `designer.html` but had no branch of its own, so a step fell through to the generic
external one: `+ param` name/value rows, an output-var repeater it has no use for, and a Script field
reading *(not used for this executor)*. Every parameter had to be typed by hand as a name/value pair
with nothing to say what the names are — and this executor has fourteen, thirteen of them optional.

Built on the `elarxml` panel delivered in the previous turn, which is now the model for both.

## Shape

`inputDir` — the only required parameter — then three collapsible subsections, grouped by **what is
decided together** rather than listed in declaration order:

- **Line length**: target beside receiver limit, with the reason they are two findings and not one
  written between them. Folding them into a single number would hide which of the two you have.
- **Element names**: the three local names plus the mandatory list, carrying the note that a tag
  missing on nearly every record is a mapping problem while one missing on three records is a data
  problem. That sentence tells an operator which of the two they are looking at, and it belonged
  beside the field rather than only in `USAGE.md`.
- **Optional checks and reporting**: the already-delivered directory, the findings cap, and the three
  switches.

Each field carries the executor's own default as its placeholder and its reasoning in a `title`, so
the *why* travels with the field. Three of those reasons are easy to lose and now sit on the screen
beside their setting:

- the charset is deliberately **not** the one the files declare — trusting the declaration would
  surface an encoding mismatch as a spurious structural error, the most misleading thing a checker
  can do;
- the findings cap caps the **list** and never the counters;
- `failOnFindings` is off so a gate can branch on the counters, because a step that always failed
  could not drive the check-then-repair shape this executor exists for.

## Two properties the panel states outright

That the executor is **read-only by construction** — which is what makes it safe against a live
delivery folder — and that **no field value reaches the findings file or any log line**. Neither is
decoration: the first is the reason it can be pointed anywhere, the second is why it can be pointed
at files carrying customer names and tax codes. Both are asserted by the test, so they cannot quietly
fall out of the panel later.

## Validation

`clientValidate` requires `inputDir`, and refuses a **target line length above the receiver limit**.
The executor accepts that combination and the two findings then read backwards — "over target" would
be the rejection and "over the receiver limit" the survivable one — which is worse than a refusal.
Equal values are allowed: the receiver limit is a bound, not a strict outer one.

## `inputCharset` deliberately left out of `PARAM_OPTIONS`

The Variables page's dropdown table is keyed by **parameter name with no executor context**, and the
two ELAR executors share `inputCharset` with different defaults — UTF-8 for `elarxml`, windows-1252
for `elarcheck`. One dropdown would print the wrong default for one of them, and on a mass-edit page
a confidently wrong label is worse than a free-text box. The unambiguous enums (`checkPull`,
`verifyHash`, `failOnFindings`) were added. Worth remembering before the next executor reuses a
common parameter name.

## Verified

- **67 assertions** with jsdom, driving the real `designer.html` against a real DOM: the panel is not
  the generic one; a bound field for each of the 14 parameters; no field writes a parameter
  `runElarCheck` does not read; both stated properties are present; the `inputDir` refusal; the
  target-above-receiver refusal and the equal-values case; the `buildXml` round-trip for twelve
  parameters; untouched optionals staying absent; re-enabling `checkPull` removing the parameter
  rather than writing `true`, because the executor's rule is *anything but false means on*; and the
  generated XML parsing with every `<param>` a direct child of `<step>`, which is what
  `WorkflowXmlParser` reads.
- **The same suite fails 23 of them against the pre-patch file** — the reported gap stated as a test.
- **Three of those assertions render an `elarcheck` and an `elarxml` step side by side** and check
  each keeps its own panel. The two branches are adjacent in the same chain, and a collision there
  would be invisible in either suite on its own. The `elarxml` suite (88 assertions) was re-run
  unchanged.
- Template scans clean on `designer.html` and `variables.html`: no uncommented `[[` / `[(`, no
  literal escape sequences in the inline script, `node --check` clean, no duplicate top-level
  `function` / `var`.
- `buildXml` needed no change: every field is a step `<param>`, which it already emits generically.

**Not compiled here**: this delivery touches only two Thymeleaf templates, which cannot be rendered
without the internal Nexus. No Java changed. `mvn clean package` remains the gate before deploy.
