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

7. **`X-User` × container identity** (§3.1 item 2). Batch 3 decides.

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
Point 4 is still open.

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
4. `CLAUDE.md` «Cos'è OpenProteo» still says "senza embedded server", stale since the standalone
   artifact of 2026-08-03. Seen, not touched: unrelated to this amendment.
