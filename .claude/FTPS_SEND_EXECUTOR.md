# `ftpsend` — FTPS delivery executor

**Batch 0. Specification only. No code, no dependency, no registration in this commit.**

A new internal executor that uploads the files of a packaging directory to an FTPS server over an
explicit TLS control channel, authenticating with a client certificate held in a PKCS#12 file. It
replaces `scripts/Send-FTPS.ps1`, which has never been more than a skeleton that sleeps two seconds
and returns 0, and it replaces the external `FTPS.exe` invocation used for the z/OS deliveries.

---

## 1. What is known, and how it is known

Everything in this section was **read from a working configuration or from a session log**, not
inferred. Where something is inferred it says so.

From the FTPS client currently in service (its connection panel and its action table):

* FTP with **explicit TLS**, port **2121**, passive mode.
* Authentication is a **client certificate loaded from a file** — a `.pfx` path, not a Windows
  certificate store with an alias. The panel of the other tool exposes a named key entry, but the
  WinSCP session that reaches the same estate names a file, and a file is the smaller assumption.
* **The user password is empty.** The session logs in with `USER S211094` and no `PASS`. The z/OS
  session log shows the same shape: `230-User S210834 is an authorized user` arrives immediately
  after `USER`. A client that always sends `PASS` after `USER` will send it into a `230` and either
  desynchronise the reply stream or be rejected with `503`.
* **"Reuse TLS session ID for data connections" is enabled**, and minimum/maximum TLS are 1.2/1.3.
* Post-login commands are empty for the UNIX target. For z/OS they are not: the working session
  issues `SITE CY PRI=10 SEC=10 RECFM=VB LRECL=30000 SBDATACONN=(IBM-500,ISO8859-1) MGMTCLAS=COM#E035`
  before the first `put`, and without it the dataset is allocated wrongly.
* Two target profiles exist and they differ in more than a hostname. UNIX/Transarch: binary, remote
  paths, no `SITE`. z/OS/ELAR: ASCII with a server-side codepage conversion, remote names that are
  **quoted dataset names** rather than paths, `SITE` mandatory.

From the packaging directory of a real feed (`feeds/TF0004165/40_PACKAGING/`), five files per
delivery: `.tar` (49 KB), `.md5` (1 KB), `.control` (**0 KB**), `.audit.xml` (1 KB) and
`.U1.data.csv` (46 KB), named `TF0004165.20260904.S001.V1.<ext>`.

## 2. The UAT finding, which decided two rules

Against `gbwu2719685.gbl.ad.hedani.net` the control channel completes in full — TLS established,
client certificate accepted, session started — and **the data channel does not open**. `LIST` fails
with the server answering `227` with an unroutable private address (`10.117.145.81`), which the
client correctly replaces with the control host, and the connection still does not complete. A
49 KB `.tar` uploads **0 bytes**.

Two consequences, and they are the reason this section exists rather than a note at the end:

1. **The client is not the problem, and no client-side option will fix it.** The working client
   already reuses the TLS session and already substitutes the passive address — the two behaviours
   this specification requires — and it fails anyway. The passive port range is either closed on the
   path, or a load balancer is sending the data connection to a backend that did not handle the
   control connection. Both are network facts. This is recorded so that when the executor is first
   run against UAT and fails, the failure is not searched for in the code.
2. **A remote file existing proves nothing.** The `.control` weighs 0 bytes, and a second upload
   attempt reported the file already present on the server — which looked like success and was not.
   Many servers create the destination file on accepting `STOR`, before a byte crosses the data
   channel. This is the origin of §7.

## 3. The executor

`exec="ftpsend"`. Internal, LOCAL source directory only. One step delivers one package to one
target.

| field | meaning |
|---|---|
| `target` | id of an entry in the FTPS target registry (§5). Required. |
| `source` | local directory holding the files to send. Required. Variables resolved. |
| `<send>` elements | the ordered mask list (§4). At least one required. |
| `remoteDir` | default remote directory, overridable per mask. Empty = the login directory. |
| `siteCommands` | commands sent verbatim after login, one per line, before the first transfer. Empty by default. A non-2xx reply fails the step. |
| `renameSent` | rename each local file after a verified upload. Empty by default (**nothing is renamed**). |
| `failFast` | fixed at true, not a parameter. See §4. |

The step is a delivery, not a synchronisation: it never reads the remote directory to decide what
to send. What is sent is what the masks match locally.

## 4. The ordered mask list

The answer to the batch-0 question "which of the five files are sent, and in what order" is that
**the executor must not know**. Different feeds carry different file sets and the completion
semantics belong to the receiving system, not to us. The step therefore carries an ordered list of
DOS/UNIX file masks, edited with ADD / DELETE and reordered with UP / DOWN.

In the workflow XML this is a repeated child element, the way `<replacement>` and `<reportQuery>`
already are:

```xml
<step id="send" exec="ftpsend" target="TRANSARCH_XF" source="${feedDir}/40_PACKAGING">
  <send pattern="*.tar"     transfer="BINARY"/>
  <send pattern="*.md5"     transfer="BINARY"/>
  <send pattern="*.audit.xml" transfer="BINARY" optional="true"/>
  <send pattern="*.control" transfer="BINARY"/>
</step>
```

Per-mask fields: `pattern` (required), `transfer` (`BINARY` default, or `ASCII`), `remoteDir`
(overrides the step default), `optional` (default **false**), `enabled` (default true, so a mask can
be parked without deleting it — the action table of the tool in service has exactly this toggle).

The rules, and the reasoning that makes them rules rather than preferences:

* **Wildcards are `*` and `?` only.** No `**`, no recursion into subdirectories, no character
  classes, no regular expressions. Directories are never matched, only regular files. A glob is
  translated by a matcher written for the purpose; it is **not** translated into a regular
  expression by string substitution, because a filename mask contains `.` on every use and one
  unescaped metacharacter turns `*.tar` into something that also matches `Xtar`.
* **Matching is case-insensitive.** The source directory is on NTFS, where `*.TAR` naming `.tar` is
  what an operator means. Stated here because it is the kind of decision that is otherwise made by
  accident by whichever comparison the implementation happened to use.
* **Order is: masks in list order; within a mask, files sorted by name.** The sort is explicit.
  Directory enumeration order is name order on NTFS and is *not* on other filesystems — measured
  during the tiffcompress work, where `Files.newDirectoryStream` returned hash order on ext4 — so a
  delivery whose ordering came from the directory would be correct on the target and untestable
  anywhere else.
* **First match wins.** A file already sent by an earlier mask is not sent again by a later one, so
  a trailing `*` catch-all is safe and does not duplicate the `.tar`.
* **A mask that matches nothing fails the step**, unless it is marked `optional`. A delivery missing
  its `.md5` is a broken delivery, and the failure mode that ELAR taught is the silent one. The
  strict behaviour is the default; the permissive one is opt-in and visible in the XML.
* **The first failure aborts the whole step. Nothing after it is attempted.** This is what makes the
  ordering meaningful at all: if `.tar` fails, the `.control` that announces the package as complete
  must never follow it. Not configurable.
* **The full ordered plan is logged before the first byte moves** — every file, in the order it will
  be sent, with its size and its transfer mode. An operator can then check the order they configured
  against the order that will happen, before it happens rather than in a post-mortem.

`ASCII` mode translates the local line separator to CRLF on the wire, which is what the protocol
requires and what makes a z/OS `RECFM=VB` dataset receive records rather than one long one. The
INDX that `elarxml` writes terminates lines with LF only (`WrappingXmlOut`, `NL = (char) 10`), so
without that translation the whole file is a single record and exceeds `LRECL`. **That the current
`FTPS.exe -a` invocation performs this translation is an inference from the fact that the delivery
works**, not something read from its documentation, and it is listed in §13.

## 5. The target definition

A new registry, `orchestrator.ftp-targets-file` (default `./ftp-targets.json`), holding
`FtpTargetDef` entries keyed by id, loaded and persisted exactly the way `DataSourceStore` does —
temp file plus atomic move, ids unique, no server.

It is deliberately **not** a `DataSourceDef` with `type="ftps"`. The field sets are disjoint, and
the datasources page offers a "test connection" that runs a query; an FTPS entry there would carry a
button that cannot mean anything for it.

```
id, name
host, port (2121), security (EXPLICIT | IMPLICIT, default EXPLICIT)
minTls (1.2), maxTls (1.3)
username, password (may be empty — see §1)
clientCertFile, clientCertPassword          PKCS#12
trustMode (JVM | FILE | ANY, default JVM), trustStoreFile, trustStorePassword
passive (true), ignorePasvAddress (true), reuseTlsSession (true)
dataProtection (PRIVATE, i.e. PROT P)
systemType (UNIX | ZOS), controlEncoding (UTF-8)
connectTimeoutSec (30), dataTimeoutSec (300)
```

* `ignorePasvAddress` and `reuseTlsSession` are **on by default**, which is a departure from the
  house rule that a new behaviour is born off. The justification is §2: the address in the `227`
  reply from this estate has been observed to be unroutable, and the client that works has session
  reuse enabled. A default that is known to be wrong for the only two servers in scope is not a
  conservative default, it is a broken one. Both remain switchable per target.
* `trustMode=ANY` accepts any server certificate. It exists because it may be the only way to get a
  first delivery through, and it **writes a WARN line to the step log on every run that uses it** —
  the same way `deleteContentAfterEmbed` announces itself before acting rather than after.
* **Passwords are stored in clear text**, as `datasources.json` already does, and the file is
  protected by filesystem ACLs. Neither password is ever written to a log, at any level, in any
  form — not the value, not its length.

Where the `.pfx` files live on the OpenProteo host is §13.

## 6. The transport

A new package `com.legalarchive.orchestrator.ftps`: **Spring-free, JDK-only, compiling under
`javac --release 8`**, behind an `FtpsTransport` interface with `JdkFtpsClient` as the
implementation.

The reason for JDK-only rather than Apache Commons Net is the same constraint that shaped
`json2csv`: **Maven Central is unreachable from the chat sandbox**, so an implementation importing
`commons-net` could not be compiled here, let alone exercised, and would arrive on the target having
never run. A hand-written client can be driven end to end here against a loopback FTPS server built
in the test itself. The scope that makes this defensible is narrow — `STOR` to two known servers,
explicit TLS, passive — and it is not a general-purpose FTP client. `commons-net` remains the
fallback behind the seam if the real conversation needs something this does not cover; whether it is
on the internal Nexus is §13.

The conversation, in order:

```
connect control socket            -> 220 (multiline tolerated)
AUTH TLS                          -> 234, then TLS handshake on the same socket
USER <user>                       -> 230 done | 331 -> PASS <password>
PBSZ 0                            -> 200
PROT P                            -> 200
SITE <...>                        -> 2xx, for each configured command
per file:
  TYPE I | TYPE A                 -> 200   (only when it changes)
  PASV                            -> 227 (h1,h2,h3,h4,p1,p2)
  connect data socket to (control host, p1*256+p2)
  wrap data socket in TLS, resuming the control session
  STOR <remote name>              -> 150 | 125
  stream the file, close the data socket
                                  -> 226 | 250
  SIZE <remote name>              -> 213 <n>
QUIT
```

Three details that are the whole difficulty:

* **The host from the `227` reply is discarded and the control host is used**, keeping only the
  port. §2 is the evidence; RFC 1123 permits it and every serious client does it.
* **TLS session resumption on the data channel** is obtained by creating the data `SSLSocket` over
  the connected plain socket while passing the **control** connection's host and port as the peer
  identity, so the JSSE session cache is consulted under the key it was populated with. No
  reflection into `sun.security.ssl`, which is the other published technique and which breaks
  between JDKs.
* **Replies are multiline until proven otherwise.** A reply line beginning `NNN-` continues until a
  line beginning `NNN` followed by a space. The z/OS log shows exactly this shape on the welcome
  banner and on the login. A parser that reads one line and moves on desynchronises on the second
  command and reports the wrong error for everything after it.

## 7. Verification after the PUT

**After each file: `SIZE` on the remote name, and the answer must equal the local file's byte
count.** Not "greater than zero" — equal.

Both halves of that sentence are load-bearing, and §2 is why. A `> 0` check rejects the `.control`,
which legitimately weighs zero bytes and is a valid part of the delivery. An absent check accepts a
49 KB `.tar` that arrived empty, which is the exact failure observed in UAT and which looked like
success from the client. Only equality separates the two.

`SIZE` travels on the control channel, so the verification still works on an estate where the data
channel is unreliable and where `LIST` is refused.

If `SIZE` is unsupported (`500`/`502`) the step **fails** rather than assuming the transfer was
good. A target that cannot be verified is a decision to take deliberately, not a default; if such a
target turns up, a `verify=SIZE|NONE` field is added to the target with `NONE` documented as
unverifiable.

## 8. What this executor never does

* **It never sends `LIST`, `NLST` or `MLSD`.** It has no reason to — the file set comes from the
  local masks — and in UAT the listing is precisely what fails. Not listing removes a whole class of
  failure from the delivery path.
* No `DELE`, no `RNFR`/`RNTO`, no `MKD` on the remote side. The executor does not modify anything it
  did not upload.
* No download. `GET` is a plausible future need (`Check-LandingIn.ps1` suggests an inbound flow
  exists) and would be a separate executor, `ftpget`, reusing this transport.
* No `REST`/resume, no active mode, no proxy support. Implicit TLS is in the target model but not
  implemented in batch 1.
* It does not create the local source directory, and it does not delete local files. `renameSent` is
  the only local mutation, and it is off by default.

## 9. Step log and run variables

`key=value`, stdout-parseable, milliseconds, no colour, no prompts.

```
plan file=TF0004165.20260904.S001.V1.tar bytes=50176 transfer=BINARY order=1
sent file=... bytes=50176 remoteBytes=50176 ms=812
```

Variables published: `${filesSent}`, `${bytesSent}`, `${filesMatched}`, `${masksEvaluated}`,
`${firstFailure}` (empty on success). **`${filesMatched}` must equal `${filesSent}` on a successful
run**, stated in the log as its own line: it is the cheapest assertion that the step did what it
claims, and the one number a gate can branch on.

## 10. The designer panel

Executor dropdown entry, a panel, and `clientValidate` requiring `target`, `source` and at least one
mask.

The mask list is the first **reorderable** list in a step panel. ADD and DELETE follow `addRepl` /
`delRepl`; UP and DOWN reuse the `.mv` button class and the `\u2191` / `\u2193` escapes already used
by the node reorder buttons at designer.html:865. Two constraints inherited from earlier defects:

* **Every control drives the panel through its own handler**, and the suite drives the controls the
  same way. A test that mutates the model directly hides the defect the test exists to catch — the
  `batchBy` lesson, and `tools/scan_panel_redraw.js` covers the class.
* The reorder buttons must be visually distinct from the `enabled` toggle. The `.nc-toggle` was
  enlarged and boxed for exactly this confusion.

No literal `\n` or `\r` in any JS added, and no `[[` or `[(` outside a Thymeleaf inlining comment.

## 11. Registration — the six places

Read at `21cb46b`, not recalled: `WorkflowXmlParser` line 89 (the whitelist), line 91 (the error
message, which lists every kind by hand), line 94 (the `internal` set); `WorkflowEngine.internalKind`
line 1027; the `else if` dispatch chain in `InternalSteps.run()` ending at line 112; and
`designer.html` — the dropdown ending at line 940, its panel, and its `clientValidate` block. All
six, or the executor is unreachable in a way that only appears at runtime.

## 12. Batches

1. **Core, no network.** The glob matcher, the reply parser, the plan builder and its ordering
   rules. Spring-free, no I/O. Exercised in the sandbox, mutations proved to fail before filing.
2. **The transport**, against a loopback FTPS server written in the test: the full conversation,
   multiline replies, `230`-without-`PASS`, the discarded `227` address, `STOR`, `SIZE`, and the
   abort-on-first-failure path. What cannot be proved here is declared: real session resumption
   against a real server, and the z/OS `SITE` dialogue.
3. **The executor and its six registrations**, the target registry and store, the designer panel.
4. **The targets admin page and `USAGE.md`.**

Nothing before batch 3 can change the behaviour of any existing feed, because until then nothing
outside the new package is touched.

## 13. Open questions

1. **Which host will run OpenProteo when it sends, and is it the host the working tool runs on?**
   Now the first question, because of §2: if it is a different host, the passive port range must be
   opened towards the FTPS server and no code substitutes for that request. The request needs source
   host, destination host, port 2121 and the passive range — and it should state that TLS and the
   client certificate are already proven to work, so that "check the credentials" is not the answer.
2. **Is `commons-net` on the internal Nexus, and at what version?** Needed only as the fallback.
3. **Where do the `.pfx` files live on the OpenProteo host** — a directory in the external
   `application.properties`, in the manner of `mask-pools-dir`.
4. **Does `FTPS.exe -a` translate LF to CRLF today?** §4 assumes it does because the ELAR delivery
   works. If it does not, something else is supplying the record boundaries and this specification
   has the mechanism wrong.
5. **Is the `.control` the completion marker for the receiving system?** The ordering is the
   operator's to configure either way, but if it is, it belongs last in every mask list by
   convention and that convention should be written into `USAGE.md`.
6. **Will an inbound flow be needed?** If yes, `ftpget` is designed with this transport rather than
   bolted onto this executor.

## 14. Predictions, recorded so they can be struck through

* **`trustMode=JVM` will fail on first contact.** The working client stores an accepted fingerprint
  rather than validating a chain, which suggests the server certificate is issued by an internal CA
  that is not in the JVM `cacerts`. If so the answer is `FILE` with that CA, and `ANY` is not the
  answer.
* **TLS 1.3 will make the session-resumption trick unreliable.** Resumption in 1.3 is ticket-based
  and is not the session-ID mechanism the "reuse TLS session ID" option was built around. If the
  data channel is refused while everything else works, capping `maxTls` at 1.2 is the first lever to
  pull, and this prediction is the reason the field exists.
