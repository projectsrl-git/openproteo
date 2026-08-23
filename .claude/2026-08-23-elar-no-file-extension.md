# ELAR: no file extension is expected, and the PULL had lost the counter

Reported: **ELAR expects no fixed extension.** A delivered file ends at its `.CHHMMSS` counter, which
is how `output.index_name_pattern` is written in the properties file. The generator already produced
that name correctly — the whole name comes from the pattern, and nothing in the executor adds,
assumes or requires an extension.

## What was actually broken

The name the **PULL references**. `BatchNaming.stripExtension` removed the last dot-segment with
`lastIndexOf('.')`:

- on `x.INDX.C152100.xml` that is indistinguishable from removing `.xml`;
- on `x.INDX.C152100` it eats the counter, because there the counter **is** the last dot-segment.

So `[INDEX_NAME]` was substituted as `x.INDX`, and **every delivered PULL referenced a file that does
not exist**. Both files still look right in a directory listing — correct names, correct counters,
matching pair — and only the manifest inside the PULL is wrong. Nothing on our side would have said
so; ELAR would.

The legacy tool did `replace(".xml","")`: a literal replace, and therefore a **no-op** on a name
without an extension. Legacy was right here and the rewrite was wrong. Rewriting that crude line as
something tidier is what introduced the defect — `lastIndexOf('.')` is the "obviously equivalent"
version, and it is only equivalent while an extension is present. Worth remembering the next time a
legacy oddity looks like it can be cleaned up: the oddity may be the whole point.

## And the checker agreed with it

`ElarCheckRun.stripExtension` had the same shortcut, and it decides what name the PULL must contain.
With no extension it looked for `...INDX` — a substring of almost any PULL for that family, including
the PULL's own file name — so the pair check **passed on a broken pair**.

The two bugs agreed with each other, which is why neither could reveal the other. That is the failure
direction that makes a checker worthless: the pair-integrity check, one of the reasons `elarcheck`
exists, was reporting confidence it did not have.

## The fix

Both now strip a literal `.xml` suffix, case-insensitively. One deliberate divergence from legacy:
`replace` substituted the sequence **anywhere** in the name while this removes it only as a suffix, so
a family whose name contained `.xml` in the middle — which legacy would have corrupted — is left
alone.

`USAGE.md` states the rule where the file names are explained: there is no extension and none is
expected, the whole name comes from the pattern, and the only place one is ever removed is the PULL's
reference to its INDX.

## Verified

- **15 assertions** on the generator, `--release 8`: the extension-less pattern end to end — the
  delivered INDX ends at `.C152100`, the PULL on disk names that exact file, and the referenced name
  equals the delivered file name — plus the `.xml` pattern still stripping only the extension,
  uppercase `.XML`, an unrelated `.dat` left alone, and a bare name returned unchanged. The first
  group fails against the previous code.
- **6 assertions** on the checker. The decisive one: a PULL naming `...INDX` without the counter is
  now reported as a broken pair. Against the pre-patch code that same case returns **zero findings**.
- The `.done` rename suite (33 assertions) re-run unchanged.
- The `elarcheck` **read-only scan re-run clean** — no `FileOutputStream`, `Files.write/delete/copy/move`,
  `renameTo`, `createNewFile`, `mkdir(s)`, `delete()`, `deleteOnExit`, `setWritable` or
  `setLastModified` anywhere in the package. The change removes characters from a String and touches
  no file API, but the property is asserted rather than reasoned about.

**Not compiled here**: `mvn clean package` needs the internal Nexus. Both classes changed sit in
packages free of Spring and were compiled and executed in full. The build on the target machine
remains the gate.
