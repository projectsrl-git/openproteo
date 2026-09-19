# objpack — Batch 4: USAGE.md

The operator-facing documentation: one bullet in the Executors list and a `## The objpack step`
section, following the shape `ftpsend` set.

## What the section leads with

Not the parameter table. The three things that will actually cost someone an afternoon:

* **The `.md5` sits beside the tar, not inside it** — it is the checksum *of* the tar, so it cannot
  be a member of it, and its content is the bare hash, deliberately not `md5sum` format.
* **`transmissionDate` is never filled in for you**, and the reason is spelled out rather than
  asserted: a step that quietly used today's date would rename the whole submission on a retry the
  next morning, and the retry would arrive as a different, unknown package.
* **Re-running can rename things**, because the OID padding depends on how many objects the
  submission has. Nine objects yesterday and ten today give `OID1` then `OID01` for what may be the
  same document. That is the archive's rule, and the mitigation — one output directory per step —
  is stated next to it instead of being left to be discovered.

The line-based CSV limitation gets its own subsection under a heading that says what it is for:
*A limitation worth knowing before you debug it*. Documentation that hides a known limitation in a
parameter list is documentation that will be read after the time has already been lost.

## Verification

A prose batch still has something mechanical to check, and it is the thing that rots first: **does
the guide name anything that does not exist?**

* Every parameter named in the section was extracted and compared against every
  `pv(params, vars, …)` key in `runObjPack`. **33 parameters, 33 documented, no ghosts and no
  omissions.**
* Every `${variable}` in the section compared against the `res.outVars.put(…)` keys. **8 outputs, 8
  documented, none invented.**
* Every default the prose claims — `maxObjectMb` 2048, `maxSubmissionMb` 20480, `maxObjects`
  100000, `failOnOversize` on, `failOnStaleBusinessDate` off, `businessDateMonths` 10,
  `outDelimiter` `;`, `recurse` off, `emitObjects` off, `onMissingObject` fail — checked against
  the field initialisers in `ObjectPack`. **None contradicted.**
* The XML example was parsed for its `<param name="…">` keys, all of which exist, and `exec="objpack"`
  confirmed present in the parser whitelist. A documented example that would not run is worse than
  no example.
* Code fences balanced, in the new section and in the file as a whole.

## Not verified

The rendered page has not been looked at. Nothing here touches Java or JavaScript, so no suite was
re-run and `mvn clean package` was not attempted.

## The feature is complete

Batches 0 to 4: the transcription and Gate 0, the ustar writer, the packager and its registration,
the designer panel, and this. Five Gate 0 questions remain open — version padding, compression, the
approved delimiter list, the business-date window and the missing-object policy — each with a
conservative default in place and each named in the guide where an operator would meet it. None of
them blocks use.
