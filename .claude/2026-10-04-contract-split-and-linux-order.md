# 2026-10-04 — `CLAUDE.md` back to a contract; Linux first, authentication last

Base `51d173f`. Documentation only.

## What

1. **`CLAUDE.md` is the contract only.** The 3472 lines of delivery entries that had accumulated at
   its end (from "Mask pools: selezione per-file + gestione" to the batch 2 entry) are moved,
   verbatim and in order, to the new `.claude/HISTORY.md`, a closed archive. `CLAUDE.md` goes from
   3962 lines to 578.
2. **A delivery no longer appends to `CLAUDE.md`.** It was a habit, never a written rule. What a
   delivery did and learned goes in its note; a lesson that must bind future work becomes a rule
   in the section it belongs to; a measured fact goes in the spec of its area. Written in the new
   section «Come e' organizzata la documentazione di sviluppo» and in «Convenzioni di commit».
3. **«Verifica prima della consegna» caught up** with what the last two batches established: run
   the real class (Spring stubbed), not an extract; a real Java 8 runtime and PowerShell for Linux
   can be fetched into the sandbox; the sandbox is root and has no locale, and both falsify tests;
   a test about processes, command lines or locales is itself one.
4. **External runners have their own registration path**, now in the contract beside the
   8-location rule for internal executors (it was only in the batch 2 spec).
5. **The programme's order** (spec §9): Linux first, authentication and what needs it last. The
   author's Linux run of the standalone war is recorded for exactly what was reported.

## Why this shape

A new chat is told to read `CLAUDE.md` in full. At 3962 lines, 12% of it was contract; the rest was
history, most of it about areas the task at hand does not touch, and the newest lessons were at the
very end. And every delivery edited the same last line, so two chats in parallel always conflicted
- it happened twice in this programme.

The history is not lost and not summarised: it is the same text in another file, to be searched by
area. Nothing was rewritten, because rewriting 170 entries would have meant deciding which measured
facts still matter, which nobody can do without re-reading the code they describe.

## For the prompt that opens a new chat

"Clona il repo e leggi `CLAUDE.md` per intero" still holds and now costs about 580 lines. Worth
adding one sentence: "cerca in `.claude/HISTORY.md` l'area del task prima di progettare".

## Files

- `CLAUDE.md` — history removed; one new section; four sections amended.
- `.claude/HISTORY.md` — new: a short header, then the moved entries.
- `.claude/LINUX_AND_GUI_CONFIG.md` — §2 note, §9.
- `.claude/2026-10-04-contract-split-and-linux-order.md` — this note.
- `COMMIT_MSG.txt`.

No Java, template, JS, `pom.xml`, `USAGE.md`, `README.md`. No runtime parameter.

## Order of application

**Apply this before any other patch in flight, or regenerate that one on top of it.** A patch from
another chat that appends at the end of `CLAUDE.md` no longer applies after this one: its entry
belongs in its own note now.

## Verification

Verified on Linux (sandbox; nothing compiled): `git ls-remote origin main` = `51d173f` before
generating, fresh clone; the moved block is byte-identical (SHA-256 of the text removed from
`CLAUDE.md` equals that of `HISTORY.md` after its header: `bd81c00b70b58167...`); the first 490
lines of the old `CLAUDE.md` are all still in the new one except the lines deliberately amended;
no «...» reference in the contract points at a heading that moved; `git apply --check` on a
second clean clone; no CR.

Verified on Windows: nothing; nothing here is platform-dependent.

Not verified on either: that every FACT the contract's sections state is still true of the code -
this delivery moved and amended text, it did not audit the contract against the code.

## Follow-up

L1, the Linux audit of the internal executors (spec §9.2). And, from the author: what `/platform`
shows on the Linux instance, and the report of the Windows kit.
