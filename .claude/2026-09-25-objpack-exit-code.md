# objpack — every successful run was reported as failed

The step built the package, verified it and wrote the checksum, and the run history showed
**FAILED, exit code -1**, with no message. The package on disk was correct.

## The defect

`StepExecutor.Result.exitCode` is initialised to `-1`. Every internal executor is expected to set it
to `0` on its success path — some literally, some through a ternary such as
`res.exitCode = failed > 0 ? 1 : 0`. `runObjPack` set `2` on each of its refusal paths and **never
set anything on the path where the work succeeded**, so the initial `-1` survived and the engine
reported a failure.

It went unnoticed because until today the step had never completed: every earlier run stopped at a
real refusal, which does set an exit code. The first genuinely successful packaging is also the
first run able to expose it.

## Why the tests did not catch it

`PackSuite` exercises `ObjectPack.run()` directly. `InternalSteps.runObjPack` — the assembly layer
— is never executed by it, and cannot be: it needs the Spring dependency tree that is not available
where this is built.

The batch-2 specification says of that layer that it "only reads parameters and reports the outcome,
which is why it has no logic worth testing of its own". **That claim is now falsified.** Reporting
the outcome *is* logic, it is the one piece of state the layer owns, and it was wrong.

## The check that would have caught it, and now exists

A static lint over `InternalSteps`: for each `run*` method, does any assignment to `exitCode` have a
value that can be zero — a literal, or a ternary with a zero branch — or does the method delegate to
another `run*`? Anything else can never report success.

The first version of the lint matched the literal string `res.exitCode = 0` and flagged seven
methods. Six were false alarms: `runDiffText`, `runDiffTextSet`, `runElarCheck` and
`runEncodingBatch` set it through ternaries, and `runDiff` delegates to its sub-modes. Only
`runObjPack` could never report success. After the fix the lint reports none — across all 28 run
methods, not just this one.

## Verification

The lint, before and after. Brace balance on the edited file. `ObjectPack` itself is untouched, so
the 128 assertions and 36 mutations still stand from the previous batch and were not re-run for a
change that adds one statement to a different file.

## Not verified

`mvn clean package`. The fix has not been run against the real feed — the confirmation to look for
is the same package the step already produced correctly, this time with the step shown as
successful.
