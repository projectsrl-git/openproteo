# Archive extraction executor (`unarchive`) — specification

Status: **batches C and R delivered** (opt-in deletion §22.3, opt-in nested archives §22.5).
Linux hosts complete (§21). Batches 0–4 delivered the executor for Windows. Batch 2 delivered the zip reader, limits,
staging, commit and manifest. Gate 0 Q1–Q3 answered 2026-09-30, Q4–Q12 confirmed with the recommended
defaults 2026-10-03 (§13). Batch 0 written on `0997815`, revised on `0509aa9`, batch 2 on `a4f02c8`.

**Revision 2026-09-30** — three corrections, struck through where they were made rather than
rewritten: the case-folding rule (§5, it disagreed with `rename.CaseInsensitive` and with NTFS),
the zip legacy charset recommendation (§4.2, after Q3), and the registration line numbers (§11).
And, per the CLAUDE.md principle added on 2026-09-30, every rule that meets another rule on the
same case now says which one wins, beside both; §16 lists them all with the test that pins each.

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
| tar (v7, no magic) | none — only a valid header checksum | only when the author or the extension says tar (§3.2, **∩ I3**) |
| tar (empty) | first 512 bytes all zero (GNU's empty archive is 10 240 zero bytes, measured) | same condition as v7 (**∩ I3**) |
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
  exactly the case auto exists for. **∩ I1** with the extension, **∩ I6** with §3.3: the magic wins
  over the extension only when the magic names an archive; a `.zip` that is plain text is refused. No magic at all and an extension of `.tar` → tried as v7 tar,
  accepted only if the first header's checksum is valid (either the unsigned or the signed sum, as
  GNU tar accepts). Anything else unrecognised → refused (§3.3).
* **Explicit format: the author has asserted it, and a file that contradicts the assertion is
  refused**, naming what the magic says. Silently extracting a zip because it was "obviously" one
  would make the parameter decorative. **∩ I2**: the explicit format wins even where `auto` would
  have read the file — `format=tar` on a gzip is refused, not unpacked. **∩ I5**: `format=gz` means
  *decompress only* and never unpacks a tar inside; `format=tar.gz` refuses a gzip whose content is
  not a tar. One exception to "the mismatch is named": a file whose magic is a refused format
  (`x.bz2` with `format=tar`) is refused as UNSUPPORTED naming the real format, which is the more
  useful of the two messages.
* **Short files**: a file shorter than the magic it would need is not a tar "with zero members" —
  it is refused as unrecognised. This closes §2.5's empty-list-without-error by construction.
  **∩ I4**: a **0-byte** file is refused as `EMPTY_FILE` whatever its name — measured, GNU tar ("does
  not look like a tar archive") and python both refuse it — while GNU's 10 240-zero empty archive
  named `.tar` is a valid tar with zero members (**∩ I3**).

### 3.3. A selected file that is not an archive

A file matched by the author's `pattern` and not recognised as an archive **fails the step** by
default: the pattern said it was one. **∩ I6** with `auto`'s tolerance of extensions (§3.2): the
tolerance covers a wrong *archive* extension, never a non-archive. **∩ I22**: names ending in `.done`
(`afterExtract=rename`) and the `.unarchive-*.part` staging directories are never selected, whatever
`pattern` says — a pattern of `*` must not re-extract yesterday's archives. `pattern` is the author's filter; widening it to `*` and
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
  mirror image of §2.5's lost prefix. **Measured in batch 1**: `tar --format=gnu -G` writes atime and
  ctime in octal there (`15257155562\0…`), so with the prefix read `a.txt` would become
  `15257155562/a.txt`. The first suite had no such fixture and the mutation survived (§17). **∩ I17**.
* **Extended names**: GNU `L` (long name) and `K` (long link name) apply to the next header; pax `x`
  records (`<len> <key>=<value>\n`) apply `path`, `linkpath`, `size`, `mtime` to the next header;
  pax `g` (global) is **parsed and ignored**, its keys logged once per archive (§2.4, **∩ I18**). An
  `L`/`K`/`x` not followed by a header is refused. Extended blocks are capped at 1 MiB — a name is not
  a megabyte, and the cap stops a header from allocating whatever it declares.
  **∩ I15**: pax `path` wins over the header name, the ustar prefix and a GNU `L`; when it is present
  the header name bytes are **not decoded at all**, so a producer that writes a transliterated legacy
  name in the header and the real one in pax (the shape expected from Windows `tar.exe`, argued, not
  measured — bsdtar is not installable in its Windows form here) is read correctly. pax `path` and a
  GNU `L` on the same member are refused as `AMBIGUOUS_NAME`. **∩ I16**: pax `size` wins over the
  header size, which may be 0 for a member over 8 GiB.
* **Typeflags**: `0`, `\0`, `7` → regular file; `5` → directory; `1` hardlink, `2` symlink → §6;
  `3`/`4` device, `6` FIFO → §6; `S` sparse, `M` multivolume, `D` dumpdir → refused naming the flag;
  `V` volume label → ignored and logged. Any other flag → refused naming it.
* **Name charset**: pax `path` is UTF-8 by the standard. Plain header names are decoded as **strict
  UTF-8** (malformed → refused, naming the header offset). Q3 answered *Windows and Linux*: Linux tar
  writes UTF-8 bytes; Windows `tar.exe` (libarchive) is expected to carry non-ASCII names in pax,
  which **∩ I15** reads without touching the header bytes. **No legacy fallback in batch 1**; a real
  Windows `.tar` failing with `BAD_NAME_ENCODING` is the evidence that would add one.
* **End of archive**: two zero blocks end it. **A missing end marker is a WARNING, not a failure**,
  matching GNU tar (§2.7) — producers vary and refusing would be stricter than every tool the
  operator will compare against. **A stream that ends inside a header or a payload is a failure.**
  **∩ I19**, the boundary between the two: the stream ending *exactly* on a header boundary, with no
  zero block, is the warning; ending anywhere else — inside a header, a payload, its padding, or after
  an extended header — is `TRUNCATED`. A truncated payload fails **inside the payload read**, not
  later at the next header, so no caller can keep a short file believing it complete (asserted).
  The reader only *reports* the missing marker; turning it into a warning line is batch 2's.
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
  4. otherwise → `zipNameCharset`, default ~~`IBM437`~~ **`IBM850`** (Q4, revised after Q3). The only
     producers that write unflagged legacy names are Windows tools using the machine's OEM code page,
     and on Western European Windows (Italian, German, French) that page is 850, not 437. The two
     agree on `à è é ì ò ù` and on `ä ö ü ß` (§2.11); they differ on `Ø`, `ı` and box-drawing.
  The step log states, per archive, how many names each rule decided. A charset guess that is never
  reported is a guess nobody can check.
  `zipNameCharset` set to a charset name **forces** step 4 for every unflagged name (skipping 2–3),
  for the day a heuristic is wrong. **∩ I20**: bit 11 wins over a forced charset, always — the
  producer declared UTF-8 and the JDK decodes it before the charset is consulted.
* **Refused, naming the entry**: encrypted entries (bit 0, or bit 6); a compression method other than STORED
  (0) or DEFLATED (8), named by number. **Checked on our own central-directory records BEFORE
  `ZipFile` is opened** (batch 2): measured, JDK 21 already refuses such an archive in the `ZipFile`
  constructor (`invalid CEN header (encrypted entry)`, `(bad compression method: 12)`), while Java 8's
  native zip code does not — so without the early check the same file would be refused under a
  different rule on each JDK — DEFLATE64 (9) is the likely one from Windows tooling and is
  not in the JDK; spanned archives.
* **Link detection** (§2.15) needs the external attributes, which Java 8 does not expose. Two ways,
  Q5: read the central directory ourselves (EOCD, ZIP64 EOCD, one record per entry: raw name,
  flags, method, external attributes) and **cross-check it against `ZipFile`** — same count, same
  names in the same order, else refuse; or accept that a zip symlink is extracted as a small
  regular file holding the target's path. Recommended: the first. It also yields the raw names
  directly, so the ISO-8859-1 bijection becomes a cross-check rather than the mechanism.
  **Q5 answered: parse.** Batch 2 measured what the cross-check is worth: with our parser's name
  offset mutated by one byte AND the cross-check removed, the archive extracts **silently under wrong
  names** (`erché.txtU`, `ir/U`); with the cross-check, the same defect is a `ZIP_STRUCTURE` refusal.
* **∩ I28**: an entry is a directory if and only if its name ends in a separator; attributes decide
  only the Unix special types. A DOS directory bit on a name without a trailing separator does not
  make a directory.

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

* a leading `./` (and a bare `./` root entry, which creates nothing) — §2.2. **∩ I7** with the
  dot-segment rule below: **one** leading `./`, first segment only; `././a` and `a/./b` are refused;
* `\` treated as a separator equal to `/` **for validation** — so `..\x` is caught on every host —
  and written as the platform separator. Counted and logged, since APPNOTE says zip names use `/`.
  **∩ I8** with the absolute rule below: a **leading** `\` is absolute and refused; only one inside a
  name separates. On Linux a tar member literally named `a\b` becomes `a/b` — deliberately, so the
  same archive gives the same tree on a Linux test box and on the Windows server;
* a trailing `/` on a directory entry. **∩ I9** with the empty-segment rule: exactly one; `a//` is
  refused.

**Refused (rule named in the message):**

* **Traversal**: any `..` segment, after splitting on both separators. **∩ I10**: `..` also ends in
  `.` and the trailing-dot rule would refuse it too; traversal is checked first, so the message names
  traversal (measured: with this check mutated away the trailing-dot rule refused it instead). Per segment, not substring:
  `report..v2.pdf` is a legal name. (`WorkflowPorter.extractToStaging` uses `contains("..")` and
  would refuse it; noted, not changed — out of scope.)
* **Absolute and rooted** (**∩ I8**): leading `/` or `\`; a drive letter (**∩ I11**: checked before
  the `:` rule so the message says *drive*; note `a:b` IS a drive path — drive A: — and my first test
  expected `COLON` for it, which was my error, not the code's) (`C:`, `C:x`, `C:\x` — `C:x` is
  relative to *that drive's* current directory, never to ours, the copy-file-list lesson); UNC
  `\\server\share`; device paths `\\?\`, `\\.\`.
* **`:` anywhere**: besides drive letters it is the NTFS alternate data stream separator —
  `report.pdf:hidden` writes a stream **inside** `report.pdf` that no directory listing shows.
  **∩ I25**: refused on Linux too, where `report_10:41.txt` is a legal name — the same workflow must
  give the same result on every host, and production is Windows.
* **Windows-invalid characters**: `< > " | ? *` and code points `0x00`–`0x1F`.
* **Windows reserved device names**, per segment, case-insensitive, **with or without an extension**:
  `CON PRN AUX NUL COM1`–`COM9 LPT1`–`LPT9`, the superscript forms `COM¹ COM² COM³ LPT¹ LPT² LPT³`,
  `CONIN$ CONOUT$`. `nul.txt` is as reserved as `NUL`.
* **Segments ending in `.` or space**: Win32 strips them, so `a.txt.` and `a.txt` are one file.
* **Empty segments** (`a//b`, **∩ I9**) and `.` segments other than the leading one (**∩ I7**) — no
  real tool produces them; accepting them would be normalising silently.
* **Segments over 255** UTF-16 units **or** 255 UTF-8 bytes (NTFS counts the first, ext4 the second;
  both are checked so every host agrees). Added in batch 1.
* **Path length**: the **absolute** target path (output directory + subdirectory + entry) longer than
  `maxPathLength` (default 259, Windows `MAX_PATH` minus the terminator). Java on Windows may well
  *create* a longer path (NIO prefixes `\\?\`), which is exactly why the limit exists: the file
  would be written and then be unreadable to Explorer, PowerShell 5.1 and the next `ifscopy`.
  Checked against the resolved output directory at run time, since `${stepDir}` differs per host.
  **∩ I26**: in `subdir` layout the subdirectory name counts. A path of exactly the limit passes
  (asserted at 259).
* **Case-insensitive collisions within one archive**: two entries equal under
  ~~`toLowerCase(Locale.ROOT)`~~ **per-character upper-casing, no culture** (`OrdinalIgnoreCase`, the
  rule of `rename.CaseInsensitive`) are refused, naming both. Corrected in batch 1, measured: string
  `toLowerCase` turns `İ` into two characters, string `toUpperCase` makes `straße` equal `STRASSE`;
  NTFS does neither. The package keeps its own copy — it may not depend on another executor's — and
  the suite compares the two over all 65 536 BMP characters: 0 differences. **∩ I14**: refused
  whatever `onExisting` says; `onExisting` governs what was on disk *before*, never two entries of one
  archive. **∩ I24**: the key does not normalise Unicode, so an NFC and an NFD spelling are two names —
  as they are on NTFS. On NTFS the second silently overwrites the
  first. **Enforced on every host, Linux test boxes included**: a workflow must not extract two files
  on a developer's machine and one on the server (the `FileMask` lesson).
* **Exact duplicates** (§2.14): refused, naming the entry. **∩ I12**: a *directory* named twice with
  the same spelling is accepted — creating it is idempotent and GNU tar stores a directory again when
  it is named twice on the command line; a *file* named twice is refused. **∩ I13**: two directories
  differing only in case (`Dir/a`, `dir/b`) are refused as `DIRECTORY_CASE_MISMATCH` — merged on
  Windows, separate on Linux — and parents count even without a directory entry of their own. tar's "the later one
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
  `GZIPInputStream`, and the ratio is also evaluated over the whole stream. **∩ I21**: ~~both the
  per-entry and the whole-stream ratio apply; the first to trip decides and the message names which~~.
  **Corrected in batch 2, measured**: the per-entry ratio applies only where an entry's compressed
  size is KNOWN — zip, from the central directory. For a gzip stream only the whole-stream ratio
  applies: a per-entry figure would be the compressed bytes consumed during the entry, and the
  decompressor's read-ahead makes that meaningless — 30 MB of zeros compress to ~30 KB, all of it
  read before the first entry starts, so every entry read as an infinite ratio and a legitimate
  setting (`maxRatio=5000`) still refused it. The counter exists since batch 1 and is asserted to
  equal the file length after a full read.
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
directory is a plain directory), which is why Q8 asks whether `delete` should exist at all. ~~Q8: not offered~~ — **Gate 0 C1 (2026-10-04): offered, opt-in**, deleted only after the
archive's own commit, never when it failed, was stopped or was skipped (§22.3, ∩ I49, I50).

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
flat layout), `manifestFile`, `warnings`, and since §21 `hostRules` (`windows` | `linux`).

**No per-file list in run variables.** The engine audits every out var with its value and OUTPUT
DATA shows it: 100 000 paths there is unusable. Instead
`<stepDir>/unarchive_manifest.csv`, UTF-8 without BOM, CRLF, RFC 4180, `;`-separated:
`archive;entry;target;bytes;sha256;mtime`. SHA-256 is computed while the bytes stream past, no
second read (`manifestHash`, default on — Q11). File names and hashes only, never content.

`failOnEmpty` (default off, same shape as `sqlreport`'s): no archive matched the pattern → fail.
**∩ I23**: it is about *no archive matched*, never about an archive with zero entries.
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
| `layout` | `subdir` | §8.1; ~~`flat`~~ refused naming Gate 0 Q10 |
| `onExisting` | `fail` | §8.3 |
| `onUnsupportedEntry` | `fail` | §6 |
| `zipNameCharset` | `auto` (legacy fallback `IBM850`) | §4.2; a charset name forces it for unflagged names; never over bit 11 (**∩ I20**). The fallback itself is NOT a step parameter: `UnarchiveRun.zipLegacyCharset` exists so a test can show the refusal when the charset is missing from the JRE (Java 8 keeps it in `lib/charsets.jar`) |
| `maxEntries` / `maxEntryMb` / `maxArchiveMb` / `maxRatio` | 100000 / 2048 / 20480 / 200 | §7 |
| `maxPathLength` | ~~259~~ `auto` | §5, §21: `auto` = 259 UTF-16 units on a Windows host, 4096 UTF-8 bytes on Linux; a number applies on both, in the host's unit |
| `checkFreeDisk` | `true` | §7; same name and default as elarxml's |
| `afterExtract` | `keep` | §8.4; `keep`, `rename` or `delete` (~~refused naming Gate 0 Q8~~ opt-in since Gate 0 C1, §22.3) |
| `preserveMtime` | `true` | §8.5 |
| `manifestHash` | `true` | §9 |
| `failOnEmpty` | `false` | §9 |
| `nested` | `none` | §22.4: `extract` = archives inside archives, selected by name, extracted in place |
| `nestedDepth` | 3 | §22.4: deepest nested level extracted; deeper ones kept as files with a warning |

Conservative defaults hold trivially — a new executor changes no existing feed — but every default
above is also the *refusing* or *non-destructive* choice, so a step configured with only `sourceDir`
cannot delete, overwrite, follow a link or accept an ambiguous name.

## 11. Registration and the exit code

Verified on the code, not on the CLAUDE.md table (§2.19). Eight places. ~~Line numbers at `0997815`~~
— they move with every executor; at `0509aa9`, after `filerename`, read on `objpack`/`filerename`:

1. `WorkflowXmlParser` whitelist (line 89)
2. `WorkflowXmlParser` error message (line 91) — the one that gets forgotten
3. `WorkflowXmlParser` `internal` set (line 94)
4. `WorkflowEngine.internalKind()` (line 1027)
5. `InternalSteps.run()` dispatch (lines 117–119) — **passing `control`**, which `runObjPack` does not
6. `designer.html` `<option>` (~1055)
7. `designer.html` panel branch (~1813 objpack, ~1914 filerename)
8. `designer.html` `clientValidate` (~2291, ~2302)

Re-read on the day of batch 3; these numbers are a pointer, not a contract.

plus `variables.html` `PARAM_OPTIONS` for the unambiguous enums (`format`, `layout`, `onExisting`,
`onUnsupportedEntry`, `afterExtract`), and `USAGE.md`. `buildXml` should need no change — every
field is a `<param>` — **to be confirmed by reading the emission in the panel batch**, not predicted
(the elarxml IFS lesson).

**`runUnarchive` sets `res.exitCode = 0` on success** — `StepExecutor.Result.exitCode` starts at −1
and `runObjPack` reported every successful run as FAILED for exactly that. The lint from
`2026-09-25-objpack-exit-code.md` is re-run over all `run*` methods in the registration batch.
Codes: 0 success; 2 refusal (configuration or data); −997 Stop; 1 ~~via the generic catch~~ **set
explicitly for an unexpected I/O failure**. Found while wiring: the dispatcher's catch does
`res.exitCode = res.exitCode == 0 ? 1 : res.exitCode`, so an exception reaching it while the code is
still −1 ends the step as **−1**, not 1. `runUnarchive` therefore catches its own exceptions and sets
a code on every path; the wiring suite asserts the code is never −1.

**Batch 3 split, stated**: the five backend places (1–5) are in batch 3; the three designer places
(6–8) go with the panel in batch 4, where they are verified together with it. **Done in batch 4**;
no other place in the designer names executors (checked on the template, not on the table). Until then the step is
written in the XML, and USAGE.md says so.

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

**Blocking batch 1 — ANSWERED 2026-09-30:**

* **Q1 — ANSWERED: `unarchive`.**
* **Q2 — ANSWERED: zip, tar, tar.gz/tgz, gz.** The five others stay recognised and refused.
* **Q3 — ANSWERED: Windows and Linux only.** Consequences taken: tar names strict UTF-8 with pax
  winning (§4.1); zip legacy fallback moved to IBM850 (§4.2, Q4); DEFLATE64 stays refused by number
  (Windows tooling is the likely source). Still wanted: one real zip from Explorer and one `.tar` from
  Windows `tar.exe`, since neither can be produced here.

*As written at batch 0:*

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

**Q4–Q12 — CONFIRMED 2026-10-03 with the recommended defaults**: IBM850; parse the central
directory (Q5); hardlinks refused, no copy mode (Q6); `onExisting=fail` (Q7); `afterExtract` keep or
rename, no delete (Q8); limits as §7 and `maxPathLength` 259 (Q9); `subdir` layout only (Q10);
manifest with SHA-256 (Q11); missing tar end marker is a warning (Q12). `layout=flat` and
`afterExtract=delete` are therefore REFUSED with a message naming the Gate 0 question, not ignored.

*As written at batch 0:*

* **Q4 — zip legacy charset**: `IBM437` (APPNOTE) or `IBM850` (Italian Windows OEM page)? Identical
  for `à è é ì ò ù` (§2.11); different for `Ø`, `ı`, box-drawing. Recommended ~~`IBM437`~~ **`IBM850`**
  since Q3 (the producers are Windows machines, whose OEM page is 850 in Western Europe), with the
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
  (§4.1), gzip handling (§4.3). Standalone, unwired, fixtures and hostile set. **Delivered**, §17.
* **2** — `ZipArchiveReader` (§4.2, including Q5's central-directory parser if chosen), limits
  (§7), staging and commit (§8), manifest (§9), `UnarchiveRun` end to end. **Delivered**, §18.
* **3** — registration (eight places), `runUnarchive`, Stop, exit-code lint. **Delivered** (backend
  places 1–5, USAGE.md section), §19.
* **4** — designer panel, `PARAM_OPTIONS`, mechanical parameter comparison. **Delivered**, §20.
* **5** — ~~`USAGE.md`, verified through `docs.html`'s own `render()`~~ moved into batch 3, since the
  executor became reachable there; ~~batch 5 only adds the panel's part to it~~ the panel's part went
  into batch 4 with the panel. **No batch 5.**

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

## 16. Decided intersections

Every place where two rules of this spec can apply to the same case, the winner, and the assertion
that pins it. Each is also written beside both rules above (marked **∩ In**).

| # | Case | Rules that meet | Winner | Pinned by |
|---|---|---|---|---|
| I1 | `report.tar` that is gzip | magic vs extension (`auto`) | magic, warning logged | format: `b_renamed.tar` |
| I2 | `format=tar` on a gzip | explicit format vs what `auto` would do | explicit: refused | format: `b_pax.tgz tar` |
| I3 | zero-filled or magic-less first block | "magic decides" vs no magic | tar only if named `.tar` or `format=tar` | format: `data.bin`, `zeros.tar` |
| I4 | 0-byte `x.tar` | `.tar` name vs empty content | refused `EMPTY_FILE` (GNU/python parity) | format: `zero.tar` |
| I5 | tar inside `format=gz`; CSV inside `format=tar.gz`; zeros in `.gz` vs `.tgz` | explicit gzip formats vs the inner sniff | `gz` never unpacks; `tar.gz` refuses non-tar; zeros are a tar only in `.tgz`/`.tar.gz` | gzip group |
| I6 | `.zip` that is text | `auto` tolerance vs "pattern said archive" | refused | format: `text.tar` |
| I7 | `././a`, `a/./b` | leading `./` accepted vs `.` segment refused | one leading `./`, first segment only | names |
| I8 | `\x`, `a\b` | `\` as separator vs absolute | leading = absolute; inside = separator | names, `h_ABSOLUTE2` |
| I9 | `a//` | trailing separator vs empty segment | exactly one trailing separator | names |
| I10 | `..` | traversal vs trailing dot | traversal checked first, names it | mutation 15 |
| I11 | `C:x`, `a:b` | drive letter vs colon | drive letter first | names, `h_DRIVE_LETTER` |
| I12 | same directory twice | duplicate vs idempotent mkdir | directories accepted, files refused | `h_DUPLICATE` |
| I13 | `Dir/a` + `dir/b` | case collision vs directory merge | refused `DIRECTORY_CASE_MISMATCH` | `h_DIRECTORY_CASE_MISMATCH` |
| I14 | `A.txt` + `a.txt`, `onExisting=replace` | case collision vs overwrite policy | collision refused regardless | existing: `zh_CASE_COLLISION` under replace |
| I15 | pax `path` + header name / prefix / `L` | extended names | pax wins; pax + `L` refused | `b_pax.tar`, `h_AMBIGUOUS_NAME` |
| I16 | pax `size` + header size | sizes | pax wins | `b_paxsize.tar` |
| I17 | bytes 345+ in a GNU header | prefix vs GNU atime/ctime | prefix only under POSIX magic | `b_gnu_incr.tar` |
| I18 | pax `g` with `path` or `size` | global vs per-entry | global never changes an entry | `b_git.tar` |
| I19 | stream ends | missing end marker vs truncation | boundary = warning; elsewhere = `TRUNCATED`, inside the payload read | flags, hostile |
| I20 | UTF-8 flag + forced `zipNameCharset` | flag vs parameter | flag | zipNames, mutation |
| I21 | tar.gz ratio | per-entry vs whole-stream | ~~both; first to trip~~ zip: both; gzip: whole stream only (read-ahead, measured) | ratio, grace |
| I22 | `x.zip.done`, `.part` dirs, `pattern=*` | pattern vs own artefacts | own artefacts never selected | afterExtract, sweep |
| I23 | empty archive, `failOnEmpty=true` | "nothing to do" vs "nothing inside" | `failOnEmpty` only for no archive matched | config |
| I24 | NFC + NFD spellings | collision key vs normalisation | not a collision, reported | names (flag) |
| I25 | `a:b.txt` from Linux | colon rule vs Linux legality | refused on every host | names |
| I26 | long subdir + long entry | path length vs layout | subdir counts, final path not staging path | pathLength, mutation |
| I27 | ~~hardlink `copy` mode (if Q6 says yes)~~ | ~~"never creates links" vs copy~~ | **Q6 answered: no copy mode** — hardlinks are refused or skipped like links | unsupported |
| I28 | zip entry: DOS dir bit, no trailing `/` | attributes vs name | the name; attributes decide only Unix special types | equivalent mutation (two spellings of one rule) |
| I29 | link named `../x` with `onUnsupportedEntry=skip` | skip vs name validation | the name is validated first, refused even though the entry would be skipped | unsupported: `hostile_link.tar` |
| I31 | `failOnEmpty`, `checkFreeDisk` | this executor's `yes()` vs sqlreport's `"true".equalsIgnoreCase` and elarxml's, on the same `PARAM_OPTIONS` key | the panel writes `true`/`false`, never `yes`/`no`, so the shared key is right for all three; same meaning and default in each | panel suite |
| I32 | `layout` | "every parameter explicit" vs a parameter with one value | shown as fixed text, not a select; still seeded as `subdir` | panel suite, mechanical |
| I30 | tar magic with a wrong checksum | detection vs reader | the magic: a corrupted tar, refused `BAD_CHECKSUM`; only a magic-less header needs the checksum | `h_BAD_CHECKSUM` via the run, mutation |

## 17. Batch 1 as built

Package `com.legalarchive.orchestrator.unarchive`: `ArchiveFormat`, `EntryName`, `NameIndex`,
`TarStreamReader`, `GzipSupport`, `UnarchiveException`. JDK only, `javac --release 8 -Xlint:all`
clean. **Nothing outside the package changes**; no registration, no call site — no feed can reach
it, by design. Read-only by construction for now: a scan finds no write API in the package, and the
same scan finds 12 in `objpack` (positive control). No `ofPattern` anywhere.

**Verification — 232 assertions green**, suite not committed (as the `elar`/`objpack` suites):

* **Byte-identical against GNU tar 1.35 on 18 benign fixtures produced by real tools**: GNU tar in
  `gnu` (with `-C dir .` and with an explicit list), `pax`, `ustar`, `oldgnu`, `gnu -G`
  (incremental) and `v7` formats; `git archive` of this repository; python `tarfile` PAX with
  Italian and Japanese names; a gzip of each; a gzip renamed `.tar`; **a multi-member gzip split in
  the middle of a member's payload**; GNU's empty archive; a tar cut at a member boundary; and three
  hand-built headers each first confirmed accepted by GNU tar — base-256 size, signed checksum, pax
  size over a header size of 0. The fixture set includes a 700 000-byte member and members of 0,
  511, 512 and 513 bytes.
* **The same archives read one byte per `read()` through a stream whose `skip()` never moves**:
  names with every payload left unread equal `tar --quoting-style=literal -tf`, and extraction is
  byte-identical. This is what pins "never trust `skip()`".
* **29 hostile fixtures, each refused by the rule written for it** — the test compares the
  `UnarchiveException.Rule`, not a phrase — plus the truncated payload failing *inside the payload
  read*.
* Detection on 25 cases (10 accepted with kind, handling and warning asserted; 15 refused with the
  rule), name validation on 49, the collision key against the real `rename.CaseInsensitive` compiled
  from the repository over 65 541 strings, gzip naming and the compressed counter.

**34 mutations on copies of the package, all caught.** Each anchor asserted to match exactly once
and the file asserted changed; `javac`'s and the suite's exit codes read directly. Run in three
foreground slices under the command limit. **One survived first and was a real suite gap**: reading
the prefix under the GNU magic changed nothing because no fixture had data in those bytes. GNU's
incremental format does (measured: atime/ctime in octal), `b_gnu_incr.tar` was added, and the mutation
is caught. Two first-run suite failures were **my expectations**: `a:b` is a drive path, not a
colon case; and a hand-built pax record declared 13 bytes for a 10-byte record — GNU tar refused it,
it was corrected, and the wrong one kept as the hostile `BAD_EXTENDED_HEADER` fixture.

**Not verified**: Windows (reserved names, `MAX_PATH`), Java 8 at runtime (JDK 21 with `--release 8`
checks the API, not behaviour), Windows `tar.exe` output, and `mvn clean package` — which this batch
does not need, since no file outside the new package changes, but which will compile it.

## 18. Batch 2 as built

New in package `unarchive`: `ZipCentralDirectory` (EOCD, ZIP64 locator and record, sizes from extra
`0x0001`, raw names, external attributes), `ZipArchiveReader` (`ZipFile` for the streams, our
records for names, types, encryption and method, the two readings cross-checked before pairing,
CRC-32 and size checked at the end of each entry's stream), `FileMask` (copy of `json2csv`'s,
compared with it), `Budget` (§7 limits on bytes written), `UnarchiveRun` (the whole executor,
Spring-free). Batch 1 classes changed in two places only: `ArchiveFormat.sniff` (∩ I30) and new
rules in `UnarchiveException`. **Still no registration and no call site.**

Two test seams, both of the "inject the figure" kind already used by elarxml's disk guard:
`usableSpace` (a disk cannot be filled on demand) and `mover` (a rename cannot be made to fail on
demand). Production uses `File.getUsableSpace` and `Files.move(ATOMIC_MOVE)` with the back-off.

Manifest rows of an archive are kept in memory until it commits (bounded by `maxEntries`: 100 000
rows of a few hundred bytes), so a failed archive never leaves a row.

**Verification**: batch 2 suite **194 assertions**, batch 1 suite **232** re-run green on the batch 2
code. Benign zips from **four real producers** — Info-ZIP 3.0 (UTF-8, no flag), `jar` (flag + data
descriptor), python (flag), python streaming (STORED + data descriptor) — each extracted
**byte-identical to the source tree it was built from**; a real ZIP64 with 66 000 entries (EOCD64
present) and one with a 5 GiB entry (sizes only in the extra field), refused early by the declared
size without writing a byte. Every hostile zip AND every hostile tar of batch 1 run through
`UnarchiveRun`: refused by its own rule, and **`outputDir` empty — no subdirectory, no staging**.
End-to-end over four archives of four kinds; failure in the middle (first committed, rest not,
manifest lists only the first); every precheck refusing before the first extraction; skip, replace,
replace with a hostile archive (old content intact), replace whose rename fails (old moved back);
sweep touching only its own directories; Stop mid-archive; each limit; ratio over the grace and
under it; the disk margin at 5% (refused) and exactly 10% (passes); path length one over and exactly
at the limit; mtimes; warnings; links skipped or refused, and a skipped link with a traversing name
still refused; selection (recursive, case-sensitive, `.done` never selected, a non-archive refused);
manifest bytes (no BOM, CRLF, RFC 4180, one row per file, SHA-256 equal to the file).

**Mutations, on copies, foreground slices**: batch 2 **35 — 33 caught, 2 equivalent**, each opened:
removing the cross-check (equivalent while our parser is right; the compound mutation in §4.2 shows
what it guards) and removing the trailing-slash directory test in the zip reader (the validator
applies the same rule, ∩ I28). **One was caught only after a test was strengthened**: removing the
explicit `layout=flat` refusal still refused through the generic "must be subdir" check, and the
suite compared only the rule — the message naming Gate 0 Q10 could have gone unnoticed (the
CLAUDE.md corollary: a test must tell the new message from the old). Three mutations of mine did not
compile or missed their anchor and were repaired, not dropped. Batch 1's **34** re-run on the cleaned
harness: all caught. The "no sort" mutation is caught because ext4's enumeration order differs from
the sorted order on this fixture set; on a file system that enumerates sorted it would pass, which
is why the sort is stated in the code rather than left to the test.

**An incident, stated**: during the first mutation run the suites left their temporary directories
behind and the disk filled (3 090 directories, 936 KB free). Every mutation result from that run was
discarded; the suites now delete their own run root in a `finally`, and all mutations of both
batches were re-run from zero with 9.7 GB free.

**Corrections to the spec found by tests in this batch**: the per-entry ratio for gzip (∩ I21, struck
through above); the JDK 21 `ZipFile` constructor refusing what Java 8 accepts (§4.2); the
magic-with-bad-checksum case nobody had decided (∩ I30). One fixture of mine was invalid by my own
rules (a `"` in an entry name).

**Not verified**: Windows (reserved names, `MAX_PATH`, rename under a scanner — the back-off is
argued, the seam proves only the rollback logic), Java 8 at runtime, a real Explorer zip or Windows
`tar.exe` archive, `mvn clean package`.

## 19. Batch 3 as built

**Registration, backend**: `WorkflowXmlParser` whitelist, error message and `internal` set;
`WorkflowEngine.internalKind()`; `InternalSteps.run()` dispatch passing `control`. **`runUnarchive`**
translates the 19 parameters (relative paths rebased on `${feedDir}`, `outputDir` defaulting to
`${stepDir}`, malformed numbers refused naming the parameter, never defaulted), runs `UnarchiveRun`,
publishes the nine outputs on every path, and sets 0 / 2 / −997 / 1 explicitly. The manifest goes to
`${stepDir}/unarchive_manifest.csv`. Nothing in the `unarchive` package changed.

**USAGE.md**: "The unarchive step", plus its line in the executor list. The deviation stated since
batch 0 ends here: the guide arrives with the first batch that makes the step usable.

**Verification**:
* **Wiring, 78 assertions**: `runUnarchive`, `pv`, `yes`, `rebaseRel`, `blankToNull` and
  `WorkflowEngine.internalKind` are **lifted verbatim** from the sources by a script (only `private`
  removed so the harness can call them) and compiled with `--release 8` against the real
  `StepExecutor`, `VarResolver`, `RunControl`; the **real parser** is compiled with `model/def`. Every
  exit path, every output, every parameter reaching its own field, Stop, `${feedDir}` rebasing,
  `${stepDir}` default, an unsafe `runId`.
* **Mechanical agreement**, script: spec §10 table == names read by `runUnarchive` (19/19); boolean,
  string and numeric defaults equal across spec, adapter and `UnarchiveRun`; the dispatch line calls
  `runUnarchive` with `control` (`run()` cannot be lifted: it carries Spring dependencies).
* **Exit-code lint, extended**: the original accepted only a literal 0, a ternary with a 0 branch, or
  delegation, so `res.exitCode = code` (used by `filerename` and now here) would be a false alarm.
  Now a variable counts if one of its own assignments can be 0, and a method call is opaque. Run on
  the current code: 30 `run*` methods, none flagged. **Positive controls**: the code BEFORE the objpack
  fix (`fea867e~1`, 28 methods) flags exactly `runObjPack`, as the original lint did; a synthetic
  method that can only return non-zero is flagged.
* **USAGE.md through `docs.html`'s own `render()`** (lifted verbatim, run in node, HTML read with
  jsdom): 39 assertions — section, contents, the XML example shown as text, no raw markdown, 19/19
  parameters equal to the executor and the spec, every stated default equal to `UnarchiveRun`'s, 9/9
  outputs, the four exit codes equal to those set. **The pre-patch guide fails 19**, and a guide with
  one default changed on purpose fails on exactly that default.
* Batch 1 and 2 suites re-run: 232 + 194 green.

**Mutations, wiring: 17, all caught.** **Two were caught only after closing real gaps**: assigning
`maxPathLength` to the wrong field changed nothing because no test proved each numeric parameter
reaches its own limit (now each gets a value that changes the outcome recognisably); and ignoring a
malformed number survived because the configuration cases ran on the HOSTILE feed, which ended in
exit 2 anyway — exit 2 for the wrong reason, the CLAUDE.md corollary again. They now run on a benign
feed and must name the parameter.

**Mistakes of mine caught on the way**: the documentation check's default regex stopped at the first
`.` and compared `*` with the pattern; it then compared `textContent`, where backticks no longer exist,
and checked 5 defaults instead of the 16 the guide states literally (plus `outputDir`, stated in words); and one "pre-patch exit 0" was `tail`'s exit code, not node's.
All three fixed before any result was used.

**Not verified**: `mvn clean package` (first batch that changes `InternalSteps`, `WorkflowEngine` and
the parser — compiled here only as lifted methods and, for the parser, as itself), a run inside the
container, Windows, Java 8 at runtime.

## 20. Batch 4 as built

**Designer** (`designer.html`): the `<option>`; `uaSeed`, called from `updNodeR` like `frSeed`, which
writes the 17 defaults into the step when the executor is chosen and never overwrites a value already
there (`sourceDir` has no default, `outputDir` defaults to the step directory and is left empty); the
panel in three sections — Archives, Output, Safety and limits — with every parameter except `layout`
as a control (∩ I32); hints that follow `format=gz`, `onExisting=replace`, `afterExtract=rename`;
`clientValidate` mirroring the executor's static refusals (`sourceDir` required, whole numbers above
0 unless a `${variable}`, `layout` subdir only, `afterExtract` keep or rename, `onExisting` values,
`outputDir` equal to `sourceDir` with a trailing separator ignored). **`buildXml` needed no change**:
read, not predicted — it emits every `n.params` entry as `<param>`.
**`variables.html`**: eight `PARAM_OPTIONS` keys; `failOnEmpty` and `checkFreeDisk` shared (∩ I31).
**USAGE.md**: two sentences written in batch 3 had become false ("Until the designer offers the
step…", "No designer panel yet") — replaced; the guide check now asserts it does not deny the panel.

**Verification**:
* **Panel, 111 assertions, the REAL template in jsdom** (scripts on, network stubbed, the one
  Thymeleaf rendering the script needs — `th:href="@{/}"` — applied), every control driven through
  **its own handler attribute**. Mechanically: the 18 names the controls write + seeded `layout` ==
  the 19 names `runUnarchive` reads; every seeded value == `UnarchiveRun`'s default; every select's
  options == the values `configure()` accepts (read from the Java source, not assumed), booleans only
  true/false, no `delete` or `flat` offered; `PARAM_OPTIONS` == the panel's options with the
  executor's default marked; placeholders == defaults; emptying a field removes the parameter.
* **Designer → parser → run**: the XML `buildXml` emits is read by the real `WorkflowXmlParser`
  (`script=null`: recognised as internal) and its parameters run through `runUnarchive` lifted
  verbatim, on a feed with a real tar: exit 0, extracted.
* **The pre-patch template fails** (unarchive not offered). USAGE.md: 41 assertions; the batch-3
  guide fails exactly the two new staleness checks.
* Batch 1–3 suites unaffected (no Java changed in this batch).

**Mutations, panel: 19 — 18 caught, 1 equivalent.** The equivalent: rendering a blank `preserveMtime`
with neither option selected — the browser then shows the first option, which is the default, so the
display is identical (it relies on the default being listed first, as it is for every boolean select
here). **One was caught only after closing a real gap**: removing the re-render from the format
select's handler survived, because the tests set the parameter and re-rendered by hand — no test
changed the select itself and looked for its hint. **One "caught" was caught for the wrong reason**: a
control writing a misspelt name crashed the suite before it printed the failure that named it; the
suite now records a throw as a failure and prints everything found.

**Not verified**: a real browser (jsdom only), `mvn clean package`, a run inside the container.

## 21. Linux hosts

### 21.1. The requirement and the decision

New requirement (2026-10-03): extraction must be fully compatible with a Linux environment. Gate 0:
**L1 answered (a)** — Linux and Windows both supported, **everything built so far kept**; and the
rule set is chosen by **detecting the server's OS automatically**, not by a parameter (Fabiano's
decision). The trade-off, stated once: the same workflow can extract different files on a Windows
test machine and on a Linux server. It is made visible rather than avoided: the first log line names
the host and the rules, and every run publishes `${hostRules}`.

L2–L6 were recommendations; adopted as defaults since the instruction was to apply what Linux needs:
L2 links created on Linux, confined; L3 `rwx` preserved, never setuid/setgid/sticky or owner;
**L4 corrected by measurement** (below); L5 FIFO/devices/sparse unchanged; L6 non-UTF-8 tar names still
refused (Java cannot create arbitrary byte names reliably). L2 and L3 are batch L2.

### 21.2. Measured on this Linux host (GNU tar 1.35, Info-ZIP unzip 6.0 Debian, ext4)

* GNU tar extracts **as is**: `report_10:41.txt`, `a<b>c|d?e*f"g.txt`, `CON`, `nul.txt`, `aux.c`, names
  ending in `.` or space, `Makefile` and `makefile`, `Dir/` and `dir/`, `a\b.txt`, `C:x`,
  `\lead.txt`, and a name containing a newline.
* NAME_MAX is **255 bytes**: a 256-byte name fails (`File name too long`), so does 128 × `é`
  (256 bytes); GNU tar exits 2 on it. PATH_MAX 4096. `os.name` = `Linux`.
* **unzip converts `\` to `/` only for a zip whose "made by" host is 0 (MS-DOS)**, with a warning;
  made-by NTFS (11), VFAT (14) and Unix (3) keep it literally. The batch-0 recommendation ("`\`
  separates in zips, as unzip does") was wrong for most zips and is corrected here.
* `jar` (Java) declares host 0 together with the UTF-8 flag, and **Debian's unzip mangles that zip's
  non-ASCII names** (`perché.txt` → `perch├й.txt`): it applies its OEM conversion to DOS-made zips
  even when they declare UTF-8. Python declares host 3, and unzip reads it correctly.

### 21.3. The rules

`HostRules.detect(os.name)`: starts with `Windows` → **WINDOWS**; `Linux` → **LINUX**; anything else
→ refused (CONFIGURATION). macOS is refused on purpose: its default file system is case-insensitive
like Windows yet allows what Windows refuses, so neither rule set describes it.

**WINDOWS**: §3–§9 exactly as built. The existing signatures (`EntryName.validate(raw, dir)`,
`checkLength(base, n, max)`, `new NameIndex()`) still mean the Windows rules, so batches 1–2's suites
run unchanged — the batch 2 suite gained one line setting the host to Windows, and no assertion.

**LINUX** — what changes:

| Rule | Windows | Linux |
|---|---|---|
| `\` | separator | **tar: a character** (GNU tar); **zip: separator only if made on MS-DOS** (unzip) — ∩ I34 |
| drive letter, `:`, `< > " \| ? *`, control chars | refused | kept (NUL still refused) — ∩ I33, I39 |
| reserved device names, trailing `.`/space | refused | kept — ∩ I33 |
| case-only differences (files, directories, subdirectory names) | refused | distinct — ∩ I33, I40 |
| segment length | 255 UTF-16 units and 255 bytes | 255 UTF-8 bytes (NAME_MAX) — ∩ I38 |
| `maxPathLength=auto` | 259 UTF-16 units | 4096 UTF-8 bytes — ∩ I37 |

What does **not** change on Linux — deliberate restrictions where GNU tar is more permissive, kept
because they are about safety or ambiguity, not about Windows (∩ I35, I36): a leading `/` is refused
(GNU tar strips it with a warning); `a/./b` and `a//b` are refused (GNU tar normalises them); an exact
duplicate is refused (GNU tar keeps the later one); `..` is refused wherever the separator is.

### 21.4. Intersections

| # | Case | Rules that meet | Winner |
|---|---|---|---|
| I33 | `:`, `CON`, `a.`, `A.txt`+`a.txt` on Linux | Windows-only refusals vs a Linux host | Linux: kept as names (GNU tar parity); Windows: refused, unchanged |
| I34 | `\` in a name on Linux | separator vs character | tar: character; zip: separator iff made-by host 0; Windows: always separator |
| I35 | leading `/`, `a/./b`, `a//b` on Linux | GNU tar parity vs refuse-not-normalise | refused on both hosts |
| I36 | exact duplicate on Linux | GNU tar "later wins" vs §5 | refused on both hosts |
| I37 | `maxPathLength` | one number vs two units | `auto` = the host's limit; a number = that many units of the host |
| I38 | segment length | UTF-16 vs bytes | Windows keeps both checks; Linux bytes only (NAME_MAX) |
| I39 | control characters | log safety vs Linux legality | Linux keeps them in names (GNU tar does, measured); the log still escapes them |
| I40 | `a.tar` + `A.tar` | subdirectory collision key | the host's key: Windows case-insensitive, Linux exact |
| I41 | host neither Windows nor Linux | detection | refused before anything is read |
| I42 | zip made on MS-DOS with the UTF-8 flag (jar) on Linux | unzip parity vs the zip's declaration | the declaration (∩ I20): the step writes `perché.txt` where Debian's unzip writes `perch├й.txt` |

### 21.5. Batch L1 as built

`HostRules` (new); `EntryName.validate(raw, dir, rules, backslashSeparates)` and
`checkLength(base, n, max, rules)` (new overloads; Linux path added, Windows path untouched);
`NameIndex(HostRules)`; `ZipCentralDirectory.Record.madeOnDos()` and `ZipArchiveReader.Entry.backslashSeparates`;
`UnarchiveRun.hostOs` (default `os.name`), `hostRules`, `maxPathLength` 0 = auto, the log line;
`runUnarchive` accepts `maxPathLength=auto` and publishes `hostRules`; the panel seeds `auto` (the field
is now text, since a number field cannot hold it), validates it, and says the rules follow the server;
USAGE.md "Windows and Linux servers".

**Verification**: the Windows suites unchanged and green (232 + 194). **Linux suite, 138 assertions**:
GNU tar / unzip parity on every benign fixture of batches 1–2 and on new Linux fixtures; every
hostile fixture either still refused by its rule with nothing left behind, or — the Windows-only
ones — byte-identical to GNU tar or unzip; the backslash rule on zips made by FAT, NTFS, VFAT, Unix;
NAME_MAX and PATH_MAX boundaries in bytes; detection, refusal of other OSes, the log line.

### 21.6. Batch L2: links, permissions, folder times (Linux hosts only)

**Measured** (GNU tar 1.35 run as a non-root user and, equivalently, as root with
`--no-same-permissions --no-same-owner`; Info-ZIP unzip 6.0 as root and as a non-root user; umask 022):

* GNU tar applies the archive's permission bits **minus the umask** (`777` → `755`) and drops
  setuid/setgid/sticky (`4755` → `755`); **unzip applies them as stored** (`777` stays `777`) and
  also drops setuid. Root with the two flags gives exactly the non-root result.
* GNU tar creates symlinks as stored, **including `../outside`**, and dangling ones; hard links share
  an inode; a `555` folder gets its mode **after** its content, and folder times are restored.
* `rm -rf` of such a tree **fails** for a non-root user (`Permission denied` on the read-only folder).
* A same-parent directory rename keeps the renamed folder's time; Java's `Files.createDirectory` and
  `Files.write` create with `0777`/`0666 & ~umask`, so the umask is read from the fresh staging folder.

**The rules**, applied only when the host is Linux (on Windows nothing changes: links refused or
skipped as before, no modes):

* **Symlinks** (tar `2`; zip entries with Unix mode `S_IFLNK`, whose content is the target) are
  created when they stay inside the archive's folder. `LinkGuard.lexical` refuses at once an empty
  target, NUL, an absolute target, or a climb above the root *as text*; `LinkGuard.verify`, once every
  entry exists and before the commit, resolves each link **through the archive's other links** inside
  the staging folder, never outside it, and refuses an escape or a loop (more than 40 hops). The text
  check alone misses chains — `d/up -> ..`, `d/up2 -> up/..` — and so does a dangling tail —
  `d/m -> up/nothere/../../x`; both are fixtures.
* **Nothing is written through a link**: a symlink is registered in `NameIndex` as a file, so
  `link/x` after `link` is a file/directory conflict. (Without that, measured by mutation: the write
  went through the link into the folder it points to.)
* **Hard links** (tar `1`) only to a regular file already extracted from the same archive; the
  manifest row carries the target's size and hash.
* **Modes**: the permission bits only — `& ~umask` for a tar, as stored for a zip (∩ I46) — never
  setuid, setgid, sticky (Java's `PosixFilePermission` cannot even express them) or owner.
* **Folders**: modes and times applied after everything else, deepest first, the archive root's on
  the staging folder itself (it keeps them across the commit's rename).
* **Cleanup**: deleting a staging folder, a replaced tree or a leftover gives the owner `rwx` back on
  each folder before entering it; links are never followed.

**Intersections**:

| # | Case | Rules that meet | Winner |
|---|---|---|---|
| I43 | `link`, then `link/x` | GNU tar writes through the link vs "nothing written through a link" | refused (`FILE_DIRECTORY_CONFLICT`) on both hosts |
| I44 | a symlink's manifest row | manifest format unchanged vs links being entries | listed with `bytes` 0 and an empty `sha256`; a hard link's row carries its target's size and hash |
| I45 | `../outside`, `/etc/passwd`, an escaping chain | GNU tar parity vs confinement | refused (`LINK_ESCAPE`), as Python's `data` filter refuses them |
| I46 | permission bits | GNU tar (minus umask) vs unzip (as stored) | per format: tar like GNU tar, zip like unzip; setuid/setgid/sticky/owner never |
| I47 | `onUnsupportedEntry=skip` on Linux | skip vs links being supported there | applies to devices and FIFOs only; an escaping link is refused even with `skip` |
| I48 | a read-only folder in the archive | applying its mode vs extracting into it and deleting it later | mode applied after the content; cleanup restores owner `rwx` before deleting |

**Verification**: Windows suites unchanged (232 + 194); Linux L1 suite 140 (one expectation changed
on purpose: a confined zip symlink is now created, as unzip does); **links suite 77** — GNU tar's own
archive of a real tree identical to GNU tar's extraction in types, link targets, content, modes, file
and folder times and hard-link inode groups; `zip -ry` of the same tree identical to unzip's; 12
hostile fixtures refused by their rule with nothing left, also under `skip`; Windows unchanged on the
same archives; the FIFO still under the policy; `LinkGuard` units; manifest rows; the umask read
equals the shell's. **Run as a non-root user** (it cannot be seen as root): extraction with a `555`
folder, `replace` over it, a failed commit after modes were applied, the sweep of a leftover — 6/6;
with the cleanup's `chmod` removed the same program fails 3 of them (positive control).

**Mutations, 17, all caught**, two of them only observable as a non-root user (folder modes applied
before the content; cleanup without `chmod`). No mutation for "setuid applied": Java's NIO permission
API has no way to set it, so the strip is structural.

## 22. Opt-in deletion and nested archives

### 22.1. Gate 0 (2026-10-04)

* **F1 — `layout=flat` stays excluded.** (Answer "lasciamo flat", read as "leave flat as it is";
  stated here so that a different reading is corrected rather than built on.)
* **D1 — FIFOs and devices: unchanged** — refused, or skipped with `onUnsupportedEntry=skip`, on
  both hosts. They appear only in system backups; a FIFO can block a reader forever, a device needs root.
* **Formats** — zip, tar and gz are what is needed; the others stay recognised and refused by name.
* **Non-UTF-8 tar names** — stay refused (confirmed): Java cannot create arbitrary byte names reliably.
* **C1 — `afterExtract=delete`, opt-in, with the proposed rules** (§22.3). ~~Q8: delete not offered~~.
* **R1–R6 — nested archives, opt-in, the recommended answers** (§22.4).

### 22.2. Intersections added

| # | Case | Rules that meet | Winner |
|---|---|---|---|
| I49 | `afterExtract=delete` and an archive that failed, was stopped, or was skipped | delete vs "nothing changes unless committed" | deleted only after ITS commit; failed, stopped and skipped archives are kept |
| I50 | a failed deletion | the extraction is committed vs the step reporting success | the step fails (`COMMIT_FAILED`) saying "extracted and committed, but deleting the archive failed"; the extraction stays |
| I51 | a `.docx` / `.xlsx` / `.jar` inside an archive, with `nested=extract` | "content decides the format" (§3) vs nested detection | nested archives are selected **by name** (`pattern`); content decides only the format of what was selected. Office files are zips and are never opened |
| I52 | a nested archive beyond `nestedDepth` | recurse vs limit | kept as a file, with a WARNING naming it; not refused |
| I53 | limits with nested archives | per archive vs per tree | cumulative over the outer archive and everything inside it (the 42.zip defence) |
| I54 | `afterExtract` with nested archives | outer vs inner | `afterExtract` applies to the outer archive only; a nested archive is removed after its own extraction (R4) |
| I55 | a gzip stream's ratio with cumulative limits | tree-wide byte count vs per-stream ratio | size limits cumulative; a stream's ratio counts only the bytes THAT stream produced (found while building R: a 14 KB nested gzip after 12.6 MB would have read as ~850:1) |
| I56 | a nested archive selected by the pattern but without an archive extension (`payload.bin`) | `baseName` vs the file's own name | refused (`SUBDIR_COLLISION`) saying it has no archive extension, and what to do |

### 22.3. Batch C: `afterExtract=delete` (as built)

`afterExtract` accepts `delete`. The archive is deleted only at the end of `one()`, after its commit —
a failed or stopped extraction leaves before that point, and `onExisting=skip` returns earlier still
(∩ I49). A failed deletion fails the step with `COMMIT_FAILED`, the extraction staying committed
(∩ I50). A symlink selected as an archive is removed as a link. Logged per archive. Designer: the
option, a warning that there is no undo, validation; `PARAM_OPTIONS`; USAGE.md (the line "no deletion
of the archive" removed, since it became false).

**Verification**: batch 2 suite 199 (+5: deleted after commit; refused archive kept; skipped kept;
a/b/c — committed deleted, refused kept, unreached kept; Stop — in-progress kept, earlier deleted);
non-root program 7 (+1: a deletion that fails — source folder read-only — fails the step and keeps the
extraction; root cannot make a deletion fail); Windows 232, Linux 140, links 77, wiring 92, panel 117,
guide 47. Three existing expectations inverted by the decision, not deleted (delete refused → delete
accepted; "no option offers delete" → "delete offered, flat not"; validation now flags an invented
value instead). **Mutations 10, all caught** — the swallowed-deletion-failure one only by the non-root
program.

### 22.4. Batch R: nested archives (specification, to build)

* **Parameter** `nested` = `none` (default) | `extract`; `nestedDepth` default 3 (R2). The outer
  archive is depth 0.
* **Selection by name (R1, ∩ I51)**: an entry is a nested archive if it is a regular file whose name
  matches the step's `pattern` (case-sensitive, the same mask). The format of a selected entry is then
  decided by its content (`auto`, whatever `format` says for the outer archives); an entry selected by
  name that is not an archive refuses the outer archive, as a top-level one would (∩ I6).
* **Placement (R3)**: `P/inner.zip` is extracted into `P/inner/` (the same `baseName` rule) inside the
  outer archive's staging folder. If `P/inner` already exists in the archive, the outer archive is
  refused naming both (`SUBDIR_COLLISION`).
* **Removal (R4, ∩ I54)**: the nested archive file is removed once its own extraction succeeded;
  `afterExtract` applies to the outer archive only.
* **Depth (∩ I52)**: an archive found at a depth beyond `nestedDepth` is kept as a file and named in a
  WARNING.
* **Limits (R5, ∩ I53)**: one `Budget` for the outer archive and everything inside it — entries,
  `maxArchiveMb`, per-entry size, ratios. A nested archive's own bytes count when written and again
  as the bytes extracted from it.
* **Atomicity (R6)**: everything happens in the outer archive's staging folder; any failure at any
  depth fails the outer archive, whose staging is removed. Nothing partial is committed.
* **Names and links**: nested entries obey the same host rules; path lengths are counted against the
  final location. On Linux, a nested archive's links are confined to **its own** folder (`P/inner/`),
  hard links to files of the same nested archive.
* **Manifest**: rows for nested content name the entry as `P/inner.zip!entry` (the jar-URL
  convention); the removed nested archive has no row of its own.

### 22.5. Batch R: nested archives (as built)

`UnarchiveRun.nested` / `nestedDepth`; `expandNested` walks breadth-first over the regular files each
context wrote, selects by name with the step's `FileMask` (names ending `.done` excluded, as at the top
level), decides the format by content with `auto`, extracts into the sibling folder inside the same
staging folder, deletes the nested file and its manifest row, and queues the new context. A context
(`Ctx`) now carries its archive, its log label (`outer.zip!inner.tar.gz!deep.zip`), its manifest
prefixes and its depth; with `nested=none` they equal what they were. `Budget.startStream()` (∩ I55).
Nested links are verified against their own folder before the commit; nested folder modes join the
outer list and are applied with it. Adapter: `nested`, `nestedDepth` (refused when malformed),
`${nestedExtracted}`. Designer: the select, the depth, a hint, validation, two seeded defaults;
`PARAM_OPTIONS`; USAGE.md "Archives inside archives" (and the line "no archive inside an archive is
opened" removed).

**Verification**: nested suite 30 — two levels from real tools (Info-ZIP zip of a GNU `tar -czf` of a
zip) extracted in place and compared with the source files; a `.docx` (a zip) never opened; off by
default; depth limit with the kept archive named in a warning; collision; a hostile nested archive
refusing the outer one with nothing committed (and not deleted under `afterExtract=delete`); a file
named like an archive that is not one; cumulative size limit (6 MB fits 8 MB, the tree does not); a
nested gzip bomb; ∩ I55; manifest rows; configuration; on Linux a nested link escaping its folder, a
nested chain escaping it only when followed, a confined link; outer `format=zip` with nested tar.gz;
Stop during nested extraction; ∩ I56. Existing suites unchanged and green (Windows 232, batch 2 199,
Linux 140, links 77, non-root 7); wiring 101, panel 130, guide 52, mechanical 20.

**Mutations: core 14, adapter 3, panel 5 — all caught.** Two gaps were closed *before* running them:
the nested-link verification needed a nested chain fixture (the escaping link was already stopped by
the text check), and I55 needed its own fixture. **One panel mutation survived first and exposed a
broken test of mine**: checks on `document.body.textContent` include the designer's inline script
source, which contains every hint string — so they passed whatever the page showed. Fixed (the body
without `<script>`); the batch-L1 "rules follow the server" check had the same flaw (its mutation had
been caught only because it changed the script text too) and now passes for the right reason.
