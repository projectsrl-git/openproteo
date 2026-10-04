# 2026-10-04 — objunpack, batch 0: specification and Gate 0

Base `89a66ad`. Documentation only: no Java, no template, no runtime parameter.

## What

`.claude/OBJECT_UNPACK_EXECUTOR.md`: the specification of `objunpack`, the inverse of `objpack`,
with what was measured before writing it (§3), eight named intersections (U1–U8), the limits that
cannot be removed (§10) and eight Gate 0 questions (§11), each with a recommendation. Batches 1–4
wait for the answers.

## Why a spec with measurements

The mapping from the name in the package to the original name was already decided (audit.json
joined with metadata.csv). The spec's job was to find what that decision meets on real packages.
Ten were produced by the real producers — `ObjectPack` on Java 8, and the legacy PowerShell script
under `pwsh` with GNU tar and with bsdtar — and read with the classes to be reused.

What the measurements changed with respect to the task as given:

- **Two things to be reused do not exist.** `AuditJson` has no reader (a private positional key
  scanner only) and `SubmissionName` parses no names. Both are specified as additions to those
  classes; `objpack`'s write path is not touched.
- **`LinkGuard` would never be called**: a package is flat, so a link is refused as a
  non-conforming member (∩ U1).
- **The legacy tar has the objects before the audit and metadata**, so objects are staged under
  their member names and renamed afterwards; one pass.
- **`PsCsvReader` is the reader**; `FlatCsvReader` miscounts a record that spans two lines.
- **`maxEntries` cannot be `unarchive`'s 100 000**: a full package has 100 003 members (∩ U5).
- **`objpack`'s default `outDelimiter=;` would silently change a legacy feed's `,`** in the rebuilt
  package; the template passes the package's own (∩ U8).

## Found and NOT done

`ObjectPack.glob` lower-cases a regex built with `Pattern.quote`: every `include` / `exclude`
pattern other than bare `*` / `?` throws `PatternSyntaxException` when the objects directory is
listed (`objectSource=order`, or `name` with `recurse`). Measured on Java 8 and 21. And the objpack
spec's default for `exclude` ("the five output patterns") is not the code's (empty). Recorded in
§3 of the new spec; needs its own patch and its own decision.

## Files

- `.claude/OBJECT_UNPACK_EXECUTOR.md` — new.
- `.claude/2026-10-04-objunpack-batch0-spec.md` — this note.
- `COMMIT_MSG.txt`.

`CLAUDE.md`, `USAGE.md`, `README.md` not touched: no rule changes and nothing exists to document
for a user yet.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432, run, not `--release 8`; PowerShell 7.4.6; GNU tar
1.35; bsdtar 3.7.2; root; `LANG=C.UTF-8`):** the packages `objpack`, `unarchive`, `rename`,
`platform`, `elar/FlatCsvReader` and `ds/CsvWriter` compiled with the Java 8 `javac`; ten packages
produced by the real producers; `ArchiveFormat` / `GzipSupport` / `TarStreamReader` against Python
`tarfile` on six of them; `FlatCsvReader` and `PsCsvReader` against Python `csv` on three shapes;
a prototype of the join (Python) with the re-pack done by the real `ObjectPack`: restore
byte-identical on 9 of 10, round trip on 9 of 10, the exceptions being the two declared limits
(§3 M6, M7); `ObjectPack.glob` on Java 8 and 21. `git apply --check` on a second clean clone.

**Verified on Windows:** nothing.

**Not verified on either:** everything `objunpack` will be — no line of it exists; the prototype
proves the design, not an implementation. Windows PowerShell 5.1 and its `ConvertTo-Json`. A legacy
package made by the real script file on Windows. The chain's `csvsql`, `dequote`, `validate`,
`ftpsend` on an unpacked metadata CSV. Not run as non-root and not with an empty `LANG`: nothing
measured here depends on a permission, and the encoding-dependent runs declared `C.UTF-8`.

## Follow-up

Gate 0 answers (§11), then batch 1. One real legacy package from production would close §10.7.
