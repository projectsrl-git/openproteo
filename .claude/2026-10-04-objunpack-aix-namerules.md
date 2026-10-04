# 2026-10-04 — AIX gets the POSIX name rules; `nameRules` on the objunpack step

Base `f34e507`. Spec: `.claude/OBJECT_UNPACK_EXECUTOR.md` §18.

## What happened

On an AIX server `objunpack` stopped with `this host is neither Windows nor Linux (os.name='AIX')`.
The author: AIX is to be treated as Linux, and it must be possible to set this from the GUI
without a restart.

## What

- **AIX is detected** and gets the Linux (POSIX) name rules — nothing to configure there.
  `unarchive` shares the detection and gains AIX as well.
- **`nameRules`**, a new parameter of the objunpack step: `auto` | `linux` | `windows`. For a
  server the detection does not know. Set in the designer panel, applied at the next run of the
  step, no restart. `linux` on a Windows server is refused.
- On AIX the automatic path limit of objunpack is 1023 bytes instead of 4096.

## Runtime parameters introduced (checklist 10)

`nameRules` — a step `<param>`, settable from the objunpack panel (Output section) and listed in
the Variables page options; default `auto`; applies at the next run of the step. No file-only key.

## Files

- `unarchive/HostRules.java` — AIX; `posixPathMax`.
- `objunpack/ObjectUnpack.java` — `nameRules`, the first log line, the AIX path limit.
- `engine/InternalSteps.java` — reads `nameRules`.
- `templates/designer.html`, `templates/variables.html` — seed, field, two notes, validation,
  option list.
- `workflows/_TEMPLATE-objunpack-resend.xml` — `nameRules=auto`, like every other default.
- `static/USAGE.md` — objunpack and unarchive sections.
- `.claude/OBJECT_UNPACK_EXECUTOR.md` §18, `.claude/UNARCHIVE_EXECUTOR.md` I41, this note,
  `COMMIT_MSG.txt`.

## Behaviour that changes for an existing workflow

Only on a server whose `os.name` is `AIX`: `unarchive` and `objunpack` run there instead of
refusing. On Windows and Linux nothing changes; a step without `nameRules` behaves as before.

## Proposed, NOT done

- `unarchive`: the 1023-byte limit on AIX, and a `nameRules` parameter of its own.
- Other POSIX systems in the detection (Solaris, HP-UX, BSD): today `nameRules=linux`.

## Verification

**Verified on Linux (sandbox; Temurin 1.8.0_432 and JDK 21.0.10 both run; root and uid 65534;
`LANG=C.UTF-8` and empty), with `os.name` injected:** core suite in the eight combinations, 694
assertions, 38 new; 10 mutations of the new core code, all caught; the lifted `runObjUnpack`, 90
assertions, 23 mutations all caught; the panel through its own controls, 141 assertions, twelve
fields against twelve parameters read, 34 mutations with 32 caught and 2 equivalent; differential
compile identical; `node --check` and the scans of the added template lines, with controls;
`USAGE.md` through `render()`; the template through the real parser; `git apply --check` on a
second clean clone and the suites re-run from it.

**Verified on Windows:** nothing.

**Verified on AIX:** nothing by me. Not one line ran there.

**Not verified on any:** the IBM JVM's behaviour (`sun.jnu.encoding`, `Files.move`); the 1023
figure, taken from AIX's documentation; `mvn clean package`; a browser.
