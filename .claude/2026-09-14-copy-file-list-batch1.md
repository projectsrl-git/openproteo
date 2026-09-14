# Copy file list — batch 1: the reader is extracted, and ifscopy is proved unchanged

Base commit: `f900d99`. Spec: `.claude/COPY_FILE_LIST.md`, whose Gate 0 is now answered.

## What is in this batch

`engine/CopyListSupport` — JDK only, no Spring, no JTOpen, no orchestrator types — absorbing
`engine/IfsListSupport`, which is **deleted**. `runIfsCopyList` is the only call site and now asks for
`Flavour.IFS`. The `LOCAL` flavour exists and is tested; **nothing calls it yet**. No executor gains a
parameter, no template changes, no feed can behave differently.

A delegating facade keeping the old name was considered and rejected: the name would then claim that
only `ifscopy` uses the class, which is the opposite of why it was extracted.

## The one thing that differs, and why it is the only one

Reading the list, resolving the column by name or index, RFC-4180 splitting, the BOM, blanks,
duplicates, the reporting cap and the collision accounting are identical whatever the destination is.
Exactly one rule is not: how a base directory and a listed name are joined, and therefore what the file
will be called in the destination. That is the `Flavour`. Two values, side by side in one file so they
can be read against each other — a strategy interface for two short rules would be ceremony.

**`Paths.get(name).isAbsolute()` is not the test for `LOCAL`.** On Windows
`Paths.get("/logs/x.pdf").isAbsolute()` is **false**: a leading slash there means the root of the
*current drive*, so the name is relative to something. That is exactly the shape a list produced by an
earlier `ifscopy` step carries, and joining it under the source directory would give
`D:\landing\in\/logs/x.pdf`. The rule is therefore an explicit first-character test for `/` or `\` —
which also covers the UNC form — then an explicit drive-letter test, and `Paths.get` only as a last net.
A drive-qualified name with no separator (`C:x.pdf`) is treated as complete: it is relative to *that*
drive's current directory, not to ours, so prepending a base could only produce nonsense.

**The separator follows the base.** A base written with backslashes gets a backslash, one written with
slashes gets a slash, and a base carrying neither gets the platform's own. Joining with a fixed
character works but produces `D:\landing\in/x.pdf`, which reads as a mistake in every log line and in
every error message that then quotes it.

**Backslashes are left alone in both flavours, for opposite reasons**: legal inside an IFS *name*, a
separator the platform already understands locally. The divergence is asserted rather than assumed — the
same string yields `back\slash.pdf` as an IFS destination name and `slash.pdf` as a local one. If the
two ever agree on that, one of them is wrong.

## The no-op is proved differentially, not asserted

`.claude/COPY_FILE_LIST.md` §4 promised this and it is what was done: the **pre-patch `IfsListSupport`**
taken out of `f900d99` and the **post-patch `CopyListSupport`** compiled side by side and run over the
same corpus, with **every field of `ListResult` compared** — `error`, `columnLabel`, `columnIndex`,
`dataRows`, `blankRows`, `duplicates`, `paths`, `blankLines`, `collisions` — plus `join`, `localName`
and `MAX_REPORTED` directly.

**22 018 compared cases, 0 mismatches.** The corpus crosses 16 files against 3 charsets (one of them
unknown), 2 delimiters, header and no header, 13 column specifications (name, wrong case, padded, index,
index past the end, index 0, a miss, empty, null, a non-number) and 10 bases (null, empty, whitespace,
trailing slashes, root, relative, Windows). It includes quoting with the separator inside a value and a
doubled quote, a BOM on the header and on the first data row when there is none, ISO-8859-1 accents, a
file that does not exist, an empty file, a header-only file, a file of nothing but newlines, and two
files of 130 rows that push past the 50-line reporting cap in both directions.

A reconstructed list of the original 73 assertions would only have proved that I transcribed them
correctly. Comparing the two classes proves they agree.

## Fourteen mutations, and the two that reported nothing

Every mutation is applied by a runner that **asserts its anchor is present before editing**, because
"two mutations came back green and neither was a pass" is already on this project's record. It happened
again here, and twice:

* One mutation's anchor never matched — shell quoting — and the run printed a pass. The runner now fails
  loudly on a missing anchor instead.
* One mutation genuinely came back green: *a whitespace-only line becomes a row*. Opened rather than
  filed, and it was a **gap in the corpus, not a bad mutation** — no file in it contained a line of
  nothing but spaces. Added, along with a line of nothing but delimiters, which is the opposite case: a
  legitimate row of empty fields that must stay one. The mutation is now caught by 900 mismatches.

All fourteen are caught: six on the shared body, three on the IFS rules, five on the LOCAL ones.

**Every LOCAL mutation leaves the differential suite green**, and that is the load-bearing observation
of this batch rather than a curiosity: breaking the new flavour cannot change what `ifscopy` does. The
inertness is a property of the structure, not of my care.

## `LOCAL` carries its own assertions

52 of them, since it has no predecessor to be compared against: what counts as a complete name (rooted,
UNC, drive with and without a separator, drive-relative, and the negatives), the join in every base
shape, the destination name under mixed separators, and one real read in which the base falls back, a
rooted name ignores it, a duplicate collapses, a blank is counted with its line number and the collision
guard fires — with the same list read as `IFS` reporting **no** collision, because there the two names
differ.

An unexpected throw in that suite is now a **failure** rather than a stack trace: one mutation crashed
it on `collisions.get(0)` and stopped it reporting everything after, which is the opposite of what a
suite is for.

## Verified, and not

* `javac --release 8` on both classes — stronger than the project's own `maven.compiler.source/target`,
  which does not check the API surface.
* Brace, parenthesis and bracket balance on `InternalSteps.java` with strings and comments stripped.
* **By reflection, not by eye**: every member of `ListResult` that `runIfsCopyList` touches exists on the
  new class, and the seven-argument `read` signature matches the call.
* The class in the patch is **byte-identical** to the class the suites ran against (SHA-256 compared).
* **NOT compiled: `InternalSteps.java`** — it needs the Spring tree from the internal Nexus, unreachable
  from the sandbox. `mvn clean package` is the gate. `CopyListSupport` is in the same package, so the
  change adds no import.
* **NOT reproducible here: the Windows half of the `isCompleteLocal` rule.** The sandbox is Linux, where
  `Paths.get("/logs/x.pdf").isAbsolute()` is already true, so the case the rule exists for cannot be
  made to fail. What the mutations *do* prove is that the explicit test is load-bearing for the other
  three shapes — `\logs`, UNC and `D:\` all answer false from `isAbsolute` on Linux. For the leading
  slash on Windows the argument is the documented semantics of `Paths`, not a measurement. Same class of
  limitation as the `ofPattern("DD")` behaviour that differs between Java 8 and the sandbox's JDK.

## Next

Batch 2: the `filecopy` and `safecopy` executors, including the local pre-scan and the refusal of
`mode=move` / `mode=list` under a list (Gate 0 Q4). The suites are not committed, as the `elar` ones are
not; they can be, with a runner, on request.
