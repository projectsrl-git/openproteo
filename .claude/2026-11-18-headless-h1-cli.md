# Headless H1 — control channel and command line

Base `4851793`. Spec `.claude/HEADLESS.md`; the full account of this batch is its §13.

## What

OpenProteo can now be driven from the command line on the server through a control directory:
`ping`, `feeds`, `runs`, `status`, `log`, `run` (with `--var` and `--wait`), `wait`, `stop`,
`approve`, `reject`, `resume`, `reload`, `audit-verify`. The client runs from a third artifact,
`openproteo-headless.war`, which is built without the embedded Tomcat. It talks to the running
instance and never starts a second engine. The user recorded is the OS owner of the request file.
The control directory is set from the Platform page and applies at the next start.

## Why

This is the first half of headless operation: execution without a page. H2 adds the instance
that runs with no web server at all.

## Files

* engine: `WorkflowEngine` (overloads; GUI and cron calls unchanged)
* new: `control/*`, `cli/*`, `HeadlessApplication`, `web/ControlController`,
  `META-INF/spring.factories`, `tools/openproteo.cmd`, `tools/openproteo.sh`
* `AppProperties` (`controlDir`), `pom.xml` (second repackage), `platform.html`, `USAGE.md`
* `.claude/HEADLESS.md` (§6.3 code 23, §13), `CLAUDE.md` (three artifacts, layout)

## Verification

Verified on Linux: nothing (no Linux machine reachable from this session).
Verified on Windows: Windows 11, Zulu OpenJDK 1.8.0_362, real `mvn clean package` artifacts.
Every command, refusal and exit code; hand-made, expired and malformed requests; owner identity
and `declaredUser`; the withdrawal when no instance answers; the instance lock; an inbox open to
`BUILTIN\Users` switching the channel off; the Platform block in the browser; the `.cmd` wrapper.
List in §13.3.
Not verified on either: Linux as a whole; a second account; the Administrators-owner policy; the
plain WAR under an external Tomcat; a clean stop; `scan_platform_whitelist.js` (no node).

## Observed

Two instances started on the same feed directory broke the audit chain at start-up (duplicate
`seq` from two `WORKFLOW_LOADED`). This is the Q10 residual risk, seen for real (§13.4).

## Follow-up

H2: `HeadlessApplication` runs the instance (engine, cron, channel, no HTTP) and takes the
engine lock; `serverAddress` and the Host/Origin filter on the standalone.
