# Headless H3a — the loopback GUI moved to a branch

Base `01849c5`. Spec `.claude/HEADLESS.md` §15.9 (decision D1), §15.15 (delivery).

## What

The loopback GUI of H2 (bind address `serverAddress` and the Host/Origin guard) is not used in the
RHEL8 scenario, where the server runs only the headless instance. It now lives on the branch
`feature/loopback-gui` (at `20a9198`, pushed). On `main` it is removed with a forward commit; history
is not rewritten.

## Files

* removed: `web/LoopbackRules.java`, `web/LoopbackGuard.java`
* `InstanceSettingsPostProcessor`, `InstanceSettings`, `ControlService`: no `serverAddress`
* `web/ControlController.java`, `templates/platform.html`: back to their H1 version
* `USAGE.md`: subsection "The GUI only from the server" removed; `HeadlessConfig` comment
* `.claude/HEADLESS.md`: notes at Q7, Q11, §14, and §15.15

## Verification

Verified on Linux: nothing.
Verified on Windows: Zulu OpenJDK 1.8.0_362, Maven artifacts. A standalone with a saved
`serverAddress: 127.0.0.1` binds every interface and accepts a foreign Host; the saved file is not
rewritten; the headless artifact still starts, lists feeds and stops; the template scans are clean.
Not verified on either: nothing specific to this change beyond the above.
