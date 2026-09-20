# objpack — Batch 5: compression (Gate 0 9.4 answered)

Gate 0 9.4 is answered: follow the specification and allow compression where it permits it. gzip is
implemented; bzip2 and xz are refused with the reason.

## The name, which the specification does state

**Corrected 2026-09-19.** This note originally claimed the specification contradicted itself here
and that the code had to choose a name. That was overstated. §3.5 says gzip is *"resulting in a
`.tar.gz` file"*, and names `.tar.bz2` and `.tar.xz` for the other two; §2's pattern ends in `.*`,
a wildcard, so those names satisfy it without strain and its `.tar` examples are the uncompressed
case rather than a rule against the rest. There was no conflict to resolve — only one I had read
into it.

The `.md5` keeps its own name and holds the hash of whatever is delivered.

Naming a compressed archive `.tar` anyway was considered and rejected. It would often work — GNU
tar and bsdtar sniff the magic bytes — and often not: anything parsing ustar headers directly, which
includes commons-compress, a strict system tar, and `UstarReader` here, finds nothing where the
header should be. Which one Transarch uses is undocumented, and the failure modes are not
symmetric: a wrong extension is rejected at the naming validation, early and visibly, while a
compressed file wearing a `.tar` name passes size and checksum and breaks at extraction, possibly
after the source has been decommissioned.

## The platform limit, measured

`java.util.zip.GZIPOutputStream` is in the JDK. `BZip2OutputStream` is not, `commons-compress` is
not on the classpath, `org.tukaani.xz` is not, and `commons-compress` is not in `pom.xml` — all four
checked by loading the classes rather than assumed. So bzip2 and xz would each mean a new dependency
that cannot be confirmed against the internal Nexus from here. They are **refused with that reason**
rather than silently downgraded to gzip or quietly accepted and ignored.

## How it is built

The compressor wraps the tar stream as it is produced, so no uncompressed intermediate is ever
written — the same reasoning that keeps the objects from being staged. The finished package is then
read back **through the decompressor**, so what is verified is the file that will actually be
delivered rather than something that never existed on disk. `UstarReader` gained a sequential
stream reader for that, with a `skipFully` that does not trust `InputStream.skip` to move everything
it is asked to.

**An `ftpsend` mask of `*.tar` stops matching a compressed package.** That is a real operational
trap, so it is called out in three places: the panel warns in orange the moment gzip is selected,
the guide states it as the first consequence, and the mask failing to match fails that step — which
is the good outcome, since it stops rather than delivering half a package.

## Verification

**113 assertions green** — 103 in `PackSuite`, 10 cross-reader on the compressed package: `file`
identifies it as gzip, `gunzip -t` validates the stream, GNU tar lists the same six members in the
same order and extracts them byte-identical, the `.md5` matches `md5sum` of the `.tar.gz`, the
compressed file is smaller than the plain one, and no uncompressed `.tar` is left behind.

**26 mutations, all caught.** Three did not pass first time and none of the three was a code defect:

* One mutation **did not compile** — it was malformed, built by a bad expression in the runner.
  Repaired rather than counted.
* **The md5 gap was real.** Hashing the wrong file survived, because `PackSuite` only checked that
  the `.md5` file matched what the step reported — self-consistent whatever was hashed. The bash
  cross-check did test it, but mutations do not run bash. The suite now hashes the delivered archive
  independently.
* **`skipFully` needed forcing, and the first mutation of it was equivalent.** Moving `left -= got`
  out of the branch changes nothing, because `got` is zero on the path that falls through — no
  defect, so no catch. Replaced with one that claims the whole skip succeeded without checking, and
  a test that reads the package through a stream whose `skip()` refuses to move more than one byte
  now catches it. `GZIPInputStream` always skips everything asked on these fixtures, so the
  defensive loop was otherwise unreachable.

One `PackSuite` assertion failed on the first run and it was **an expectation that had aged**: it
asserted gzip was refused with "not implemented", which was true until this batch. The code was
right; the test recorded the old behaviour.

Panel parameter names re-checked against the executor: 33 written, 33 read, nothing orphaned in
either direction. Guide re-checked: 33 of 33 parameters documented.

## Not verified

`mvn clean package`. Any run on Windows. Any Transarch ingestion — in particular **nobody has yet
confirmed the archive accepts `.tar.gz`**, which is the one thing that would settle the naming
reading above. The panel has still never been opened in a browser.

---

## Follow-up, 2026-09-19: the correction, and the default restated

The claim that the specification contradicted itself on the archive name was **wrong and is
withdrawn**, here and in `USAGE.md`, `CLAUDE.md`, the executor spec, the transcription's
contradiction list and `SubmissionName.archive`'s javadoc. §3.5 names the resulting files itself —
`.tar.gz`, `.tar.bz2`, `.tar.xz` — and §2's pattern ends in `.*`, so the two agree. I had read a
conflict into `.tar` examples that describe the uncompressed case.

What remains genuinely unstated is whether compression is *expected* or merely *tolerated*; the
transcription's item 4 now says that rather than claiming a contradiction.

Naming a compressed archive `.tar` regardless was raised as a way to satisfy both readings, and was
rejected on measured evidence rather than taste. A gzip stream renamed `.tar`: GNU tar `-tf` and
`-xf` unpack it, python's `tarfile` in its default mode unpacks it, python in forced-uncompressed
mode fails with "truncated header", and a direct ustar parser finds no magic at offset 257 at all —
the category that includes commons-compress, strict system tars and `UstarReader`. Which Transarch
uses is undocumented. The failure modes are asymmetric, and that is the deciding argument: a wrong
extension is rejected at the naming validation, early and visibly, while a compressed file wearing
a `.tar` name passes both the size and the checksum check and breaks at extraction, possibly after
the source data has been decommissioned. In a legal archive the second is the one you cannot afford.

**No behaviour changed.** `compression` still defaults to `none`, still delivers an uncompressed
`<base>.tar`, and `USAGE.md` now says so in as many words instead of leaving it to be inferred from
a parameter table. 103 suite assertions and 26 mutations re-run after the edits, all green.
