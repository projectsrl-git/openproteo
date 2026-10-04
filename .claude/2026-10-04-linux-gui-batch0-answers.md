# 2026-10-04 — Contract amendment, the author's four answers

Base `eee2d8c` (batch 0 as committed). Spec: `.claude/LINUX_AND_GUI_CONFIG.md`. Documentation only.

## What

Batch 0 closed with four questions. The answers, applied where each rule lives:

1. **«Identico» was the wrong word.** The Linux rule now says the application and every internal
   executor work in an EQUIVALENT way on Windows and Linux, respecting the context and the
   specifics of the host OS - OpenProteo adapts to its host. Corrected where it was written, the
   old word struck through. `unarchive` following the detected OS (batch L1) is the rule working.
2. **Linux rule vs conservative defaults** is written beside both rules: the case and its example
   under «Default conservativi», a pointer in the Linux rule.
3. **«Checklist pre-commit»** gains item 9 (three-line Linux / Windows / neither declaration, JDK
   named) and item 10 (no new file-only runtime parameter, or the delivery calls itself incomplete).
4. **`server.port`**: settable from the GUI, effective from the next start of the application.
   Conservative, "for now". Recorded in «Configurazione esterna», the "open case" wording struck.

## Two things in the patch that are mine, not dictated

- The clause **"e il run dichiara quali regole ha usato"** after the author's sentence in the Linux
  rule. Proposed in the previous delivery as the test for §4.5; the answer neither took it nor
  refused it. Kept because "adapts to the host" alone would also allow a difference nobody can see
  afterwards. One line to remove if unwanted.
- **`server.port` only**, not `server.*`. The answer named that key; the rest stays out of scope as
  container configuration. And the note that under the external Tomcat the value has no effect,
  left to the batch 4 spec.

## Files

- `CLAUDE.md` — Linux rule; «Default conservativi»; «Configurazione esterna»; checklist 9 and 10;
  two "Resolved" markers in the batch 0 entry; new entry at the end.
- `.claude/LINUX_AND_GUI_CONFIG.md` — §4.2, §4.5, §4.6, §6: answered, superseded lines struck.
- `.claude/2026-10-04-linux-gui-batch0-answers.md` — this note.
- `COMMIT_MSG.txt`.

No Java, template, JS, `pom.xml`, `USAGE.md` or `README.md`. `USAGE.md` untouched for the same
reason as batch 0: nothing a user can see changed. No new runtime parameter (checklist 10).

## Still open

- Spec §6.4: «Cos'è OpenProteo» still says "senza embedded server", stale since the standalone
  artifact - and more visibly so now that the standalone port is a first-class setting.
- Spec §4.7: `X-User` header vs container identity. Batch 3.

## Verification

Verified on Linux (sandbox; no JDK involved, nothing compiled): `git ls-remote origin main` =
`eee2d8c` before generating, fresh clone; every edit anchor matched exactly once; the patch touches
only `CLAUDE.md` and two `.claude` files; `git apply --check` on a second clean clone; no CR in
`CLAUDE.md`; the checklist is still one numbered list, 1 to 10.

Verified on Windows: nothing. Nothing here is platform-dependent.

Not verified on either: how the struck-through text renders in the author's Markdown viewer.

## Follow-up

Batch 1: platform diagnostics panel, spec first, as a new section of the programme file.
