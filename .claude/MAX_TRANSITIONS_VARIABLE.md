# The transition limit, raised per workflow from a variable

Spec and record of one delivery, 2026-10-13, on `63dc39c`. Request: "Preparala come variabile per
il workflow" - the limit `orchestrator.max-transitions` (500) stopped a check of 50 packages at the
50th, and it could be raised only by restarting the server with a JVM option.

## The rule

The engine reads the run variable `maxTransitions` at every entry into a run - start, release from
a hold, decision on a manual gate - which is also when its transition count starts again. The
value is resolved (`${...}`) and then:

| value | limit | audit `TRANSITION_LIMIT` |
|---|---|---|
| absent or blank | `orchestrator.max-transitions` | none |
| a whole number above the configured limit, up to 100 000 | that number | "raises the limit from ..." |
| a whole number at or below the configured limit | the configured limit | "is not above ... : the configured limit applies" |
| a whole number above 100 000 | 100 000 | "is above the ceiling ..." |
| anything else | - the run ends FAILED before its next node, saying what is expected | |

`WorkflowEngine.transitionLimit(configured, value, note)` is the whole decision, static and pure.
The message of a run that reaches the limit now names both the property and the variable.

## Intersections

**∩ T1 — "a safety limit against gate loops" × "a variable anyone can set in the designer".** The
variable can only raise the limit, and never above 100 000: a loop that runs away still stops, at
worst after 100 000 transitions (minutes, and that many audit lines), and the configured value
stays the floor. Nobody can lower the safety below what the server was configured with.

**∩ T2 — "Default conservativi" (CLAUDE.md) × a new variable.** Without the variable nothing
changes: same limit, same behaviour, only a longer message when the limit is reached. A workflow
or global variable already named `maxTransitions` for another purpose would start to count; none
is in the repository's workflows and templates (checked), and the name is the engine's own word.

**∩ T3 — "Configurabilità integrale da GUI" (CLAUDE.md) × `orchestrator.max-transitions`.** The
property stays file-only and stays in the debt list; what the GUI now has is a way to raise the
limit per workflow (designer) or for every workflow (global variables), applied from the next run.
No new file-only key is added.

**∩ T4 — "global variables have the lowest precedence" (`buildRun`) × an empty workflow
variable.** A workflow variable hides the global one even when empty or lower. Therefore the
templates do NOT carry an empty `maxTransitions` variable - it would hide a global value - and only
their descriptions say how to add it. Said in USAGE.

**∩ T5 — "a bad value must not pass silently" × "a run already going".** Read at every entry, a
value that becomes invalid during a run - a step output named `maxTransitions`, for example -
stops it at the next entry, with the message. Decided: same rule as at the start; a limit nobody
can read is not a limit to run under.

## Evidence (Linux; Temurin 1.8.0_432 and OpenJDK 21.0.12)

* `transitionLimit` extracted by position and run: 21 cases (absent, blank, raise, blanks around,
  plus sign, leading zeros, 501, equal, lower, zero, negative, huge negative, the ceiling, above it,
  longer than a long, a configured value already above the ceiling, `2,000`, `2000.0`, `2e3`, a
  word, an unresolved `${...}`), both JDKs. Mutations: 7; 6 caught, 1 equivalent (the explicit
  removal of a `+` sign, which `Long.parseLong` accepts) - the line was removed instead.
* The walker of OBJECT_UNPACK_EXECUTOR.md §22 now applies the same, lifted method with a
  configured limit it is given: on `_TEMPLATE-objunpack-check-quarantine.xml`, limit 30 - the run
  stops at the 31st transition; `maxTransitions=100` - raised, audited, the run completes;
  `maxTransitions=10` - not lowered; `2,000` - stopped before the first node; `${myLimit}` with
  `myLimit=60` - resolved. The three other suites unchanged (24, 7, 8).
* `WorkflowEngine` by differential compilation, before and after: identical error sets; positive
  control - a call to a missing method in place of `transitionLimit` - reported.

**Not verified:** the engine's loop itself (the walker's loop is the author's, as in §22): that
`loop()` uses the computed limit is read and compiled, not run; the designer (not changed: a
variable is a variable); AIX; Windows.
