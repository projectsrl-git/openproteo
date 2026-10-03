# unarchive — Batch 2: zip, limits, staging and commit, manifest

Gate 0 Q4–Q12 confirmed with the recommended defaults. `UnarchiveRun` now does the whole job end to
end against real files; still unregistered, no call site. Spec `.claude/UNARCHIVE_EXECUTOR.md` §18.

## What is in it

* `ZipCentralDirectory` — our own reading of the central directory (ZIP64 included) for raw names and
  external attributes, which Java 8 does not expose.
* `ZipArchiveReader` — `ZipFile` for the streams, our records for everything else; the two readings
  must agree on count and names before they are paired; names by four rules (flag, `0x7075`, valid
  UTF-8, IBM850); symlinks recognised; CRC-32 and size checked at the end of each entry.
* `FileMask` (copy of `json2csv`'s, compared with it), `Budget` (limits on bytes written).
* `UnarchiveRun` — configuration refusals, sweep of its own leftovers, selection, prechecks over every
  archive before the first is extracted, one staging directory per archive, one rename to commit,
  `replace` that puts the old directory back if the rename fails, manifest, Stop.

## Found by tests, corrected in the spec (struck through)

* **The per-entry ratio cannot be computed for gzip.** The decompressor reads ahead: 30 MB of zeros
  compress to ~30 KB, consumed whole before the first entry, so every entry read as an infinite ratio
  and even `maxRatio=5000` refused it. Gzip now uses the whole-stream ratio only; zip keeps both.
* **JDK 21 refuses encrypted and method-12/14 zips in the `ZipFile` constructor; Java 8 does not.**
  Those checks now run on our own records first, so the refusal has the same rule on both JDKs.
* **A tar magic with a bad checksum** was "unrecognised" at detection; now it is a corrupted tar,
  refused `BAD_CHECKSUM` by the reader (∩ I30).

## What the zip cross-check is worth, measured

A one-byte error in our parser's name offset, with the cross-check removed, extracts the archive
silently under wrong names (`erché.txtU`). With the cross-check, the same error is a refusal.

## Verification

194 assertions (batch 2) + 232 (batch 1, re-run). Zips from Info-ZIP, `jar`, python and python
streaming, byte-identical to their source tree; real ZIP64 (66 000 entries; a 5 GiB entry refused by
its declared size before a byte is written). Every hostile zip and tar refused by its rule through
`UnarchiveRun` with `outputDir` left empty.

Mutations: batch 2, 35 — 33 caught, 2 equivalent (opened and argued in §18); one caught only after
the flat-refusal test was made to check the message, not just the rule. Batch 1's 34 re-run: all
caught.

**Incident**: the suites' temporary directories filled the disk during the first mutation run (3 090
of them, 936 KB free). All results from that run were thrown away; the suites now delete their run
root, and both mutation sets were re-run from zero.

## Not verified

Windows, Java 8 at runtime, a real Explorer zip or `tar.exe` archive, `mvn clean package`.
Registration, `runUnarchive` and the exit-code lint are batch 3; the panel batch 4; USAGE.md batch 5.
