# Headless H0 — spec for a GUI-less artifact and a command-line control channel

Base `eb9790d`. Spec only, no code: `.claude/HEADLESS.md`, revision r4.

## What

The spec of a third artifact, `openproteo-headless.war`, with no web server in it. Execution and
observation move to a CLI, which talks to the single instance through a file-based control
directory. Access control becomes the server's own: two-factor logon, plus the ACL of the inbox.

## Why

OpenProteo has no sign-in, and every page and `/api/**` endpoint answers anyone who can reach the
port. That includes the CSV viewer, which pages through working files that hold client data.
Authentication is the last batch of `LINUX_AND_GUI_CONFIG.md`. This closes the HTTP exposure
without it.

## Decided with the author (§12 of the spec)

| Question | Answer |
|---|---|
| Q1 | one engine plus a file channel (option B), not a JVM per command |
| Q2 | reads go through the channel too |
| Q3 | `controlDir` and `serverAddress` are saved in `<workflowsDir>/openproteo-instance.json`, written from the Platform page |
| Q4 | operators use personal accounts only |
| Q5 | the audit records run variables as name and value |
| Q7 | the setup GUI may be bound to loopback |
| Q8 | a world-writable inbox switches the channel off |
| Q10 | the engine lock belongs to the headless artifact only |
| Q11 | a Host/Origin filter on the loopback GUI |
| (author's proposal) | a third artifact instead of a gui/headless switch |

Still open, neither of which changes the design:

* Q6: on Tomcat hosts, the CLI client runs from the headless artifact copied beside it;
* Q7: whether a browser is available on the server.

## Files

* `.claude/HEADLESS.md` (new)
* this note

## Verification

Verified on Linux: nothing (spec only).
Verified on Windows: nothing (spec only).
Not verified on either: everything; the verification plan per batch is §11 of the spec.

## Follow-up

H1: the control channel, the CLI client, `engine.start` with variables, the instance settings
file and its Platform block, and the build of the headless artifact. H2: the headless instance
itself, and `serverAddress` with its filter.
