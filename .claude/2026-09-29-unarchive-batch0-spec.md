# unarchive — Batch 0: specification and Gate 0

Spec at `.claude/UNARCHIVE_EXECUTOR.md`. **No code in this commit**; nothing in the WAR changes.

## What was asked

A new internal executor that extracts the archives found in a directory, format chosen or detected
from the extension or the magic bytes, spec-first with the security questions (traversal, links,
bombs, Windows names, zip charsets, layout, interruption) settled before code.

## What was measured rather than assumed

Twenty-one facts, listed in §2 of the spec with tool versions. The ones that changed the design:

* **`objpack.UstarReader` cannot be the basis for extraction.** Run against real GNU tar output it
  reports `././@LongLink`, `./PaxHeaders/…` and `pax_global_header` as members, truncates long names,
  **ignores the ustar `prefix` field** (a nested file comes back as a root file, no error), has no
  typeflag handling, and returns an **empty list without error** for a small gzip renamed `.tar`.
  Correct for objpack, which only reads its own writer's output; left untouched so objpack's
  verification stays independent.
* **Info-ZIP 3.0 writes UTF-8 names without flag bit 11 and without the 0x7075 extra**, while a real
  CP437 name makes `new ZipFile(f)` refuse the whole archive and `ZipInputStream` throw an
  `IllegalArgumentException`. No fixed charset is right; the spec defines a four-rule decision and
  logs per archive how many names each rule decided.
* **`ZipFile` does not check CRC-32** (a flipped payload byte is read silently) while
  `ZipInputStream` does — but `ZipInputStream` refuses STORED entries with a data descriptor, which
  `ZipFile` reads. Hence `ZipFile` plus a CRC computed while streaming.
* **Java 8's `ZipEntry` does not expose external attributes**, so a `zip -y` symlink reads as a
  regular file containing its target. Gate 0 Q5.
* **GNU tar extracts a tar with no end-of-archive blocks with exit 0.** The spec warns, not fails.
* **Legitimate data compresses at most 25.5 : 1** on the shapes measured; zeros hit deflate's
  1028 : 1. `maxRatio` default 200.
* **Registration is eight places in the code**, read on `objpack`: three in the parser, one in the
  engine, the dispatch, and three in the designer. CLAUDE.md's table still says four.
* **`id="extract"` is used by three workflow steps** here, so the executor name recommended is
  `unarchive`.

## Decisions taken in the spec (all revisable at Gate 0)

* One name validator for every format; refusal is the rule, with exactly three accepted
  transformations (leading `./`, `\` as a separator for validation, trailing `/` on directories),
  each justified by real tools producing it.
* Case-insensitive collisions refused **on every host**, so a Linux test box and the Windows server
  extract the same set.
* Nothing but regular files and directories is ever created. Links and devices: `fail` or `skip`.
* Each archive is extracted into a `.part` staging directory and committed by one rename; failure
  removes the staging, earlier archives stay committed, a killed JVM's leftovers are swept next run.
  The `flat` layout is declared **not** atomic.
* Parameter names checked against `PARAM_OPTIONS`: `recursive` and `checkFreeDisk` reused with the
  same defaults; `overwriteExisting` and `renameProcessed` deliberately **not** reused, because
  elarxml owns them with different semantics and the table has no executor context.
* No date-pattern parameter, so the `YYYY`/`DD` trap cannot arise here.
* `runUnarchive` must set `exitCode = 0` on success; the objpack lint re-runs in the registration
  batch.

## Deviation from the delivery request, stated

**No `USAGE.md` section in this batch.** `USAGE.md` is served at `/docs` to operators; a section for
an executor that the parser still refuses would document something nobody can use — the "does the
guide name anything that does not exist?" check from objpack batch 4 would fail on it. The section
is batch 5, verified through `docs.html`'s `render()`. Say so and it goes in now, marked as planned.

## Gate 0

Twelve questions in §13 of the spec. Blocking batch 1: **Q1** the name, **Q2** formats in scope,
**Q3** who produces the archives — ideally answered with one or two real samples, since bsdtar
(Windows `tar.exe`) cannot be run here.

## Not verified

Everything the spec says about Windows (reserved names, `MAX_PATH`, rename under a scanner) and about
Java 8 at runtime: the measurements ran on JDK 21 with `--release 8`. `mvn clean package` untouched —
nothing here is compiled into the WAR.
