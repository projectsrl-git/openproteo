# Headless H3b — the console, start and shutdown

Base `20a9198`. Spec `.claude/HEADLESS.md` §15 (scenario restated by the author, decisions D1-D6),
§15.13 (answers Q12/Q14, findings), §15.14 (this delivery, verification).

## What

* `openproteo start` starts the headless instance in the background from `OPENPROTEO_HOME`, under
  `setsid` on Linux. It refuses if an instance already serves, and waits until the new one answers.
* `openproteo connect` opens an interactive console. Every command is one request on the control
  channel, so each line is recorded under the OS account of the person who typed it.
* `shutdown` (console, with confirmation; or one-shot for scripts) stops the instance cleanly. It is
  refused with active runs unless `--force`, and audited in every feed.
* The inbox is polled every 250 ms. The wrappers default `OPENPROTEO_HOME` to their own folder. A
  systemd template is added.

## Why

The author's scenario for RHEL8: no HTTP on the server, people reach OpenProteo only by SSH, one
instance started from the shell, then a console inside the program. Done ahead of H3a (the loopback
GUI to a branch) for a demo on a Linux server.

## Files

* `cli/OpConsole` (new), `cli/Starter` (new), `cli/OpCli`, `cli/ControlClient`
* `control/EngineBackend` (shutdown), `control/ControlProtocol` (`force`), `control/ControlChannel`
  (250 ms)
* `tools/openproteo.sh`, `tools/openproteo.cmd`, `tools/openproteo.service` (new)
* `USAGE.md` "On a Linux server: install, start, connect"; `.claude/HEADLESS.md` §15

## Verification

Verified on Linux: nothing yet (first run: the author's demo on a Linux server).
Verified on Windows: Windows 11, Zulu OpenJDK 1.8.0_362, Maven artifacts. Covered: start and a
refused second start; a scripted console session (feeds, use, run with a variable followed to the
gate, status, log, approve followed to SUCCESS, runs, errors, exit); shutdown refused with a running
run, then forced, giving a clean stop (stopped flag, process gone, INSTANCE_SHUTDOWN audited,
chains verifying after restart); a one-shot shutdown; ping after a stop. The list is in §15.14.
Not verified on either: setsid and surviving SSH logout; Ctrl-C while following (no interactive
terminal here); the systemd unit; start under sudo; two operators.

## Follow-up

H3a: move the loopback GUI to `feature/loopback-gui` and remove it from `main` (§15.9).
