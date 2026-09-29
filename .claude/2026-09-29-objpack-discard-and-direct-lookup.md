# objpack — discard a row instead of failing, and stop listing the directory

## The request

A run on tf0005766 stopped with `line 7: record_business_date 20160921 is older than 120 months
(spec 4)`. The feed needs a configuration option to leave such a row out rather than fail.

## What changed

**`onStaleBusinessDate` = warn | fail | skip.** It replaces the `failOnStaleBusinessDate` checkbox
in the designer. The boolean is still read, and still means fail, whenever the new setting is
absent — so a workflow that ticked it, as this feed did, keeps failing exactly as before until the
choice is changed. The designer shows such a step as *fail the step*.

**The window moved before id assignment.** It used to run in pre-flight, after object ids and
file names existed; skipping there would have left a gap in the OID sequence and a width computed
from the pre-discard count. It now runs straight after pairing, so the kept rows are numbered
1..N and the OID width follows N.

**A discarded row is never silent.** In a legal archive a discarded row is a document that does not
arrive. Every one — stale under `skip`, or missing under `onMissingObject=skip` — goes to
`discarded_rows.<base>.csv` in the output directory: source line, reason, and every original
column. The name deliberately does **not** begin with the submission base name, so a delivery mask
like `<base>.*` can never send it to the archive. Exposed as `${discardedFile}`. The missing-object
skip used to write one warning per row; it now shares the file and a single summary line.

Refused rather than guessed: discarding under a mapped `object_id` (an id the feed chose cannot be
renumbered, and with rows gone it cannot ascend from 1 — the message says so, instead of the
sequence error that would otherwise appear), and discarding every row.

The `warn` mode also changed shape: one summary line with a count, not one line per stale row.

## The measurement that was asked for last time

The same run's console answered the question the progress work was built to answer:

```
listed 19933 files in 101.4s
read 100 rows, paired 100 objects in 101.4s
```

All 101 seconds went into listing the objects directory on `P:` — about 5 ms per file, two
file-system round trips each (`isDirectory`, then `isFile`) — in order to find **100** files. The
second line is cumulative; reading and pairing cost nothing.

So in `name` mode without recursion the directory is **no longer listed**: each row's file is looked
up directly, one call per row. Only when an exact name fails to resolve on a **case-sensitive** file
system is the directory listed, once, to match regardless of case — as a listing always did. With
recursion the listing stays, since the name may be in any subfolder.

Whether the file system is case-insensitive is **asked of the file system, not assumed from the
platform**: the objects directory's own name is looked up with its case flipped, and counts only if
it resolves to the same canonical path — so sibling folders that differ only in case do not fool
it. If the probe cannot tell, it answers "sensitive", and the cost of being wrong is a listing: the
old behaviour, slow but never incorrect. On this feed, if all 100 names exist, no listing happens
whatever the probe says.

Names that are not plain file names (`sub/x.json`, `../m.csv`) are never resolved as paths in name
mode — a listing never matched them either.

## Verification

**190 assertions, 60 mutations all caught.** New tests: two stale rows among twelve discarded, the
ten kept numbered `OID01`..`OID10`, no stale date and no discarded object in the package, the audit
counting ten, the discards file's exact header, one line per discard with its source line number,
and its name not starting with the base name; `fail` with the old message; the old boolean still
failing; the new setting overriding it; `warn` giving one line; unknown values, all-discarded and
mapped-id-with-discards refused. Direct lookup: two files found among 1502 with no listing; a case
mismatch on this case-sensitive system found through exactly one listing; a sibling `OBJECTS`
folder not fooling the probe; `sub/x.json` and `../m.csv` not resolved as paths.

Things that did not pass first time, none in the code:

* Two tests encoded the **old** behaviour (per-row skip warnings; a `listed` line in name mode).
  Updated to the new behaviour, which they now assert.
* A fixture had an `object_id` column with empty values; by the documented fallback it counts as
  mapped, and the step rightly refused it. Fixture corrected.
* **My own fixture edits missed their anchors twice** — once through a Python escape error, once
  because an earlier attempt had already applied one of them. The script now asserts every anchor
  matches exactly once, and the second failure was caught by that assertion before writing.
* One discards test expected the name-mode reason in a path-mode fixture. The file was right.
* **Two mutations survived, both exposing weak tests, and a third did not compile.** The "one warning
  line" test counted lines by their wording, so a flood worded differently slipped past; the
  mutation now reproduces the regression that actually existed. The mapped-id test looked for
  "Leave map.objectId unset", which the old confusing message *also* contains; it now looks for
  "were discarded". The probe mutation removed the only call that could throw `IOException`, making
  the `catch` unreachable; rewritten to stay compilable.
* The output-documentation check missed `md5` and `md5File` because its regex accepted letters
  only. The guide was complete; the check was wrong.

The designer's stale-mode line was executed under node, extracted verbatim from the file: an
existing step with the boolean set shows *fail*, the new setting wins over it, and clearing the
boolean on change does not bring *fail* back. Panel and executor: 35 parameters each way. Guide:
35 of 35 parameters and 9 of 9 outputs documented.

## Not verified

`mvn clean package`. The probe's answer on the Windows share — `getCanonicalPath` returning the
on-disk case is what JDK 8 on Windows is expected to do, but it was not observed here; if it does
not, the fallback costs a listing on a miss and nothing else. A run on the real feed, which should
now show a lookup line instead of a 101-second listing.
