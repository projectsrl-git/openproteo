# objpack — mime_type names the packaged object, and the step reports progress

Two things from a run on feed tf0005756.

## 1. The original file need not carry its type's extension

The step failed with `mime_type '.json' does not match the object's extension '.2' for
TF0005756_ACCOUNT.DEBIT.INT.GG_GB0010007_1.2`. The file held JSON; its name ends in a version
suffix. Nothing wrong with it.

**The failure was hiding a worse defect.** The object's extension *inside the package* was taken
from the **original** file name — `extensionOf("…GB0010007_1.2")` is `.2` — so without the check the
file would have entered the tar as `…OID1.2`, with `.json` declared in both the metadata CSV and the
audit JSON. That is precisely the disagreement Transarch's §4 validation ("specified mime type in
metadata against file name") rejects, because the archive checks the name **in the package**, not
the original. The check stopped a bad package, but for the wrong reason, and with a message that
accused a legitimate file.

The design error was in the Gate 0 9.3 answer as I wrote it into the spec — "the pre-flight compares
the column against the object file's own extension" — which compared the declared type with the
wrong file. Corrected in the spec, struck through rather than deleted.

**Fix:** the packaged extension now comes **from mime_type**. The name Transarch checks and the type
it checks against agree by construction; the original may be called anything and is preserved in
`original_object_name`. A mime_type that cannot be a single suffix token (`application/json`) is
refused, since it would put a path separator inside a tar member name.

The original-name comparison survives as **`mimeCheck` = off | warn | fail**, default **warn**,
because it still has one use: an early hint that rows and files were paired wrongly — it is what
caught the positional-pairing near miss on tf0003709. In warn mode it produces **one** summary line
with a count and three examples, never a line per object: on a feed like this one, where no original
carries its type, per-object lines would bury the log.

## 2. Progress in the live console

The step ran 82 s — 11:48:18 to 11:49:40 — and failed on **the first row**, yet the mime check only
runs after every row has been read and paired. So the 82 s were spent before any check, most
plausibly in file-system round trips on `P:`. That is an inference, not a measurement, and it is
deliberately **not** acted on here: no optimisation without knowing which phase is slow.

What was added is the means to know. Every phase announces itself and **reports its duration** —
listing the objects directory, reading and pairing, pre-flight, writing the tar, the read-back
verification, the md5 — and each long loop emits a heartbeat at most every 5 s with the count so
far. A fast run prints phases only. `ObjectPack` takes a `Consumer<String>`; `InternalSteps` passes
the step's own `line`, so it streams to the live console. The size measured in pre-flight is cached
on each pair, so the tar heartbeat costs no extra stat on a network share.

If the next run shows the time in *listed … files* or *pre-flight done*, the candidate fix is
`Files.walkFileTree`, which on Windows gets attributes from the directory enumeration instead of one
round trip per file. Not done until the numbers say so.

## Verification

**155 assertions, 45 mutations all caught.** New tests reproduce the production name exactly and
assert the object is packaged as `…OID1.json` and **not** `…OID1.2`, that the audit's `file_name` and
`mime_type` agree, that the original survives in the metadata, and that GNU tar extracts it at its
original size. Also: `mimeCheck=off` is silent, forty differing names give **one** warning line, a
dotless `json` still yields `.json`, `application/json` is refused, and every phase line appears
with its duration.

Three things did not pass first time:

* Two tests asserted the **old default**, refusal. Moved to `mimeCheck=fail` rather than deleted, so
  the fail path stays covered.
* The "a small run does not flood" test used **one object**, so a heartbeat that fired on *every*
  item would have added three lines and stayed under the bound — it could not detect the defect it
  was named for. A forty-object run at the default interval now asserts **zero** heartbeat lines,
  and the always-firing mutation is caught.
* One mutation was unusable because **my own rewrite of the tar loop broke its anchor**. Realigned,
  not discarded; it is caught.

Panel and executor: 34 parameters written, 34 read. `node --check` on the designer. Brace balance on
both Java files.

## Not verified

`mvn clean package`. A run on the real feed — which is where the progress durations will first say
something useful.
