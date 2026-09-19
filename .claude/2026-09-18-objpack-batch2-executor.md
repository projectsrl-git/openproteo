# objpack — Batch 2: the packager and its registration

The executor itself: pairing, mapping, pre-flight, the five artifacts, and the four registration
locations. `ObjectPack` is Spring-free, so the whole pipeline runs against real files outside the
container; `InternalSteps.runObjPack` only reads parameters, which is why it has no logic of its own
worth testing.

## Gate 0

Answered and implemented: **9.1** OID width derives from the object count (5 objects give `OID1`,
12 give `OID01`), for every feed; **9.3** `mime_type` is the dotted extension, so the pre-flight
compares it to the object's own extension and there is no media-type table; **9.5** the executor
produces metadata + audit + control + tar + md5 from the *source* CSV; **9.8** `transmissionDate` is
required and never defaults to today — a default would rename the submission on a retry the next
day, which §2 forbids.

Still open, each with a conservative default and none of them blocking: **9.2** `V001` per §2 rather
than §3.5's `V1`; **9.4** `compression` refuses anything but `none`, with the message naming the
question; **9.6** `outDelimiter` is accepted but not validated, since the Accepted Delimiters List
was never captured; **9.7** a stale business date warns, and only fails under
`failOnStaleBusinessDate`; **9.9** a missing object fails, as the script does, unless
`onMissingObject=skip`.

## Decisions worth the argument

* **Nothing is reused that already exists.** `FlatCsvReader` reads the source CSV and `CsvWriter`
  writes the output one — a second CSV implementation in this repo would be the `FileMask` mistake
  again. `FlatCsvReader` is line-based, so a record split by a bare newline cannot be rejoined by
  it; rows whose field count disagrees with the header are **refused with the fix named**
  (`dequote`, `csvsql`), rather than parsed into something plausible.
* **The objects are never copied.** A tar member's name is independent of where its bytes are read
  from, so the Transarch rename happens while streaming straight out of the landing zone. The
  PowerShell script staged every object into `%TEMP%` first, which at the documented 20 GB CS
  ceiling is 20 GB written and read to produce nothing. `emitObjects=yes` materialises the renamed
  copies for inspection or for the non-TAR path; it is off by default, because a TAR submission
  delivers only the tar and the md5.
* **Mapping is six parameters, not `<column>` children.** This reverses what the batch-0 spec said,
  and the reason is in the code: `<column>` is a *parser-level* construct shared with `xlsx2csv` and
  built for mapping ~100 columns in `json2csv`, so adding roles to it would mean touching the
  parser's column handling for three executors. Six fixed keys are what `diff` already expresses as
  `match.N.*` parameters. The result is that the parser change is the whitelist and nothing else.
* **A mapped `object_id` that does not ascend from 1 is refused, not renumbered.** Silently
  replacing an id the feed chose breaks every downstream reference to it. Leaving `map.objectId`
  unset asks the packager to assign 1..N, and the error message says so.
* **An ambiguous name match fails naming both paths.** Picking the first would archive a plausible
  wrong document under a right-looking name, which is the failure this package format exists to
  prevent.
* **A CSV-supplied path is resolved against `objectsDir` and refused if it escapes**, absolute paths
  included; both separators are accepted, since the CSV may have been produced on either platform.
* **Nothing is written until every cheap check has run**, so a submission that is going to be
  rejected fails before it has produced a 20 GB archive.
* **The finished tar is read back by `UstarReader`**, which shares no code with `UstarWriter`.
  Verifying an archive with the code that wrote it proves very little.

## Verification

**96 assertions green** — 83 from `PackSuite` running the real pipeline over real files, and 13
cross-reader on the produced package: GNU tar lists the six members in the specified order and
extracts them byte-identical against their sources, including one that came from a sub-directory;
python's `json` confirms the type asymmetry the specification requires, reading `transmission_date`
as an int and `sequence_number` and `object_id` as strings; python's `csv` parses the metadata file;
and `md5sum` agrees with the sidecar.

**20 mutations, all caught.** Two were green on the first run and both were **real gaps in the
suite**, not bad mutations:

* Ignoring `recurse` survived, because in `order` mode the extra nested file is simply never
  consumed — the CSV had fewer rows than the listing, so the count was 2 either way and the
  assertion was not measuring the flag at all. A `name`-mode case now fails to find a nested object
  when `recurse=false`.
* Disabling the audit `record_count` check survived, because `write` always writes a consistent
  count and nothing made the two disagree. `AuditJson.validate` is now called directly with a
  mismatched list, a renamed file and a changed `object_id`.

The mutation runner works on copies in a scratch tree, so the working copy cannot be left dirty.

Three `PackSuite` assertions failed on their first run and all three were **my expectations, not
defects**: the fixture deliberately leaves `map.objectId` unset to exercise auto-assignment, which
makes its `doc_id` column ordinary feed metadata that passes through after the four mandatory ones —
exactly what §3.2 allows.

## Not verified

`mvn clean package` — the Spring classes cannot be compiled here without the dependency tree. What
was done instead, per the contract: `javac --release 8` on the whole `objpack` package plus its two
collaborators; brace balance on all four edited files, ignoring strings and comments; every helper
the new `InternalSteps` code calls confirmed to exist exactly once (`intParam`, `longParam`,
`rebaseRel`, `VarResolver.resolve`), and `pv`/`yes` confirmed not to be duplicates; `node --check`
on the designer's inline JavaScript, with a positive control proving the check can fail and a
baseline proving it can pass.

No run on Windows. No Transarch ingestion. No designer panel — batch 3.
