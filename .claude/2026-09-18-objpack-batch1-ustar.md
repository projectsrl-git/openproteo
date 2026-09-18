# objpack — Batch 1: the ustar writer and the MD5 sidecar

Two classes in a new Spring-free package `objpack`, compiled with `javac --release 8` and run here
against real files. Nothing is wired: no dispatch, no `internalKind`, no designer. A workflow cannot
reach this code yet, by design — batch 2 wires it.

## Why hand-written rather than a library

Java 8 has no tar anywhere in the platform, and `commons-compress` cannot be confirmed against the
internal Nexus from this sandbox. A design resting on an artifact that may not be there is a design
that may have to be redone after the build fails on the real machine. ustar is a 512-byte header
plus aligned payload: small enough to write correctly, and — the actual argument — small enough to
**prove against an independent reader**, which is what happened.

## Measured, not assumed

`tar -cf t.tar -C stg -- f1 f2` stores **bare member names**, magic `ustar `, with **no `./`
prefix**. The PS1 script's `-replace '^[.][/\\]', ''` defends against a tar that does prefix them; it
does not describe the expected output. `UstarWriter` writes bare flat names and asserts it.

## Decisions taken in the code

* **POSIX ustar (`ustar\0` + `00`), not the old GNU magic** that the local GNU tar emits. ustar is
  the documented standard and the one both GNU tar and python `tarfile` read without a warning.
* **Flat names are structural, not a convention**: a name containing `/` or `\` is refused outright.
  That is what makes "no member can arrive as `./name`" a property of the class rather than
  something to remember at the call site.
* **A name over 100 bytes is refused, never truncated and never split into the ustar prefix field**
  — the prefix can only carry a directory part, and these names have none. A truncated member name
  would be a submission that passes packaging and fails Transarch's naming validation.
* **Fixed ownership and permissions** (0644, uid/gid 0, empty uname/gname) so the same inputs give
  the same archive on any machine and any account. The only per-entry variable is mtime.
* **The file is streamed**, and a source that grows or shrinks mid-read fails the step: a short or
  long member silently corrupts every following header, so there is nothing safe to do but stop.
* **`Md5.writeSidecar` writes the bare hash and nothing else** — 32 bytes, no file name, no `*`, no
  newline. This is deliberately *not* md5sum's format, which is the easy mistake and is the one the
  spec's red box exists to prevent.

## Verification

**93 assertions green.** 69 from `TarSuite` (header fields, checksum recomputed the way a reader
does it, block alignment at sizes 0/1/511/512/513/1024/1025, 200 members, mtime, nine refusals, MD5
known vectors, the sidecar's exact shape) and 24 cross-reader: **GNU tar** lists the members in
write order with empty stderr, extracts them byte-identical including a 700 000-byte member and a
1-byte one, and renders `-rw-r--r-- 0/0`; **python tarfile**, which shares no code with GNU tar,
reads the same members, sizes, mode 420, empty uname and the same payload bytes; and `md5sum` agrees
with `Md5` on the same tar. bsdtar is not installed here and was skipped, not silently passed.

**16 mutations, all caught.** Three were green on the first run and all three were opened rather
than filed:

* **One was a real gap in the suite.** Dropping one of the two terminating zero blocks changed
  nothing observable, because the blocking-factor padding fills the tail with zeros either way —
  the two archives are byte-identical. It is only visible at `blockingFactor=1`, where there is no
  padding, and the suite never exercised that. A test at factor 1 now asserts the exact length and
  the two zero blocks, and the mutation is caught.
* **Two were bad mutations of mine.** A trailing space instead of NUL is a *legal* POSIX numeric
  terminator, so that mutation introduced no defect; it was replaced by overwriting the terminator
  with a digit, which changes the parsed value and bites. And the "hex encoder drops the leading
  zero" mutation was a no-op — `HEX[0]` *is* `'0'` — replaced by removing the `& 0xFF`, the sign
  extension that hex encoders actually get wrong.

The mutation runner mutates **copies** in a scratch tree and compiles from there, so the failure
recorded in the copy-file-list batch 3 note — a runner killed by SIGPIPE leaving the tree mutated —
is not possible here by construction rather than by remembering to restore.

## Not verified

`mvn clean package`; any run on Windows; any Transarch ingestion. The suite is not committed, as the
`elar` suites are not; it can be, with a runner, on request.
