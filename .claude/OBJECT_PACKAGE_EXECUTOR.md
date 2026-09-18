# Object submission packager (`objpack`) — specification

Status: **Batch 0 — design, Gate 0 open. No code written.** Nine questions in §9 decide details that
cannot be inferred from the sources; five of them come from contradictions inside the Transarch page
itself and cannot be resolved by reading it again.

Builds a Transarch **TAR-packaged object submission** — audit JSON, metadata CSV, renamed object
files, control file, tar, md5 — replacing `Object_CS_Archiving_v4_UK_PS5.ps1`. Target is the
"applicazioni ex CS" path of the Transarch spec, transcribed in
`.claude/TRANSARCH_OBJECT_SUBMISSION_SPEC.md`; section references below are to that file.

Self-contained: everything needed to implement is here or in that transcription.

---

## 1. Why an executor rather than the script

The script works, and "it works" is a reason to leave a thing alone. Four properties are what it
cannot have, and they are the whole justification:

1. **It stages every object.** Lines 443-460 copy each object into `%TEMP%\obj_pkg_<guid>\staging`
   before invoking `tar.exe -cf … -C staging`. For a 20 GB CS submission at the documented ceiling
   that is 20 GB written and 20 GB read that produce nothing: the tar could have been written
   straight from the landing zone. The Java writer streams, and the temp requirement drops to zero.
2. **It cannot be composed.** Renaming, metadata, audit, control, tar and md5 are one monolith with
   19 parameters. Inside a workflow, the same steps become nodes that can be skipped, put on hold,
   retried from a point, and looped over feeds — which is what the orchestrator is for.
3. **It is invisible to the run history.** No step log, no output variables, no audit trail, no
   Operations row. A failed package is a console message on somebody's desktop.
4. **`tar.exe` is a Windows-version dependency.** Line 425 fails the run if
   `%SystemRoot%\System32\tar.exe` is missing, which is a property of the OS build, not of the
   feed.

What the script gets right and this spec keeps verbatim: fail-fast with `Set-StrictMode` and
`$ErrorActionPreference='Stop'`; UTF-8 **without BOM** for every text artifact (its
`Write-Utf8NoBom` exists precisely because PS5's `-Encoding utf8NoBOM` does not); the re-read and
re-validation of the generated audit JSON (lines 396-414) rather than trusting what was just
serialised; and the verification that every intended member is actually **inside** the finished tar
(lines 466-488) rather than assuming `tar` did its job.

## 2. Scope

**In:** producing the five submission artifacts from a metadata CSV plus a directory of objects, and
the tar + md5 around them.

**Out:** producing the source metadata CSV (that is `csvsql` / `json2csv` / `sql`), moving objects
into the landing zone (`ifscopy` / `filecopy` / `safecopy`), and delivery (`ftpsend`). This executor
is the last mile only, as requested.

**Also out, deliberately:** the non-TAR AzCopy/Axway path. It shares the four artifacts but has
different constraints (§1 of the transcription: 500 GB vs 20 GB, and no `TargetDestination`
requirement). Adding a mode for a path nobody is asking for would double the test surface for a
hypothesis. `packageMode` is specified in §5 so the shape is ready, but batch 1 implements `tar`
only and refuses the other value.

## 3. The three questions asked directly

### 3.1. Is `dataschema.json` needed? — Yes, and it is already the right file

Measured rather than assumed: `samples/dataschema.json` in this repo is

```json
[{"name":"NDG","nullable":false,"type":"string"}, …]
```

and the Transarch "correct txt schema for Metadata example" (§3.2) is

```json
{"name":"object_id","nullable": false,"type":"string" }, …
```

**The same shape.** The feed's existing shared dataschema is structurally the schema Transarch
validates the metadata CSV against — no conversion, no second artifact, and
`InternalSteps.readSchemaColumnNames` already reads it.

Two consequences, and the second is the one that matters:

- **The dataschema is NOT packaged.** It appears nowhere in the tar listing in §1 or §3.5 of the
  transcription. It is an input to checking, not a submission member. Shipping it inside the tar
  would add an unexpected member and fail validation.
- **It is the authoritative source for column order and nullability**, which §3.2 makes a hard
  requirement ("must appear in the stated order starting from the first column", "NOT nullable").
  So `objpack` uses it, when configured, to refuse a package before it is built: mandatory columns
  present, in the mandatory order, and non-null in every row.

It stays **optional**. A feed without one still packages; it simply loses the pre-flight. Making it
mandatory would block every feed whose schema has not been produced yet, and the executor's job is
packaging, not schema governance. When absent, `objpack` logs that the check did not run — a check
that silently did not happen is worse than one that is known not to have.

### 3.2. Field mappers for the index — yes, inside the feed

The source metadata CSV comes from another executor and will rarely carry Transarch's four mandatory
column names. Renaming them upstream would mean a `csvsql` step per feed whose only purpose is
aliasing. Instead the mapping is declared on the `objpack` step, as `elarxml` and `json2csv` already
declare theirs, via `<column>` children rather than flat params:

```xml
<step id="pack" exec="objpack">
  <param name="metadataCsv" value="${dir.extract}/source.metadata.csv"/>
  <param name="objectsDir"  value="${landingOut}/objects"/>
  <column role="objectId"          source="ID_DOC"/>
  <column role="recordBusinessDate" source="DT_RIFERIMENTO" format="yyyy-MM-dd" outputFormat="yyyyMMdd"/>
  <column role="mimeType"          source="TIPO_FILE"/>
  <column role="originalObjectName" source="NOME_ORIGINALE"/>
  <column role="objectPath"         source="PERCORSO_FILE"/>
  <column role="nameLabel"          source="TIPO_REPORT"/>
</step>
```

- **Roles, not positions.** Four mandatory roles plus two optional ones (`objectPath`, §3.3;
  `nameLabel`, the "extra information in between" permitted by §3.3 of the transcription, e.g.
  `…V001.monthly_report.OID2.pdf`).
- **An unmapped role falls back to the Transarch name**, so a CSV that already uses `object_id`,
  `record_business_date`, `mime_type`, `original_object_name` needs no `<column>` at all.
- **`objectId` is a special case.** §3.2 requires it to start at 1 and ascend within the submission,
  which is a property of the *package*, not of the source data. Mapping it means "use this column's
  value"; leaving it unmapped means "assign 1..N in processing order". The second is what the script
  does (line 256, `object_id = ($i + 1)`) and is the default. A mapped `objectId` that is not a
  strictly ascending integer sequence starting at 1 is **refused, not renumbered**: silently
  replacing an id the feed chose would break every downstream reference to it.
- **All remaining source columns pass through**, in source order, after the four mandatory ones —
  which is exactly §3.2's "the first columns should match the mandatory schema order, but the rest
  can be in any order".
- `format`/`outputFormat` on `recordBusinessDate` exist because §4 validates the business date
  format and §3.2 makes it the retention key. Everything else is copied verbatim.

### 3.3. Recursive reads and paths — yes, with the path coming from the CSV

Three shapes, chosen by `objectSource`:

| `objectSource` | Where the object is | Recursion |
|---|---|---|
| `path` (default when `objectPath` is mapped) | `objectsDir` + the value of the `objectPath` column, which may contain sub-directories | irrelevant — the path is exact |
| `name` | the object is looked up by the `originalObjectName` value | `recurse=yes` searches the whole tree |
| `order` | i-th CSV row ↔ i-th file in the listing | `recurse=yes` includes sub-directories in the listing |

- **`recurse` defaults to `no`.** A recursive default would silently widen an existing directory
  selection the first time someone deployed it.
- **`path` is the mode this feature exists for**: it lets the metadata CSV itself say where each
  object lives, sub-directory included, which is what "percorsi che possono essere indicativi del
  file da indicizzare" asks for. The value is resolved **against `objectsDir` and must stay inside
  it** — an absolute path or a `..` that escapes is refused, not clamped. Backslash and forward
  slash are both accepted as separators, since the CSV may have been produced on either side.
- **`name` with `recurse=yes` must refuse ambiguity.** If two sub-directories hold a file of the
  same name, the executor fails naming both paths rather than picking the first: picking one would
  archive a plausible wrong document under a right-looking name, which is the one failure mode this
  whole package format exists to prevent.
- **`order` is the fragile one** and it is the script's default (`MapMode=Order`, aligning by index
  after `Sort-Object Name -Unique`, line 184). It is kept for parity but it is **not** the default
  here, and with `recurse=yes` it requires an explicit `orderBy` (`name` | `path`) because a tree
  walk has no single obvious order the way a flat listing does.

In every mode the object count and the CSV row count must match exactly — §4's first content rule,
and the only assertion that catches a half-copied landing zone.

## 4. What is built, in order

1. **Resolve** base name `<tfId>.<transmissionDate>.S<seq>.V<ver>` and pair every CSV row with its object (§3.3).
2. **Pre-flight**: dataschema check if configured (§3.1); mandatory values non-empty; business-date format; mime type against the object's extension (§9.3); per-object and total size ceilings (§1 of the transcription); object count ≤ 100 000.
3. **Rename/copy** each object into the output directory as `<base>[.<nameLabel>].OID<n>.<ext>`.
4. **Write** `<base>.metadata.csv` — UTF-8 no BOM, RFC 4180 quoting, configured delimiter, mandatory columns first.
5. **Write** `<base>.audit.json` — the §3.1.1 shape, then **re-read and re-validate** it (record count, object-file count) as the script does.
6. **Write** `<base>.control` — 0 bytes.
7. **Write** `<base>.tar` — members in the order audit, metadata, objects, control; flat, bare names.
8. **Write** `<base>.md5` — lowercase hash of the tar, bare, no file name.
9. **Verify** the finished tar by reading it back: every intended member present, with the byte size it has on disk.

Step 9 is not ceremony. The script has it (lines 466-488) and it is the only check that distinguishes
"the archive was written" from "the archive contains what we meant".

### 4.1. The tar writer

**Pure JDK, hand-written ustar. No new Maven dependency.** The reasoning, since this is the decision
with the longest reach:

- `java.util.zip` has no tar. Java 8 has none anywhere in the platform.
- `commons-compress` would do it, but it is a new dependency and CLAUDE.md forbids adding one without
  confirming it on the internal Nexus — which cannot be confirmed from this sandbox. A design that
  depends on an unverifiable artifact is a design that may have to be rewritten after the fact.
- ustar is a 512-byte header of fixed fields plus 512-byte-aligned payload. It is small enough to
  write correctly and, more to the point, small enough to **prove**: GNU tar is present in the
  sandbox, so every archive the writer produces can be read back by a real, independent tar and
  compared byte-for-byte against one that real tar produced from the same inputs.

Measured here rather than assumed, because the script's normalisation implies otherwise: `tar -cf t.tar -C stg -- f1.pdf f2.csv`
stores the member names **bare** — `f1.pdf`, `f2.csv` — with magic `ustar ` and no `./` prefix. The
script's `-replace '^[.][/\\]', ''` (line 474) defends against a tar that does prefix them; it does
not describe the expected output. `objpack` writes bare flat names and asserts it.

Fields that need deciding rather than defaulting, because a receiver may or may not care: mode
(`0000644`), uid/gid (`0`), uname/gname (empty), mtime (the source file's), typeflag (`0`), and the
two 512-byte zero blocks plus padding to the blocking factor at the end. Names over 100 bytes need
the ustar prefix field — the base name alone is ~30 characters, so a long `nameLabel` can reach it,
and the writer must refuse rather than truncate.

## 5. Parameters

| Param | Default | Meaning |
|---|---|---|
| `tfId` | — | Feed id, `^tf[0-9]{7}$` (§2) |
| `transmissionDate` | — | `yyyyMMdd` (§2); typically `${currentDate}` or a feed variable |
| `sequenceNr` | `1` | 1..999 → `S%03d` |
| `versionNr` | `1` | 1..999 → `V%03d` |
| `targetDestination` | — | endpoint url; mandatory for `packageMode=tar` (§3.1) |
| `metadataCsv` | — | source metadata CSV |
| `inDelimiter` | auto | as `csvsql` autodetects |
| `outDelimiter` | `;` | must come from the Accepted Delimiters List (§9.6) |
| `objectsDir` | — | root of the objects |
| `objectSource` | `path` if mapped, else `order` | §3.3 |
| `recurse` | `no` | §3.3 |
| `orderBy` | `name` | only for `objectSource=order` |
| `include` / `exclude` | `*` / the five output patterns | as the script's `$Include`/`$Exclude` |
| `dataschema` | — | optional; pre-flight only, never packaged (§3.1) |
| `oidPadding` | `auto` | `auto` (from object count, §3.3) or a fixed width — §9.1 |
| `packageMode` | `tar` | `tar` only in batch 1 |
| `compression` | `none` | §9.4 |
| `outputDir` | `${stepDir}` | |
| `failOnOversize` | `yes` | |

Output variables: `objectCount`, `submissionBaseName`, `tarFile`, `md5File`, `tarBytes`, `md5`,
`metadataRows`, `skippedRows`.

Conservative defaults are satisfied trivially: this is a new executor, so no existing feed changes
behaviour on deploy.

## 6. Registration

Per CLAUDE.md §"Regola delle 4 location": `InternalSteps.run()` dispatch,
`WorkflowEngine.internalKind()`, the designer `<option>`, and `clientValidate()`. The parser
whitelist and internal set are named in the diff-executor batch notes as part of that count and will
be **checked against the code, not the table**, since the two disagree.

Designer panel is **batch 3**. Batches 1 and 2 configure with `+ param` and hand-written `<column>`
entries, as `elarxml` and `json2csv` did.

## 7. Testability

The `elar`/`json2csv` discipline applies: the core is Spring-free and compiles standalone with
`javac --release 8`, so it runs against real files here. Jackson is not needed — the audit JSON has
seven keys and a flat array, and writing it by hand avoids the dependency question entirely while
making the number-vs-string asymmetry of §3.1.1 explicit rather than incidental. Reading it back for
step 5's re-validation is the one place a real parser would help; a minimal check of the two counts
does not need one.

What the sandbox cannot close, and what must therefore be declared in `COMMIT_MSG.txt`: `mvn clean
package`, a run on Windows, and an actual Transarch ingestion of a generated package.

## 8. Batches

- **0** — this spec (current).
- **1** — ustar writer + md5, standalone, proved against GNU tar. Nothing wired.
- **2** — the executor: pairing, mapping, pre-flight, the five artifacts, registration.
- **3** — designer panel, including the `<column>` role repeater.
- **4** — `USAGE.md`.

## 9. Gate 0 — to be answered before batch 1

**9.1 OID padding.** `auto` per §3.3 (5 objects → `OID1`), or fixed 6 as the script does
(`SeqPad=6` → `OID000001`)? The live feeds were built by the script, so `auto` would change the file
names of an established feed. Which is authoritative — and if `auto`, does it apply only to new
feeds?

**9.2 Version padding.** `V001` per §2, or `V1` per §3.5's examples? The script emits `V001`. If the
delivered packages so far were named `V001` and were accepted, that settles it — but it is worth
saying so explicitly, because §3.5 is the section that describes the tar.

**9.3 Mime type.** Does the `mime_type` column carry the dotted extension (`.pdf`) as every example
shows and §4's "mime type against file name" check implies, or a real media type
(`application/pdf`) as §3.2's reference to the records-management requirements implies? This decides
whether the pre-flight compares the column to the extension or maps it through a media-type table.
A question for Soffici.

**9.4 Compression.** Confirm `none`. The script produces an uncompressed `.tar` and that is what has
been delivered; §3.5's "Do only use" list reads as permission, not obligation, and a `.tar.gz` named
`.tar` would be a naming violation.

**9.5 Does `objpack` own the whole package?** The task says the metadata CSV and the landing-zone
copy belong to other executors, but the script also *rewrites* the metadata CSV (adding `object_id`
and reordering) and creates the audit and control files. Confirm that `objpack` produces
metadata + audit + control + tar + md5 from a *source* metadata CSV — i.e. that the upstream
executor's CSV is the input, not the final `<base>.metadata.csv`.

**9.6 The Accepted Delimiters List.** Not captured in the screenshots. Until it is, `outDelimiter`
can be accepted but not validated. Can you get that page, or shall the parameter stay free-text with
a warning?

**9.7 Business date and retention.** §4 requires object record business dates within the last 10
months of the submission date (13 for yearly). Should `objpack` enforce this — it is the cheapest
possible place to catch it — and if so, is the feed yearly? Suggested: warn by default, refuse under
a `failOnStaleBusinessDate` flag, defaulting off per the conservative-defaults rule.

**9.8 Transmission date semantics.** §2 is emphatic that if the file cannot be transmitted on the
intended date the field must **not** be changed. That means `transmissionDate` cannot default to
`${currentDate}`, or a retry the next day would silently rename the submission. Confirm it is always
an explicit feed variable, set once per submission.

**9.9 Empty submissions and skipped rows.** The script fails when no objects are found (line 187).
Should a CSV row whose object is missing fail the whole package (the script's behaviour, via the
count check) or be skippable into a `.skipped` discards file as `elarxml` does? The count rule in §4
argues for failing; operational experience with 100K-object submissions may argue otherwise.
