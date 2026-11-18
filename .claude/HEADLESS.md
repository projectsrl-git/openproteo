# Headless instance and command-line control — spec

Opened 2026-11-12 on base `eb9790d`. **Spec only (batch H0): no code until the author confirms §12.**
Every later batch adds its own section here before its code, as `LINUX_AND_GUI_CONFIG.md` does.

Revisions:

* **r1, 2026-11-12.** First draft. It proposed one artifact with a `gui` / `headless` switch.
* **r2, 2026-11-12.** Two changes from the author:
  * **No switch: a third artifact.** The author proposed it, and r2 adopts it. Headless exists only
    as `openproteo-headless.war`, an artifact with no web server in it. The r1 switch, the mode
    file, `gui on|off`, and the Tomcat 404 filter are gone (§4).
  * **Q7 answered "yes".** The GUI used for setup can be bound to the loopback address. The
    author's reason: "if no HTTP service is exposed, you have to access the server to get in" (§4.4).
* **r3, 2026-11-13.** Q11 answered "yes": a Host/Origin filter on the loopback GUI (§4.4).
* **r4, 2026-11-18.** Q1, Q2, Q3, Q4, Q5, Q8 and Q10 answered (§12). The only change to the design is
  Q10: the engine lock is now **headless-only**, and the residual risk that follows is written in
  §4.3.

## 1. What it is, and why

OpenProteo has no sign-in. Every page and every `/api/**` endpoint answers anyone who can reach
the port. That includes the CSV viewer (`/api/workflows/{feedId}/csv/page`, `ApiController:3531`),
which pages through working files that hold client-identifying data. Authentication is batch 3
of `LINUX_AND_GUI_CONFIG.md`, scheduled last.

This spec closes that gap without building authentication:

* **Setup** uses the GUI: designing workflows, variables, datasources, FTPS targets, masking pools.
* **Production** runs a separate artifact, `openproteo-headless.war`. It contains no HTTP
  server, and it is driven from the command line on the server.
* **Access control** becomes the server's own: who can log on to the server (two-factor at UBS),
  and who the OS lets create a file in one directory (§5).

The two existing artifacts are not changed in behaviour by default. An installation that deploys
only `openproteo.war` or `openproteo-standalone.war`, and sets none of the new settings, behaves
exactly as at `eb9790d`, with no exception: the engine lock belongs to the headless artifact only
(§4.3, Q10).

## 2. Baseline, read on the code at `eb9790d`

| Fact | Where |
|---|---|
| One build, two artifacts. `openproteo.war` for the external Tomcat. `openproteo-standalone.war` is the Boot repackage with `classifier=standalone`, with `spring-boot-starter-tomcat` in `WEB-INF/lib-provided`, started with `java -jar` | `pom.xml:96-116` |
| Start-up: `OrchestratorApplication.main` → `SpringApplication.run`, and `ServletInitializer` under Tomcat. No `Filter`, `HandlerInterceptor`, `WebMvcConfigurer` or `EnvironmentPostProcessor` exists | grep |
| The servlet API is used only by the controllers: 6 `@Controller`/`@RestController` classes (`web/ApiController`, `PageController`, `PlatformController`, `LogReportController`, `diag/DiagnosticsController`, `CertIdentityProbeController`) and 1 `@ControllerAdvice` (`web/BuildIdAdvice`). No other class imports `javax.servlet` | grep |
| 87 request mappings, none authenticated | the classes above |
| The audit identity is `X-User`, then `getRemoteUser()`, then `operator@<ip>`. A client can name itself | `ApiController:3833-3838` |
| **The engine is single-process by construction.** These are all JVM fields: the per-feed lock `runningFeeds`, the FIFO `pending`, the admission count `active`, the `RunControl` handles that Stop uses, and the run-id counter `seq` | `WorkflowEngine:76-91`, `:535` |
| **The audit chain caches its tail in memory.** `AuditLogger` reads `seq` and `lastHash` per feed from the file once, then keeps them. A second process appending to the same file breaks the chain: duplicate `seq`, wrong `prevHash` | `AuditLogger:47-49`, `:79-83` |
| Run files are `{feedDir}/_runs/{runId}.json`, written as tmp then move, under a JVM monitor | `RunStore:38-55` |
| A run id is `feedId_yyyyMMdd-HHmmss_NNN`, where `NNN` is the JVM counter mod 1000. Two JVMs starting the same feed in the same second can produce the same id | `WorkflowEngine:535` |
| `engine.start(feedId, trigger, user)` takes no variables. A run's variables come from globals, builtins, the workflow's `def.variables`, and script aliases, in that order | `WorkflowEngine:157`, `:532-557` |
| Cron is `WorkflowScheduler`, in-process, which calls `engine.start(feedId, "CRON", "scheduler")`. A missed fire is not caught up | `WorkflowScheduler:93-110` |
| Gate decisions and releasing a hold read the run from its file if it is not in memory, so both survive a restart | `WorkflowEngine:326-395` |
| Every default path is relative to the working directory (`./workflows`, `./feeds`, ...) | `AppProperties`, `LINUX_AND_GUI_CONFIG.md` §3.1 item 6 |
| The registry reads only `*.xml` files in the workflows directory | `WorkflowRegistry:316` |

## 3. One engine, and a CLI that talks to it

Two architectures were considered. The third artifact of r2 does not change this choice: whatever
the packaging, the question is who runs the engine.

### 3.1 Option A: one-shot JVM (rejected)

Each CLI command would start the engine in its own JVM, run the workflow in-process, and exit.
The baseline in §2 shows the engine assumes it is alone. Two JVMs would cause:

* two runs of the same feed at once;
* a broken audit chain;
* colliding run ids;
* two writers on the rolling application log.

Each of these can be fixed, but only with cross-process locks around the audit, the feed and the
log, plus a stop signal between processes. Even then, the FIFO and `max-parallel-runs` stop
meaning anything across JVMs.

There is also a security cost. The CLI user would need:

* read access to `datasources.json` (database passwords), the FTPS credentials, and the masking
  secret;
* write access to every feed directory and to the audit files.

With write access to the audit files, a user can rewrite the whole chain, because it is an
unkeyed SHA-256 chain.

### 3.2 Option B: one engine, file-based control channel (chosen)

A single long-running OpenProteo process owns the engine, as today. The CLI is a thin client: it
drops a request file into a directory and reads the answer from another. The CLI never:

* starts the engine;
* reads `application.properties`;
* touches a feed directory or a secret.

What B gives:

* **The FIFO and admission rules are unchanged.** A CLI `run` calls the same `engine.start` the
  Run button calls, and queues under the same `max-parallel-runs`.
* **Two-process writes cannot happen.** Run files, audit files, step logs and the application log
  have one writer: the instance. The CLI writes only into the inbox (§5). This holds by design;
  there is no lock to get right.
* **Stop, gates and holds behave as from the GUI.** The instance that runs the run holds its
  `RunControl`.
* **Cron is unchanged** (§7).
* **The CLI user needs none of the instance's secrets.** Their authority is exactly "may create a
  file in the inbox".

What B costs:

* The instance must be running. Between restarts nothing answers, and the CLI says so (§6.3).
* Each command has about one second of polling latency.

Transports rejected for B:

* **Loopback HTTP:** any local user and any local process reaches it with no OS identity, which
  rebuilds the problem this spec removes.
* **Unix domain sockets:** they need Java 16.
* **Windows named pipes:** Java 8 has no server side for them.

A directory is the only channel that is portable on Java 8 and carries an OS identity: the owner
of each file.

## 4. Three artifacts instead of a switch

### 4.1 What the build produces

One `mvn clean package` produces three artifacts:

| Artifact | GUI / HTTP | Engine and cron | Control channel | Typical use |
|---|---|---|---|---|
| `openproteo.war` | yes, in the external Tomcat | yes | when `controlDir` is set (§4.4) | as today |
| `openproteo-standalone.war` | yes, embedded Tomcat | yes | when `controlDir` is set | as today; setup |
| **`openproteo-headless.war`** (new) | **none: there is no web server in the artifact** | yes | **required** | production without GUI; CLI client |

**Intersection, decided by name: «Configurabilità integrale da GUI» × the choice of artifact.**
The contract lists "installation and update of the artifact" as out of scope. Choosing the headless
artifact is an installation choice, so there is no setting to put in the GUI and no way back to
design. To get the GUI back:

1. stop the headless service;
2. start a GUI artifact on the same configuration.

That order is an operating procedure, written in `USAGE.md`. Since Q10 nothing in the GUI
artifacts enforces it (§4.3).

**Headless is standalone only.** A Tomcat host that wants it installs `openproteo-headless.war`
beside Tomcat and undeploys `openproteo.war`. The headless artifact needs only a Java 8 runtime,
which that host already has. r1's 404 filter under Tomcat is dropped: the container's connector
would have stayed open anyway.

### 4.2 How the headless artifact is built and started

It comes from a second execution of `spring-boot-maven-plugin` `repackage`, with:

* `classifier=headless`;
* `mainClass=com.legalarchive.orchestrator.HeadlessApplication`;
* `excludeGroupIds=org.apache.tomcat.embed`.

The third setting removes `tomcat-embed-core`, `-el` and `-websocket`, and with them the servlet
API. `HeadlessApplication`:

* runs `SpringApplication` with `WebApplicationType.NONE`. Deduction would reach the same answer
  once no servlet class is present; setting it explicitly makes the intent visible;
* scans the same packages as `OrchestratorApplication`, with exclude filters **by annotation**:
  `@Controller` (which covers `@RestController`) and `@ControllerAdvice`. These are exactly the
  classes that use the servlet API (§2). Filtering by annotation also catches a controller added
  next year without anyone remembering this file;
* keeps the JVM alive by having `main` wait on a latch that the shutdown hook releases. It does not
  rely on the cron pool's threads happening to be non-daemon. Boot 2.7 has no
  `spring.main.keep-alive`;
* with the first argument `cli`, does not start Spring at all. It hands off to the CLI client
  `cli/OpCli` (§6.1).

The templates and static files stay inside the artifact. Without a server they are inert data;
removing them would need a separate WAR execution, for no gain in what can be reached.
`OrchestratorApplication` and `ServletInitializer` are **not modified**, so the two existing
artifacts start exactly as now.

**What is proven, and how (H2):**

* the archive listing contains no `org/apache/catalina`, no `tomcat-embed-*`, and no
  `javax/servlet`;
* the started artifact holds no listening socket, checked from its PID on Linux and Windows;
* a positive control: the same check on the standalone shows its port;
* a mutation: removing the exclusion makes the first test fail.

### 4.3 Engine lock: two engines on the same data must not both start

r1 had one artifact changing mode. r2 has two artifacts that can be started side by side by
mistake, for example the standalone for a setup session while the headless service runs. Two
engines on the same feeds produce every failure of §3.1.

~~**The rule:** every artifact takes `<workflowsDir>/.openproteo-engine.lock` ... This applies to
all three. If the lock cannot be created, the two GUI artifacts start anyway, with a warning.~~
~~**Intersection, decided by name: "Default conservativi" × the engine lock.** The lock is a new
behaviour of the existing artifacts, and it is on from the start.~~ The r2 text, recommended in
Q10, was **overruled by the author on 2026-11-18**: "no, only the CLI". The existing artifacts are
left as they are.

**The rule since 2026-11-18:**

* **Only `openproteo-headless.war` takes the lock.** It takes
  `<workflowsDir>/.openproteo-engine.lock` with `FileChannel.tryLock` at start and holds it for
  its life. If the lock is held, or cannot be created, it refuses to start, and the log names the
  holder. The holder's PID, host, artifact and start time go to a sibling `.openproteo-engine.json`.
  On Windows a locked region cannot be read by other processes, which is why the details sit in a
  separate file. Two headless instances on the same data are therefore impossible.
* **The two GUI artifacts neither take the lock nor check it.** With no new settings they behave
  exactly as at `eb9790d`. With `controlDir` set, the one thing they do is what §5.1 already
  says: if `instance.lock` is held, they do not serve the channel, and the log line names the
  holder, for example "control directory served by openproteo-headless.war, pid 4312". That is a
  message, not a refusal: the GUI instance starts anyway.

**Residual risk, accepted by the author's answer and stated here so it is not forgotten.** If a
GUI artifact is started on the same `workflows-dir` while the headless service runs, two engines
work on the same feeds. Both run the cron, and the audit chains, run ids and per-feed locks break
as in §3.1. Nothing stops it. It is the same risk as today's two GUI instances on the same
directory, which nothing stops either. The protection is the operating procedure in §4.1 (stop
the headless service before starting the GUI for setup), written in `USAGE.md` with this
paragraph as its reason.

### 4.4 Settings, and the loopback GUI for setup (Q7, answered)

Two keys:

| Key | Values | Default | Read by | Applies |
|---|---|---|---|---|
| `orchestrator.control-dir` | absolute path, or empty | empty: no channel | all three artifacts | at restart |
| `server.address` | an address of the host, typically `127.0.0.1` | empty: all interfaces, as today | `openproteo-standalone.war` only | at restart |

**Q7, answered by the author 2026-11-12:** the setup GUI may be bound to loopback. A browser
then reaches it only from a session on the server itself, which is behind the two-factor logon.

**Intersection, decided by name: `server.address` × the 2026-10-04 decision "`server.*` out of
scope except `server.port`".** `server.address` joins `server.port` in the "at restart" class, for
the standalone only. The other `server.*` keys stay out of scope. Under the external Tomcat the
binding is the connector's `address` attribute in `server.xml`: container configuration, out of
scope, and documented in `USAGE.md` as the Tomcat equivalent. The Platform page shows the value
there as "no effect under the external Tomcat"; it is not accepted silently.

**Where the two values are saved.** The settings mechanism of batch 4 (override file, its
location, last-known-good) does not exist yet. **Intersection, decided by name: these keys ×
«Configurabilità integrale da GUI» × batch 4.**

* Both live in `<workflowsDir>/openproteo-instance.json`
  (`{ "controlDir", "serverAddress", "changedBy", "changedAt" }`). The workflows directory is the
  one location every artifact already knows at bootstrap. The registry ignores the file (it reads
  only `*.xml`).
* An `EnvironmentPostProcessor` reads the file and puts its values above `application.properties`,
  the precedence the contract fixes for GUI-saved values. Without the file it adds nothing.
* The **Platform page** of the GUI artifacts writes the file, in a "Headless and access" block.
  The block shows the value now and at the next start, and the control directory with its checks
  (§5.3).
* Batch 4 absorbs this file into its override file and says so in its own spec.
* The headless artifact has no GUI. It reads the same file, written during setup. So its
  parameters are still set from the GUI, from the other artifact.

**Lock-out cases, declared.**

* An address that does not exist on the host makes the standalone fail at the next start. The
  recovery is deleting the file, from a session on the server, which is the only place this file
  is reachable anyway.
* Binding to `127.0.0.1` from a remote browser makes the GUI disappear for that browser at the next
  start. The confirm dialog (`opConfirm`, `danger`) says so.

**What loopback does not do.** "Only from the server" means **every account that can log on to
that server**, and every process running there, with no further check. The inbox, by contrast, has
its own ACL (§5.3). On a server shared with people who are not OpenProteo operators, the loopback
GUI is a weaker boundary than the CLI. That is acceptable for a setup window. It is not a reason to
leave the GUI running in production.

Two further limits of the loopback GUI, which the headless artifact does not share:

* **No attribution.** Every request arrives from `127.0.0.1` with no `X-User`, so every GUI action
  is audited as `operator@127.0.0.1`. The audit shows *that* someone on the server acted, never
  *who*. The CLI records the OS account (§5.2).
* **A browser on the server is a way in from outside.** A page opened in that browser can make
  it send requests to `http://127.0.0.1:<port>`:
  * a cross-site form POST to `/api/workflows/{feedId}/run`, which needs no read access and is
    refused by nothing today;
  * DNS rebinding, which can also read responses, including `/csv/page`.

  **Mitigation, decided by the author on 2026-11-13 (Q11, "yes"):** in the standalone, when `serverAddress` is a loopback address, a
  filter refuses with 403 any request whose `Host` is not `127.0.0.1`, `localhost` or `[::1]`
  (with the port), which defeats rebinding. It also refuses any non-GET request whose `Origin`
  or `Referer`, when present, is not that same origin, which defeats cross-site POSTs. Without
  that setting the filter is not registered, so the default does not change.

## 5. The control directory

### 5.1 Layout

```
<control-dir>/
  instance.lock     held exclusively (FileChannel.tryLock) by the serving instance for its life
  instance.json     heartbeat: pid, host, artifact, build, startedAt, lastSeen, protocol version
  inbox/            the CLI creates <id>.tmp, then renames it to <id>.json (the rename publishes it)
  work/             the instance claims a request by renaming inbox/<id>.json -> work/<id>.json
  outbox/           the instance writes <id>.tmp, then renames it to <id>.json: the answer
```

* **One instance per control directory.** A second instance that finds `instance.lock` held does
  not serve the channel, and its log names the holder. If it is the headless artifact, it refuses
  to start. A GUI instance starts anyway (Q10). The engine lock of §4.3 covers two headless
  instances sharing a workflows directory. This lock covers any two instances that share a
  control directory.
* **Claiming and cancelling are both renames.** The CLI cancels a request that was not picked up
  in time by renaming `inbox/<id>.json` to `<id>.cancelled`. Exactly one of the two renames
  succeeds, on both OSes, so a request is never both executed and reported as not executed.
* **Polling.** The instance reads `inbox/` once a second, in arrival order: modification time,
  then name.
* **Size limit.** A request file larger than 64 KB is refused unread.
* **Expiry.** A request older than 120 s when claimed is refused, not executed. This covers the
  requests that piled up while the instance was down. When the request names a feed, the refusal
  is audited in that feed as `CLI_REQUEST_EXPIRED`.
* **Cleanup.** The instance deletes answers older than 10 minutes. The CLI cannot delete them on
  Linux (§5.3).

### 5.2 Who started it

**The recorded user.** For a CLI request it is the **OS owner of the request file**, read by the
instance with `Files.getOwner`. The OS sets the owner. On Linux a non-root user cannot change it.
On Windows only holders of take-ownership or restore privileges can, and those are the box's
administrators. Format: `DOMAIN\name` on Windows, `name` on Linux.

**The declared identity.** The CLI also writes the identity of its own process into the request.
It reads it from the process token (`com.sun.security.auth.module.NTSystem` / `UnixSystem`,
present in Java 8 and exported in 9+), never from `user.name`, which `-Duser.name=` overrides.
The declared identity is evidence, never the `user`. When it differs from the owner, the audit
line carries it as `declaredUser`. There is no `--user` option.

**Known Windows trap, not verified.** The policy *"System objects: Default owner for objects
created by members of the Administrators group"* can be set to *Administrators group*. A file
created by an administrator is then owned by `BUILTIN\Administrators`, not by the person. In that
case the owner is recorded as it is, the declared user goes beside it, and the Platform page
warns. Q4 asks how the UBS server is set.

### 5.3 The ACL is the authorisation, so it is checked

Whoever can create a file in `inbox/` can run, stop and approve every feed, and read run statuses
and step logs.

Recommended set-up, documented in `USAGE.md` and not enforced by the application:

* the control directory is owned by the service account;
* `inbox/` grants "create files" to an operators group, without listing. On Linux that is mode
  `1730` with the group set; the sticky bit means only the owner of a request can rename it back;
* `outbox/` grants read to the same group.

**The author's principle**, "no HTTP service exposed, so you have to access the server", holds
only if nobody can write into `inbox/` **without logging on to the server**. Two cases break it,
and they are documented as things not to do:

1. **The control directory on a network share** (SMB, NFS). Anyone with the share mapped can write
   from another machine. File locks over SMB/NFS are also not trustworthy. Use a local disk only.
2. **A file share exposing the local disk**, including the administrative shares (`C$`). They
   allow writing into `inbox/` remotely, with no interactive logon. Only accounts that already
   administer the server can use them. Their write is recorded under their own name (§5.2), but it
   bypasses the two-factor logon.

The instance checks at start, and shows on the Platform page:

| Check | Linux | Windows | Effect |
|---|---|---|---|
| directory exists, is a directory, is writable by the instance | `Files` | `Files` | channel off if it fails |
| `inbox/` writable by everyone | `o+w` in the POSIX permissions | an ACE for `Everyone`, `Authenticated Users` or `Users` with `ADD_FILE` or `WRITE_DATA` (`AclFileAttributeView`) | **channel off** (Q8) |
| on a network file system, or exposed by a share | not detectable reliably | not detectable reliably | declared in `USAGE.md`, not checked |

### 5.4 Protocol (version 1)

Request `inbox/<id>.json`:

```
{ "protocol": 1, "id": "<uuid>", "created": "2026-11-12T10:15:02.123+01:00",
  "command": "run", "feedId": "FEED01", "runId": null,
  "vars": { "cutoffDate": "20261031" }, "note": null, "offset": 0,
  "declaredUser": "CORP\\jdoe", "cliBuild": "<build id>" }
```

Answer `outbox/<id>.json`: `{ "protocol": 1, "id", "ok", "code", "error", "runId", "data" }`.

* `code` is the exit code of §6.3, so the CLI only relays it.
* An unknown `protocol` is answered with code 22 and not executed.
* A CLI whose build differs from the one in `instance.json` prints a warning and goes on: the
  protocol number is what has to match.

## 6. The CLI

### 6.1 How it is started

```
java -jar openproteo-headless.war cli <command> ...
```

* `HeadlessApplication.main` sees `cli` and hands off to `cli/OpCli` **before** Spring is touched.
  The client uses only the JDK and Jackson: it starts in about a second, reads no configuration,
  and loads no bean.
* The client exists only in the headless artifact. The entry points of the two existing artifacts
  are not touched (§4.2). During setup on a GUI host, the client still comes from the headless
  artifact of the same build, copied beside it. It can talk to a GUI instance whose `controlDir`
  is set, so the CLI is tried and compared with the GUI before the GUI is switched off.
* Typing the start command without `cli` would start a second headless instance. The engine lock
  stops it at once (§4.3).
* The control directory comes from `--control <dir>`, or else from the environment variable
  `OPENPROTEO_CONTROL`. It is the only thing the client needs to know.
* `tools/openproteo.cmd` and `tools/openproteo.sh` are wrappers that set the variable and find
  `java`.

### 6.2 Commands

The CLI covers execution and observation only. **It never changes a definition or a setting.**
Setup stays in the GUI, which is the point of the split. Step-by-step test runs also stay in the
GUI.

| Command | What the instance does | Same as in the GUI |
|---|---|---|
| `ping` | answers from `instance.json` and confirms with a round trip | - |
| `feeds [--json]` | feedId, name, production, locked, cron, cron error, last run id and status | dashboard |
| `runs <feedId> [--limit N] [--json]` | the feed's runs, newest first (`RunStore.list`) | runs page |
| `status <feedId> <runId> [--json]` | status, message, trigger, triggeredBy, times, each step's status, the waiting gate or held step | run page |
| `log <feedId> <runId> [<stepId>] [--tail N] [--follow]` | the step log; without a step, the run's step list. `--follow` repeats with an offset | step log tail (`ApiController:1719`) |
| `run <feedId> [--var name=value]... [--wait]` | `engine.start` with variables (§6.4). Returns once the run is QUEUED and prints the run id. `--wait` waits for the end (§6.3) | Run button |
| `wait <feedId> <runId>` | waits until the run ends or pauses | - |
| `stop <feedId> <runId>` | `engine.stop`. A second `stop` on a run still alive is the existing force-stop | Stop, Stop again |
| `approve` / `reject <feedId> <runId> [--note text]` | `engine.decide` | gate buttons |
| `resume <feedId> <runId>` | `engine.resumeHold` | Resume |
| `reload` | `registry.reload()` and `scheduler.reschedule()` | Reload button |
| `audit-verify <feedId>` | `AuditLogger.verify` | audit page |

Reads go through the channel too. Operators then need **no** access to the feed directories, which
hold the working data with client-identifying values, and they see exactly what the GUI shows.
This is Q2.

### 6.3 Exit codes

| Code | Meaning |
|---|---|
| 0 | command done; for `run --wait` and `wait`, the run ended SUCCESS |
| 1 | run ended FAILED |
| 2 | usage error: unknown command or bad arguments. Checked locally; nothing is sent |
| 3 | run paused WAITING_APPROVAL at a manual gate |
| 4 | run paused ON_HOLD |
| 5 | run ended REJECTED |
| 6 | run ended ABORTED |
| 7 | run ended SKIPPED |
| 10 | refused: unknown feed or run |
| 11 | refused: feed locked for maintenance |
| 12 | refused: state conflict (a run is already active for the feed; approve on a run that is not waiting, ...) |
| 13 | refused: invalid variable (reserved name, malformed) |
| 20 | no instance is serving the control directory (heartbeat older than 60 s), or the request was not picked up within 30 s. In the second case the request is cancelled (§5.1) |
| 21 | request expired at the instance |
| 22 | protocol mismatch |
| 30 | local error: control directory missing or not writable, or an I/O error |

* Messages go to stderr and data to stdout; `--json` gives a machine-readable form.
* The ranges are meaningful: 0–9 are run outcomes, 10–19 refusals by the instance, 20–29 channel
  failures, 30 and above local failures. A scheduler script can branch on the range.

### 6.4 Run with variables

The GUI has no equivalent today, so this is new engine surface. A new overload,
`start(feedId, trigger, user, overrides, auditExtra)`, does the work. The existing three-argument
`start` delegates to it with empty values, so GUI and cron calls behave and audit exactly as now.

* **Where the variables go:** right after `def.variables` and before the script aliases
  (`WorkflowEngine:556-557`). A CLI variable behaves as if it were written in the workflow's
  Variables, for that one run.
* **Reserved names, refused with code 13:** `runId`, `feedId`, `parentId`, `sourceId`, `targetId`,
  `__prod`, `feedName`, `runDate`, `runTs`, `sharedDir`, and every key of `layout.dirVars()`.
  A workflow's own variables can shadow these today. A CLI variable cannot: a typo on the command
  line must not be able to redirect a run's output directory.
* **Persisted** in the run file's `vars`, like every other variable.
* **Audited** on `RUN_QUEUED` as `var.<name>` = value, name and value both (Q5, answered
  2026-11-18). `USAGE.md` states that run variables are configuration, never personal data.

## 7. Scheduling in headless mode

**Unchanged.** `WorkflowScheduler` runs inside the headless instance as it does inside the others.
It fires `engine.start(feedId, "CRON", "scheduler")` as today, with the same no-catch-up
behaviour. Cron expressions stay in the workflow XML and are set from the designer during setup.

There is no `cron-tick` command and no OS-scheduler integration: option A needed them, along with
a whole catch-up policy. An OS scheduler can still call `openproteo run <feedId> --wait` and
branch on the exit code. For OpenProteo that is a manual run, recorded under the account the OS
scheduler runs as.

## 8. State and audit: identical to a GUI-started run

**Intersection, decided by name: "identical to a GUI-started run" × "the audit must say the run
came from the CLI".** The run file and the event names stay identical; the origin goes into the
details only.

| | GUI run | CLI run |
|---|---|---|
| run file | `trigger=MANUAL`, `triggeredBy=<X-User / remote user / operator@ip>` | `trigger=MANUAL`, `triggeredBy=<OS owner of the request>` |
| audit events | `RUN_QUEUED`, `RUN_RUNNING`, ..., `RUN_COMPLETED` | the same events in the same order, written by the same `AuditLogger` in the same process |
| `RUN_QUEUED` details | `trigger`, `workflow` | `trigger`, `workflow`, plus `channel=cli`, `request=<id>` and `var.*`. `declaredUser` appears only when it differs |
| Stop, gate decision, resume | `user` from the request | `user` = owner, with the same extra details |
| step logs, `_runs`, audit report | unchanged | unchanged |

* GUI-originated audit lines get **no** new keys, so their shape is byte-for-byte today's. A
  missing `channel` means the GUI.
* The hash payload is unchanged, so an audit verified before the change verifies the same after.
* A run started on the headless instance is indistinguishable, in its files, from one started on a
  GUI instance on the same data. The artifact that ran it is in the application log only.

## 9. What stays reachable

| Situation | Over HTTP | Locally |
|---|---|---|
| GUI artifact, no new settings (default) | **everything, unauthenticated, as today** | - |
| GUI artifact + `controlDir` (setup with the CLI) | everything, unauthenticated, as today. The CLI adds no HTTP | whoever can create files in `inbox/` |
| `openproteo-standalone.war` with `serverAddress=127.0.0.1` (setup, Q7) | only from the server itself, by any account logged on there and any process running there, unauthenticated | the same, plus the inbox |
| **`openproteo-headless.war`** | **nothing: the artifact contains no HTTP server** (§4.2). JVM options that open ports on their own (JMX, a debug agent) are JVM configuration, out of scope by the contract | whoever can create files in `inbox/` |

What headless does **not** change, said so that nobody assumes otherwise:

* **Working data stays on disk until Clear History.** Headless removes the HTTP way to read it;
  it does not remove the files. A CLI `clear-history` is a possible follow-up, not in this spec.
* **The OS boundary is only as good as the server's logon.** Every account that can log on to the
  server, or write into `inbox/` through a share (§5.3), is inside the boundary.

## 10. Contract rules, one by one

| Rule | How it is met |
|---|---|
| Java 8 | `FileChannel.tryLock`, `Files.getOwner`, `AclFileAttributeView`, `PosixFileAttributeView`, `NTSystem` / `UnixSystem`: all Java 7/8. No API ≥ 9 |
| Windows and Linux equivalence | One code path. The OS-specific parts are named: owner format, the class giving the declared identity, the "writable by everyone" check (POSIX bits or ACL), the shutdown signal (SIGTERM or service stop), the wrappers (`.cmd` / `.sh`). A rename within one directory is atomic on both. Every delivery declares Linux and Windows separately |
| Default conservativi | Without `openproteo-instance.json` and with an empty `control-dir`: no channel thread, no new property source, the bind address unchanged, and no lock file: the engine lock is headless-only (§4.3, Q10). The suite asserts all of this |
| «Configurabilità integrale da GUI» | `controlDir` and `serverAddress` are set from the Platform page and apply at restart (§4.4). The choice of artifact is installation, out of scope (§4.1) |
| No new file-only key | None. Both keys live in the GUI-written file; `application.properties` stays the base layer |
| Rule 9, `HostFiles` | the inbox listing goes through `inHostOrder` and is then sorted explicitly; claim and cancel use `renameNoReplace` |
| PII never logged | answers carry statuses and step-log text, as the GUI does. The application log records command, feedId, runId and user, never variable values. The audit gets them (Q5) |
| Millisecond timestamps | `created`, `lastSeen` |
| JS rules | apply to the Platform page block only |
| Deploy (rule 8) | `USAGE.md` gains the headless install. The artifact runs as a service, not under Tomcat, so rule 8 does not apply to it |

## 11. Batches and verification

| # | What | Verifiable here | Left to the author |
|---|---|---|---|
| H0 | this spec | - | the answers in §12 |
| H1 | control channel in the instance (all artifacts, active when `controlDir` is set); `engine.start` with variables; `openproteo-instance.json` and its post-processor; Platform block; the headless artifact's build with the `cli` entry; CLI client; wrappers; exit codes; `USAGE.md` | The channel and the CLI as real classes, run on Linux on Java 8 and 21: claim/cancel races, expiry, owner identity **as two different non-root users** (`setpriv`), the refusal of a world-writable inbox, the second-instance lock, every exit code. The engine overload by differential compilation, with the variable placement extracted and run. That a GUI artifact with no new settings creates no new file in `workflows-dir` | Windows: the `Files.getOwner` format, the Administrators-owner policy, `AclFileAttributeView` on the real inbox, the `.cmd` wrapper |
| H2 | the headless instance: `HeadlessApplication`, controller exclusion, latch, refusal without a channel, the engine lock (headless only); `serverAddress` on the standalone, with its Host/Origin filter (Q11) | The archive listing (no Tomcat, no servlet API) and the headless artifact **started for real**. No listening socket from its PID, with a positive control on the standalone. A mutation removing the exclusion must fail. `serverAddress=127.0.0.1`: a connect on the external interface fails, and one on loopback succeeds. The filter, as a real class against a mock request and then on the started standalone: `Host: evil.example` gets 403 on GET `/api/workflows/X/csv/page`; a POST with a foreign `Origin` gets 403 and starts no run (checked in the run files); same-origin GET and POST pass; a POST with neither `Origin` nor `Referer` passes, since `curl` and the old corporate browser may send neither. Mutations: each of the two checks removed must make its test fail. Without `serverAddress` the filter is not registered, and every route answers as before | as a Windows service; with the UBS service wrapper; after the Maven build on the author's machine (the build cannot run in the sandbox) |

H1 comes first on purpose: the CLI gets used and compared while the GUI is still there to compare
with. Only then does production move to the headless artifact.

## 12. Gate H0: questions for the author before any code

r3, 2026-11-13: Q11 answered "yes". r4, 2026-11-18: Q1, Q2, Q4, Q5, Q8 and Q10 answered.
Q3 confirmed the same day. Struck-through questions are answered or made moot; their text is
kept. **Still open: the second half of Q6 and the second half of Q7.** Neither changes the design.

1. ~~**Option B (one instance, file channel) instead of A (one-shot JVM).** Recommended for the
   reasons in §3. Confirm or overrule.~~ **Answered 2026-11-18: B.**
2. ~~**Reads go through the channel**, so operators need no access to the feed directories.
   Recommended. The alternative, reading the files directly, is simpler but hands the CLI user the
   working data.~~ **Answered 2026-11-18: yes, through the channel.**
3. ~~**The switch lives in `<workflowsDir>/openproteo-mode.json`**, written from the Platform page
   now and absorbed by batch 4 later.~~ **Moot in r2**: no switch. The same idea carries
   `controlDir` and `serverAddress` in `openproteo-instance.json` (§4.4). ~~Confirm that file, or
   prefer both keys file-only, declared incomplete until batch 4.~~ **Confirmed 2026-11-18.**
4. ~~**Identity on the UBS server.** Do operators run commands under their **personal** accounts, or
   `runas` a shared functional ID?~~ **Answered 2026-11-18: personal accounts only.** So the owner
   of a request file names a person, and the audit attributes every CLI action to a person. Not
   answered, and moved to the Windows verification of H1 (§11): whether the Administrators-owner
   policy (§5.2) is set on that server. It matters only for operators who are members of
   Administrators. When it is set, those operators' requests are owned by
   `BUILTIN\Administrators`, and the person survives only as `declaredUser`.
5. ~~**Variables in the audit:** values (reproducible, but a value could be personal data) or names
   only?~~ **Answered 2026-11-18: both**, name and value, as `var.<name>` = value on `RUN_QUEUED`
   (§6.4). `USAGE.md` states the rule that run variables are configuration, never personal data.
   The application log keeps names only (§10).
6. ~~**Tomcat hosts**: headless under Tomcat or the standalone?~~ **Moot in r2**: headless is
   standalone only (§4.1). The second half remains: on a Tomcat host, the CLI client comes from the
   headless artifact of the same build, copied beside it. Confirm.
7. ~~**Narrow the setup window** with a GUI bound to `127.0.0.1`?~~ **Answered 2026-11-12: yes.**
   "If no HTTP service is exposed, you have to access the server to get in." Designed in §4.4,
   including what loopback does not protect against. Still open: is a browser available in the
   server session?
8. ~~**A world-writable inbox switches the channel off** (§5.3). Recommended: otherwise any local
   account can run feeds. The alternative is a warning only.~~ **Answered 2026-11-18: yes, the
   channel goes off.** On the headless artifact that means it refuses to start, because the
   channel is required there (§4.1).
9. ~~**Mode changes are audited only in the application log.**~~ **Moot in r2**: there are no mode
   changes. Changes to `openproteo-instance.json` keep `changedBy` / `changedAt` and go to the
   application log.
10. ~~**The engine lock is taken by the existing artifacts too** (§4.3). It is a new behaviour, on
    from the start, which refuses a second engine on the same workflows directory. Recommended:
    the alternative leaves "standalone for setup while headless runs" able to corrupt audit chains
    without any warning.~~ **Answered 2026-11-18: "no, only the CLI".** The lock belongs to the
    headless artifact alone. The existing artifacts are unchanged, and the residual risk is
    written in §4.3.

11. ~~**Host and Origin check on the loopback GUI** (§4.4). Recommended. It is the price of the
    answer to Q7: without it, a page opened in a browser on the server can drive the GUI from
    outside.~~ **Answered 2026-11-13: yes.** It is part of H2 (§11).

Not decided here and not blocking: a CLI `clear-history`; marking runs left RUNNING by a restart
(existing behaviour, unchanged by this spec).
