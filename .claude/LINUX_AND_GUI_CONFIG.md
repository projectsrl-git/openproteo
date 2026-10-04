# Linux compatibility and GUI-only configuration — programme spec

Opened 2026-10-03, delivered on base `712db45`. This file is the master document of a seven-batch programme.
Batch 0 (this commit) amends the contract and records the baseline; every later batch adds its own
section here BEFORE its code, and code follows only after the author confirms. One batch per turn.

## 1. The two rules

Text in `CLAUDE.md`, «Stack e regole irrinunciabili»: **Compatibilità Linux obbligatoria** and
**Configurabilità integrale da GUI**. They are quoted there, not here, so there is one copy.

Both are targets the code does not meet today. `CLAUDE.md` «Configurazione esterna» lists what is
already reachable from the GUI and what is file-only debt.

## 2. Batches

| # | Subject | Depends on | Adds an execution surface |
|---|---|---|---|
| 0 | Contract amendment (this) | - | no |
| 1 | Platform diagnostics panel, read-only, public until batch 3 | 0 | no |
| 2 | `bash` runner, process-tree kill, platform-sensitive `powershellExe` default | 0 | no more than the existing `.ps1` upload |
| 3 | Authentication and authorisation (viewer / operator / admin), audited | 0 | no - it removes one |
| 4 | Settings from the GUI (override file, last-known-good, write-only secrets) | 3 | yes |
| 5 | JDBC drivers from the GUI (delegating `Driver` shim) | 3 | yes |
| 6 | Inline commands and scripts for powershell, cmd, bash | 2, 3 | yes |

Rule fixed by the author for the three "yes" rows: with authentication disabled the existing
behaviour stays identical, and the NEW functions of batches 4, 5, 6 are unavailable. No new
execution surface on an instance without authentication.

## 3. Baseline: the stated context, re-verified on the code

Checked by reading the deciding line, not re-analysed. Read at `a4f02c8`, then again at `712db45`
after `main` moved eight commits (the `unarchive` executor, batches 2 to R). None of the files cited
below is in `git diff --name-only a4f02c8 712db45`, so every line number holds. **One claim was true
at the first reading and is false at the second** - the first row.

| Claim | Where | Status |
|---|---|---|
| No `os.name` / `os.arch` anywhere in `src/main` | 0 hits at `a4f02c8`; at `712db45` `unarchive/UnarchiveRun:100` reads `os.name` and `unarchive/HostRules` branches on it | **no longer true** - see §4.5 |
| External executables are parametric | `AppProperties:26` `powershellExe`, `:29` `javaExe`, `:32` `cmdExe` | confirmed |
| `trustMode` defaults to WINDOWS in the model | `FtpsTarget:27` | confirmed |
| ... in the empty-value fallback | `TrustMode.parse`, lines 29-31 | confirmed |
| ... in the page | `ftptargets.html:191` and `:206` | confirmed |
| `loadWindowsRoot` needs SunMSCAPI | `SslContexts:86-90` (`KeyStore.getInstance("Windows-ROOT")`) | confirmed |
| `StepExecutor.Kind` is POWERSHELL, CMD, JAR | `StepExecutor:39`, detection `:66-73` | confirmed |
| PowerShell script passed as data | `StepExecutor:190-208` (`[ScriptBlock]::Create`, `-ExecutionPolicy`, `-EncodedCommand`) | confirmed |
| Timeout/abort only `destroyForcibly()` | `StepExecutor:131` | confirmed |
| No authentication | no `security` in `pom.xml`; no `Filter`, `HandlerInterceptor` or `WebSecurity` class | confirmed |
| Script upload from the GUI | `ApiController:3379` (`kind=script`) | confirmed |
| `getRemoteUser()` fallback | `ApiController:3823` | confirmed |
| JDBC drivers deliberately not bundled | `SqlSupport:32-48` | confirmed |
| `mask-pools-dir` itself is file-only | `ApiController:3231`, `:3254` refuse without it | confirmed |

### 3.1 Found beyond the stated context

Not changed in batch 0. Each is assigned to the batch that owns it.

1. **A fourth WINDOWS default**: `FtpsTarget.setTrustMode(null)` at line 114 returns WINDOWS. A
   target deserialised with an explicit `null` lands there, not in `TrustMode.parse`. Batch 4.
2. **The audit identity is client-supplied today**: `ApiController.user()` reads the request
   header `X-User` at line 3822, BEFORE `getRemoteUser()`. Any client can name itself in the
   hash-chained audit. It was presumably meant for the IIS front end; batch 3 must decide by name
   whether the header survives, and from whom it is trusted.
3. **Two runtime keys are outside `orchestrator.*`**: `openproteo.logreport.window-days`
   (`LogIndexer:76`) and `openproteo.logreport.export-enabled` (`LogReportController:29`), both
   `@Value`. A settings page generated from `AppProperties` alone would not show them. The rule
   says "ogni parametro di runtime", so they are in scope. Batch 4.
4. **A second Windows-path placeholder**: `ftptargets.html:56` (`D:\certs\S211048_TRANSARCH.pfx`)
   beside the one at line 73; and `designer.html:88` (`D:/feeds`). Batch 4.
5. **`System.lineSeparator()` in two API responses**: `ApiController:1488-1493` and `:1689` build
   the log head/tail text with the platform's line ending. Probably harmless (it is display text),
   but it is exactly "fine riga assunto dalla piattaforma". To be read, not assumed, in batch 1.
6. **Every default path is relative to the working directory**: `./workflows`, `./scripts`,
   `./feeds`, `./shared`, `./datasources.json`, `./ftp-targets.json`, `./logs/openproteo.log`. On
   Linux a packaged Tomcat usually runs with a working directory its user cannot write. That is
   what the batch 1 panel exists to show, and what the batch 4 override-file location must not
   depend on.

## 4. Intersections of rules, decided by name

Per the 2026-09-30 principle. Each is written where it applies; this is the index.

1. **GUI rule × existing file-only keys.** The rule says a feature with a file-only parameter is
   not complete; today every `orchestrator.*` key is file-only. On EXISTING debt the state wins:
   those features stay in production and the debt is closed by batches 4 and 5. On every NEW key
   the rule wins from this commit: the delivery declares itself incomplete in `COMMIT_MSG.txt` and
   adds the key to the debt list. A step `<param>` set from the designer already satisfies the
   rule. Written in `CLAUDE.md` «Configurazione esterna».
2. **Linux rule × conservative defaults.** Making a default platform-neutral could change what an
   existing instance does. Conservative defaults win on anything already saved: a stored value is
   never rewritten to make it portable. A platform-sensitive default applies to new instances and
   new objects only. Example: an FTPS target saved with `trustMode=WINDOWS` loads, saves and runs
   as WINDOWS on any host, and fails on a JVM without SunMSCAPI with the existing message; only a
   NEW target is proposed a mode by platform. Same for `powershellExe`: `pwsh` is proposed on a new
   Linux instance, an already configured value is never touched. ~~Written in the `CLAUDE.md` entry
   of this batch; NOT beside the two rules, whose text was dictated - see §6.2.~~ **Since
   2026-10-04 written beside both**: in full under «Default conservativi», as a pointer in the
   Linux rule.
3. **"Verified on Linux" × the sandbox JDK.** The sandbox compiles with a newer JDK and
   `--release 8`; it does not run Java 8. Every delivery names the JDK beside the word Linux.
   `ofPattern("DD")` is the recorded case where the two differ.
4. **Linux rule × external runners.** Stated in the rule itself: for powershell, cmd, bash the
   requirement is detection shown in the GUI, not identical behaviour. A `cmd` step on Linux is
   reported as unavailable; it is not a violation.

5. **~~«Funzionare in modo identico»~~ «Funzionare in modo equivalente» × `unarchive` -
   ANSWERED 2026-10-04.** «Identico» was the wrong word, by the author's account. The rule now
   reads: equivalent, respecting the context and the specifics of the host OS; OpenProteo adapts to
   its host. `unarchive` following the detected OS is therefore the rule working, not an exception,
   and the reading below needed no defending. Its test is now part of the rule text: where the host
   itself differs the behaviour follows it, and the run says which rules it used. The original
   paragraph is kept as written:

   ~~**«Funzionare in modo identico» × `unarchive`.**~~ The rule says every internal executor works
   identically on Windows and Linux. Since batch L1 `unarchive` does not, by the author's own Gate 0
   decision: it follows the detected OS, so the same workflow extracts different files on the two
   (Linux lifts the Windows-only name refusals, creates confined links, applies modes). On that
   case the Gate 0 decision stands, read as the rule's second branch, «rilevato a runtime»: the
   difference exists because the two file systems differ, and the run announces it - first log
   line, `${hostRules}`, a paragraph in `USAGE.md`. The opposite case stays neutral: json2csv's
   `FileMask` is case-sensitive on every host ("the same workflow must not select a different set
   of files on a developer's machine and on the server"), because nothing in the host requires a
   selection rule to differ. Test applied to future cases: a behaviour may follow the host only
   where the host itself differs AND the run says which rules it used. **This reading is mine** -
   see §6.1.

6. **Out-of-scope "container configuration" × the standalone artifact - ANSWERED 2026-10-04.**
   `server.port` is ignored under the external Tomcat, but in `openproteo-standalone.war` the
   embedded Tomcat takes the port from nowhere else. ~~Container configuration or runtime
   parameter? Batch 4 decides.~~ Conservative rule, for now: **`server.port` is settable from the
   GUI and takes effect at the next start of the application** (class "al riavvio"). `server.port`
   only; the other `server.*` keys stay out of scope. Left to the batch 4 spec: how, and what the
   GUI shows under the external Tomcat, where the value has no effect - a setting that cannot take
   effect says so, it is not silently accepted.

Open, deliberately not decided in batch 0:

7. **`X-User` × container identity** (§3.1 item 2). Batch 3 decides - **confirmed by the author
   2026-10-04**: the header is not touched before then; the batch 3 spec decides by name whether it
   survives and from whom it is trusted.

## 5. Declaration every delivery carries from now on

In `COMMIT_MSG.txt` and in the session note, three separate lines, never merged:

```
Verified on Linux (sandbox, JDK <n>, --release 8): ...
Verified on Windows: ...            (normally "nothing - left to the author", with the list)
Not verified on either: ...
```

## 6. Open points for the author

Points 1 to 3 were answered on 2026-10-04 and are struck through; the answers are in §4.2, §4.5,
§4.6 and in `CLAUDE.md`. The `server.port` question was put in chat, not in this list; it is §4.6.
~~Point 4 is still open.~~ Point 4 was answered the same day ("correggi") and is done.

1. ~~**Confirm or correct the reading in §4.5.** Taken literally, «identico» makes `unarchive` L1 a
   violation of a rule written the same day. Either the reading stands (and one clause could be
   added to the rule: "dove l'host stesso differisce, il comportamento lo segue e il run lo
   dichiara"), or `unarchive` is the declared exception. The rule text was dictated, so it is not
   edited here.~~ The word was corrected by its author; the clause was added.
2. ~~Promote intersection 2 into `CLAUDE.md` next to «Default conservativi», so it sits beside both
   rules as the principle asks? Left out because the rule text was dictated.~~ Done.
3. ~~Add two lines to «Checklist pre-commit»: (9) the Linux / Windows declaration of §5 is present;
   (10) no new parameter is file-only, or the delivery says it is incomplete. Not added: the
   request named the two places to align and the checklist was not one of them.~~ Added.
4. ~~`CLAUDE.md` «Cos'è OpenProteo» still says "senza embedded server", stale since the standalone
   artifact of 2026-08-03. Seen, not touched: unrelated to this amendment.~~ Corrected, together
   with the same claim in the Spring Boot line of «Stack e regole irrinunciabili».

## 7. Batch 1 — platform diagnostics panel (SPEC ONLY, no code yet)

Written 2026-10-04 on base `aaaf68f`. Code follows only after the author confirms §7.12.

### 7.1 What it is, and what it is not

One read-only page, `/platform`, backed by one endpoint, `GET /api/platform`. It answers the
question every later batch of this programme starts from: *what does this instance think its host
is, where do its paths really point, and which interpreters can it find* - before a run fails to
find out. It shows; it configures nothing. Changing any of these values is batch 4.

It adds **no runtime parameter** (checklist 10 holds trivially), **no dependency**, and touches no
executor, so no feed can change output.

### 7.2 Surface

| | |
|---|---|
| Endpoint | `GET /api/platform` -> JSON. Always HTTP 200: a field that cannot be computed carries its own `error` text and the rest is still returned. A diagnostics page that dies on the first unreadable directory is useless exactly when it is needed. (And an actionable outcome never travels in a 4xx - the IIS rule.) |
| Page | `/platform` (`platform.html`), route in `PageController`. |
| Link | One `Platform` button in the dashboard nav, after `Docs` (`dashboard.html:96`). Not in every topbar: that is 18 templates for a page opened a few times in an instance's life. |
| Code | `platform/PlatformProbe` - **JDK only, no Spring**, everything it needs is passed in, so it compiles with `--release 8` and RUNS in the sandbox against a real Linux file system. `web/PlatformController` is the thin adapter (a new controller, as `LogReportController` is, not more lines in `ApiController`). |
| Access | Public until batch 3 exists - the author's constraint. From batch 3 it is gated; which role is that batch's decision. |

### 7.3 The whitelist - every field, where it comes from, and why exposing it is acceptable

Nothing outside this table is returned. No `System.getProperties()`, no environment map, no secret,
no content of any configuration JSON.

| Field | Source | Why it is on the panel | Why exposing it is acceptable |
|---|---|---|---|
| `os.name`, `os.arch` | `System.getProperty`, literal keys | which host rules apply (unarchive already follows `os.name`) | fingerprint only; see the honest caveat in §7.8 |
| `java.version`, `java.vendor` | same | the contract is Java 8; this is where a Java 11 Tomcat is noticed | same |
| `file.encoding` | same | the platform default charset; differs between a Windows box and a Linux service | not sensitive |
| `user.dir` | same | every default path is relative to it (§3.1 item 6) | a path; same class as the path rows below |
| path rows (§7.4) | `AppProperties` getters | the point of the page | absolute directories are ALREADY returned to any caller by the file-list endpoints (`ApiController:3203`, `:3312` put the absolute `dir` in the response). No new class of disclosure. |
| `sunMscapi` | `Security.getProvider("SunMSCAPI") != null` | whether `trustMode=WINDOWS` can work on this JVM (`SslContexts:86-90`) | a boolean about the JVM, implied by `os.name` |
| interpreter rows (§7.5) | `AppProperties` + a search that executes nothing | availability shown in the GUI, never discovered at the first failed run - the rule's own words | the configured value is a file name; the resolved path is an install location, same class as the path rows |
| `caseSensitivity` (§7.6) | a probe in `defaultBaseDir` | objpack and unarchive both behave differently by it | one of three words |

**Excluded by name**, so the absence is a decision and not an oversight: `user.name`, `user.home`,
`java.class.path`, `java.home` as a field, the value of `PATH`, any `orchestrator.*` value that is
not one of the listed paths or interpreters, `orchestrator.masking-secret` **including whether it
is set** (a "set / not set" indicator for secrets belongs to batch 4, behind authentication), the
host name (already in `/api/env`, not duplicated), and any count derived from the configuration
files (see intersection 7.9.4).

### 7.4 Path rows

Keys, in this order: `workflowsDir`, `scriptsDir`, `sharedDir`, `defaultBaseDir`, `datasourcesFile`,
`ftpTargetsFile`, `maskPoolsDir`. For each:

| Column | Rule |
|---|---|
| `configured` | the raw value, as the application holds it |
| `absolute` | `Paths.get(value).toAbsolutePath().normalize()` - what `new File(value)` means to the code that uses it, since both resolve against `user.dir` |
| `expected` | `directory` or `file` |
| `exists` | `Files.exists` |
| `kindOk` | exists and is of the expected kind (a FILE where a directory is expected is the classic half-configured instance) |
| `writable` | `Files.isWritable`. For a FILE that does not exist yet: whether its parent directory exists and is writable, reported as `creatable`, because `datasources.json` is legitimately absent on a new instance. |

**The panel never creates anything**: no `mkdirs`, no touch. A missing directory is a finding.

`Files.isWritable`, not `File.canWrite()`: on Windows `canWrite()` looks only at the read-only
attribute, which means nothing for a directory. **Read from the jdk8u source, not run on Windows**:
`WindowsFileSystemProvider.checkAccess` asks for the effective `FILE_WRITE_DATA` right (for a
directory the same bit is "add file") and then checks the volume is not read-only. On Linux it is
`access(2)`, which **root always passes** - so the Linux verification must run as a non-root user,
the lesson unarchive L2 already paid for.

### 7.5 Interpreter rows - resolved WITHOUT being executed

One row per configured interpreter: `powershell` (`powershellExe`), `cmd` (`cmdExe`), `java`
(`javaExe`). Batch 2 adds `bash` as a fourth row with no change to the page.

Status is one of four values, and the fourth matters:

| Status | When |
|---|---|
| `NOT_CONFIGURED` | the value is empty |
| `FOUND` | a regular file was found; `resolved` carries its absolute path, `how` says `absolute path` or `searched` |
| `NOT_FOUND` | nothing matched |
| `UNDETERMINED` | the value is a RELATIVE path containing a separator (`tools/pwsh`). `StepExecutor:84` gives each step its own working directory, and what a relative executable path is resolved against then differs by OS and by step. The panel cannot know, and says so rather than guessing. |

How a bare name (`pwsh`, `cmd.exe`) is searched, which is what `ProcessBuilder` leaves to the OS:

- **Not Windows**: each directory of the `PATH` the JVM was started with, in order; first entry
  that is a regular file and `Files.isExecutable`. No extension is added.
- **Windows** (`os.name` starts with `windows`, the same test `HostRules.detect` uses): `.exe` is
  appended when the name has no extension - `CreateProcess` does that, and it does NOT consult
  `PATHEXT`, so a `foo.cmd` on the path is not found by the name `foo`, here as there. Order:
  the directory of the JVM's own executable, the working directory, `%SystemRoot%\System32`,
  `%SystemRoot%\System`, `%SystemRoot%`, then `PATH`.

The search reads exactly two environment variables, `PATH` and `SystemRoot`, by name. Neither is
returned.

**Limits, stated on the page itself**: `FOUND` means a file is there, not that it runs - a broken
`pwsh` install is still `FOUND`. No version is shown, because showing one means executing it. And
the Windows order above is the documented behaviour of `CreateProcess`, written from documentation:
**it cannot be exercised in the sandbox.**

### 7.6 Case-sensitivity probe of `defaultBaseDir`

Method as required: create a temporary file, look it up under a different case, delete it.

1. Only if `defaultBaseDir` exists, is a directory and is writable. Otherwise the result is
   `NOT_DETERMINED` with the reason - never `CASE_SENSITIVE` by default.
2. File name `.op-caseprobe-<16 hex>.tmp`, created with `CREATE_NEW` (never overwrites).
3. Lookup: `Files.exists` on the same name upper-cased. Found -> `CASE_INSENSITIVE`; not found ->
   `CASE_SENSITIVE`.
4. Deleted in a `finally`. If the delete fails the response says so and names the file
   (`leftover`), so a stray file has an explanation.

Two things the method needs that the request did not spell out:

- **The probe is cached.** The endpoint is a public GET and the probe WRITES. Uncached, anyone
  could make the instance create and delete files as fast as they can send requests. The result is
  kept per absolute path for the life of the JVM, with the time it was taken shown on the page; a
  `Probe again` button re-runs it, and the server refuses to probe more than once per 60 seconds
  whatever the client asks.
- **The answer is about that directory, not about "the server".** On Linux case sensitivity is a
  property of the file system, and with ext4 casefold of a single directory; a feed whose own
  `baseDir` lives elsewhere can differ. The page says which directory was probed.

objpack already has a probe that writes nothing (`ObjectPack:509`: the directory's own name with
the case flipped must resolve to the same canonical path). It is NOT used here: it answers for the
PARENT directory, and it cannot answer at all for a name without letters. The requested method
answers for the directory itself.

### 7.7 Response shape

```
{ "ok": true,
  "system":  { "osName": "...", "osArch": "...", "javaVersion": "...", "javaVendor": "...",
               "fileEncoding": "...", "userDir": "..." },
  "paths":   [ { "key": "workflowsDir", "configured": "./workflows", "absolute": "...",
                 "expected": "directory", "exists": true, "kindOk": true, "writable": true } ],
  "sunMscapi": false,
  "interpreters": [ { "key": "powershell", "configured": "powershell.exe",
                      "status": "NOT_FOUND", "resolved": "", "how": "searched" } ],
  "caseSensitivity": { "directory": "...", "result": "CASE_SENSITIVE",
                       "probedAt": "2026-10-04 09:12:03.412", "leftover": "" } }
```

### 7.8 The disclosure argument, without pretending

"Harmless" is too strong for `os.name` and `java.version`: they tell an attacker which exploits to
try. The honest argument is comparative. On an instance without authentication a caller can already
upload a script and run it (`ApiController:3379`), read the host name and build (`/api/env`), and
read absolute directories (the file-list endpoints). Against that, this endpoint discloses nothing
the same caller could not print with one step. **That argument expires with batch 3**: once
authentication exists the endpoint must be gated, and the reason it was acceptable to leave it
public stops being true the same day.

No kill switch is added. The precedent (`openproteo.logreport.export-enabled`) would be a new
file-only key, which checklist item 10 now forbids - see intersection 7.9.5.

### 7.9 Intersections, decided by name

1. **"Read-only panel" × the case probe, which writes.** The probe is the one declared exception:
   one temp file, only inside `defaultBaseDir`, only when that directory already exists and is
   writable. The panel never creates a directory. Example: `defaultBaseDir` missing -> the path
   row says `exists: false`, the probe says `NOT_DETERMINED: directory does not exist`, nothing is
   created.
2. **"Resolve every path" × an empty value.** `Paths.get("")` resolves to `user.dir`. An unset
   `maskPoolsDir` (the default) would then be shown as the working directory, existing and
   writable - a lie. Empty wins: the row says `not set` and nothing is resolved.
3. **"Resolvability of every interpreter" × a relative path with a separator.** `UNDETERMINED`
   wins over a guess (§7.5).
4. **"Useful diagnostics" × "no content of the configuration JSON".** The useful line "N FTPS
   targets are saved with `trustMode=WINDOWS` and this JVM has no SunMSCAPI" needs a count read
   from `ftp-targets.json`. The prohibition wins on a public endpoint: `sunMscapi` is shown alone.
   The combined warning moves to the FTPS targets page in batch 4, where the data already is.
5. **"Public endpoint" × "no new file-only parameter".** No kill-switch property. The mitigation
   is the whitelist and batch 3, not a switch nobody can reach from the GUI.
6. **"Equivalente" × this page.** The page shows DIFFERENT things on Windows and Linux by design -
   that is its job. The rule's clause applies to itself: the page states which search rules it used
   for interpreters (`windows` or `path-only`).
7. **Linux rule × `HostRules`.** `unarchive.HostRules.detect` REFUSES an OS that is neither Windows
   nor Linux, which is right for an executor and wrong for a diagnostics page: on macOS the panel
   must still render. The panel does not call it; it uses the same `startsWith("windows")` test,
   and the suite lifts `HostRules.detect` from its source and asserts the two agree on Windows and
   Linux names.

### 7.10 `System.lineSeparator()` in `ApiController` - read, as §3.1 item 5 promised

- `:1689` builds the text the run page shows as the step log tail. Display only. Harmless.
- `:1488-1493` builds the step-log block that goes INTO `audit_report.md` / `.docx`. So the line
  endings inside the fenced log block of an audit report follow the host: CRLF on Windows, LF on
  Linux. The report reads the same; its bytes differ.

**Not changed, and recommended to stay**: forcing `\n` would change the bytes of every audit report
generated on the Windows production box, for no reader-visible gain. Under «equivalente» a report
whose content is the same and whose line endings follow its host is within the rule. Recorded so
the next person who greps for `lineSeparator` finds the decision instead of a suspicion.

### 7.11 Verification plan

Verified on Linux (sandbox, JDK 21 with `--release 8`), by running the real `PlatformProbe`:

- paths: existing, missing, a file where a directory is expected, empty value, a non-writable
  directory - **as a non-root user**, because the sandbox runs as root and root passes every
  `access(2)` check; with a positive control showing the same check passes as root;
- interpreters: `bash` and whatever `pwsh` the sandbox has, an absolute path, a bogus name, a
  relative path with a separator, an entry on `PATH` that is a directory or not executable; the
  WINDOWS search order exercised with injected `os.name`, environment and a fake directory tree
  (`.exe` appended, `PATHEXT` ignored, System32 before `PATH`) - which proves the logic, not Windows;
- case probe: `CASE_SENSITIVE` on the sandbox file system; no leftover file; the 60-second floor;
  `NOT_DETERMINED` on a missing and on a read-only directory. A case-insensitive file system on
  Linux will be attempted (a vfat or casefold loop mount); if the sandbox refuses it, the
  `CASE_INSENSITIVE` branch is declared unexercised rather than faked;
- whitelist: a scan over the `platform` package - no `getProperties(`, no argument-less `getenv()`,
  `getProperty` literals within §7.3, `getenv` literals within `{PATH, SystemRoot}` - each with a
  positive control; and a test that sets a masking secret and asserts the value appears nowhere in
  the response;
- page: jsdom against the real template, controls driven through their own handlers, structure
  asserted by nesting; no literal `\n` / `\r`, no `[[` / `[(`, only defined CSS variables;
- mutations on copies, each opened if it stays green.

Left to the author on Windows, with the expected values to compare: `cmd.exe` and `powershell.exe`
`FOUND` under `System32`; a bogus name `NOT_FOUND`; `sunMscapi: true`; `CASE_INSENSITIVE` on NTFS;
a directory denied by ACL shown `writable: false`; and the same page behind IIS.

Not verifiable on either before deploy: `mvn clean package`, the controller wiring, a real browser.

`USAGE.md` gains a «Platform diagnostics» section in the CODE delivery, verified through
`docs.html`'s own `render()`. Not in this one: there is nothing to document until the page exists.

### 7.12 Gate 1 - five questions before any code

1. **Three more rows than the request listed?** Recommended yes: `globalVarsFile` (effective path:
   the explicit value, else `<sharedDir>/global-vars.properties`, as `GlobalVarsStore:40` resolves
   it), the log file directory (`./logs`, relative to `user.dir` - the first thing that fails on a
   Linux Tomcat), and `java.io.tmpdir` (`WorkflowPorter:253` stages imports there).
2. **Two more system fields?** Recommended yes: `sun.jnu.encoding` and the effective
   `Charset.defaultCharset()`. A Linux service started with no locale gets `ANSI_X3.4-1968` for
   file NAMES, and every non-ASCII file name then fails - the most Linux-specific failure this page
   could catch, and `file.encoding` alone does not show it.
3. **Probe caching as in §7.6** (JVM-lifetime cache, `Probe again`, at most one probe per 60 s)?
   The alternative is probing on every GET, which on a public endpoint is a write anyone can drive.
4. **The link only in the dashboard nav** (§7.2), or in every topbar?
5. **Leave `System.lineSeparator()` in the audit report as it is** (§7.10)?
