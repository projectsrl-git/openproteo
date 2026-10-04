# 2026-10-04 — FTPS trust on Linux (L2)

Base `41b4041`. Spec: `.claude/LINUX_AND_GUI_CONFIG.md` §9.4. Finding F7 of `.claude/LINUX_AUDIT.md`.

## What

On a server without the Windows certificate store (any Linux), an FTPS target using trust mode
`WINDOWS` cannot connect - and until now nothing said so before the first run, and a new target
was proposed exactly that mode.

- `ftps/TrustAdvice` + `GET /api/ftp-targets/trust`: whether the store exists here, and the mode
  to propose for a NEW target (`WINDOWS` where it exists, `JVM` elsewhere).
- The FTPS targets page: a new target takes the proposed mode; the `WINDOWS` option is labelled as
  not available; choosing it explains that it will fail and what to choose; each target in the list
  that would fail is marked. Placeholders no longer show a Windows path.
- `SslContexts`: the failure for `WINDOWS` on a JVM without the store now says what to choose.
- `USAGE.md`: «The server certificate, on Windows and on Linux», under ftpsend.

## Decided without the author (he cannot answer before L3)

- **A saved target is never touched** - his own rule. Asserted on the page.
- **An ABSENT trust mode still means `WINDOWS` on every host.** He asked for a proposal with its
  risk; the proposal is to change nothing. Reasons and risk in the spec §9.4.
- **`JVM`, not `FILE`, is proposed on Linux.** Reason in the spec §9.4.
- **`WINDOWS` can still be chosen on Linux**, with the warning: a target can be prepared here for a
  Windows server.

## Files

- `ftps/TrustAdvice.java` — new, JDK only.
- `ftps/SslContexts.java` — the message. `ftps/TrustMode.java` — a comment recording the decision.
- `web/ApiController.java` — one GET endpoint.
- `templates/ftptargets.html`.
- `static/USAGE.md`.
- `.claude/LINUX_AND_GUI_CONFIG.md` §9.4, `.claude/LINUX_AUDIT.md` (F7 marked), this note,
  `COMMIT_MSG.txt`.

`FtpsTarget.java` is NOT changed. No runtime parameter. `CLAUDE.md` not touched. `README.md` not
touched.

## Verification

**Verified on Linux (sandbox; Java 1.8.0_432 and JDK 21.0.12, both run):**

- `TrustSuite`, 23 assertions on the real `TrustAdvice`, `TrustMode`, `FtpsTarget` and
  `SslContexts`, with truststores made by `keytool`. 8 mutations, all caught - three of them are
  "the model default follows the host", which is exactly what was decided against.
- `ftptargets.html` in jsdom, 37 assertions, with the PRE-PATCH page loaded beside it: on a host
  with the store the list, the hint of each of the four modes and the save payload are identical;
  with the endpoint failing or answering HTML, likewise. 13 mutations, all caught.
- `USAGE.md` through `docs.html`'s `render()`: 21 for the new section (it is followed by
  «Parameters», so it swallowed nothing), and the earlier 122 still pass.
- No literal `\n` / `\r`, no `[[` / `[(` in the page; `git apply --check` on a second clean clone.

**Verified on Windows: nothing.** To check there: the FTPS targets page looks and behaves as
before (new target = Windows store, no marker in the list, same hints); an existing feed's
`ftpsend` step is unchanged.

**Not verified on either:** the new endpoint in `ApiController` (three lines, not compiled: that
class needs Spring); a connection to a real FTPS server from Linux; RHEL-family `cacerts` (stated
in the guide as "other distributions arrange it in their own way").

## Follow-up

L3: `.claude/LINUX_AUDIT.md` §5.
