# Headless H2 — the instance with no web server, and the loopback GUI

Base `dbfdf3f`. Spec `.claude/HEADLESS.md`; the full account of this batch is its §14.

## What

* `java -jar openproteo-headless.war` (no `cli`) now runs the headless instance: the engine, the
  cron and the control channel, with no HTTP listener and no servlet API in the artifact.
* Before any bean exists it takes the engine lock in the workflows directory (headless only, Q10)
  and checks the control directory. Either failure refuses the start with exit 1.
* The standalone GUI can be bound to an address saved from the Platform page (`serverAddress`,
  applied as `server.address` at the next start). Bound to loopback, a Host/Origin guard refuses
  DNS rebinding and cross-site changes (Q11).

## Why

This is the second half of headless operation: nothing in OpenProteo is reachable over HTTP in
production, and the setup GUI can be limited to sessions on the server.

## Files

* new: `com/legalarchive/headless/HeadlessConfig`, `control/EngineLock`, `web/LoopbackRules`,
  `web/LoopbackGuard`
* `HeadlessApplication`, `ControlChannel` (`preflight`), `ControlService` (mode, serving),
  `InstanceSettings`, `InstanceSettingsPostProcessor`, `ControlController`, `platform.html`,
  `USAGE.md`
* `.claude/HEADLESS.md` §14, `CLAUDE.md` (artifact description, layout)

## Verification

Verified on Linux: nothing (no Linux machine reachable from this session).
Verified on Windows: Windows 11, Zulu OpenJDK 1.8.0_362, artifacts from `mvn clean package`.
* The headless instance ran with no TCP or UDP socket of its own, a standalone serving as the
  positive control.
* Runs and approvals went through it.
* Four refused starts, each leaving nothing on disk.
* The archive listing.
* A mutation of the scan exclusions fails the start (`NoClassDefFoundError: javax/servlet/Filter`).
* `serverAddress` bound to loopback only, with an off-host address refused at save.
* The guard on real requests: 403 on rebinding and on cross-site changes, with no run created.
* The pages working through the guard in the browser.
* The rules harness and two mutations.

Not verified on either: Linux; a clean stop (every process was killed with no shutdown hook); a
Windows service or systemd; the guard under an external Tomcat; an IPv6 loopback bind.

## Follow-up

Open questions Q6 (the CLI client and the headless instance on a Tomcat host) and Q7 (a browser on
the server, for the loopback GUI). The Q10 residual risk, seen in H1, is documented in `USAGE.md`
as an operating rule: never run a GUI and a headless instance on the same workflows directory.
