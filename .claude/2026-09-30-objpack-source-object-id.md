# objpack — a source object_id column is not the id

## What happened

The discard option shipped yesterday. The next run on tf0005766 discarded one stale row and then
failed with:

```
the mapped object_id must ascend from 1 with no gaps (spec 3.2): row 6 on line 8 has
object_id 7, expected 6. Leave map.objectId unset to have the packager assign 1..N.
```

`map.objectId` **was** unset. The message told the user to do what they had already done.

The same run also carried the first real measurement of yesterday's lookup change:
`read 100 rows, paired 100 objects in 0.5s`, against **101.4 s** the day before on the same share.

## Two defects, stacked

**The specification contradicted itself, and the code followed the wrong half.** §3.2 of
`OBJECT_PACKAGE_EXECUTOR.md` said both that "an unmapped role falls back to the Transarch name" and
that "leaving objectId unmapped means assign 1..N". For a CSV with an `object_id` column those
cannot both hold — and every Transarch-shaped metadata CSV has one, since it is a mandatory column.
The code took the fallback, so a column merely *named* `object_id` became the id. The PowerShell
script took the other reading, explicitly:

```powershell
# Se object_id è presente nel sorgente lo ignoriamo
$cols = @( $cols | Where-Object { $_ -ne 'object_id' } )
```

So discarding any row from a normally shaped CSV left a gap in the source ids and failed. The
discard option was unusable on exactly the files it was built for. This is the **second** time in
this executor that a spec saying two things produced code following the worse one — the first was
the positional-pairing default.

**The guard written to prevent this very message checked the wrong thing.** Yesterday's patch
added a clear error for "discarding under a mapped object_id". It tested whether the `map.objectId`
parameter was set. Here the id came through the name fallback, the parameter was empty, the guard
never fired, and the confusing sequence error it was meant to replace appeared anyway. Its test used
an explicit mapping, so it never exercised the path production takes. **No test in the suite had an
unmapped `object_id` column** — the shape of every real metadata file.

## The fix

`object_id` no longer falls back. Only an explicit `map.objectId` makes a source column the id. A
column merely named `object_id` is set aside, with a line in the live console saying so and how to
use it instead, and the kept rows are numbered 1..N — the script's contract.

**Why no run that worked can change.** A fallback-mapped `object_id` only ever succeeded when its
values were exactly 1..N in row order: the sequence check enforced it. Assigning 1..N yields those
same values. Only configurations that used to fail behave differently. A test asserts it on a
source already numbered 1..4, and the 190 existing assertions all passed unchanged after the fix.

## Verification

198 assertions. New tests reproduce the production shape exactly — an unmapped `object_id` column
1..12, one stale row discarded — and assert eleven packaged, numbered 1..11 with no gap, the row
after the discarded one taking id 6 where the source had 7, the source column not passed through a
second time, and the log line present. Also: an unmapped source of 5, 9, 2 gets 1, 2, 3; an explicit
mapping is still the feed's own and still refused out of order.

**62 mutations, all caught**, including the one that restores the fallback — i.e. the code that
produced the production error — which the new test catches.

The full mutation set now exceeds the five-minute limit on a single command. A first attempt ran it
"in the background"; it was killed when the command returned and **produced nothing**, which I
noticed only by checking the process rather than trusting the launch. It was then run in the
foreground in four slices, each reporting per mutation.

## Not verified

`mvn clean package`. A run on the real feed with this fix.
