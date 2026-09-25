# objpack — the pairing default, and the near miss it caused

A feed failed with a mime-type mismatch that looked incoherent: row 1 declared `.pdf` and was
handed a `.msg`. The pairing was the problem, and behind it a defect of mine.

## What happened

`objectSource` was not set in the step. The code read
`mode = cPath != null ? "path" : "order"` — so with no path column mapped it fell through to
**positional pairing**, the most fragile of the three modes.

Measured on the feed's real shape, seven objects whose CSV order is not the directory's sort order:

```
csv=SECTSC8d.pdf  file=SECTSC8.msg    <-- wrong
csv=SECTSC8e.msg  file=SECTSC8a.pdf   <-- wrong
...
7 wrong pairings out of 7
```

**The mime/extension check caught it by luck.** The extensions were mixed, so the first row's `.pdf`
met a `.msg` and the step stopped. Had all seven objects been PDFs, every pairing would still have
been wrong, nothing would have complained, and the submission would have been built, checksummed,
delivered and accepted — seven documents each filed under a neighbour's metadata. In a legal archive
that is the failure that matters, because nothing downstream would ever reveal it.

## The defect was also in the specification

`.claude/OBJECT_PACKAGE_EXECUTOR.md` said, in §3.3, that order mode "is **not** the default here",
and in its parameter table that the default was "`path` if mapped, else `order`". The two
contradicted each other and the implementation followed the worse one. Corrected.

## What changed

* **The default is `name`.** `original_object_name` is mandatory and non-nullable under §3.2 of the
  archive specification, so matching by name is always available. Positional pairing is now never
  what an unconfigured step does.
* **`order` detects its own misalignment.** After every row is read: if the file handed to one row
  is the file another row **declares as its own**, then the CSV names do describe the files on disk
  and the order they arrived in is not the listing's order — so the pairing is demonstrably wrong
  and the step refuses, naming both rows and the way out.
* **The test is against declared names, not disk names.** When the two sets do not overlap — objects
  renamed on their way into the landing zone, which is the case positional pairing exists for —
  there is nothing to compare and nothing is refused. My first attempt compared against the disk
  listing instead and broke exactly that case; the suite caught it on the first run.
* **The mime message now points at the pairing** when the row's declared name is not the file it
  was given, instead of only reporting the extension clash.

## Verification

128 assertions, **36 mutations all caught**. The new tests reproduce the production shape — seven
objects in a non-sorted order — and assert that an unconfigured step now packages all seven with
each row keeping its own document, that asking for `order` explicitly on that data is refused, that
`order` still works when the listing does agree with the CSV, and that it still works when the CSV
names are not the disk names at all.

Two suite runs failed first, both on my own work: the rewritten check broke the renamed-objects case
(a real defect in the fix, caught before delivery), and one fixture accidentally contained the
correct file, so the mime-hint path was never reached. One mutation also survived because it was
**badly written** — it replaced only the opening line of a ternary, leaving the words the test looks
for intact, so it changed nothing.

## Not verified

The fix has not been run against the real feed. `mvn clean package` not run; the panel not opened in
a browser.
