# elarxml: designer panel, and the `.done` rename made per file

Two defects, both reported from the first real use of the executor.

## 1. The `.done` rename ran once at the end of the run

`ElarRun` renamed the processed inputs in a loop placed *after* the last batch of the last file, so
it renamed either all of them or none. A run of three CSVs in which the third failed left the first
two **delivered but not renamed**; the next run reprocessed them, colliding on the synthetic filename
clock when `output.start_time` is set and producing silent duplicates when it is not — which is the
live configuration.

§5 of the spec already said *"only after every batch that input produced has reached its final
name"*. The implementation read that as *every* batch; the correct reading is *its own*. §5 has been
rewritten so it cannot be read the other way.

**Now**: two lists — the inputs that contributed a document to the batch currently open, and the
inputs read to the end whose last documents are still in it. Closing a batch flushes the
intersection; an input contributing to no open batch is renamed as soon as it has been read. The
contributor is recorded *before* the document is written, so a document that closes its own batch
under `ROLL_THEN_ALONE` still counts its input as one of that batch's producers. Every close goes
through one helper, `closeBatch`, and the rename is downstream of it, so `.done` continues to mean
*delivered*.

The correctness runs in both directions and both are tested. An input wholly inside a committed batch
must be renamed **while later files are still unread**; an input with even one document in a batch
that is aborted must **not** be, even if its other documents were delivered.

Files: `elar/ElarRun.java`, `.claude/ELAR_XML_EXECUTOR.md` (§5 + a follow-up section).

## 2. The executor had no designer panel

`elarxml` was in the executor dropdown but had no branch in the panel chain, so it fell through to
the generic external one — `＋ param` name/value rows and a disabled Script field. Added a dedicated
branch on the `sqlreport` model: the six required parameters, then three subsections (reading the
source CSV, writing the INDX, batching) carrying every optional parameter of §8 with the executor's
own defaults as placeholders. `clientValidate` names all six missing parameters in one message, as
the executor itself does, and refuses a multi-character `separator` or `quoteChar` — both are read
with `charAt(0)` and would otherwise be truncated in silence.

`maxBytesPerBatch` and `oversizeDocumentPolicy` stay **visible** under `batchBy=DOCUMENTS`, labelled
*NOT in effect*, rather than being hidden: the step log already names the ignored limit with its
value for the same reason.

**`buildXml` needed no change** — every field is a step `<param>`, which it already emits
generically. This is not the `reportQuery` case. Checked, not assumed: those assertions pass against
the unpatched file too, which is the proof.

`variables.html` gained the matching `PARAM_OPTIONS` entries, so a parameter that is a dropdown in
the designer is not a free-text box in the mass editor.

Files: `templates/designer.html`, `templates/variables.html`, `.claude/ELAR_XML_EXECUTOR.md`.

## Verified

- **33 assertions**, `--release 8`, running the whole executor against real files on disk: the happy
  path; one document per batch with the third file failing (the reported defect — it fails against
  the previous code with exactly three assertions); one batch spanning all three inputs, where
  nothing may be renamed; an input straddling two batches with the second aborted; and an input that
  produced no document at all. The mid-run failure is a metadata value the output charset cannot
  represent, which is a *writing* failure and therefore invisible to the pre-scan.
- **88 assertions** with jsdom against the real `designer.html`; the same harness fails **34** of
  them against the pre-patch file.
- Template scans clean on both templates: no uncommented `[[` / `[(`, no literal escape sequences in
  the inline script, `node --check` clean, and no duplicate top-level `function` / `var` (the scan is
  brace-depth aware — an indentation-based one flagged every local in every function body).
- `elar` package rule scans clean with comments stripped: no family literal, no `StringWriter` /
  `ByteArrayOutputStream` / `Transformer` / `DOMSource`, no `ofPattern`, no argument-less
  `getBytes()`, no `ELAR:` literal in `ElarRun`, braces balanced.

**Not compiled here**: nothing in this delivery touches Spring, so the `elar` package compiles
standalone in full — but `designer.html` and `variables.html` cannot be rendered without the internal
Nexus, and they were checked structurally and under jsdom instead. `mvn clean package` remains the
gate before deploy.

## Found while working, NOT changed — needs a decision

`max.line.length` from the properties file is **read, logged and then ignored**. `ElarRun:104`
resolves it and the step log prints it, but `Batch.open` and the PULL writer recompute the width as
`o.maxLineLength > 0 ? o.maxLineLength : 20000` — without the config — so unless the step parameter
`maxLineLength` is set explicitly, every INDX is wrapped at the hardcoded 20000 whatever the family
declares.

Measured, not inferred. A family declaring `max.line.length=25000`:

```
properties file declares : max.line.length=25000
the step log says        : max line 25000
longest line delivered   : 20000
```

Two consequences. The step log states a width the bytes do not have, which is the worst shape a
defect can take in an evidence trail. And §8b's decision — the fallback is 25000, not the legacy
20000 — never reached the code either; `CLICT@DT` sets the key explicitly and is unaffected by the
fallback, but it *is* affected by this, since its declared 25000 is being ignored.

Fixing it is one line, but it **moves the line breaks of every delivered INDX on the first run after
deploy**, which is precisely the kind of change the contract requires to be deliberate rather than
folded into a defect fix. Left alone here; say the word and it goes in its own batch, together with
the §8b fallback.
