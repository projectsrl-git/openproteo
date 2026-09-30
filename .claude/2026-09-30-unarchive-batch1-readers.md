# unarchive — Batch 1: the readers, the name validator, detection

Gate 0 Q1–Q3 answered: name `unarchive`; formats zip, tar, tar.gz/tgz, gz; producers Windows and
Linux only. Spec revised (`.claude/UNARCHIVE_EXECUTOR.md` §13, §16, §17) and batch 1 delivered.

## What is in it

Package `com.legalarchive.orchestrator.unarchive`, JDK only, `javac --release 8 -Xlint:all` clean:

* `ArchiveFormat` — magic bytes decide under `auto`, the extension is compared and a disagreement is a
  warning; an explicit `format` refuses a contradicting file; bzip2, xz, 7z, rar, zstd and spanned zip
  are recognised and refused naming the format.
* `EntryName` — the one validator for every entry name; refusal as the rule, three accepted
  transformations, each with its intersection written beside it.
* `NameIndex` — duplicates, case collisions, file/directory conflicts, directories differing in case.
* `TarStreamReader` — sequential, streaming; POSIX and GNU magic, pax `x`/`g`, GNU `L`/`K`, base-256,
  prefix only under the POSIX magic, typeflags reported for the caller's policy.
* `GzipSupport` — counts compressed bytes read, sniffs the first decompressed block, never uses FNAME.
* `UnarchiveException` carries a `Rule`, so a refusal can be tested for the rule that made it.

**Nothing outside the package changes**: no registration, no call site. No feed can reach this code.
Nothing in the package writes a file (scan, with `objpack` as positive control).

## Corrections to batch 0, struck through in the spec

* **Case folding.** The spec said `toLowerCase(Locale.ROOT)`. Measured: it turns `İ` into two
  characters; string `toUpperCase` makes `straße` equal `STRASSE`. NTFS does neither. The rule is now
  per-character upper-casing with no culture — the rule `rename.CaseInsensitive` (the `filerename`
  executor, merged meanwhile) already uses. The package keeps its own copy and the suite compiles the
  REAL `CaseInsensitive` from the repository and compares them over every BMP character: 0 differences.
* **zip legacy charset**: IBM437 → IBM850 as the recommendation, because Q3 says the producers are
  Windows machines, whose OEM page is 850 in Western Europe. Batch 2.
* **Registration line numbers** moved with `filerename`; the spec now calls them a pointer.

## The new CLAUDE.md principle, applied

Twenty-seven intersections are decided by name in §16, each beside both of its rules and each pinned
by an assertion or assigned to the batch that implements it. Writing them out found two cases the
batch-0 spec did not decide: a zero-filled gzip (`.gz` → single file, `.tgz` → empty tar) and a
0-byte `.tar` (refused, as GNU tar and python refuse it — measured).

## Verification

232 assertions, 34 mutations all caught (three foreground slices; the full set exceeds the command
limit). Every benign fixture comes from a real tool and is compared byte for byte with GNU tar's own
extraction; every hostile fixture must be refused by its own rule. One mutation survived first and
was a real gap: reading the ustar prefix under the GNU magic was invisible because no fixture had
data in those bytes — `tar --format=gnu -G` writes atime/ctime there (measured) and is now a fixture.
Two first-run failures were my expectations (`a:b` is a drive path; a hand-built pax record with a
wrong length, which GNU tar refused and which is now a hostile fixture).

## Deviation, still stated

No `USAGE.md` section until the executor is reachable (batch 5), for the reason given in batch 0.

## Not verified

Windows, Java 8 at runtime, Windows `tar.exe` output, `mvn clean package` (not needed for a package
with no call site, but it will compile it). Q4–Q12 keep their recommended defaults until batch 2.
