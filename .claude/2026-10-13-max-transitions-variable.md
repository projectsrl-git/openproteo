# 2026-10-13 — maxTransitions as a workflow variable

Base: `63dc39c`. Request: raise the transition limit per workflow, without restarting the server.
Spec and evidence: `.claude/MAX_TRANSITIONS_VARIABLE.md`.

- `WorkflowEngine`: `transitionLimit` (pure), read at every entry of `loop()`; audit
  `TRANSITION_LIMIT`; the limit message names property and variable.
- Templates check-quarantine, check-and-correct, precheck-resend-all: descriptions only (no empty
  variable: it would hide a global one).
- USAGE: "Loops" (the variable and its rules), the objunpack limits bullet.

Verified on Linux (JDK): Temurin 1.8.0_432 and OpenJDK 21.0.12 - 21 cases on the lifted method, 7
mutations, walker on the template with a lowered limit (5 cases), differential compile of the engine.
Verified on Windows: nothing.
Not verified: the engine's loop at run time, AIX.
