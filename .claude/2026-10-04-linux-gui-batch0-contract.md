# 2026-10-04 — Contract amendment: Linux compatibility, GUI-only configuration (batch 0)

Base `712db45`. Third generation of this patch: first on `a4f02c8`, regenerated on `0456c9e`, then on
`712db45` after the `unarchive` batches; the second was never committed. Spec: `.claude/LINUX_AND_GUI_CONFIG.md`. Documentation only.

## What

Two rules added to `CLAUDE.md` «Stack e regole irrinunciabili», with the text the author dictated:
**Compatibilità Linux obbligatoria** and **Configurabilità integrale da GUI**. The Zero CDN line
and «Configurazione esterna», which both said that configuration lives in the external
`application.properties`, are aligned. Old wording is struck through, not deleted.

## Why it is written the way it is

The rules describe a target; the code at `712db45` does not meet it. An aligned section that only
restated the rule would have made `CLAUDE.md` claim that paths are configured from the GUI, which
is false until the settings batch. So «Configurazione esterna» states the rule, then the state:
what is already in the GUI, what is file-only debt, and which of the two wins when the rule and
the state meet (existing debt: the state; any new key: the rule).

## What the review on `712db45` changed

`unarchive` batch L1 landed in between and reads `os.name`. Two consequences. The baseline row "no
`os.name` in the code" is no longer true and says so. And the new rule's «funzionare in modo
identico» meets an executor that, by the author's Gate 0, deliberately does not: the intersection is
decided by name in the spec §4.5 and in the `CLAUDE.md` entry (the Gate 0 decision stands, as the
rule's «rilevato a runtime» branch), and it is the first open point because the reading is mine.

## Files

- `CLAUDE.md` — two rules; Zero CDN line; «Configurazione esterna»; entry at the end.
- `.claude/LINUX_AND_GUI_CONFIG.md` — programme spec: batches, baseline, intersections, open points.
- `.claude/2026-10-04-linux-gui-batch0-contract.md` — this note.
- `COMMIT_MSG.txt`.

No Java, no template, no JS, no `pom.xml`, no `USAGE.md`, no `README.md`.

## Deviation, stated

The task asks for `USAGE.md` at every batch. Not touched here: nothing a user can see changed, and
`USAGE.md` documents the product as it runs. Its «Deployment and configuration» section says
configuration lives only in the external `application.properties`, which is still true. It changes
with batch 4, when it stops being true.

## Verification

Verified on Linux (sandbox): `git ls-remote origin main` re-read before generating = `712db45`;
generated on a fresh clone of it, the `CLAUDE.md` edits re-applied by script, the three other files
carried over and revised; the baseline of the spec §3 by reading each cited line at `712db45`, and
that none of the cited files is in `git diff --name-only a4f02c8 712db45`; the eight `CLAUDE.md`
entries added in between were read; the patch touches only `CLAUDE.md` and the two
`.claude` files (`COMMIT_MSG.txt` travels beside the patch, not in it); `git apply --check` on a second clean clone; `CLAUDE.md` has no CR and
every edit anchor matched exactly once.

Verified on Windows: nothing. Nothing in this batch is platform-dependent.

Not verified on either: that the two rule paragraphs are character-identical to the dictated text.
They were transcribed from the request; there is no second copy to diff against in the sandbox, and
comparing my transcription with itself would prove nothing. Seventeen lines, to be read once.

## Order of application

This patch appends an entry at the end of `CLAUDE.md`. So does every other delivery, including the
`unarchive` batches running in parallel. Whichever is applied second must be generated on top of
the first; the base hash in the zip name is what says so.

## Follow-up

Batch 1: platform diagnostics panel. Spec first, as a new section of the programme file.
Four open points for the author in §6 of the spec; the first one matters.
