# Copy file list — batch 2: the filecopy and safecopy executors

Base commit: `0861916`. Spec: `.claude/COPY_FILE_LIST.md`.

## What is in this batch

`engine/LocalCopySupport` — JDK only, no Spring, no orchestrator types — is the transfer half of the
option whose reading half batch 1 extracted. `InternalSteps.runCopyList` is the one body `filecopy` and
`safecopy` share; they differ only in whether each file is staged under a temporary name first, and in
nothing else, which is why there is one method and not two. Both executors gain the `listSource` switch,
`filecopy` finally receives `resolvedParams` (it was the only copy executor that did not), and `ifscopy`
is untouched.

**Engine only. The option is not reachable from the designer** — no panel, and `clientValidate` still
requires `source` unconditionally on both. That is batch 3.

## The decisions that are not obvious

**The pre-scan is the local shape's reason to differ from `ifscopy`.** Over IFS an existence check is a
round trip per file — for a list of thousands, the whole transfer twice over — and buys nothing the
failure message does not already say, so `ifscopy` deliberately has none. Here it is a
`Files.isRegularFile` on the very filesystem the copy is about to read. Under `onMissingFile=fail` the
step therefore refuses **before copying anything** rather than stopping half way through. It matters
most on `safecopy`, whose whole purpose is that a process watching the landing zone never sees something
incomplete: a half-done run leaves real, correctly renamed files in that zone with nothing saying the
delivery was short.

**A listed name that already ends in the temp suffix is REFUSED, not skipped.** Under a pattern,
skipping is right — it is somebody else's in-flight file and nobody asked for it. In a list it was asked
for *by name*, and delivering it would put a file into the landing zone under a name every watcher is
built to ignore. That is a delivery that silently never arrives, which is the failure this executor
exists to prevent, arriving through the front door.

**A bare name with no base at all is refused**, where `ifscopy` only logs. There a bare name still lands
in the connection's home directory; here it would resolve against the process working directory —
Tomcat's — which is never what anybody meant. The message names both fields that fix it.

**A path that exists but is not a file is reported apart from one that is not there.** Same counter,
different sentence, because the remedy is different and "not found" would send somebody looking in the
wrong place.

**`mode=move` and `mode=list` are refused with a list** (Gate 0 Q4), naming the mode and saying which
way out to take. Not silently downgraded to a copy, and the mode not silently ignored: a CSV read as an
instruction to *remove* files is what the refusal is cheap insurance against.

## The executors are RUN, not reviewed

`InternalSteps.java` cannot be compiled here — it needs the Spring tree from the internal Nexus. But
`runFileCopy`, `runSafeCopy`, `runCopyList` and the four helpers they use touch only the JDK,
`VarResolver`, `StepDef` and `StepExecutor.Result`, **all of which are Spring-free**. So they are lifted
**verbatim at build time** out of the real file, compiled against those real types, and executed against
real files on disk. A transcription could drift from what ships; a lift cannot, and the lifter asserts
each body it emits is present byte-for-byte in the source.

**141 assertions, 14 mutations, all caught.**

**The pattern shape is proved unchanged the way batch 1 proved the reader**: the pre-patch bodies taken
out of `0861916` and the post-patch ones run over identical trees across eight pattern/mode scenarios —
`*.csv`, `*`, absent, empty, a multi-pattern list, a pattern matching nothing, and `move` and `list` —
comparing the exit code, every published variable, the destination listing, the source listing
afterwards and the whole log.

**That `safecopy` stages under the temp name cannot be deduced from the result**: the final directory
looks identical either way. So the destination is **watched while the copy runs** and
`big.bin.on_fly_` has to be seen. The mutation that writes straight to the final name is caught by that
assertion and by nothing else.

## Three things the run found that review would not have

**A mutation reported as caught, and was not.** The anchor
`if (!lr.collisions.isEmpty() && failOnCollision) {` occurs **twice** in `InternalSteps` —
`runIfsCopyList` carries the identical line — so `replace(..., 1)` mutated `ifscopy`, which this suite
does not exercise. It still read as *caught*, because the suite happened to be flaking on that run. Two
independent faults agreeing to produce a pass, which is the shape that is hardest to see: had either one
been absent, the other would have shown. The anchor is now unique.

**The suite was flaky, 2 runs in 10.** The in-flight watcher asserted it had also seen the *final* name,
which races its own shutdown — the watcher is stopped the instant the copy returns — and was redundant
anyway, since the final listing proves the file is there. Removed. **15 consecutive clean runs before
any mutation result was trusted**, because a suite that fails intermittently reports every mutation as
caught for the wrong reason.

**An assertion of mine was wrong rather than the code.** "COPY_ATTRIBUTES preserves the modification
time" failed while working: `FileTime.equals` compares **nanoseconds** and this filesystem truncates
them, so it was measuring clock resolution. It now stamps the source file in 2020 and asserts that
`filecopy` keeps that time and `safecopy` does not — which is the actual difference between the two
executors and had never been asserted at all.

## Known duplication, deliberately not removed

`runCopyList` repeats a good deal of `runIfsCopyList`'s parameter reading and reporting. Merging them
would change `ifscopy`'s log wording — its lines say "names that are not absolute", the local ones say
"names that are not already complete paths", and they are different statements — and no feed asked for
that. The part where a divergence would actually cost something, the reader, is already shared. Revisit
only together with a decision about the log text.

## Verified, and not

* `javac --release 8` on `LocalCopySupport` and on the lifted executor bodies.
* 141 assertions against real files; 14 mutations of the **real sources**, each re-lifted and rebuilt,
  every one caught, every anchor asserted present before the edit.
* Brace, parenthesis and bracket balance on `InternalSteps.java` with strings and comments stripped.
* `CopyListSupport` is **byte-unchanged** by this batch — asserted, not assumed.
* Both new classes are in `engine`, the same package as `InternalSteps`, so no import was added.
* **NOT compiled: `InternalSteps.java` as a whole.** `mvn clean package` is the gate.
* **NOT run: on Windows.** Everything here is exercised on Linux; the separator and drive-letter rules
  are the ones from batch 1, whose Windows half remains an argument from the documented semantics of
  `Paths` rather than a measurement.
* **NOT exercised: a list of tens of thousands of files.** The list is held whole, as `ifscopy` holds
  it, and the limit is stated in `CopyListSupport`'s javadoc. One log line per file also stands — the
  same `PER_FILE` noise follow-up `ifscopy` recorded, and the fix if it bites is a summary every N
  files, not a silent copy.

## Next

Batch 3: the designer panels for both executors, driven under jsdom through the handlers the page wires,
never around them — including the shape dropdown, which must set the param **and re-render**, and
`clientValidate`, which must stop requiring `source` in the list shape and must refuse `mode=move` /
`mode=list` there. Then batch 4, `USAGE.md`.
