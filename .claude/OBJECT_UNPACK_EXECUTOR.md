# Object submission unpacker (`objunpack`) — specification

Status: **complete — batch 4 delivered** (the template and the chain — §16) on `930aaaa`; batch 3 (designer panel — §15) on `1203b25`; batch 2 (registered — §14) on `538d1bb`; batch 1 (the core — §13) on `5f6c8c3`. Batch 0, the specification,
was written 2026-10-04 on `89a66ad`. Gate 0 answered 2026-10-04 (§11). Corrections made by batch 1
are struck through where the wrong text was, not rewritten.

`objunpack` is the inverse of `objpack`: it takes a Transarch TAR-packaged object submission
(`<base>.tar` or `<base>.tar.gz`, plus `<base>.md5`) and gives back the packaged metadata CSV and
the objects under their original names, ready for the chain that rebuilds and resends:
`csvsql (SELECT {{columns}} FROM SOURCE) -> dequote -> validate -> objpack -> ftpsend`.

Format references: `.claude/TRANSARCH_OBJECT_SUBMISSION_SPEC.md` (§ numbers below prefixed **T**),
`.claude/OBJECT_PACKAGE_EXECUTOR.md`. Reused components: `.claude/UNARCHIVE_EXECUTOR.md`.

---

## 1. The decision already taken, and why

The mapping *name in the package → original name* is the join of two files inside the package:
`audit.json` gives `file_name` ↔ `object_id`, `metadata.csv` gives `object_id` ↔
`original_object_name`. **Never a name template.** Measured before this spec, and re-measured for it
(§3 M1): with `map.nameLabel` set, `objpack` writes `<base>.SECTSC2028300108d_pdf.OID3.pdf`; the
template of `Rename-FilesFromCsvMap.ps1` and of `filerename` builds `<base>.OID3.pdf` and finds
nothing, and the label is lossy (`.` → `_`). `audit.json` lists the exact member name whatever the
producer, the padding or the label.

Consequences fixed here: the script is **not** ported to `.sh`, and `filerename` is **not** called
by `objunpack` — neither can express the join.

## 2. Scope

**In:** one package → its objects under their original names, its metadata CSV byte for byte, and
the submission's identity as output variables. A workflow template with the whole chain.

**Out:** zip and the non-TAR AzCopy path; bzip2/xz packages (recognised by `ArchiveFormat`, refused
naming the format, as `unarchive` does); anything the package does not contain (§10).

## 3. Measured on 2026-10-04

Linux sandbox, Temurin 1.8.0_432 (real Java 8; the reused packages compiled with its own `javac`),
PowerShell 7.4.6, GNU tar 1.35, bsdtar 3.7.2 (libarchive — the same program as Windows' `tar.exe`),
Python 3.12 as the independent reference. Source: six objects, among them
`TF0005756_ACCOUNT.DEBIT.INT.GG_GB0010007_1.2` (JSON, suffix `.2`), `Makefile` (no suffix),
`Relazione perché 2026.txt`, `note & co's.xml`; a metadata CSV with a quoted comma and doubled quotes.

Ten packages, every one produced by a real producer:

| Fixture | Producer |
|---|---|
| `op_P`, `op_L`, `op_Z`, `op_LZ`, `op_PC` | the real `ObjectPack`: plain; `map.nameLabel`; gzip; label + gzip; `outDelimiter=,` |
| `legacy_gnu`, `legacy_bsd` | `Object_CS_Archiving_v4_UK_PS5.ps1` (sha256 `f6c47026…404583d4`, the photographed transcription) under `pwsh`, `-MapMode Filename`, with GNU tar and with bsdtar as `tar.exe` |
| `legacy_marker`, `legacy_order`, `legacy_ml` | same script: `-UseObjectMarker`; `-MapMode Order`; a metadata value containing a line break, no `-TargetDestination` |

| # | Question | Result |
|---|---|---|
| M1 | Does the join restore the right bytes under the right names? | Prototype (python join, real `ObjectPack` for the re-pack): on 9 of 10 fixtures the restored objects are byte-identical to the sources under their source names. The tenth is M6 |
| M2 | Round trip package → restore → `ObjectPack` → restore | 9 of 10: same objects, same header, same rows, same ids. The tenth is M7 |
| M3 | Do `ArchiveFormat`, `GzipSupport`, `TarStreamReader` read every producer? | Yes: ustar (`objpack`, bsdtar), GNU (`ustar  `), `.tar.gz`; names, sizes and types equal Python `tarfile` on all; end marker seen on all |
| M4 | Which existing CSV reader reads `metadata.csv` correctly? | **`rename.PsCsvReader`.** `elar.FlatCsvReader` is line-based: on `legacy_ml` it returns 4 rows for 3 records. `PsCsvReader` equals Python `csv` on all three shapes tried |
| M5 | Is there a reusable `audit.json` reader? | **No.** `AuditJson` only has a private key scanner for its own self-check: it pairs `file_name` and `object_id` by position in the document, and does not decode `\uXXXX`. And `SubmissionName` builds names; it parses none |
| M6 | `-MapMode Order` | The script pairs the i-th object in name order with the i-th CSV row. In `legacy_order`, `OID000001` holds the bytes of `Makefile` and its row says `statement_12345.pdf`. The package is internally coherent; the names are not the files' own |
| M7 | A metadata value with a line break | Restored correctly; then `ObjectPack` refuses the CSV (`line 5 … has 2 fields`), as its spec says. The chain's `csvsql` *line breaks inside values* option exists for this |
| M8 | Member order | `objpack`: audit, metadata, objects, control. Legacy: **objects first**, then audit, metadata, control |
| M9 | Legacy metadata | `object_id` first, then the source's columns **in the source's order** (not the Transarch order); `,`; host line endings; rows in the objects' name order |
| M10 | `.md5` | 32 lowercase hex characters, no line ending, both producers |
| M11 | `PsCsvReader` details | drops leading blanks of an unquoted field (`Import-Csv` does); skips a first line starting `#TYPE`; refuses duplicate header names; decodes malformed bytes without error |
| M12 | `objpack` name pairing | trims the name, tries the exact name first, falls back to a case-insensitive listing |

**Found on the way, NOT part of this work:** `ObjectPack.glob` lower-cases the regex it has just
built with `Pattern.quote`, turning `\Q…\E` into `\q…\e`. Measured on Java 8 and 21: every
`include`/`exclude` pattern other than bare `*`/`?` throws `PatternSyntaxException` (`*.pdf`,
`*.audit.json`). It is reached only when the directory is listed (`objectSource=order`, or `name`
with `recurse`), which is why the feeds in production, pairing by name, have not met it. Also,
§5 of the objpack spec gives `exclude` a default of "the five output patterns"; the code's default
is empty. Both need their own patch.

## 4. How it works

One pass over the archive, because of M8 — the legacy tar has the objects before the files that
name them:

1. **Locate.** `archive` resolves to exactly one file (§6). Its name is parsed by a new
   `SubmissionName.parse` (same class as the builder; M5).
2. **Checksum** (§11 G1), with `objpack.Md5.ofFile` — the code that writes the sidecar.
3. **Extract to staging**, `<outputDir>/.objunpack-<runId>.part/`, each member ~~under its **member
   name**~~ **under a number** (corrected in batch 1: an object never lands under its member name,
   so staging it there only imported that name's length and character problems; the member name is
   still validated): `ArchiveFormat.decide` → `GzipSupport` → `TarStreamReader`, every name through
   `EntryName.validate` with the detected `HostRules`, every byte through `Budget`.
4. **Read** `audit.json` with a new `AuditJson.read` (§5.2) and `metadata.csv` with `PsCsvReader`.
5. **Check** the package (§7). Nothing has a final name yet.
6. **Rename** each object inside staging to its original name, validated as a host file name (§8).
7. **Commit**: staging becomes `<outputDir>/objects/` and `<outputDir>/package/` (§6), with
   `HostFiles.renameNoReplace`; cleanup with `HostFiles.deleteTreeNoFollow`.
8. **Publish** the variables (§6) and exit 0.

Disk: the size of the package's content, once. No second read of the archive except the checksum.

## 5. What is reused, and the two things that do not exist

### 5.1 Reused as is

`unarchive`: `ArchiveFormat`, `GzipSupport`, `TarStreamReader`, `EntryName`, `HostRules`,
`NameIndex`, `Budget`, `UnarchiveException`. `objpack`: `SubmissionName`, `Md5`. `rename`:
`PsCsvReader.parse`. `platform`: `HostFiles`. ~~All already `public`; no visibility changes.~~
**Corrected in batch 1:** one change — `EntryName.printable` (control characters shown as escapes in
a log line) was package-private and is now `public`. The alternative was a second copy of it. Also
reused, not listed in batch 0: `unarchive.FileMask` for the `archive` wildcard.

**∩ U1 — "reuse `LinkGuard`" × "a package is flat".** A Transarch package holds regular files with
bare names (T§1, measured on all ten). A link, directory, device or a name with a separator is not
something to confine: it is a non-conforming member and is **refused on every host**. `LinkGuard`
is therefore not called — there is never a link to guard. Stated because the task listed it.

**∩ U2 — `UNARCHIVE_EXECUTOR.md` §5 "a package may not depend on another executor's" × this
task's "no second implementations".** The task wins: `objunpack` depends on `unarchive`, `objpack`
and `rename`. The cost is that a change to `EntryName` now has two callers; batch 1's suite pins the
calls it relies on.

**∩ U3 — `PsCsvReader` decodes leniently (M11) × "nothing is guessed".** `objunpack` decodes the
metadata bytes itself, strictly as UTF-8 (BOM accepted), and refuses a malformed byte naming its
offset; only then does it call `PsCsvReader.parse(text, delimiter)`.

**∩ U4 — `PsCsvReader` drops leading blanks of an unquoted field (M11) × "the original name is
restored exactly".** The reader wins: a name written unquoted with a leading blank is restored
without it. `objpack` trims the name it looks up (M12), so the re-pack still finds the file.
Example: `1; a.pdf` restores `a.pdf`. **Extended in batch 1 to trailing blanks, for the same
reason:** the name is trimmed on both sides, exactly as `objpack` trims it — measured on a legacy
package whose row says `a.pdf ` (the script keeps the trailing blank): restored as `a.pdf`.

### 5.2 New, in the class where they belong

* **`SubmissionName.parse(String archiveLeafName)`** → tfId, transmission date, sequence, version,
  and the compression suffix. Accepts `V001` and `V1` (T§6.1). It is validated by building the name
  back with the existing constructor and comparing.
* **`AuditJson.read(File)`** → a typed model. A small strict JSON parser, JDK only: objects, arrays,
  strings with every escape including `\uXXXX`, numbers, literals; duplicate keys refused. It reads
  each `submission_object_files` item **by key**, so the unstable key order of the legacy hashtable
  and any indentation are irrelevant. `object_id`, `sequence_number`, `version_number` are accepted
  as string or number (T§6.5). `AuditJson.write` and `validate` are not touched, so `objpack` does
  not change. Alternative considered: Jackson, already on the application classpath — rejected
  because the core would stop compiling and running in the sandbox, which is the only place it is
  tested before delivery.

## 6. Parameters and output

| Param | Default | Meaning | Applies |
|---|---|---|---|
| `archive` | — (required) | the package: a path, or a path whose last segment has `*`/`?` and matches **exactly one** file. None, or more than one: refused, listing them | next run |
| `outputDir` | `${stepDir}` | receives `objects/` and `package/` | next run |
| `md5Check` | `require` | `require` \| `ifPresent` \| `off` — G1 | next run |
| `onInconsistency` | `fail` | `fail` \| `warn` — §7, G4 | next run |
| ~~`onExisting`~~ | ~~`fail`~~ | ~~`fail` \| `replace`: what to do when `objects/` or `package/` already exist~~ **Removed in batch 2 by the author's decision (§14): nothing that exists is ever replaced. An existing `objects/` or `package/`, even empty, refuses the step** | — |
| `metadataDelimiter` | `auto` | `auto` = the character after the `object_id` header; or one character | next run |
| `maxObjects` | 100000 | objects; the member limit is this **plus three** (∩ U5) | next run |
| `maxObjectMb` / `maxArchiveMb` / `maxRatio` | 2048 / 20480 / 200 | `Budget`, as `unarchive` | next run |
| `maxPathLength` | `auto` | as `unarchive` (259 UTF-16 units on Windows, 4096 bytes on Linux) | next run |
| `preserveMtime` | `true` | objects keep the time stored in the tar | next run |

Every parameter is a step `<param>` set from the designer panel: checklist item 10 is met, no
file-only key.

**∩ U5 — "same ceilings as `unarchive`" × "a legal package".** `unarchive`'s `maxEntries` is
100 000; a package at Transarch's limit of 100 000 objects has 100 003 members. The package wins:
the limit counts objects.

Layout: `<outputDir>/objects/<original_object_name>` (flat), and `<outputDir>/package/` with
`<base>.audit.json`, `<base>.metadata.csv`, `<base>.control` exactly as extracted.

Output variables: `tfId`, `transmissionDate`, `sequenceNr`, `versionNr` (plain integers, what
`objpack` takes), `nextVersionNr`, `submissionBaseName`, `targetDestination`, `objectCount`,
`metadataRows`, `metadataCsv`, `metadataDelimiter`, `objectsDir`, `compression` (`none` | `gzip`),
`md5`, `md5Checked` (`true` | `false`), `inconsistencies`, `warnings`, `hostRules`.

Exit codes: 0; 2 refusal (configuration or package); 1 unexpected I/O; −997 Stop. `res.exitCode`
is set on every path and the suite asserts it is never −1.

## 7. Package checks — two classes, and which one `onInconsistency` governs

**Always refused**, because without them the restore would be a guess:

* an archive whose own name is not a submission archive name (added in batch 1): the identity
  published as variables is read from it, so a renamed archive has none;
* a member stored twice (added in batch 1; `NameIndex`), a hard link, a truncated tar;
* no `*.audit.json` member, more than one, or not valid JSON; no `submission_object_files`;
* the metadata member named by the audit (or, failing that, the single `*.metadata.csv`) missing;
* no `object_id` or no `original_object_name` column (legacy `Order` mode does not require the
  latter — §10);
* an audit `file_name` absent from the tar, or listed twice;
* an `object_id` twice in the audit or twice in the metadata; an audit `object_id` with no
  metadata row;
* an empty `original_object_name` for an object; a name the host cannot hold (§8); two objects
  that would get the same file (§8);
* a member that is not a regular file, or whose name has a separator (∩ U1).

**Governed by `onInconsistency`** — `fail` ~~refuses at the first~~ **refuses after logging every
one** (corrected in batch 1: the author correcting a package needs the list, not the first item),
`warn` logs each, counts them in `${inconsistencies}` and restores:

* `record_count` ≠ number of `submission_object_files` ≠ metadata rows;
* a metadata row with no object; a tar member the audit does not list (under `warn` it is left in
  `package/`, never in `objects/`);
* control file missing, or not 0 bytes;
* the base name of a member, `metadata_file_name`, or the audit's date / sequence / version
  disagreeing with the archive's own name — **the archive's name wins for the output variables**;
* `mime_type` differing between audit and metadata for the same object.

**Not an inconsistency:** an empty `TargetDestination`. It is what the legacy script writes when
the parameter is not given (measured, `legacy_ml`). `${targetDestination}` is then empty, and
`objpack` downstream requires its own.

**∩ U6 — "refuse an incoherent package" × "the package being corrected may be the incoherent
one".** The reason to unpack is often that Transarch rejected the submission. So the checks that
make the restore unambiguous are never relaxed, and the conformance checks can be — by an explicit
`warn`, never by default.

## 8. Original names on the host

`original_object_name` must be one file name: it goes through `EntryName.validate` with the
detected `HostRules`, tar semantics, and must yield exactly one segment.

* A `/`, a `..`, a leading separator, NUL: refused on both hosts. A `\`: a separator on Windows,
  hence refused; a character on Linux, hence kept.
* `:`, `< > " | ? *`, reserved device names, a trailing dot or blank: refused on Windows, kept on
  Linux.
* Two names differing only in case: one file on Windows → refused naming both rows; two files on
  Linux → restored. `objpack` resolves the exact name first (M12), so on Linux the re-pack finds
  both.
* The run says which rules it used: first log line, `${hostRules}`.
* **Added in batch 1, measured:** off Windows the JVM encodes file names with the service's locale
  (`sun.jnu.encoding`). Under an ASCII locale (`LANG` empty) `Relazione perché 2026.txt` cannot be
  created, on Java 8 and on 21. Such a name is refused naming the encoding and the remedy, before
  anything is renamed. **∩ U9 — "the host's rules" × "the JVM's locale".** The locale is part of
  the host: the package is refused, never restored under a substituted name. Windows file names are
  UTF-16 and the check does not apply there.
* `object_id` is compared as a number when it is one: `000004` in the metadata is the audit's `4`
  (added in batch 1; anything not all digits is compared as written).

**∩ U7 — "refused, never normalised" × "restore as much as possible".** Refusal wins: one bad name
refuses the package, nothing is committed, and the message names the row and the rule. No
substitution character, no skipping of single objects — a restore that silently lacks one object
is re-packed as a submission that silently lacks one record.

## 9. The chain and the template

`workflows/_TEMPLATE-objunpack-resend.xml`: `objunpack` → `csvsql` → `dequote` → `validate` →
`objpack` → `ftpsend` (on hold, as the existing feed workflows). Wiring fixed here:

* `objpack.objectsDir = ${OBJUNPACK.objectsDir}`, `objectSource` left to its default (`name`).
  The packaged `object_id` column is set aside by `objpack` and the rows renumbered 1..N
  (objpack spec §17) — measured in M2: same ids when no row is dropped.
* ~~(implied by batch 0: the whole chain follows the package's delimiter)~~ **Corrected in batch
  4, read on the code:** the `delimiter` attribute of `csvsql`, `dequote` and `validate` is used
  raw — `step.delimiter.charAt(0)` — and never goes through `VarResolver`, so it cannot carry
  `${OBJUNPACK.metadataDelimiter}`. See ∩ U12 in §16.
* `objpack.outDelimiter = ${OBJUNPACK.metadataDelimiter}`. **∩ U8 — `objpack`'s default `;` × "the
  same metadata rows".** Legacy packages use `,`. Left to the default, the rebuilt package would
  change the delimiter the feed was onboarded with. The template passes the package's.
* `objpack.compression`: the template leaves `none`; `${OBJUNPACK.compression}` is there for an
  author who wants the same.
* Identity: G5.

Not run in the sandbox and said now: `csvsql`, `dequote`, `validate` and `ftpsend` live in
`InternalSteps`, which does not compile here. Batch 1 proves the round trip `objunpack` →
`ObjectPack` on the real classes; batch 4 lifts the middle steps' bodies where it can and declares
the rest. What `dequote` does to a quoted value containing the delimiter (`"ROSSI, MARIO"` in a
`,` file) is ~~**not known yet** and is the first measurement of batch 4~~ **measured in batch 4:
it keeps the value whole, wrapped in quotes** (§16).

## 10. Limits that cannot be removed — declared

1. **Rows discarded at packaging are not in the package** (`onStaleBusinessDate=skip`, missing
   objects). `objunpack` returns what was sent, not what the source held.
2. **The sub-folder structure of `objectSource=path` is gone.** Objects come back flat.
3. **The source CSV is not recoverable**, only the packaged one: `objpack` moved the four mandatory
   columns first, renamed mapped columns and may have reformatted the business date; both producers
   added `object_id`.
4. **Legacy `-MapMode Order`**: names come from the CSV, not from the files (M6). The restore is
   what the package declares. If the CSV had no `original_object_name` column there is nothing to
   restore to, and the package is refused.
5. **A metadata value containing a line break** is restored as is, and must be normalised by the
   chain (`csvsql`) before `objpack` (M7); those rows then differ from the package's by that
   normalisation.
6. **Windows PowerShell 5.1 was not run.** Its `ConvertTo-Json` indents differently and escapes
   `< > & '` as `\u00XX`. `AuditJson.read` is a JSON parser and is indifferent by construction; the
   suite will carry one audit file **written by hand** in the 5.1 shape — the single fixture not
   produced by a real tool, and labelled as such.
7. **The legacy script is a transcription from photographs**; a package from the real file on
   Windows (bsdtar under Windows, CRLF metadata) has not been seen. One real legacy package from
   production would close this.

8. **A quote inside a metadata value does not survive the chain** (added in batch 4, ∩ U13):
   `dequote` removes it. `objunpack` itself returns the value untouched.

## 11. Gate 0 — decisions needed before batch 1

Each has a recommendation; "yes to all" is a valid answer.

**G1 — `.md5`.** Recommended: **`md5Check=require` by default, verified before anything is read
from the archive**, with `Md5.ofFile`. A missing `<base>.md5` refuses the package naming the file
expected. `ifPresent` verifies when the file is there and warns when it is not; `off` skips.
The sidecar must be exactly 32 hex characters after trimming; upper case is accepted with a
warning (T§3.5 requires lower). Cost, stated: one extra sequential read of the archive (up to
20 GB). The alternative — hashing while extracting — saves that read and discovers a corrupt
package only after staging it.

**Second round, 2026-10-04 (after batch 1):** G1 and G3–G8 confirmed as recommended; the file-name encoding rule (∩ U9) accepted; and on `onExisting=replace` — *"I prefer that nothing of the source is ever modified"* — see §14.

**Answers, 2026-10-04.** G2: *"file names should be unique but I cannot guarantee it"*. G1, G3–G8:
not answered one by one; batch 1 was built on the recommendations, as "yes to all" was offered —
**to be confirmed**, each can still be reversed. On G2 the refusal stands, and because uniqueness
is not guaranteed the refusal reports **every** clash — how many names, how many objects, and for
the first ten the name and all its `object_id` values — so that the first real occurrence gives
the measure needed to decide whether the separate design is worth building.

**G2 — two rows with the same `original_object_name`.** Recommended: **refuse**, naming both rows.
Why not the others: a suffix changes the name, so the metadata no longer names the file and only
positional pairing could re-pack it; a sub-folder per row needs `objpack` in `path` mode with an
extra column, which `csvsql`'s `{{columns}}` drops and which would otherwise enter the submission's
schema. Both break the chain the output is for. This makes a `path`-mode package with the same
leaf name in two folders not unpackable — **do real feeds have that?** If yes, it needs its own
design (a role column `objpack` reads and does not pass through).

**G3 — names invalid on the host, and case.** As §8: the host's rules, refusal, `${hostRules}`.
Recommended as written.

**G4 — package coherence.** As §7: the two classes, `onInconsistency=fail` by default.

**G5 — intent of the rebuild.** `objunpack` only exposes the identity. Recommended template
default: **corrective resend** — same `tfId`, same `transmissionDate` (T§2: "must not be changed"),
same `sequenceNr`, `versionNr = ${OBJUNPACK.nextVersionNr}`. A new submission is the author
overriding those four `objpack` parameters. `nextVersionNr` is empty, with a warning, at 999.

**G6 — metadata delimiter.** Recommended: **the package's, untouched**; the file is copied byte for
byte and the delimiter published as `${metadataDelimiter}` (∩ U8).

**G7 — where the legacy script lives.** The repository is public and the script is UBS's, with
storage account names and share paths in its comments. Recommended: **not committed**; the suite
takes its path as an argument, and this spec records its hash (§3). Test suites are not in the
repository today either.

**G8 — one package per step.** Recommended as §6. Several packages are a LOOP over the step.

## 12. Batches

| # | Content | Verified by |
|---|---|---|
| 0 | this spec | — |
| 1 | **delivered, §13** — `SubmissionName.parse`, `AuditJson.read`, `objunpack/ObjectUnpack`, unwired | the ten fixtures regenerated by the real producers; round trip on both; Temurin 8 and the newer JDK; root and non-root; `LANG` empty and `C.UTF-8`; mutations on copies |
| 2 | **delivered, §14** — registration read on the code of `unarchive` (parser ×3, `internalKind`, dispatch passing `control`), `runObjUnpack`, exit codes | differential compile with its positive control; exit-code lint |
| 3 | **delivered, §15** — designer: seed on choosing the executor, `<option>`, panel, `clientValidate`; `variables.html` `PARAM_OPTIONS` | parameters written vs read, mechanically; `\n` and `[[` scans with positive controls; `node --check` |
| 4 | **delivered, §16** — the template, `USAGE.md`, the chain's middle steps measured | as §9 |

Every delivery declares Linux, Windows and neither on three separate lines.

## 13. Batch 1 as built

`objpack/SubmissionName.parse` (+ `Parsed`), `objpack/AuditJson.read` (+ `Document`, `FileItem`, a
strict JSON reader), `objunpack/ObjectUnpack`, `objunpack/ObjUnpackException`; `EntryName.printable`
made public. JDK only, compiled with the Java 8 `javac`. **Not registered**: no executor name, no
dispatch, no panel — that is batches 2 and 3.

What the code decided beyond §4–§8, each also written where it belongs above:

* Members are staged under numbers (§4.3). Refusals of `unarchive` and `objpack` keep their own
  types; `ObjUnpackException` carries a `Reason` for the ones that are this executor's.
* `onExisting=fail` is decided **before** the checksum is computed, and checked again at the commit
  (a folder that appeared while the step ran is refused and left alone).
* ~~`onExisting=replace` deletes the old `objects/` and `package/` only after the new content is
  complete in staging; a package that is refused leaves the old result untouched. **It is not
  atomic**: if the old content cannot be fully deleted the step stops with part of it gone, and
  the two folders are moved one after the other.~~ **Removed in batch 2 (§14).** What remains
  non-atomic: the two folders are moved one after the other.
* The two limits chosen rather than derived: the audit file is read whole up to 256 MB, the
  metadata up to 512 MB.
* A leftover `.objunpack-*.part` of a killed run is swept at the next run, without following links.

**Measured in batch 1** (same tools as §3):

| # | Fact |
|---|---|
| B1 | With `LANG` empty a non-ASCII name cannot be created (Java 8 and 21); before the rule of §8 the step failed at the rename with nothing to say why |
| B2 | GNU tar stores a file named twice on the command line as a **hard link** to the first, not as a duplicate; a true duplicate needs `--hard-dereference`. Both are fixtures |
| B3 | `TarStreamReader` itself refuses a truncated member; a size check written in `ObjectUnpack` was dead code (a mutation survived) and was removed |
| B4 | The legacy script keeps a trailing blank of `original_object_name` and drops a leading one (`Import-Csv`) |

**Fixtures.** 44 packages. 21 straight from the producers: `ObjectPack` (plain, label, gzip, label +
gzip, `,`, 137 objects) and the legacy script under `pwsh` with GNU tar and bsdtar (Filename, marker,
Order, multi-line value, 137 objects, and nine `Order` runs whose CSV carries the names under
test: duplicate, case-only, `sub/x`, `..`, `./a`, empty, Windows-invalid, trailing blank, no name
column). 23 `t_*` rebuilt from a real package's own members with GNU tar, `md5sum`, `bzip2` and
Python `zipfile`. One audit file is hand-written, in the Windows PowerShell 5.1 shape (§10.6).

**Suite.** 638 assertions under a UTF-8 locale; 179 under an ASCII one, where the packages with
ASCII names are restored and round-tripped and the others are asserted to be refused for the
encoding; 7 more that only a non-root user can observe (read-only output folder, `replace` over a
folder that cannot be emptied, unreadable archive). Green in all eight combinations of Temurin
1.8.0_432 / JDK 21.0.10, `LANG=C.UTF-8` / empty, root / uid 65534.

The acceptance criterion — package → `objunpack` → `ObjectPack` → `objunpack` — holds on ten
packages of both producers: objects byte-identical, header and every metadata row equal, ids
included, audit equal in count, target, date and id → mime, version the new one; for `objpack`'s
own packages the object names are equal apart from the base.

**Mutations: 56, on copies, every anchor checked to replace exactly once.** 55 caught. One is
equivalent: removing `^` from the archive-name pattern changes nothing because `Matcher.matches`
anchors anyway. On the way, three survived a first run and were opened, not filed: the dead size
check (B3, removed); an empty original name had no fixture — the refusal came from `EntryName`
with a different message — and now has one from the real script; the commit-time `EXISTS` check
had no test, and now has one that makes the folder appear during the run. One mutation
(`replace` ignoring a failed delete) is caught only as a non-root user.

**Not verified:** anything on Windows; Windows PowerShell 5.1; a legacy package made by the real
script file on Windows; `mvn clean package`; the step inside the application (it is not wired).

## 14. Batch 2 as built

**Decision, 2026-10-04: nothing is ever modified.** Asked whether `onExisting=replace` should exist
given that it is not atomic, the author answered *"I prefer that nothing of the source is ever
modified"*. Read in both of its possible senses, and both are now guaranteed and tested:

* **the source package**: the archive and its `.md5` are opened for reading only — never renamed,
  moved or deleted, and a folder the service may only read is enough. There is no `afterExtract`
  and there will not be one. Asserted on the folder's listing, bytes and times after a success, a
  refusal and a Stop;
* **anything already in the output**: `onExisting` is removed. An existing `objects/` or
  `package/`, even empty, refuses the step before the archive is read, and a foreign file in
  `outputDir` is left alone. The only things the step deletes are its own `.objunpack-*.part`
  staging folders.

**∩ U10 — "nothing existing is replaced" × re-running a step.** With `outputDir` left to its
default this never bites: the step directory is new at every run. With a fixed `outputDir` a
second run is refused, and the remedy is the author's — another folder, or removing the old
result by hand. Not the step's.

**Registration, read on the code of `unarchive`, the last executor registered:** the three places
in `WorkflowXmlParser` (the accepted values, the error text, the `internal` set),
`WorkflowEngine.internalKind()`, and the dispatch in `InternalSteps.run()`, which passes
`control`. The designer's four places (seed on choosing the executor, `<option>`, panel,
`clientValidate`) and `variables.html` are batch 3. **Until then** the step is written in the XML,
and the designer shows the first entry of its list for it — `USAGE.md` says so and says not to
touch that field.

`InternalSteps.runObjUnpack` translates eleven parameters in and eighteen variables out.

* Exit codes: 0; 2 for every refusal (this executor's and `unarchive`'s); −997 Stop; 1 for an I/O
  failure or anything unexpected. A code is set on every path.
* A malformed or out-of-range number is refused, never defaulted. `metadataDelimiter` is read
  untrimmed, so a tab survives.
* The identity read from the archive's name is published even when the package is then refused;
  `objectsDir` and `metadataCsv` are empty unless the step succeeded.
* A catch for `ObjPackException` was written and removed: `ObjectUnpack` wraps the two it can meet,
  so it could not be reached, and an unreachable branch cannot be tested.

**Verification.** `InternalSteps` does not compile in the sandbox, so:

* the real body of `runObjUnpack` and of the four helpers it calls is **lifted by position** into
  a class that does compile, with the real `VarResolver` and `RunControl` and a stub holding only
  the real `StepExecutor.Result`, and **run** on the fixtures: 85 assertions (87 as non-root, 71
  and 73 under an ASCII locale), the exit code checked not to be −1 after every one of 31 runs;
  green in the eight combinations. 22 mutations of that body on copies, all caught, one of them
  only as a non-root user (an I/O failure reported as a refusal);
* **differential compile** of `InternalSteps`, `WorkflowEngine` and `WorkflowXmlParser`: the 459
  error lines `javac` reports before the change are the same 459 after it; an error put on
  purpose in a changed line does appear (positive control);
* the core suite again, 639 assertions (644 as non-root; 179 / 184 ASCII), eight combinations; the
  three mutations around `EXISTS` re-run, one of which survived at first because the commit-time
  check gave the same refusal — the test now asserts it comes before the archive is read. The whole
  list of 56 core mutations re-run on this tree: 55 caught, the same one equivalent;
* `USAGE.md`: the 11 parameters and 18 variables it names compared mechanically with what the
  method reads and publishes, with a positive control; the new section rendered by the real
  `render()` of `docs.html`, no raw Markdown left outside code.

**Not verified:** the wiring itself — that the parser accepts `exec="objunpack"`, that the engine
treats it as internal and that the dispatch reaches the method — is read and differentially
compiled, **not run**; `mvn clean package`; a workflow with the step on a real instance; anything
on Windows.

## 15. Batch 3 as built

`designer.html`, the four places read on `unarchive`: the seed in `updNodeR` (`OU_DEFAULTS`,
`ouSeed`), the `<option>`, the panel branch, `clientValidate`. `variables.html` `PARAM_OPTIONS`
gains `md5Check` and `onInconsistency`; `preserveMtime` is already there for `unarchive` with the
same meaning and the same default, so it is shared, not repeated. `buildXml` needed no change:
every field is a `<param>` (read on the emission, and asserted on the XML it produces).

The panel: **Package** (archive, checksum), **Output** (directory, modification times),
**Checks and limits** (non-conforming package, delimiter, the five limits). Choosing the executor
writes the nine defaults into the step; `archive` has none and `outputDir` defaults to the step
directory, so neither is seeded. The panel says where each setting applies ("from the next run"),
that the source is only read, and — when a fixed output directory is typed — that a second run
will stop. The batch-2 caveat is gone: a step loaded from XML shows as `objunpack`.

**∩ U11 — "the panel writes every default" × "a step written by hand in batch 2".** A step that
has only `archive` is NOT rewritten when the designer opens it: rendering writes nothing
(asserted), blank controls show the executor's default — the select's default entry, the number
as placeholder — and the defaults are written only when the executor is *chosen*.

**Verification** — the real `designer.html`, loaded in jsdom with its inline script running, and
driven **through its own controls** (the executor `<select>` and each field receive `change` /
`input` events; nothing is re-rendered by hand around them):

* 128 assertions: the seed; each of the eleven fields is present, shows the seeded value, writes
  its parameter **and only that one**, and still shows it after a re-render; the XML carries all
  eleven; the three conditional notes follow their controls in both directions; `clientValidate`
  on every field, `${var}` accepted, case as the executor reads it;
* **parameters written by the panel = parameters read by `runObjUnpack`**, compared mechanically
  from the Java source: eleven on each side, with a positive control; the nine seeded defaults
  compared with the field initialisers of `ObjectUnpack`;
* 28 mutations of the panel on copies, anchors checked: 26 caught, 2 equivalent — marking the
  *first* option of a two-option select as not selected changes nothing, because a select with
  no option selected shows its first;
* one trap in the harness itself, caught by its own negative assertions: the first version read
  the notes from `document.body`, whose text includes the page's script — so every "the note is
  shown" was true whatever the panel did. It now reads the node container and throws if absent;
* `node --check` on the inline scripts of both templates, with a broken script as control; no
  literal `\n` / `\r` / `\t` and no `[[` / `[(` in the 71 added template lines, with a line
  containing both as control; no CSS variable introduced.

**Not verified:** a real browser, and the UBS one in particular (layout, the look of the notes);
Thymeleaf rendering of the template; the Variables page with the two new option lists; anything
on Windows; `mvn clean package`.

## 16. Batch 4 as built — the template and the chain

`workflows/_TEMPLATE-objunpack-resend.xml`: OBJUNPACK → CSVSQL → REMOVE_CSV_DOUBLE_QUOTES →
CHECK_CSV_VS_DATASCHEMA → OBJPACK → FTPSSEND (on hold). Step ids and shapes follow the feed
workflows in production. A corrective resend as it stands (G5). No Java in this batch.

Wiring: `csvsql` reads `${OBJUNPACK.metadataCsv}` with `<input delimiter="${OBJUNPACK.metadataDelimiter}">`
(that attribute IS resolved, line read); `validate` expects `${OBJUNPACK.objectCount}` rows;
`objpack` takes identity, target, objects folder and `outDelimiter` from OBJUNPACK and the metadata
from dequote. The `deleteOnSuccess` of the production chain is left out: the template deletes
nothing. `map.nameLabel` is left out: it is the feed's choice.

**∩ U12 — "the rebuilt package keeps the package's delimiter" (U8) × "the middle steps' delimiter
is a literal".** Read on the code: `runCsvSql`, `runDequote` and `runValidate` take
`step.delimiter.charAt(0)` unresolved; `${x}` there would make `$` the delimiter. So the three
middle steps carry a literal (a comma, as the production feeds), the author sets it to the
package's, and the description of the template and `USAGE.md` say so first. The end of the chain
is not affected: `objpack` reads its input by detection and writes with the package's delimiter.
Example: a `;` package through a `,` middle is rebuilt with `;` and the same values; only a value
containing `,` travels wrapped in quotes between the steps. **Proposed, not done:** resolve that
attribute through `VarResolver` in the three executors. It touches three executors in production
and is the author's decision.

**∩ U13 — "the same metadata rows" (the acceptance criterion) × `dequote`.** `dequote` removes a
quote character inside a value: that is what it is for. The criterion holds exactly for
`objunpack → objpack`; through the chain it holds except for values that contain a quote. The
chain wins — it is the author's chain — and the difference is declared, here and in `USAGE.md`.

**∩ U14 — objunpack's variable names × objpack's.** Read on `WorkflowEngine` (the loop that copies
`outVars`): every variable is published twice, as `name` and as `<stepId>.name`. `objpack`
publishes `objectCount`, `md5`, `metadataRows`, `submissionBaseName` and `warnings` too, so once it
has run the short names are its own. The qualified name wins, by construction: the template uses
`${OBJUNPACK.…}` everywhere, checked mechanically, and `USAGE.md` says to. The same loop is why
`${CSVSQL.outputFile}` exists although `runCsvSql` publishes `csvFile`: the engine adds it.

**Measured** (Temurin 1.8.0_432; H2 2.1.214 from the project's GitHub release, the version the
application uses; fixtures of §13):

| # | Step | Fact |
|---|---|---|
| C1 | parser | the real `WorkflowXmlParser` accepts the template: six steps, `exec`, `source`, `delimiter`, `csvFile`, `onHold`, the `<input>`, the checks. Control: the same file with `exec="objunpak"` is refused. This also runs what batch 2 could only read: the parser accepts `exec="objunpack"` |
| C2 | csvsql's reading | H2, with the `CREATE TABLE … AS SELECT * FROM CSVREAD(…, NULL, 'fieldSeparator=<sep> charset=UTF-8')` statement copied from `runCsvSql`, on the unpacked metadata of 12 packages of both producers: same row count as `PsCsvReader` on all, the multi-line record included; every cell equal on 10; on 2 the only difference is that H2 drops the **trailing blank** of an unquoted value (`a.pdf ` → `a.pdf`) — the same trimming `objunpack` applies to the name (U4), so the name still matches the file. H2 returns column names in upper case and empty values as NULL |
| C3 | dequote | the real body of `runDequote` and its six helpers, lifted by position, on the unpacked metadata of 10 packages: row count and header unchanged; `"ROSSI, MARIO"` in a `,` file kept whole and wrapped; `said "ok"` → `said ok` (1 cell of 36, `quotesRemoved=2`); nothing else changes |
| C4 | dequote → objpack | the real `ObjectPack` on dequote's output, then `objunpack` again: 10 of 10 rebuilt as V002 with the package's delimiter, objects byte-identical |
| C5 | a value with a line break | default: dequote writes the record on two lines (3 rows become 4) and `objpack` refuses it; with `embeddedNewlines=space`: one record, `objpack` accepts |
| C6 | validate `noQuotes` | read, not run: it tests for a quote left **after** CSV parsing, i.e. inside a value; a value merely wrapped in quotes passes |

**Limit 8, added to §10.** A quote inside a metadata value does not survive the chain (U13).

**Not verified:** `csvsql` itself — its `{{columns}}` expansion and its writer need Jackson and the
application; C2 is its READING statement only, so what reaches `dequote` in production is
csvsql's output, while C3 fed it the unpacked file. `validate` and `ftpsend`: not run. The
variables `${OBJUNPACK.x}` resolving inside a running workflow: the convention of the production
feeds, not run here. The template in the designer and in a real run. Anything on Windows.

**To close the feature on a real instance:** copy the template, put a real package in place, run
to the hold; compare the tar produced by OBJ PACK with the original.

