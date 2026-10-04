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

## 7. Batch 1 — platform diagnostics panel ~~(SPEC ONLY, no code yet)~~ — DELIVERED

Written 2026-10-04 on base `aaaf68f`. ~~Code follows only after the author confirms §7.12.~~ Gate 1
answered the same day (all five as recommended); code delivered on base `7b52145`. What the
implementation changed or added against this text is in §7.13, not edited in place.

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

**Answered 2026-10-04: all five as recommended.** Three more path rows; `sun.jnu.encoding` and the
effective default charset; the probe cached with a 60-second floor; the link in the dashboard nav
only; `System.lineSeparator()` in the audit report left as it is.

### 7.13 Delivered — what the implementation decided beyond §7.1-7.11

1. **`Probe again` is its own endpoint, `POST /api/platform/case-probe`**, returning the same
   document as the GET. A request that writes a file is not a GET. The first GET after a start
   still probes once; every later GET answers from the cache.
2. **A `NOT_DETERMINED` answer is never cached or throttled.** Nothing was written to reach it, so
   there is nothing to protect, and a directory created a moment later is probed at once.
3. **Fields the §7.7 example did not show**: on a path row `set` and, for an absent file,
   `creatable` (then `kindOk` / `writable` are absent, not false); on an interpreter row `rules`
   and, when there is something to say, `detail`; on the probe `cached`, `throttled`, `reason`;
   in `system` the two fields of Gate question 2 plus `interpreterRules`. A row never carries a key
   whose value would be a guess.
4. **A file that exists but is not executable** (non-Windows) is `NOT_FOUND` with a `detail`
   naming it, and the search goes on: a later `PATH` entry can still win, and then the stale detail
   is removed. Under Windows rules executability is not tested - the notion does not exist there.
5. **No `PATH` at all** (non-Windows): the JDK's own fallback, `/bin:/usr/bin`, is searched. **An
   empty `PATH` entry is skipped**: to the OS it means the current directory, which for a step is
   that step's own folder - the same unknowable as §7.9.3.
6. **Under Windows rules an absolute path is also recognised by its shape** (drive letter, UNC):
   `Paths.get("C:\\x").isAbsolute()` is false on the JVM that runs the tests. On Windows itself
   `isAbsolute()` already answers.
7. **The scan is a committed tool**, `tools/scan_platform_whitelist.js`, exit 1 on a violation -
   not advisory. It also asserts the one permitted write: exactly one `createFile`, exactly one
   `delete`, no API that creates a directory, none that starts a process.

**Not exercised, and said plainly:**

- **`CASE_INSENSITIVE` on a real file system.** Tried and refused by the sandbox kernel: no
  `mkfs.vfat`, an ext4 casefold image would not mount, tmpfs `casefold` rejected. The branch is one
  ternary whose other arm IS measured, and inverting it is caught - but the first time this code
  meets a case-insensitive directory is NTFS on the author's machine.
- **A real `pwsh`.** None is installed in the sandbox. `bash` and `sh` are the real interpreters
  found; `powershell.exe` and `cmd.exe` are correctly `NOT_FOUND` here.
- **Windows.** The search order is exercised with an injected `os.name`, environment and a fake
  directory tree - the logic, not the platform.

**Windows, seen by the author 2026-10-04 (screenshot of `/platform` at `12cfc3a`, Windows 11, Azul
Java 1.8.0_362, working directory `D:\Programmi\openproteo`).** What the screenshot shows, and
therefore what is now verified on Windows: the controller is wired and Jackson serialises the
document; the page renders in a browser; the system rows are right (`Cp1252` / `windows-1252`); the
path rows resolve against `user.dir` and report `missing` for `./workflows`, `./scripts`, `./shared`,
`./feeds`, and `absent, can be created` for `./datasources.json`. What it does NOT show, being below
the fold: the interpreter rows, the trust store row and the case probe. And since `./feeds` is
missing on that instance, the probe there answers `NOT_DETERMINED` - **the `CASE_INSENSITIVE` branch
has still not run on a real file system.** One cosmetic defect seen: the Setting column wraps
(`orchestrator.workflows-` / `dir`); fixed with the batch 2 code.

## 8. Batch 2 — `bash` runner, process-tree kill, platform-sensitive PowerShell — DELIVERED (Linux)

Written 2026-10-04 on base `12cfc3a`. ~~Code follows only after the author answers §8.14.~~ Gate 2
answered the same day; code delivered on base `5d7593d`. What the implementation decided beyond
this text is in §8.15, not edited in place. **Windows tree kill is NOT delivered** - §8.7 said it
would not be; the measurement kit is in `tools/windows-proctree-kit/`.

### 8.1 Scope

A fourth external runner, `bash`; the kill of the whole process tree on timeout and abort; a
PowerShell default that follows the host. `bash` is a NEW exec: no existing workflow uses it, so
nothing existing changes because of it. Two things DO change existing behaviour and are argued by
name: the tree kill (§8.7) and Stop on a fan-out step (§8.8).

### 8.2 Measured before designing

Sandbox: Linux 6.18, bash 5.2.21, **a real Java 8 runtime** (Temurin 1.8.0_432) and JDK 21,
**PowerShell 7.4.6 for Linux**. Every row was run, with the REAL `StepExecutor` and `RunControl`
compiled by the Java 8 `javac` wherever the row is about them.

| # | Question | Measured |
|---|---|---|
| M1 | Does the `-EncodedCommand` / `-ExecutionPolicy Bypass` / `[ScriptBlock]::Create` bootstrap work unchanged under `pwsh` on Linux? | **Yes.** Named parameters arrive intact (`O'Brien & co`, `a "q" $x` and a backtick), `##VAR` is parsed, UTF-8 output is intact, `exit 7` -> 7, a `throw` -> 1, a native command's exit code propagates, a script path containing `'` works. `-ExecutionPolicy Bypass` is accepted and has no effect (`Get-ExecutionPolicy` says `Unrestricted`). |
| M2 | What does the current default `powershell.exe` do on Linux? | `IOException: Cannot run program "powershell.exe" ... error=2`. |
| M3 | How do PowerShell errors reach the step log under `pwsh`? | As `#< CLIXML` followed by one XML line full of `_x001B_[31;1m` escapes. With `-OutputFormat Text` and `TERM=dumb` in the child's environment: `Exception: boom`. `NO_COLOR`, `$PSStyle.OutputRendering` and `$ErrorView` do NOT remove the colouring. |
| M4 | What does `destroyForcibly()` leave behind? Script: a background child, an orphaned child (`(sleep &)`), a grandchild, a `setsid` child, a `nohup` double fork, a foreground child. | **All six survive**, the foreground one included. They hold the step's stdout pipe, so the pump thread stays blocked and `execute` returns 5 s late. |
| M5 | Kill by walking the tree from the pid? | Four die. The two orphans survive: once their parent has exited they belong to pid 1. Pump still blocked. |
| M6 | Launch under `setsid`, then kill everything in that session plus the descendants? | **Zero survivors, 54 ms, pump released.** Same result on Java 8 and 21. `setsid` did not fork: the Java `Process` pid IS the session leader. |
| M7 | What still escapes M6? | A process that leaves BOTH the tree and the session: `( setsid sleep & )`. Measured: it survives and holds the pipe. |
| M8 | Is the pid reachable on Java 8? | Yes: field `pid` of `java.lang.UNIXProcess`, by reflection. On Java 9+ `Process.pid()` exists and is public. |
| M9 | A non-ASCII parameter value (`è€日`) handed to a child by `ProcessBuilder`, service started with no locale (`sun.jnu.encoding=ANSI_X3.4-1968`) | **Silently becomes `???`**, as a positional argument AND as an environment variable, on Java 8 and 21. With `LANG=C.UTF-8` both are intact. |
| M10 | The same value inside an all-ASCII `bash -c` bootstrap, written as `$'\xc3\xa8...'` | **Intact under every locale**, as PowerShell's base64 is. |
| M11 | `./script.sh` vs `bash script.sh` on a `noexec` mount | `Permission denied` vs runs. A script with no `x` bit also runs through the interpreter. |
| M12 | A `.sh` with CRLF line endings | Simple commands print their output with a trailing CR; an `if`/`fi` gives `syntax error: unexpected end of file`, exit 2. Nothing mentions line endings. |
| M13 | `##VAR name=value\r\n` | Already tolerated today: `readLine()` drops the CR and the value is trimmed (`StepExecutor:105-108`). A CR in the MIDDLE of a line ends the line there. |
| M14 | Stop on a fan-out step, three concurrent items | **Only the last-started item is killed. The other two run to the end** (12 s, exit 0). See §8.8. |
| M15 | The bootstrap under a shell that is not bash (`dash`) | `exec: : Permission denied`, exit 126 - it fails, but says nothing useful. |

### 8.3 How an external runner is registered - derived from the code, not from the internal table

The 8-location rule is for internal executors. An external runner touches:

1. `StepExecutor`: the `Kind` enum, `resolveKind` (exec name and file extension), `buildCommand`, and the constructor that receives the interpreter.
2. `AppProperties` (field, getter, setter) and `WorkflowEngine:1047`, which builds the `StepExecutor`.
3. `WorkflowXmlParser:89`: the allowed `exec` values AND the error text beside it. NOT the `internal` list, so `script` stays required.
4. `designer.html`: `isExternal` (754), the `<option>` list (1050), the script placeholder (1079), the parameter hint (2109), the help line (165). `buildXml` writes `exec` generically.
5. `filespanel.js`: the editable-extension regex (20), the upload label (36).
6. `PlatformController` and `platform.html` (`EXE_LABELS`): the interpreter row.
7. `USAGE.md`, «Executors».

Nothing in `WorkflowEngine.internalKind`, `WorkflowPorter` (it bundles any `script`), or the upload endpoint (no extension check).

### 8.4 The `bash` runner

| | |
|---|---|
| Kind | `BASH` |
| `exec` | `bash`. No `sh` alias: the runner needs bash (§8.4, M15), and a name that promises POSIX sh would lie. |
| Auto-detect | `.sh` |
| Interpreter | `orchestrator.bash-exe`, default `/bin/bash` |
| Invocation | always through the interpreter (M11): `[bashExe, "-c", <bootstrap>]` |

**The script travels as a path and the parameters as data, in an all-ASCII bootstrap** - the
PowerShell precedent, for the reason M9 measured:

```
[ -n "$BASH_VERSION" ] || { echo "orchestrator.bash-exe is not bash" >&2; exit 126; }
export OP_inputFile=$'/data/\xc3\xa8 file.csv'
export OP_dir_STEP=$'/x/it\x27s'
exec "$BASH" $'/opt/op/scripts/prepare.sh'
```

After the `exec` the process IS `bash /opt/op/scripts/prepare.sh`: `$0` is the script, the exit
code is the script's, and the command line carries no value (measured).

#### Parameter convention, against the two that exist

| Runner | Convention | Order matters | Value on the command line |
|---|---|---|---|
| CMD, JAR | positional, the name is a label | yes | yes, readable |
| PowerShell | named, `-Name 'Value'` | no | yes, base64 |
| **bash** | **named, environment variable `OP_<name>`** | **no** | **no** |

Why named: a workflow declares `<param name="...">`, and a positional script breaks silently when
two params are reordered in the designer. Why the environment and not `--name value`: bash has no
parameter binding, so named arguments would make every script carry its own `getopts` loop.
Why not positional as well: on Linux a command line is readable by every local user
(`/proc/<pid>/cmdline` is mode 0444) and the environment only by the same user (0400); positional
values would put FTPS passwords where `ps` shows them. And batch 6 needs the environment anyway.

**Name mapping.** `OP_` + the parameter name with every character outside `[A-Za-z0-9_]` replaced
by `_`; case kept. `inputFile` -> `OP_inputFile`, `dir.STEP` -> `OP_dir_STEP`. The prefix is not
decoration: without it a parameter named `PATH`, `IFS` or `LD_PRELOAD` would reconfigure the shell.
Two parameters that map to the same variable (`a.b` and `a_b`) fail the step BEFORE launch, naming
both. Reserved engine params (`deleteOnSuccess*`, `outputData.*`) are not passed, as for the others.

**Limits.** A value cannot contain NUL. The bootstrap is one argument, and Linux caps one argument
at 128 KiB: a step whose parameters exceed that fails before launch with the size, instead of
`E2BIG` from the kernel. `orchestrator.bash-exe` must be bash: the bootstrap's first line says so
in words when it is not (M15).

### 8.5 Protocol parity

`##VAR name=value`, exit code, timeout (`-999`), abort: the same code path as the other kinds, no
branch. M13 shows the trailing `\r` is already tolerated; the code batch adds the assertion, not a
change. stdout and stderr are read as UTF-8 as today.

### 8.6 A `.sh` with CRLF line endings

A script edited on Windows and uploaded fails as in M12. Proposed: **refused before launch**, with
the line number of the first CRLF and the words "bash needs LF line endings". Not normalised on the
fly: executing a corrected copy would mean the text that ran is not the file in `scripts/`, which is
the property batch 6 wants to keep as evidence. (Batch 6 normalises INLINE bodies because there it
owns the materialisation.) The check reads the file once; a file over 16 MB is not checked and runs.

### 8.7 Process-tree kill on timeout and abort

**Linux, and any non-Windows host with `/proc`** - for every external runner there (bash, `pwsh`,
`java -jar`), because the defect is the launcher's, not bash's:

1. The step is launched as `[setsid, <the command>]` when `setsid` is found on `PATH` (searched
   once at startup, without executing it, as the Platform page searches). It becomes a session
   leader; pid, exit code and streams are unchanged (M6).
2. The pid is taken by reflection: `Process.pid()` when it exists, else the `pid` field (M8).
3. On timeout or abort: victims = descendants of the pid by `/proc/*/stat`, plus every process whose
   session id is the pid. `SIGSTOP` to all, a second scan to catch a fork that raced the first,
   `SIGKILL` to all. Then `destroyForcibly()` as today. Signals are sent by `/bin/sh -c "kill ..."`
   with numeric pids only: `kill` is a builtin there, so no extra binary is assumed.

**Degraded modes, each written into the step log and shown on the Platform page, never silent:**

| Condition | What happens |
|---|---|
| no `setsid` | descendants only - orphans survive (M5) |
| pid not obtainable (reflection refused) | `destroyForcibly()` only, as today (M4) |
| no `/proc` | `destroyForcibly()` only |

**Risks.**
- Reflection on a private JDK field (Java 8). If the field is absent or inaccessible the code
  degrades, it does not fail the step. On Java 9+ no private access is needed.
- Pid reuse between the scan and the signal. The window is milliseconds and `SIGSTOP` freezes the
  set first; a process that died and whose pid was reused inside that window would be killed by
  mistake. Mitigated, not eliminated: before `SIGKILL` each victim's start time (`stat` field 22) is
  compared with the one read at scan time, and a mismatch is skipped.
- A process that leaves both the tree and the session survives (M7). Declared in `USAGE.md`.
- A script that starts a background child and then exits NORMALLY leaves it running. Not touched:
  the request is timeout and abort, and starting a daemon is a legitimate thing for a script to do.

**Why this may change existing behaviour.** `destroyForcibly()` today kills the interpreter and
nothing else (M4). A step that timed out keeps working in the background, writing into a run the
orchestrator has already closed as failed, and the next run can start beside it. Killing the tree
is what "the process was killed by the orchestrator" - the line already written to the log - has
always claimed.

**Windows: NOT solved in this batch, and said so.** On Java 8 `java.lang.ProcessImpl` holds a
HANDLE, not a pid, and turning one into the other needs native code this project does not have. A
design exists - find the child among the JVM's children by command line through
`Get-CimInstance Win32_Process`, then `taskkill /T /F`, refusing when the match is not unique - but
none of it can be run here, and a kill that picks the wrong process is worse than no kill. So:
PowerShell and CMD on Windows keep today's behaviour, the Platform page says "process tree is not
killed on this host", and the code delivery includes a **measurement kit**: one self-contained Java
class and a `.cmd` for the author to run on Windows, which reports what survives today, whether the
CIM lookup identifies the child, and how long it takes. The Windows design is then written on
measurements, as the Linux one is. On Java 9+ under Windows `Process.pid()` + `taskkill /T /F`
would work; the kit measures that too.

### 8.8 Found while measuring: Stop does not stop a fan-out

`RunControl.process` is ONE field per run (`RunControl:10`). A `forEach` step with concurrency > 1
runs several `StepExecutor.execute` calls on the same control; each overwrites the field at
`StepExecutor:90` and clears it at `:142`. `WorkflowEngine.stop()` (`:490`) destroys whatever is in
the field at that instant. M14: three items, Stop after two seconds - the last item dies, the other
two run to completion and exit 0. On every platform, for PowerShell and CMD, today.

Proposed in this batch, because the kill code is being rewritten anyway: the control holds the SET
of live processes; Stop kills each (with its tree where §8.7 applies). `process` stays as a field
for source compatibility and is no longer what Stop reads.

### 8.9 PowerShell follows the host

- **Default.** `orchestrator.powershell-exe` unset -> `powershell.exe` on Windows, `pwsh` elsewhere.
  For that to exist, the line `orchestrator.powershell-exe=powershell.exe` must LEAVE the bundled
  `application.properties` (line 15): while it is there the key is always "configured" and no
  default can apply. A value in the external file is never touched.
- **Error stream (M3).** Outside Windows the command gains `-OutputFormat Text` and the child gets
  `TERM=dumb`. On Windows nothing changes - pending §8.14 question 6.
- M1 stands: the bootstrap itself needs no change.

### 8.10 Platform page

A `bash` interpreter row. A new row "Process-tree kill" with one of: `session` (setsid found),
`descendants only`, `not available on this host`. A VERDICT on the file-name encoding row: on a
non-Windows host a `sun.jnu.encoding` that is not UTF-8 is red, with what M9 measured in one
sentence. The Setting column stops wrapping.

### 8.11 Intersections, decided by name

1. **"Invoke through the interpreter, `bash script.sh`" × the bootstrap.** The first process is
   `bash -c <bootstrap>`; after `exec` it is `bash script.sh`, same pid. The noexec property holds
   (M11): the file is never executed directly at any point.
2. **"Parameters as environment variables" × a service with no UTF-8 locale.** The environment the
   SCRIPT sees is built by bash from ASCII escapes, not handed over by the JVM, so M9 does not
   apply. For CMD and JAR kinds on a non-Windows host M9 DOES apply, today: a parameter the JVM
   cannot encode fails the step before launch with a message pointing at the Platform page, instead
   of reaching the program as `?`. That is a new refusal on an existing runner; it replaces a
   silent corruption and cannot occur on Windows, where arguments are passed as UTF-16.
3. **"A configured value is never rewritten" × the bundled `powershell-exe` line.** Removing it is
   not rewriting a configured value: it is the only way an unset key can exist. A Windows instance
   computes the same `powershell.exe` it had. A Linux instance with no explicit value moves from
   `powershell.exe` - which cannot start (M2) - to `pwsh`. An explicit value anywhere wins.
4. **"No change to existing runners" × tree kill and fan-out Stop.** Both change what timeout and
   Stop DO; neither changes what a step that completes produces. Tree kill: non-Windows only.
   Fan-out Stop: every platform.
5. **"Equivalente" × `cmd` on Linux and `bash` on Windows.** Neither is made to work. Each is
   reported by the Platform page and, per the rule, in the designer when batch 6 adds that signal.
   On Windows a configured Git-bash or WSL `bash.exe` is launched as configured; it is not tested.
6. **Checklist 10 × `orchestrator.bash-exe`.** A new runtime parameter that only a file can set,
   because batch 4 does not exist. The delivery declares itself incomplete on that point and adds
   the key to the debt list in «Configurazione esterna». Applies: at the next run of a step.

### 8.12 What is NOT in this batch

Inline commands (batch 6). Designer warning for an unavailable interpreter (batch 6). Windows tree
kill (measurement kit only). Any change to PowerShell's command on Windows.

### 8.13 Verification plan

Verified on Linux, on a real Java 8 runtime AND JDK 21, as root and as a non-root user: the real
`StepExecutor` through bash (values with quotes, `$`, backticks, newlines, non-ASCII under `LANG`
unset and `C.UTF-8`; mapping and its collision; the 128 KiB refusal; CRLF refusal with the line
number; a non-bash interpreter; exit codes; `##VAR` with CR; a noexec mount; no `x` bit); the kill
matrix of M4-M7 as assertions with survivor counts, in each degraded mode; fan-out Stop; `pwsh`
through the real bootstrap with the error stream as text; the default of `powershell-exe` by
`os.name`; designer and Platform page in jsdom; `USAGE.md` through `render()`; mutations on copies.

Left to the author on Windows: that PowerShell and CMD steps behave exactly as before (same command,
same log), fan-out Stop, the default staying `powershell.exe`, and the measurement kit.

### 8.14 Gate 2 - questions before any code

1. **Parameters to bash as `OP_<name>` environment variables only** (§8.4)? Alternative: also
   positional `$1..$n`, at the cost of the values being on the command line.
2. **A `.sh` with CRLF is refused before launch** (§8.6)? Alternatives: run it as it is (M12), or
   normalise a temporary copy.
3. **Tree kill for every external runner on non-Windows**, launched under `setsid` (§8.7)?
   Alternative: bash only.
4. **Windows: measurement kit now, design after** (§8.7)? Alternative: implement the CIM lookup
   blind and have the author test it.
5. **Fix fan-out Stop in this batch** (§8.8)? It changes existing behaviour on Windows: Stop will
   stop every item.
6. **What does a FAILING `.ps1` step log look like on Windows today** - readable text, or
   `#< CLIXML`? If it is CLIXML there too, `-OutputFormat Text` is worth applying on both; if it is
   readable, Windows stays untouched. This one needs a look at a real log, not a recommendation.
7. **Refuse a CMD/JAR parameter the JVM cannot encode, on non-Windows** (§8.11.2), and make the
   file-name encoding row red when it is not UTF-8?
8. **Remove `orchestrator.powershell-exe=powershell.exe` from the bundled `application.properties`**
   (§8.9)? Without it no platform-sensitive default is possible.

**Answered 2026-10-04.** 1-5, 7, 8: yes, as recommended. 6: "readable text, and where it is CLIXML
apply `-OutputFormat Text`". Read as: a failing `.ps1` logs readable text on Windows today, so
Windows is left exactly as it is; outside Windows, where M3 measured CLIXML, the option is applied
together with `TERM=dumb`. Not covered, and said: `pwsh.exe` configured on WINDOWS may log CLIXML
too - nobody has looked, and the rule keys on the host, not on which PowerShell it is.

### 8.15 Delivered — what the implementation decided beyond §8.1-8.13

1. **`setsid` is used only when the program can be found.** Under `setsid` a missing interpreter no
   longer failed as the JVM's "Cannot run program ..." but as exit 127 from `setsid`, a different
   failure for the three existing runners. The launcher now asks the Platform page's own search
   first and wraps only what it can find (or cannot judge: a relative path).
2. **The step waits for a Stop to finish reporting.** Stop kills from another thread; the step saw
   its process die and closed its log before the kill had written what it reached. The handle now
   carries a flag set BEFORE the first signal and a latch released after the report.
3. **`SIGSTOP` before the kill is proven necessary by a test, not assumed.** The mutation removing
   it was green. A script that forks without pause (`while :; do sleep N & done`) leaves survivors
   without it and none with it, on three consecutive runs.
4. **A Stop that arrives just before a process joins the live set is honoured**: the step checks
   `aborted` right after registering and kills what it has just started.
5. **A refusal is written to the step log AND thrown** (`!!! NOT STARTED - ...`). Thrown, because
   that is how a missing interpreter already ends a step: no retry, the message on the step.
6. **The bootstrap guards its own interpreter** (`$BASH_VERSION`), turning M15's meaningless
   `exec: : Permission denied` into "orchestrator.bash-exe is not bash", exit 126.
7. **The explicit `exec` in the bootstrap is redundant on bash 5.2 - measured** - which replaces
   itself with the last command of `-c` anyway. It stays for older bash, where it is the only thing
   keeping the parameter values off a long-lived command line. The mutation removing it is
   therefore equivalent HERE, and is recorded as such.
8. **Pid reuse** is decided by a function of two snapshots (`confirmed`), so that the rule has a
   test: a pid whose start time changed between the scans is left alone and, if it had been
   stopped, is resumed.
9. **Platform page**: a `bash` row; "Stopping a step" with `whole process tree` / `partial` /
   `interpreter only` and the reason; a red `not UTF-8` verdict on the file-name encoding outside
   Windows; the Setting column no longer wraps.
10. **`orchestrator.bash-exe` is born file-only** (§8.11.6): added to the debt list in `CLAUDE.md`
    «Configurazione esterna». On that point this delivery is incomplete by the contract's own
    definition, until batch 4.

**Three traps the test harness fell into, kept because each is the product's own subject:**

- Python sets `LC_CTYPE=C.UTF-8` for its children. The first seventeen mutation runs therefore ran
  WITH a UTF-8 locale while claiming none, which hid the mutation on unencodable arguments.
- The check "no parameter value on any command line" counted the test's own shell, whose command
  line contained the probe word. It now has a positive control run from Java.
- `pkill -f 'sleep 330'` killed the shell that typed it.

**Not exercised, and said plainly:**

- **Anything on Windows.** That PowerShell, CMD and JAR commands are unchanged there is shown by
  comparing `buildCommand` with the pre-patch class under an injected `os.name` - the logic, not
  the platform. Fan-out Stop on Windows is the same Java code but has not run there.
- **`CASE_INSENSITIVE`**, still (batch 1).
- **bash older than 5.2**, `setsid` from anything but util-linux, a `noexec` mount other than tmpfs.
- **`mvn clean package`**, Spring wiring of the changed constructor call, a browser.
- **The measurement kit** itself: compiled with Java 8, its non-Windows exit run, nothing else.
