# tiffcompress — scan a directory of TIFFs, and later recompress them

Batch 0. Specification only: no code, no dependency, no registration. Written after reading the
existing executors rather than from a picture of them, and self-contained — everything needed to
implement batch 1 is here.

## 1. Why this exists, and what would kill it

A feed of scanned documents is Base64-embedded into an INDX. If the images are stored uncompressed
the INDX carries roughly four bytes of text for every three bytes of picture, and the budget that
decides when a batch rolls is spent on padding. Recompressing the images before they are embedded
would shrink both the staging directory and the delivered INDX.

Three things can kill it, and two of them are not code:

* **The corpus may already be compressed.** If most of these files are already CCITT G4 there is
  nothing to gain and no executor to write. Nobody has measured this. That is the entire purpose of
  batch 1.
* **The dependency may not be available.** `javax.imageio` in Java 8 has no TIFF plugin — it arrived
  in Java 9 — so re-encoding needs TwelveMonkeys `imageio-tiff` or JAI, neither of which is in
  `pom.xml`, and dependencies resolve only from the internal Nexus. This is a procurement question,
  not a design one. §9.
* **Records management may forbid it.** A recompressed document is bytes the source system does not
  have, and the INDX carries the SHA-256 of what is embedded. Whether a legal archive may hold a
  re-encoded copy is a decision for the records people, and it can close the whole thing regardless
  of the numbers. §9.

Batch 1 answers the first and needs neither of the other two.

## 2. The executor

`tiffcompress`, internal, LOCAL directories only.

| parameter | default | meaning |
|---|---|---|
| `mode` | `SCAN` | `SCAN` or `COMPRESS` |
| `directory` | — | the directory to read, required |
| `recursive` | `false` | descend into subdirectories |
| `maxFilesScanned` | `1000` | how many files are OPENED and parsed; `0` = no limit |
| `scanOrder` | `RESERVOIR` | `RESERVOIR` or `DIRECTORY`; see §5 |
| `sampleSeed` | `0` | seed for `RESERVOIR`; `0` picks one and reports it |
| `reportFile` | `tiffscan.csv` | written to the **step** directory, as `elarcheck` writes its findings |

`COMPRESS` adds its own parameters in batch 2 (§8). They are named there so the panel is designed
once, but nothing implements them in batch 1.

**`mode=SCAN` is the default even though the executor is called `tiffcompress`.** Pointing it at a
directory and getting a measurement rather than a rewrite is the conservative reading of an ambiguous
configuration, and it matches every other new behaviour in this repository being off until asked for.

## 3. Two packages, because the read-only property is worth keeping

`ELAR_CHECK_EXECUTOR.md` §307: `elarcheck` is read-only **by construction**, proved by a scan that
finds no `FileOutputStream`, no `Files.write` or `Files.delete`, no `renameTo`, no `createNewFile`,
no `FileWriter` and no `.delete()` anywhere in the package, plus a test asserting that the inspected
directory's file list and modification times are unchanged after a run. That is what makes it safe to
aim at a live delivery folder. A writer in the same package ends that guarantee permanently.

So:

* `com.legalarchive.orchestrator.tiff` — `TiffInfo`, `TiffScan`, `TiffScanReport`. **No write API.**
  A new `tools/scan_tiff_readonly.js`, modelled on the elarcheck scan, asserts it, and — per the
  standing rule — the scan must be shown capable of failing by planting a write and watching it fire.
* `com.legalarchive.orchestrator.tiffpack` — the rewriting half, batch 2. Everything that can create,
  replace or delete a file lives here and nowhere else.

`InternalSteps.runTiffCompress` dispatches on `mode`. One executor in the designer, as asked; two
packages underneath, so the guarantee stays provable instead of promised.

Both packages must be free of Spring and compile standalone with `javac --release 8`, like `elar` and
`elarcheck`, so the suites run in the sandbox.

## 4. What the scanner reads

TIFF keeps everything the scan needs in the header and the IFD chain. **No pixel data is read.**

Header: eight bytes. `II` (little-endian) or `MM` (big-endian) at 0; the magic at 2, which is **42**
for TIFF and **43** for BigTIFF; the offset of the first IFD at 4. Each IFD is a 2-byte entry count,
then 12 bytes per entry (tag, type, count, value-or-offset), then a 4-byte offset to the next IFD, or
zero. Following that chain enumerates the pages.

Constructed and re-parsed by hand while writing this: a two-page little-endian TIFF carrying the six
tags below is **164 bytes** in total. Per page the scanner touches a few hundred bytes, which is what
makes scanning viable at all at this scale — the cost per file is a couple of seeks, not a read.

Tags collected per page:

| tag | name | why |
|---|---|---|
| 259 | Compression | the answer: 1 none, 2 CCITT 1D, 3 T.4/G3, **4 T.6/G4**, 5 LZW, 6 old JPEG, 7 JPEG, 8 and 32946 Deflate, 32773 PackBits |
| 258 | BitsPerSample | 1 means bilevel, which is the only thing G4 can encode |
| 262 | PhotometricInterpretation | 0/1 greyscale-or-bilevel, anything else is not a G4 candidate |
| 277 | SamplesPerPixel | >1 is colour: not a G4 candidate whatever 258 says |
| 256, 257 | ImageWidth, ImageLength | needed in batch 2 to prove a rewrite preserved the image |

**Recorded, never inferred:** a file whose pages do not agree on tag 259 is reported as `MIXED` with
the set of values, not collapsed to its first page. **BigTIFF (magic 43) is detected and reported as
its own category, explicitly not parsed** — its IFD layout differs, and silently skipping it would
remove files from the denominator without saying so. Same for a file that is not a TIFF at all, a
truncated file, and an IFD chain that loops: each is its own outcome in the report, and the outcomes
must sum to the number of files opened. That sum is the cheapest assertion that the scan did what it
claims.

## 5. Sampling, because three million files is the actual problem

`/Proteo/DOC/PDF` and `/Proteo/DOC/TIFF` hold three million files between them. Two separate costs:

**Enumerating** the directory. `File.list()` materialises a `String[]` of every entry — three million
strings before the first file is opened. `Files.newDirectoryStream` iterates lazily and is what this
uses. Enumeration is cheap per entry but not free across three million.

**Opening and parsing.** Bounded by `maxFilesScanned`, and this is the expensive part.

The sample is not a preview: it is the proportion that decides whether anything gets built. So how it
is drawn matters more than its size.

* **`DIRECTORY`** — the first N entries the filesystem hands back. Fast, one truncated enumeration,
  and **biased in exactly the wrong way**: directory order tracks creation order, so the first N are
  one feed, from one source system, scanned in one period by one generation of hardware — the very
  variables that determine which compression a file carries. A proportion from this sample is not a
  proportion of the corpus.
* **`RESERVOIR`** — one full lazy enumeration keeping a reservoir of `maxFilesScanned` names, then
  opening those. Unbiased, bounded memory, one pass, and it opens no more files than `DIRECTORY`
  does. **The default**, because the number's only purpose is to be a proportion.

The report states which order was used, the seed, how many entries were enumerated and how many were
opened. A number from this scan cannot be quoted without its sampling method attached.

## 6. The report

Written to the step directory, like `elarcheck`'s findings file, and also summarised in the step log.

**By count and by bytes, both, always.** A million small files already in G4 and ten thousand large
uncompressed ones give "99% compressed" by count and the opposite by bytes. The byte column is the
one that decides whether the branch is worth building; the count column alone would be misleading in
a way that is easy not to notice.

Per row: compression name and code, files, pages, total bytes, share of files, share of bytes, and
how many of those are G4-eligible — bilevel by 258/262/277 and not already G4. The last column is the
estimate this whole exercise exists to produce.

Counters published as run variables so a gate can branch without parsing the report:
`filesEnumerated`, `filesOpened`, `filesUnreadable`, `bytesScanned`, `filesAlreadyG4`,
`bytesAlreadyG4`, `filesG4Eligible`, `bytesG4Eligible`, `filesBigTiff`, `filesNotTiff`.

## 7. Registration — the six places

Confirmed by reading, at `65d837d`: `WorkflowXmlParser` line 89 (the whitelist), line 91 (the error
message, which lists every kind by hand), line 94 (the `internal` set), `WorkflowEngine.internalKind`
line 1027, `InternalSteps` line 98's dispatch chain, and the designer dropdown at line 938 with its
panel at 1412 and its `clientValidate` block at 1900. All six, or the executor is unreachable in a way
that only shows up at runtime.

## 8. Batch 2, named now so the panel is designed once

Not implemented in batch 1. Its shape is decided by batch 1's numbers, which is the point of the
split: bilevel uncompressed or LZW means G4 and a Nexus dependency; greyscale or colour uncompressed
means G4 does not apply at all and the candidate is Deflate, which `java.util.zip.Deflater` provides
with no dependency; mostly-already-G4 means no batch 2. Writing the compressor now would mean
choosing one of the three before knowing which.

**G4 will not be hand-rolled.** MMR coding is fully specified and is also the kind of code whose bug
yields an image that decodes correctly in some viewers and wrongly in others. If the target is G4 it
is the library or nothing.

Parameters, and the rules they must obey:

* `outputDirectory` — empty means overwrite in place.
* **The original is never removed until the compressed file is complete.** Write to a temporary name,
  close it, re-open and verify it, and only then replace. `elar.AtomicOutput` is a candidate for this
  and its constructor is to be **read** before assuming its semantics fit — it was built around INDX
  naming and may not.
* **Overwrite must be announced.** A warning block appears in the panel only when overwrite is
  selected, which means the control redraws the panel — and `tools/scan_panel_redraw.js` will enforce
  that, since a control that changes the panel's shape without redrawing is exactly what it looks for.
  A WARN line in the step log at run start as well, the way `deleteContentAfterEmbed` announces itself
  before the first deletion rather than after it.
* **If the rewrite does not reduce the file, the original bytes are kept unchanged** and the outcome
  is reported as a fallback. Non-negotiable.
* Bilevel only. Nothing lossy, in any mode, at any setting.

## 9. Open questions — these are yours

1. **Is TwelveMonkeys `imageio-tiff` on the internal Nexus?** With `imageio-core`, `common-lang`,
   `common-io`, version pinned explicitly since the Spring Boot 2.7 BOM does not manage it. If the
   answer is no and the corpus turns out to be bilevel, batch 2 does not exist in this form. Nothing
   in batch 1 depends on the answer.
2. **May an archived document be bytes the source system does not hold?** The INDX carries the
   SHA-256 of what is embedded, so recompressing changes the digest that goes into the archive. A
   records-management decision, not a technical one, and it can close the branch whatever the numbers
   say. Worth asking in parallel with batch 1 rather than after it.
3. **How deep must the losslessness check in batch 2 go?** Re-reading the header and comparing page
   count and dimensions is cheap and catches a structurally broken rewrite. Comparing decoded pixels
   is the only thing that proves nothing was lost, and it costs a full decode of both files. This
   decides batch 2's runtime and is not mine to choose.

## 10. Batch 1 deliverable

`TiffInfo` and `TiffScan` in `orchestrator.tiff`, the report, the executor and its six registrations,
the panel, `USAGE.md`, and `tools/scan_tiff_readonly.js`.

Verified in the sandbox: fixtures built **byte by byte in the test** — every compression code, mixed
compression across pages, BigTIFF, a file that is not a TIFF, a truncated file, a looping IFD chain —
with no binary sample committed. Assertions that the outcomes sum to the files opened, and that
`RESERVOIR` with a fixed seed is reproducible and, over a synthetic corpus with a known distribution,
recovers that distribution while `DIRECTORY` does not. Panel driven through its own handlers. Every
suite and scan proved capable of failing before it is filed.

`mvn clean package` stays the gate and cannot be run here.
