# elarxml: deleting the staged document once its INDX is delivered

## What was asked

Reading the payload from the local disk leaves the staged copies behind. The first real run of
CLIAC@DT left 9.3 GB of them. An option to remove each document once it has been embedded.

## Two questions answered before writing anything

* **Can two rows reference the same content file?** **No.** The answer decides whether a document may
  be deleted the first time it is embedded or only once the whole run is over: with duplicates, the
  second occurrence would resolve to a file that is no longer there, be counted as
  `FILE_MISSING`, and - since `onMissingFile` is `SKIP` by default - leave the run green while
  delivering the feed short. With the answer being no, per-batch deletion is safe and the seen-set is
  unnecessary.
* **What is in that directory?** **Only copies, never originals.** Which is why deletion is
  **forbidden** under `contentSource=IFS`: there the file is the archive's own.

Both were asked rather than inferred from the shape of the data, which is the process this repository
has twice had to correct.

## Where the deletion happens, and why it is not where it looks like it should be

The obvious place is next to `writeDocument`. It is wrong, and the reason is in `ElarRun` rather than
in any intuition about deletion: `writeDocument` writes into a batch carrying the in-flight names
`I_PART` / `P_PART` + `.part`, and the batch only reaches its final deliverable name inside
`Batch.close`. Between those two points there are three paths that discard everything the batch has
written:

* an exception anywhere - the `finally` calls `batch.abort()`;
* the disk guard - it cuts the input into `.failed` / `.done_before_failure` / `.remaining.csv` and
  throws, so the next run re-reads the remaining rows;
* an oversize document under `batchBy=BYTES` - `ROLL_THEN_ALONE` closes the batch first.

A document deleted at write time would be gone with no INDX to show for it. The rows waiting in
`.remaining.csv` would reference files that no longer exist, and the default `onMissingFile=SKIP`
would drop every one of them on the next run **without failing it**.

So deletion is anchored to `closeBatch`, immediately after `batch.close` returns - **the same event
that already anchors the `.done` rename and the `.skipped` commit**. All three now mean exactly one
thing: every batch this input contributed to has been delivered.

## The design

* **`DeletableContentStore extends ContentStore`**, new, in the `elar` package: one method,
  `boolean delete(String resolved)`. `LocalContentStore` implements it; `IfsContentStore` lives
  outside this package and does not. **The restriction is in the type, not in a check**: there is no
  method to call, so no later edit can move or invert a condition and reach the archive.
* **`deleteContentAfterEmbed`**, default `false`. The only option in this executor that removes
  something.
* **`IFS` + the flag is REFUSED** (`exitCode=2`), not warned about. The other LOCAL/IFS mismatch in
  `runElarXml` - `contentIfsPath` under `LOCAL` - only warns, and the difference is deliberate: an
  inert setting still leaves you with the run you asked for, while an operator who turned this on to
  reclaim disk and got silence would be told nothing about the one thing they wanted. `clientValidate`
  refuses the combination at save, which catches the flag left behind by switching back to IFS after
  ticking it, and the one an imported workflow carries.
* **A non-deletable store with the flag set throws at configuration**, before any output exists. Not
  reachable through the step, which refuses first; it is for a standalone caller building its own
  store.
* **`LocalContentStore.delete` reports the state afterwards, not `File.delete()`'s return value.** A
  file that had already vanished would otherwise be reported as a failure and send someone looking for
  a file that is not there. What matters to an operator is the file that is *still* there.
* **Counters `documentsDeleted` / `documentsDeleteFailed`**, published as run variables so a gate can
  act on leftover staging without parsing a log. `summary()` names them **only when the option is on**:
  a permanent `, deleted 0` on every feed that does not use it trains people to read past the one line
  that says a file was removed.
* **A failure to delete is logged and non-fatal**, on the same reasoning as `renameDone`: the INDX is
  already delivered, so failing the step would be worse than the leftover it warns about. On Windows,
  a file still held by a scanner or the indexer is the ordinary case, not the exception.
* **One log line per batch, capped at ten names.** Per document would double the log of a 200k-document
  run, and which document went where is already answered by `logDocuments`.
* **Chosen, not a consequence**: if the INDX commits but its PULL cannot be published, the pair is
  incomplete and **none** of that batch's documents is deleted, including those already inside the
  delivered INDX. Conservative, but it means a half-published pair leaves the staging full.

## Verification

**Standalone suite, `javac --release 8`, against the real `ElarRun`** - 32 assertions, 0 failures:

* flag off: nothing deleted, and the summary does not mention deletion;
* flag on: all documents gone, and the **INDX is byte-identical (SHA-256) to the run with the option
  off** - what is written did not change, only what happens afterwards;
* a batch whose PULL cannot be published: all documents still on disk, including the two already
  embedded, and the input not renamed;
* disk guard: every document listed in `.remaining.csv` still present;
* deletion refused by the store: counted, named in the log, run still succeeds;
* non-deletable store + flag: refused with nothing written and nothing deleted;
* `delete()` on a file that is already gone returns true.

**Proved the suite bites**, two mutations:

* deleting immediately after `writeDocument` -> 2 assertions fail;
* deleting in the `finally` as well, so an aborted batch deletes -> 1 assertion fails.

**Two of my own scenarios were blind before those mutations, and both are worth recording.** The first
replaced a content file with a directory to force a failure mid-batch: the **pre-scan** catches that,
so no batch was ever opened and the assertion - a disjunction, which made it worse - passed without
exercising anything. The second occupied the second batch's INDX name: `AtomicOutput` refuses an
existing final name in its **constructor**, so the documents of that batch were never written either,
and the scenario passed against the mutation it was written to catch. What discriminates is the PULL:
it is constructed **inside `Batch.close`, after the INDX has been committed**, so an occupied PULL name
fails the close with the documents fully written.

**Stated plainly: the disk-guard scenario cannot distinguish the two implementations**, because the
guard by design only fires between batches, when nothing is in flight. It is a real regression test of
the invariant and it is not evidence about the ordering.

**Designer, jsdom** - 11 assertions, 0 failures. The checkbox is present under LOCAL and absent under
IFS, counted as **elements inside the card** rather than by matching `innerHTML` as a string. It is
driven by compiling its own `onchange` attribute into a function called with `this` bound to the
element - no string surgery on the handler, so the test cannot exercise something the page does not do.
Two mutations fail it: writing `'false'` instead of removing the parameter, and dropping the
`clientValidate` rule.

**Scans**: no `[[` / `[(` outside inlining comments, no literal `\n` / `\r` in inline JS,
`node --check` on both inline scripts, brace balance ignoring strings and comments on all four Java
files. `tools/scan_panel_redraw.js`: **120 parameters (from 118), 0 defects** - the count rising is the
evidence that the scan sees the new control rather than skipping it.

**Docs**: `USAGE.md` rendered by the real `render()` extracted from `docs.html` - 78 headings, 349
paragraphs, 5 code blocks, no raw Markdown outside code blocks, the new section is an `h3` so it lands
in the TOC like the others, and the parameter entry renders as a list item.

## Not verified here, and it must not be read as if it were

* `mvn clean package` - the internal Nexus is not reachable from the chat sandbox. The build on the
  target machine is the only proof.
* **The Windows failure mode.** A file held open by a virus scanner or the indexer is the shape behind
  the `Access is denied` after 27 668 documents. The failure path is exercised by a test double that
  refuses on purpose, not by a real lock: what is shown is that a refusal is counted and does not fail
  the run, not that the delete succeeds under a scanner.
* Nothing about IFS - `Jt400Ifs` has still never been executed, and this change deliberately does not
  go near it.

## Aside, not fixed here

`CLAUDE.md` says `static/USAGE.md` is a single English source `== README.md`. They differ (866 lines
against 324) and `README.md` does not mention `elarxml` at all, so only `USAGE.md` was updated. Worth
correcting the claim or the files, in a patch of its own.
