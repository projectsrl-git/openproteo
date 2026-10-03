# unarchive — Batch 4: the designer panel (executor complete)

The last three registration places, the panel, `PARAM_OPTIONS`, and the two sentences of USAGE.md
that the panel made false. Spec `.claude/UNARCHIVE_EXECUTOR.md` §20. No Java changed.

## What is in it

* `designer.html`: the option; `uaSeed` (choosing the executor writes the 17 defaults, never over a
  value already there); a three-section panel; hints for gz, replace and rename; `clientValidate`.
  `buildXml` unchanged — read: it emits every parameter generically.
* `variables.html`: eight `PARAM_OPTIONS` keys. `failOnEmpty` and `checkFreeDisk` are shared with
  sqlreport and elarxml (same meaning, same default); the panel writes `true`/`false`, never
  `yes`/`no`, because sqlreport accepts only `"true"`.
* `USAGE.md`: "Until the designer offers the step" and "No designer panel yet" replaced.

## Verification

* Panel suite, 111 assertions, real template in jsdom, every control driven through its own handler:
  names written == names read (19), seeded values == executor defaults, options == values the executor
  accepts (read from the Java source), `PARAM_OPTIONS` == panel options, placeholders == defaults.
* The designer's XML read by the real parser and run by `runUnarchive`: exit 0, archive extracted.
* Pre-patch template fails; the batch-3 guide fails the two new staleness checks.
* Mutations: 19 — 18 caught, 1 equivalent (argued in §20). One caught only after closing a gap (a
  select's own handler never exercised for its hint); one first "caught" by a crash that hid the real
  failure — the suite now records throws as failures.

## Not verified

A real browser, `mvn clean package`, a run in the container.
