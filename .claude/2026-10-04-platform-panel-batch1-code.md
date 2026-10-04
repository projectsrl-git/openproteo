# 2026-10-04 — Platform diagnostics panel, batch 1: the code

Base `7b52145`. Spec: `.claude/LINUX_AND_GUI_CONFIG.md` §7 (decisions taken while building: §7.13).

## What

A read-only `/platform` page over `GET /api/platform`: host and JVM identity, the working directory
beside the resolved configured paths (exists, kind, writable), SunMSCAPI presence, each configured
interpreter resolved without being executed, and a case-sensitivity probe of `defaultBaseDir`.
Gate 1 was answered with the five recommendations, so: ten path rows (the seven requested, the
effective global-variables file, the log directory, `java.io.tmpdir`), `sun.jnu.encoding` and the
effective default charset, a cached probe, the link in the dashboard nav only.

No new runtime parameter (checklist 10). No dependency. No executor touched: no feed can change.

## Files

- `platform/PlatformProbe.java` — new. JDK only. Every decision is here.
- `web/PlatformController.java` — new. `GET /api/platform`, `POST /api/platform/case-probe`.
- `web/PageController.java` — route `/platform`.
- `templates/platform.html` — new.
- `templates/dashboard.html` — one `Platform` button after `Docs`.
- `static/USAGE.md` — `## Platform diagnostics`, inserted before `## The elarxml step`.
- `tools/scan_platform_whitelist.js` — new; run it on any change to the two Java files.
- `CLAUDE.md`, `.claude/LINUX_AND_GUI_CONFIG.md` (§7 marked delivered, §7.13 added), this note,
  `COMMIT_MSG.txt`.

`README.md` not touched.

## Verification

**Verified on Linux (sandbox, JDK 21.0.12, `javac --release 8 -Xlint:all` clean):**

- `PlatformProbe`, the real class against the real file system, **run twice: as root and as a
  non-root user** (`setpriv` to uid 65534). 132 / 133 assertions. Paths (existing, missing, wrong
  kind both ways, absent file with and without a writable parent, empty / blank / null never
  resolved, an invalid path reported and not thrown, nothing created); interpreters on a `PATH` of
  three directories (first wins, a directory of that name skipped, a non-executable file not found
  and named, absolute, relative, no `PATH`), plus real `bash` equal to `command -v bash`; the
  Windows search order on a fake tree with injected `os.name` and environment (18 assertions,
  including that only `PATH` and `SystemRoot` are ever read); the case probe (`CASE_SENSITIVE`
  here, no file left, cache, throttle one millisecond either side of the floor, missing directory
  NOT created, not-determined not cached).
- `PlatformController`, compiled with Spring stubbed against the real `AppProperties` and
  `GlobalVarsStore`, and called: 19 assertions, including that a masking secret and a global
  variable set on the properties are nowhere in the response.
- Agreement with `unarchive.HostRules.detect`, compiled from the repository: 12 OS names.
- `tools/scan_platform_whitelist.js`: clean; 21 controls.
- `platform.html` in jsdom, link attributes rewritten as Thymeleaf would under a context path, the
  button driven through its own handler: 74 assertions. No `[[` / `[(`, no literal `\n` / `\r`, no
  duplicate top-level function, every CSS class defined in `app.css`, `node --check` with a
  positive control.
- `USAGE.md` through `docs.html`'s own `render()`: 71 assertions; 26 fail on the pre-patch file.
  Wrapped paragraphs elsewhere in the guide: 86 before, 86 after.
- Mutations on copies: probe 35 (34 caught at once, 1 green first - a real gap, closed); page 21
  (20 caught, 1 equivalent, argued in `CLAUDE.md`); guide 8, all caught.
- `git apply --check` on a second clean clone.

**Verified on Windows: nothing.** Left to the author, with what to expect on `/platform`:

1. `orchestrator.cmd-exe` `cmd.exe` → found, resolved under `...\System32\cmd.exe`.
2. `orchestrator.powershell-exe` `powershell.exe` → found, under
   `...\System32\WindowsPowerShell\v1.0\`.
3. `orchestrator.java-exe` `java` → found (in the Java directory of the running JVM).
4. Windows trust store → present.
5. File-name case → `case-insensitive` on NTFS, and no `.op-caseprobe-*.tmp` left in the feed base
   directory. **This is the first time that branch runs on a real file system.**
6. `Probe again` twice within a minute → the second says it was not measured again.
7. Paths: every row the instance uses says `ok`; a folder denied by ACL to the service account
   says `not writable` (this is the `Files.isWritable` behaviour read from the jdk8u source).
8. The same page behind IIS.

**Not verified on either:** `mvn clean package`; Spring wiring of the new controller and the JSON
it serialises; a real browser, and the UBS one in particular; a real `pwsh`; a case-insensitive
file system (the sandbox kernel refused vfat, ext4 casefold and tmpfs casefold).

## Follow-up

Batch 2: the `bash` runner. Its spec adds a `bash` row to this page; the page already renders a
row it does not know (asserted).
