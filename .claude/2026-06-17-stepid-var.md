# 2026-06-17 — ${stepId} / ${stepName} variables

The engine now seeds, for the step currently running, ${stepId} (its id) and ${stepName}
(its name), set alongside ${stepDir} just before each step executes (WorkflowEngine ~L480).
Overwritten per step, like stepDir. Useful for building values/paths that reference the
current step generically. Added to the designer cheat sheet + path suggestions and USAGE.
76 classes compile; designer JS OK.
