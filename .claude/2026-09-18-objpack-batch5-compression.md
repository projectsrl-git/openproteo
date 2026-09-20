# objpack — Batch 5: compression (Gate 0 9.4 answered)

Gate 0 9.4 is answered: follow the specification and allow compression where it permits it. gzip is
implemented; bzip2 and xz are refused with the reason.

## The decision the specification does not make

§2 requires every file of a submission to share one base name and all its examples end `.tar`. §3.5
permits gzip, bzip2 and xz for the archive. **Those cannot both hold literally**, because a gzipped
archive is `.tar.gz` — so the code has to choose a name and the choice belongs in the open, not
buried in an implementation.

The reading taken: §3.5's "Do not" box forbids compressing the **contents** of the package — members
ending `.zip` or `.7z` — while "Do only use" permits compressing the whole archive with one of the
three named algorithms, in which case the extension must say so. Naming a gzipped file `.tar` would
misdeclare its type to the receiver, which is worse than extending the name. The `.md5` keeps its
own name and holds the hash of whatever is delivered.

This is recorded in `SubmissionName.archive`, in the guide and in the panel, so that if the archive
team reads it differently there is one sentence to change rather than an assumption to hunt for.

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
