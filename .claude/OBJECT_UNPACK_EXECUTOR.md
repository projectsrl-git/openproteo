# Object submission unpacker (`objunpack`) — specification

Status: **batch 0 — specification and Gate 0. No code.** Written 2026-10-04 on `89a66ad`.
Nothing in batches 1–4 starts before §11 is answered.

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
3. **Extract to staging**, `<outputDir>/.objunpack-<runId>.part/`, each member under its **member
   name**: `ArchiveFormat.decide` → `GzipSupport` → `TarStreamReader`, every name through
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
`PsCsvReader.parse`. `platform`: `HostFiles`. All already `public`; no visibility changes.

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
Example: `1; a.pdf` restores `a.pdf`.

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
| `onExisting` | `fail` | `fail` \| `replace`: what to do when `objects/` or `package/` already exist | next run |
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

**Governed by `onInconsistency`** — `fail` refuses at the first, `warn` logs each, counts them in
`${inconsistencies}` and restores:

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
`,` file) is **not known yet** and is the first measurement of batch 4.

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

## 11. Gate 0 — decisions needed before batch 1

Each has a recommendation; "yes to all" is a valid answer.

**G1 — `.md5`.** Recommended: **`md5Check=require` by default, verified before anything is read
from the archive**, with `Md5.ofFile`. A missing `<base>.md5` refuses the package naming the file
expected. `ifPresent` verifies when the file is there and warns when it is not; `off` skips.
The sidecar must be exactly 32 hex characters after trimming; upper case is accepted with a
warning (T§3.5 requires lower). Cost, stated: one extra sequential read of the archive (up to
20 GB). The alternative — hashing while extracting — saves that read and discovers a corrupt
package only after staging it.

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
| 1 | `SubmissionName.parse`, `AuditJson.read`, `objunpack/ObjectUnpack`, unwired | the ten fixtures regenerated by the real producers; round trip on both; Temurin 8 and the newer JDK; root and non-root; `LANG` empty and `C.UTF-8`; mutations on copies |
| 2 | registration read on the code of `unarchive` (parser ×3, `internalKind`, dispatch passing `control`), `runObjUnpack`, exit codes | differential compile with its positive control; exit-code lint |
| 3 | designer: seed on choosing the executor, `<option>`, panel, `clientValidate`; `variables.html` `PARAM_OPTIONS` | parameters written vs read, mechanically; `\n` and `[[` scans with positive controls; `node --check` |
| 4 | the template, `USAGE.md`, the chain's middle steps measured | as §9 |

Every delivery declares Linux, Windows and neither on three separate lines.
