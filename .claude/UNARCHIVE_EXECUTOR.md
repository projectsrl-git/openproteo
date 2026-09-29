# Archive extraction executor (`unarchive`) — specification

Status: **Batch 0 — spec only, no code.** Gate 0 in §13 is open; batch 1 waits on Q1, Q2 and Q3.
Base commit when written: `0997815`.

Extracts the archives found in a directory: format chosen by the author or detected, with detection
driven by magic bytes and the extension only as a hint. Security and robustness rules come first
(§5–§8) because they decide the shape of every class.

Self-contained: everything needed to implement is here. Section 2 lists what was **measured** in the
sandbox on the day this was written, with the tool and version, so a later reader can tell a
measurement from an argument.

---

## 1. Scope

**In:** a step that reads one directory (optionally recursive), selects files by a wildcard list,
recognises each as `zip`, `tar`, `tar.gz`/`tgz` or plain `gz`, and extracts it into an output
directory under the rules below, publishing counters and a manifest.

**Out, deliberately:**

* **Formats outside the JDK.** bzip2, xz, 7z, rar, zstd are *recognised* by their magic bytes and
  **refused naming the format** — never reported as "unknown". Supporting them needs
  commons-compress (or equivalent) on the internal Nexus, which cannot be confirmed from here. Q2.
* **Nested archives.** A `.zip` inside a `.tar` is extracted as a file, never opened. Recursion into
  archives is how a 42 KB file becomes petabytes; it can be a second step pointed at the output.
* **Creating archives.** That is `objpack` (tar) and `DocxWriter` (zip), both write-only.
* **Symlink, hardlink and device creation.** Never, under any setting (§6).

## 2. Facts measured on 2026-09-29 (GNU tar 1.35, Info-ZIP zip 3.0, Python 3.12, OpenJDK 21 with `--release 8`)

Measured, not assumed. Each one changes a decision below.

1. **GNU tar's default magic is `ustar␠␠\0`** (GNU), POSIX pax/ustar write `ustar\0` + `00`. A reader
   must accept both.
2. **`tar -cf x.tar -C dir .` stores every name with a leading `./`**, including a `./` entry for the
   root. (objpack measured *bare* names — with an explicit file list. Both shapes exist.) So a leading
   `./` must be accepted, or every archive of a directory is refused.
3. **A name over 100 bytes** is a separate `L` member (`././@LongLink`) in GNU format and an `x` pax
   record in pax format; **GNU tar in pax mode writes an `x` header in front of every entry**, not
   only the long ones.
4. **`git archive` begins with a pax *global* header** (`g`, `pax_global_header`, payload
   `comment=<commit>`). Refusing `g` would refuse every archive git produces.
5. **The existing `objpack.UstarReader` is not a basis for extraction**, run against the archives
   above: it reports `././@LongLink` and every `./PaxHeaders/…` as members, truncates the long name
   to 100 bytes, reports `pax_global_header` as a file, **ignores the ustar `prefix` field** — so
   `./ddd…/fff.txt` comes back as `fff.txt`, a file that would land in the wrong directory with no
   error — and has no notion of typeflag, so a symlink, a hardlink and a FIFO all read as regular
   members. On a gzip smaller than 512 bytes renamed `.tar` it returns **an empty list and no
   error**. None of this is a defect *for objpack*, which only ever reads what its own writer
   produced; it is disqualifying here. `UstarReader` is left untouched: objpack's verification must
   stay independent of this work.
6. **GNU tar sniffs a gzip renamed `.tar`** and extracts it (exit 0). Python `tarfile` mode `r:`
   refuses it (`truncated header`), mode `r:*` sniffs and reads it. The extension alone is not
   reliable.
7. **A tar with no end-of-archive blocks** (cut exactly at a member boundary) is extracted by GNU tar
   **with exit 0 and no warning**. A tar cut **inside** a member fails (`Unexpected EOF`, exit 2).
8. **Info-ZIP 3.0 on Linux writes UTF-8 name bytes with general-purpose flag bit 11 CLEAR and no
   `0x7075` Unicode-path extra field** (the extra holds only `UT` and `ux`). Decoded as APPNOTE says
   (CP437) the name `perché.txt` becomes `perch├⌐.txt`. Python `zipfile` sets bit 11 for non-ASCII
   names.
9. **A genuine CP437 name without the flag breaks Java's defaults**: `new ZipFile(f)` (UTF-8) throws
   `ZipException: invalid CEN header (bad entry name or comment)` for the whole archive, and
   `ZipInputStream` throws `IllegalArgumentException: malformed input` — not even a `ZipException`.
10. **Raw name bytes are recoverable**: `new ZipFile(f, ISO_8859_1)` returns unflagged names as a
    bijection of their bytes, while flagged names still come back decoded as UTF-8 (the JDK honours
    bit 11 whatever charset is passed). Measured on all three archives above.
11. **For the Italian accented vowels `à è é ì ò ù`, IBM437 and IBM850 decode identically**
    (`85 8A 82 8D 95 97`); they differ elsewhere (`9D` → `¥` vs `Ø`, `D5` → `╒` vs `ı`). Both
    charsets are present in the JDK used here.
12. **`ZipFile` does NOT verify the entry CRC-32**: a STORED entry with one payload byte flipped is
    read back silently, all 15 bytes. `ZipInputStream` on the same file throws
    `invalid entry CRC`. The CRC must be computed by the executor while streaming.
13. **A STORED entry with a data descriptor** (bit 3, what python writes to a non-seekable stream) is
    read by `ZipFile` and refused by `ZipInputStream` (`only DEFLATED entries can have EXT
    descriptor`). Together with 12: read through `ZipFile` (central directory), check CRC ourselves.
14. **Duplicate entry names**: `ZipFile`, `ZipInputStream` and python all return both `a.txt`
    entries. Extracted naively, the second silently replaces the first.
15. **A symlink stored by `zip -y`** has Unix mode `120777` in the external attributes and the link
    target as content. **`ZipEntry` in Java 8 has no getter for external attributes**; `ZipFile`
    reads it as a regular 5-byte file containing `a.txt`.
16. **`GZIPInputStream` reads a multi-member gzip** (two members concatenated) as one stream, like
    `gzip -dc`.
17. **The gzip `FNAME` header is attacker-controlled**: a member whose FNAME is `../../evil.txt` is
    written by `gzip -dN` as `evil.txt` (directory stripped). This executor ignores FNAME (§4.3).
18. **Compression ratio of legitimate data vs a bomb**, gzip -6: a CSV shaped like the
    `"…","","False","False","True"` extracts **15.5 : 1**; a CSV of an id plus 60 empty fields
    **25.5 : 1**; a ledger CSV **2.8 : 1**; 50 MB of zeros **1028 : 1** (deflate's ceiling).
19. **Registration of an internal executor is eight places in the code, not four** — read on
    `objpack`, the most recent one: `WorkflowXmlParser` lines 89 (whitelist), 91 (error message),
    94 (`internal` set); `WorkflowEngine.internalKind()` line 1027; `InternalSteps.run()` dispatch
    line 117; `designer.html` `<option>` line 1019, panel branch ~1777, `clientValidate` ~2180. The
    table in CLAUDE.md says four. §11.
20. **`safecopy` with zero matching files exits 0** and publishes `matchedCount=0`. That is the
    existing convention for "nothing to do".
21. **`id="extract"` is used by three workflow steps in this repository** (`${dir.extract}`). An
    executor called `extract` would read `<step id="extract" exec="extract">`. Q1.

## 3. Formats and detection

### 3.1. What is recognised

| Detected as | Magic | Handled |
|---|---|---|
| zip | `50 4B 03 04` (also `50 4B 05 06` = empty zip) | yes |
| gzip | `1F 8B` | yes; then the decompressed stream is sniffed for tar |
| tar (POSIX) | `ustar\0` at offset 257 | yes |
| tar (GNU) | `ustar␠␠\0` at offset 257 | yes |
| tar (v7, no magic) | none — only a valid header checksum | only when the author or the extension says tar (§3.2) |
| bzip2 | `42 5A 68` (`BZh`) | recognised, refused with the reason |
| xz | `FD 37 7A 58 5A 00` | recognised, refused |
| 7z | `37 7A BC AF 27 1C` | recognised, refused |
| rar | `52 61 72 21 1A 07` | recognised, refused |
| zstd | `28 B5 2F FD` | recognised, refused |
| zip, spanned | `50 4B 07 08` | recognised, refused (multi-volume) |

Detecting more than is supported is the point: "this is a bzip2 archive, bzip2 is not in Java 8"
is actionable, "unknown format" sends someone to open the file in a hex editor.

### 3.2. Who decides

`format` = `auto` (default) | `zip` | `tar` | `tar.gz` | `gz`.

* **`auto`: the magic bytes decide.** The extension is compared and a disagreement is **logged as a
  WARNING naming both** (`report.tar is a gzip archive`), never fatal — the renamed gzip of §2.6 is
  exactly the case auto exists for. No magic at all and an extension of `.tar` → tried as v7 tar,
  accepted only if the first header's checksum is valid (either the unsigned or the signed sum, as
  GNU tar accepts). Anything else unrecognised → refused (§3.3).
* **Explicit format: the author has asserted it, and a file that contradicts the assertion is
  refused**, naming what the magic says. Silently extracting a zip because it was "obviously" one
  would make the parameter decorative.
* **Short files**: a file shorter than the magic it would need is not a tar "with zero members" —
  it is refused as unrecognised. This closes §2.5's empty-list-without-error by construction.

### 3.3. A selected file that is not an archive

A file matched by the author's `pattern` and not recognised as an archive **fails the step** by
default: the pattern said it was one. `pattern` is the author's filter; widening it to `*` and
relying on detection to skip non-archives is a configuration the executor should not encourage.

## 4. Reading

The core is a new Spring-free package `com.legalarchive.orchestrator.unarchive`, JDK only,
`javac --release 8` (§12).

### 4.1. tar — a new streaming reader

Sequential, one pass, the payload streamed to its destination and never buffered.

* **Header**: checksum verified (unsigned sum, signed sum accepted as GNU tar does). Numeric fields
  octal with NUL or space terminators, **and GNU base-256** (high bit of the first byte set), which
  is what GNU tar writes for a size over 8 GiB — the octal field tops out at 8 GiB − 1. A negative
  size is refused.
* **Name**: `prefix + "/" + name` **only when the magic is POSIX** `ustar\0`. In the GNU header those
  bytes are not a prefix (GNU keeps other fields there), so using them would corrupt names — the
  mirror image of §2.5's lost prefix.
* **Extended names**: GNU `L` (long name) and `K` (long link name) apply to the next header; pax `x`
  records (`<len> <key>=<value>\n`) apply `path`, `linkpath`, `size`, `mtime` to the next header;
  pax `g` (global) is **parsed and ignored**, its keys logged once per archive (§2.4). An `L`/`K`/`x`
  not followed by a header is refused.
* **Typeflags**: `0`, `\0`, `7` → regular file; `5` → directory; `1` hardlink, `2` symlink → §6;
  `3`/`4` device, `6` FIFO → §6; `S` sparse, `M` multivolume, `D` dumpdir → refused naming the flag;
  `V` volume label → ignored and logged. Any other flag → refused naming it.
* **Name charset**: pax `path` is UTF-8 by the standard. Plain header names are decoded as **strict
  UTF-8** (malformed → refused, naming the header offset). Whether a legacy fallback is needed for
  tar names depends on who produces the archives — Q3.
* **End of archive**: two zero blocks end it. **A missing end marker is a WARNING, not a failure**,
  matching GNU tar (§2.7) — producers vary and refusing would be stricter than every tool the
  operator will compare against. **A stream that ends inside a header or a payload is a failure.**
* `InputStream.skip` is never trusted (objpack's `skipFully` lesson).

### 4.2. zip — `ZipFile`, the central directory, and our own CRC

* **`ZipFile`, not `ZipInputStream`** (§2.13): it reads the central directory, which is also what
  `unzip`, 7-Zip and Explorer trust, so a local header disagreeing with the central one cannot make
  this executor see a different archive from the tools an operator checks with. ZIP64 is supported
  by `ZipFile` since Java 7.
* **CRC-32 computed while streaming and compared to the central directory** (§2.12). A mismatch
  fails the archive.
* **Names** (§2.8–§2.11): opened with `ISO_8859_1` so unflagged names arrive as their raw bytes.
  Then, per entry:
  1. bit 11 set → the JDK has already decoded UTF-8; use it;
  2. `0x7075` Unicode-path extra present and its CRC matches the raw name → use its UTF-8 name;
  3. raw bytes are **valid strict UTF-8** → UTF-8 (this is the Info-ZIP case of §2.8; a CP437 name
     with accented letters is never valid UTF-8, because `80`–`9F` alone are continuation bytes);
  4. otherwise → `zipNameCharset`, default `IBM437` (Q4).
  The step log states, per archive, how many names each rule decided. A charset guess that is never
  reported is a guess nobody can check.
  `zipNameCharset` set to a charset name **forces** step 4 for every unflagged name (skipping 2–3),
  for the day a heuristic is wrong.
* **Refused, naming the entry**: encrypted entries (bit 0); a compression method other than STORED
  (0) or DEFLATED (8), named by number — DEFLATE64 (9) is the likely one from Windows tooling and is
  not in the JDK; spanned archives.
* **Link detection** (§2.15) needs the external attributes, which Java 8 does not expose. Two ways,
  Q5: read the central directory ourselves (EOCD, ZIP64 EOCD, one record per entry: raw name,
  flags, method, external attributes) and **cross-check it against `ZipFile`** — same count, same
  names in the same order, else refuse; or accept that a zip symlink is extracted as a small
  regular file holding the target's path. Recommended: the first. It also yields the raw names
  directly, so the ISO-8859-1 bijection becomes a cross-check rather than the mechanism.

### 4.3. gzip

* Multi-member streams are one stream (§2.16). The trailer CRC and ISIZE are checked by
  `GZIPInputStream`.
* The first 512 decompressed bytes are sniffed: a tar → §4.1 over the decompressed stream;
  anything else → a **single file**.
* **The single file's name comes from our side, never from FNAME** (§2.17): the archive's file name
  with `.gz`/`.gzip` removed; with no such suffix (auto-detected), the archive's own name — safe
  because it lands in the archive's own subdirectory (§8.1). FNAME, when present and different, is
  logged.

## 5. Entry names — refused, never normalised

Every name, from every format, goes through **one** validator, `EntryName`, before anything is
created. It returns a safe relative path or a refusal naming the entry and the rule. **The rule is
refusal**; the only transformations are the three listed as accepted, each because real tools
produce it and refusing it would refuse ordinary archives.

**Accepted and transformed:**

* a leading `./` (and a bare `./` root entry, which creates nothing) — §2.2;
* `\` treated as a separator equal to `/` **for validation** — so `..\x` is caught on every host —
  and written as the platform separator. Counted and logged, since APPNOTE says zip names use `/`;
* a trailing `/` on a directory entry.

**Refused (rule named in the message):**

* **Traversal**: any `..` segment, after splitting on both separators. Per segment, not substring:
  `report..v2.pdf` is a legal name. (`WorkflowPorter.extractToStaging` uses `contains("..")` and
  would refuse it; noted, not changed — out of scope.)
* **Absolute and rooted**: leading `/` or `\`; a drive letter (`C:`, `C:x`, `C:\x` — `C:x` is
  relative to *that drive's* current directory, never to ours, the copy-file-list lesson); UNC
  `\\server\share`; device paths `\\?\`, `\\.\`.
* **`:` anywhere**: besides drive letters it is the NTFS alternate data stream separator —
  `report.pdf:hidden` writes a stream **inside** `report.pdf` that no directory listing shows.
* **Windows-invalid characters**: `< > " | ? *` and code points `0x00`–`0x1F`.
* **Windows reserved device names**, per segment, case-insensitive, **with or without an extension**:
  `CON PRN AUX NUL COM1`–`COM9 LPT1`–`LPT9`, the superscript forms `COM¹ COM² COM³ LPT¹ LPT² LPT³`,
  `CONIN$ CONOUT$`. `nul.txt` is as reserved as `NUL`.
* **Segments ending in `.` or space**: Win32 strips them, so `a.txt.` and `a.txt` are one file.
* **Empty segments** (`a//b`) and `.` segments other than the leading one — no real tool produces
  them; accepting them would be normalising silently.
* **Path length**: the **absolute** target path (output directory + subdirectory + entry) longer than
  `maxPathLength` (default 259, Windows `MAX_PATH` minus the terminator). Java on Windows may well
  *create* a longer path (NIO prefixes `\\?\`), which is exactly why the limit exists: the file
  would be written and then be unreadable to Explorer, PowerShell 5.1 and the next `ifscopy`.
  Checked against the resolved output directory at run time, since `${stepDir}` differs per host.
* **Case-insensitive collisions within one archive**: two entries equal under
  `toLowerCase(Locale.ROOT)` are refused, naming both. On NTFS the second silently overwrites the
  first. **Enforced on every host, Linux test boxes included**: a workflow must not extract two files
  on a developer's machine and one on the server (the `FileMask` lesson).
* **Exact duplicates** (§2.14): refused, naming the entry and both positions. tar's "the later one
  wins" (append mode) is not supported: which of two same-named documents is the real one is the
  question the objpack pairing defect showed must never be answered by position.
* **A file and a directory with the same path** (`a` as file, `a/b` as entry): refused.

**Defence in depth, never the primary check**: after validation the target is resolved, normalised,
and must `startsWith` the staging root. If validation is ever wrong, this is what stops the write —
and a test asserts the validator, not this net, is what refuses each hostile fixture.

Not refused, **reported**: names that are not Unicode NFC (a macOS zip writes `é` as `e` + combining
accent). NTFS keeps them distinct from the NFC spelling, so a later name match (objpack `name`
mode) can miss a file that looks identical. Counted and logged; renaming them would be normalising.

## 6. Links, devices, FIFOs

**Nothing but regular files and directories is ever created.** A symlink written during extraction
and then used as a path prefix by a later entry is the classic escape; on Windows creating one
needs a privilege the service account should not have anyway.

`onUnsupportedEntry` = `fail` (default) | `skip`:

* tar `2` symlink, tar `1` hardlink, zip entries with Unix mode `S_IFLNK` (§4.2, if Q5 = parse),
  tar `3`/`4` devices, `6` FIFO.
* `fail` refuses the archive naming the entry and its type. `skip` counts it, names it in the log
  (at most 20 names per archive, then a count) and publishes `entriesSkipped`.

**Hardlinks are a distinct question** (Q6). GNU tar stores the *second* name of a multiply-linked
file as a hardlink to the first (§2 fixture: `a.txt` and `hard_a` share an inode; which one is the
regular member depends on traversal order). A `copy` mode — materialise the link as a copy of an
**already-extracted, already-validated** member of the same archive — is safe and small. Not in the
default; offered if such archives are expected.

## 7. Archive bombs — limits, counted not declared

Every limit is enforced on **bytes actually written**, never on sizes an archive declares. Declared
sizes are used only to refuse *early*.

| Param | Default | Scope |
|---|---|---|
| `maxEntries` | 100000 | entries per archive (same ceiling as objpack's `maxObjects`) |
| `maxEntryMb` | 2048 | bytes written for one entry (objpack's `maxObjectMb`) |
| `maxArchiveMb` | 20480 | bytes written for one archive (objpack's `maxSubmissionMb`) |
| `maxRatio` | 200 | bytes written ÷ compressed bytes read, per entry |

* **Ratio**: evaluated continuously once an entry has written **10 MB** — below that a tiny, very
  compressible file (a zero-filled template, a blank CSV) would trip it for no benefit. The 10 MB
  grace is the one figure here that is **chosen rather than derived**, as `PER_DOCUMENT_OVERHEAD`
  is in elarxml. 200 is ~8× the highest legitimate ratio measured (§2.18: 25.5) and ~5× below
  deflate's ceiling (1028). For zip, compressed bytes = the entry's central-directory size, which is
  also what bounds `ZipFile`'s inflater input. For `tar.gz`, compressed bytes = a counter under the
  `GZIPInputStream`, and the ratio is also evaluated over the whole stream.
* **Free disk** (`checkFreeDisk`, default on): for a zip the declared uncompressed total is known
  from the central directory before a byte is written — refused early if free space on the output
  volume is below it plus 10%. For `tar.gz` nothing trustworthy is known in advance (gzip ISIZE is
  the size modulo 2³², so it lies above 4 GiB); only the running counters apply. A filesystem that
  reports 0 free space disables the check with a log line, as elarxml's guard does.
* A limit tripping fails **the archive**, its staging is removed (§8.2), and the message names the
  limit, its value, the entry and the counter at the moment it tripped.

## 8. Output layout, overwrite, interruption

### 8.1. Layout

`layout` = `subdir` (default) | `flat`.

* **`subdir`**: each archive into `<outputDir>/<archive name without its recognised extension>/`.
  `report.tar.gz` → `report/`, `x.tgz` → `x/`, `data.zip` → `data/`. Two archives whose subdir
  names collide (`a.zip` and `a.tar`) in the same run → both refused before either is extracted,
  since the listing is known up front.
* **`flat`**: entries of every archive merged into `<outputDir>/`, internal paths kept (flat means
  *no per-archive folder*, not "strip directories", which would manufacture collisions).
  Case-insensitive collisions are checked **across the whole run and against what is already in
  `outputDir`**, before the first file is moved.
* `outputDir` equal to `sourceDir` is refused; `outputDir` inside `sourceDir` is refused when
  `recursive=true`. Otherwise the next run finds the extracted `.zip` inside an extracted folder and
  opens it — nested extraction by the back door.

### 8.2. Staging — nothing incomplete under a final name

Each archive is extracted into `<outputDir>/.unarchive-<runId>-<n>.part/` on the same volume, then
committed:

* `subdir`: **one directory rename** of the staging dir onto the final subdir name — atomic on one
  volume. On Windows a rename can fail transiently while a scanner or the indexer holds a handle
  inside (the elarxml `Access is denied` after 27 668 documents): 3 attempts with back-off, then the
  archive fails with the staging left in place and named.
* `flat`: per-file moves after a complete collision pre-check. **Not atomic as a whole** — declared,
  not hidden. The manifest (§9) is written **last**, so its absence marks an incomplete flat commit.

**Failure of an archive** (refusal, limit, I/O, Stop): its staging dir is deleted in a `finally`;
nothing appears under a final name. **Archives committed earlier in the same step stay committed**
and the log lists them — the same per-unit rule as elarxml's `.done`.
**JVM killed mid-archive**: the `.part` directory survives; the next run of the step sweeps
directories matching `.unarchive-*.part` in `outputDir` (only that pattern, only there) and logs each
removal.

`onArchiveError` is deliberately **not** a parameter: the first failing archive stops the step
(exit 2). Continuing past a broken archive is a reasonable wish, but a step that "succeeds" with
three of ten archives extracted is read as ten; if wanted, it is a later option with a counter a
gate can branch on.

### 8.3. What already exists

`onExisting` = `fail` (default) | `skip` | `replace`.

* `subdir`: the unit is the subdirectory. `fail` refuses the archive; `skip` does not extract it and
  counts it; `replace` stages the new one **first**, then renames the old aside, renames the new in,
  and deletes the old — the old content is never removed before the new is complete.
* `flat`: the unit is the file; same three meanings, `replace` = overwrite that file.

The name is new on purpose: `overwriteExisting` (elarxml, boolean) and `renameProcessed` (elarxml,
**default yes**) already exist in `variables.html` `PARAM_OPTIONS`, which is keyed by name without
executor context. Reusing either with a different default is the `inputCharset` / `onMissingFile`
trap a third time.

**The re-run tension, stated for Q7**: with `afterExtract=keep` and `onExisting=fail` (both
defaults), re-running a step after a partial failure fails at the first archive that *did* commit.
That is loud and correct, but it means a re-run needs a decision. `skip` is safe *in `subdir`
layout* in one sense — a final subdir only ever appears by an atomic rename after a complete
extraction — and unsafe in another: if the archive changed since, the old content is kept.

### 8.4. The source archive after success

`afterExtract` = `keep` (default) | `rename` (append `.done`, as elarxml) | `delete`. Applied **at
the archive's commit**, never before — the elarxml `deleteContentAfterEmbed` rule: tied to the same
event that makes the output final, so an archive that failed is never renamed or deleted. Unlike
elarxml there is no store type to make deletion impossible where it would be wrong (a plain
directory is a plain directory), which is why Q8 asks whether `delete` should exist at all.

### 8.5. Stop and mtime

* `control.aborted` checked between entries and every 8 MB within one; an aborted step removes the
  current staging and exits `-997`, as `runDiff`.
* File mtimes are **preserved** from the archive (`preserveMtime`, default on). Failure to set one
  is counted and logged, never fatal.
* **No date-pattern parameter exists** in this executor: subdirectory names come from the archive
  name only, and dates, if an author wants them in `outputDir`, come through `${currentDate}` and the
  resolver. The `YYYY`/`DD` `DateTimeFormatter` trap therefore cannot occur here. If a pattern
  parameter is ever added, it goes through objpack's `validateDateFormat` before a file is read.

## 9. Output variables and the manifest

Published: `archivesFound`, `archivesExtracted`, `archivesSkipped`, `entriesExtracted`,
`entriesSkipped`, `bytesExtracted`, `extractDirs` (`;`-list of committed subdirs, or `outputDir` in
flat layout), `manifestFile`.

**No per-file list in run variables.** The engine audits every out var with its value and OUTPUT
DATA shows it: 100 000 paths there is unusable. Instead
`<stepDir>/unarchive_manifest.csv`, UTF-8 without BOM, CRLF, RFC 4180, `;`-separated:
`archive;entry;target;bytes;sha256;mtime`. SHA-256 is computed while the bytes stream past, no
second read (`manifestHash`, default on — Q11). File names and hashes only, never content.

`failOnEmpty` (default off, same shape as `sqlreport`'s): no archive matched the pattern → fail.
Off, the step follows the `safecopy` convention (§2.20) and exits 0 with `archivesFound=0`. An
archive with zero entries is a WARNING either way.

## 10. Parameters

| Param | Default | Meaning |
|---|---|---|
| `sourceDir` | — (required) | directory holding the archives |
| `pattern` | `*.zip;*.tar;*.tgz;*.tar.gz;*.gz` | `;`/`,` wildcard list, case-sensitive (the json2csv `FileMask` rule) |
| `recursive` | `false` | same name and default as the existing `PARAM_OPTIONS` entry |
| `format` | `auto` | §3.2 |
| `outputDir` | `${stepDir}` | §8.1 |
| `layout` | `subdir` | §8.1 |
| `onExisting` | `fail` | §8.3 |
| `onUnsupportedEntry` | `fail` | §6 |
| `zipNameCharset` | `auto` | §4.2; a charset name forces it for unflagged names |
| `maxEntries` / `maxEntryMb` / `maxArchiveMb` / `maxRatio` | 100000 / 2048 / 20480 / 200 | §7 |
| `maxPathLength` | 259 | §5 |
| `checkFreeDisk` | `true` | §7; same name and default as elarxml's |
| `afterExtract` | `keep` | §8.4 |
| `preserveMtime` | `true` | §8.5 |
| `manifestHash` | `true` | §9 |
| `failOnEmpty` | `false` | §9 |

Conservative defaults hold trivially — a new executor changes no existing feed — but every default
above is also the *refusing* or *non-destructive* choice, so a step configured with only `sourceDir`
cannot delete, overwrite, follow a link or accept an ambiguous name.

## 11. Registration and the exit code

Verified on the code at `0997815`, not on the CLAUDE.md table (§2.19). Eight places:

1. `WorkflowXmlParser` whitelist (line 89)
2. `WorkflowXmlParser` error message (line 91) — the one that gets forgotten
3. `WorkflowXmlParser` `internal` set (line 94)
4. `WorkflowEngine.internalKind()` (line 1027)
5. `InternalSteps.run()` dispatch (line 117) — **passing `control`**, which `runObjPack` does not
6. `designer.html` `<option>` (line 1019)
7. `designer.html` panel branch (~1777)
8. `designer.html` `clientValidate` (~2180)

plus `variables.html` `PARAM_OPTIONS` for the unambiguous enums (`format`, `layout`, `onExisting`,
`onUnsupportedEntry`, `afterExtract`), and `USAGE.md`. `buildXml` should need no change — every
field is a `<param>` — **to be confirmed by reading the emission in the panel batch**, not predicted
(the elarxml IFS lesson).

**`runUnarchive` sets `res.exitCode = 0` on success** — `StepExecutor.Result.exitCode` starts at −1
and `runObjPack` reported every successful run as FAILED for exactly that. The lint from
`2026-09-25-objpack-exit-code.md` is re-run over all `run*` methods in the registration batch.
Codes: 0 success; 2 refusal (configuration or data); −997 Stop; 1 via the generic catch.

## 12. Testability and verification discipline

* **Package `unarchive` is Spring-free and JDK-only**, compiled with `javac --release 8` (stronger
  than the pom's `-source/-target`), so every rule in §3–§9 runs against real files in the sandbox.
  `InternalSteps.runUnarchive` only translates parameters and counters, and is the only untested
  layer — with the exit-code lint as its guard.
* **Fixtures are produced by real tools, not hand-built**: GNU tar 1.35 in `gnu`, `pax`, `ustar`,
  `oldgnu` formats, both `-C dir .` and explicit lists, long names, prefix-split names; `git
  archive`; python `tarfile` and `zipfile` (UTF-8 flag, duplicates, STORED + descriptor, ZIP64 via
  more than 65 535 entries); Info-ZIP `zip` (UTF-8 without flag, `-y` symlinks); a CP437 name patched
  into both headers; multi-member gzip; `gzip` with a traversing FNAME. **Hostile fixtures are
  crafted with python** (`../`, absolute, drive letter, `:`, reserved names, symlink escape,
  hardlink out of tree, device, FIFO, base-256 size header, bombs).
* **Byte-identical extraction is the acceptance test**: every benign fixture extracted by GNU tar /
  `unzip` / python into A and by the executor into B; same file set, same SHA-256 per file.
* **Every hostile fixture asserts three things**: refused, refused by the *validator* with the
  expected rule (not by the `startsWith` net), and nothing left under `outputDir` — no staging
  either.
* **Positive controls for every scan**: a scan that finds nothing is first shown able to find
  something.
* **Mutations on copies, never on the tree**; each anchor asserted to match exactly once and the
  file asserted changed (a missed anchor looks like a surviving mutation); every green mutation
  opened and classified as *equivalent*, *bad mutation* or *suite gap* before anything is filed.
* **The exit code that counts is the one read** — `javac`'s, not a pipe's `head`/`grep`.
* **Panel batch**: every key the panel writes (`setNodeParam`) compared mechanically with every key
  `runUnarchive` reads (`pv`); controls driven through their own `onchange` attribute under jsdom.

## 13. Gate 0

**Blocking batch 1:**

* **Q1 — Name.** Recommended `unarchive`. `extract` collides visually with three existing step ids
  (§2.21); `unzip` misnames tar.
* **Q2 — Formats in scope.** zip, tar, tar.gz/tgz, gz; bzip2/xz/7z/rar/zstd recognised and refused
  with the reason. Is any of the refused ones a real need? If yes, it becomes a Nexus question for
  commons-compress before it is a design question.
* **Q3 — Who produces the archives.** Windows Explorer "Compressed folder", 7-Zip, Windows
  `tar.exe` (bsdtar), Linux tar, a mainframe job, a vendor? It decides the zip legacy charset, whether
  tar names need a legacy fallback, and whether DEFLATE64 matters. **One or two real sample archives
  would replace three assumptions** — bsdtar is not installed here, so Windows `tar.exe` output
  cannot be produced in the sandbox.

**With a recommended default, not blocking:**

* **Q4 — zip legacy charset**: `IBM437` (APPNOTE) or `IBM850` (Italian Windows OEM page)? Identical
  for `à è é ì ò ù` (§2.11); different for `Ø`, `ı`, box-drawing. Recommended `IBM437`, with the
  per-rule counts in the log making a wrong choice visible.
* **Q5 — zip symlinks**: parse the central directory ourselves to read external attributes, with
  a cross-check against `ZipFile` (recommended), or extract them as small regular files?
* **Q6 — hardlinks**: `fail` by default; is a `copy` mode wanted (§6)?
* **Q7 — `onExisting` default** `fail` vs `skip` in `subdir` layout (§8.3's re-run tension).
  Recommended `fail`.
* **Q8 — `afterExtract=delete`**: offer it, or only `keep`/`rename`?
* **Q9 — limit defaults** in §7 and `maxPathLength` 259 — confirm or give real sizes.
* **Q10 — flat layout in batch 1**, or `subdir` only first? `flat` is the non-atomic one.
* **Q11 — manifest with SHA-256** on by default?
* **Q12 — missing tar end marker**: WARNING (GNU tar parity, recommended) or refusal?

## 14. Batches

* **0** — this spec.
* **1** — core readers: `ArchiveFormat` (detection), `EntryName` (every §5 rule), `TarStreamReader`
  (§4.1), gzip handling (§4.3). Standalone, unwired, fixtures and hostile set.
* **2** — `ZipArchiveReader` (§4.2, including Q5's central-directory parser if chosen), limits
  (§7), staging and commit (§8), manifest (§9), `UnarchiveRun` end to end.
* **3** — registration (eight places), `runUnarchive`, Stop, exit-code lint.
* **4** — designer panel, `PARAM_OPTIONS`, mechanical parameter comparison.
* **5** — `USAGE.md`, verified through `docs.html`'s own `render()`.

## 15. Not verifiable here, said now

* `mvn clean package` (Maven Central unreachable).
* **Windows**: reserved names actually failing, `MAX_PATH` behaviour of Java 8 NIO, a directory
  rename racing a virus scanner. Rules for them are arguments until a Windows run.
* **Java 8 specifically**: every measurement in §2 ran on JDK 21 with `--release 8`, which checks the
  API surface, not runtime behaviour. The `ZipFile` CRC and name-decoding behaviour are believed
  identical on 8 but not measured on 8 — the `ofPattern("DD")` lesson says to confirm. `IBM437`/
  `IBM850` live in `charsets.jar` on a Java 8 JRE; one line on the server confirms it:
  `jrunscript -e "print(java.nio.charset.Charset.isSupported('IBM850'))"`.
* bsdtar / Windows `tar.exe` archives (Q3).
