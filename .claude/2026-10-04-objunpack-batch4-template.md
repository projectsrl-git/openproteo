# 2026-10-04 — objunpack, batch 4: the workflow template and the chain, measured

Base `930aaaa`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §16. No Java, no template of the UI.
With this batch the feature is complete as specified.

## What

`workflows/_TEMPLATE-objunpack-resend.xml`: objunpack → csvsql → dequote → validate → objpack →
ftpsend (on hold). A corrective resend as it stands: the package's feed id, date and sequence,
version + 1, the package's own target and delimiter. `USAGE.md` describes it and what the middle
steps do to the metadata.

## Files

- `workflows/_TEMPLATE-objunpack-resend.xml` — new. Like every sample, not in the WAR: copy it
  into the workflows directory.
- `static/USAGE.md` — «The ready-made workflow», and the advice to use `${OBJUNPACK.x}`.
- `.claude/OBJECT_UNPACK_EXECUTOR.md` — §16, ∩ U12–U14, limit 8, two corrections struck through.
  This note. `COMMIT_MSG.txt`.

No runtime parameter. `README.md` and `CLAUDE.md` not touched.

## What the measurements changed

- **The middle steps cannot follow the package's delimiter.** `csvsql`, `dequote` and `validate`
  use their `delimiter` attribute unresolved. The template writes a comma in the three and tells
  the author to set them; the end of the chain still writes the package's delimiter.
- **`dequote` on `"ROSSI, MARIO"` in a comma file** — the question left open in batch 0: kept
  whole, wrapped in quotes; `objpack` reads it back.
- **A quote inside a value is removed by `dequote`** — the one place where the rebuilt rows differ.
- **A value with a line break** needs `embeddedNewlines=space` on dequote (or the csvsql option).
- **Short variable names are overwritten by `objpack`**; the template uses the qualified ones.
- Closed from batch 2: the real parser accepts `exec="objunpack"` — run, not only read.

## Proposed, NOT done — each needs the author's decision

1. Resolve the `delimiter` attribute through `VarResolver` in `csvsql`, `dequote`, `validate`, so
   the chain can follow `${OBJUNPACK.metadataDelimiter}`. Three production executors.
2. `ObjectPack.glob` throws on any include / exclude pattern with a literal character (found in
   batch 0, still open).
3. If duplicate original names turn up in a real package (Gate 0 G2): a design of its own.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432; H2 2.1.214; node 22):** the template parsed by
the real `WorkflowXmlParser`, with a refused control; template parameters and `${OBJUNPACK.…}`
variables against what the executors read and publish, mechanically, with a control; H2 with
csvsql's own `CSVREAD` statement on the unpacked metadata of 12 packages; the real body of
`runDequote`, lifted by position, on 10 packages and then the real `ObjectPack` and `objunpack`
again; `USAGE.md` through the real `render()`; the core, executor and panel suites re-run from the
patched second clone; `git apply --check` there.

**Verified on Windows:** nothing.

**Not verified on either:** `csvsql` itself (its column expansion and its writer), `validate` and
`ftpsend`; the template opened in the designer or run; `${OBJUNPACK.x}` resolving in a running
workflow; `mvn clean package`.

## To close it on a real instance

Copy the template, put a real package where `packageArchive` points, set the three delimiters and
the FTPS target, run to the hold. Compare the tar of OBJ PACK with the original: same objects,
same rows, V + 1.
