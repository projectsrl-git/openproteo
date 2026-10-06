# 2026-10-06 — objunpack: look inside a package first, and send it as it is when it needs nothing

Base `b7632e7`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §20.

## Why

The packages have 100 000 objects each and most need no correction. Unpacking and repacking
every one costs minutes and twice the disk. The author asked for: extract only the CSV and the
audit, read the expected record count from the audit, validate the CSV with it, and when it is
valid skip unpack and repack and go to the FTPS send.

## Decided by the author

- Not valid → the workflow goes on by itself with the corrective chain (so `validate` needed a
  way not to fail the run).
- The extraction is a bash script, AIX and Linux only. No PowerShell twin.
- Suites not in the repository.

## What

- `engine/InternalSteps.java`, `runValidate` — parameter `onFailedChecks` = `fail` (default) |
  `continue`. `continue`: exit 0 whatever the checks say; the result is in `${checksFailed}`.
  Missing CSV still exit 2; any other value refused with exit 2 before reading.
- `templates/designer.html` — select "When a check fails" in the validate panel, a note when
  `continue` is chosen, the check on save. `templates/variables.html` — the option list.
- `scripts/objunpack-precheck.sh` — new. Verifies the md5, extracts the audit and the metadata
  by exact name, publishes 15 variables.
- `workflows/_TEMPLATE-objunpack-precheck-resend.xml` — new. The other template is unchanged.
- `USAGE.md` — the validate bullet; a section under the objunpack step.

## Default behaviour

Unchanged everywhere: `onFailedChecks` absent = `fail`, and the designer writes no parameter for
`fail`. Old validate body against new on 17 cases: identical line for line.

## What the short cut does not check

Every per-object check of `objunpack`. It trusts the checksum, three counts and six metadata
checks. Written in USAGE in so many words. If that is too little, the cheap next step is a
listing check in the script (every `file_name` of the audit is a member): proposed, not done.

## Proposed, NOT done

- The `delimiter` attribute of csvsql, dequote and validate resolved as a variable, so the
  template could follow `${PRECHECK.metadataDelimiter}` instead of a literal the author must
  set four times. A change to three production executors: needs a decision.
- A PowerShell twin of the script.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.12; bash 5.2.21; GNU tar 1.35;
LANG=C.UTF-8):** the script against `ObjectUnpack`'s own reading of 15 real and tampered
packages plus refusals and the 100 000-object package, 280 assertions as root and 275 as uid
65534; the real `runValidate` lifted from before and after, 375 assertions on both JDKs, as
root; the template parsed by the real parser and walked with the real resolver, gate
evaluation and send plan, the script run by the real bash runner, 12 scenarios; the panel in
jsdom, 20 assertions; differential compile 459 = 459 lines; `node --check` on both templates;
the added lines scanned for `\n`-style escapes and `[[`, with a control. Mutations: script 30
(26 caught, 4 equivalent and explained in the spec), validate 10 (9 caught, 1 does not
compile), template 14, panel 7.

**Verified on Windows:** nothing.

**Verified on AIX:** nothing. The script was written for AIX's `tar` and `csum` from their
documentation and has never run there.

**Not verified on any:** the engine running the template end to end (the walk is a loop of
mine over the parsed nodes); a real FTPS send; Jackson reading the dataschema (a stub stands in
for it in the lifted validate); a browser; `mvn clean package`; bsdtar.
