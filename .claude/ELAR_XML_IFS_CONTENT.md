# elarxml: reading the `<ELAR:Content>` payload from an IFS directory

Batch 0: specification only. No code. Written from the existing `elar` package, `IfsSupport` and
`runIfsCopy` as they stand at the head of `main`, and it names the decisions that have to be taken
before any of it is written.

---

## 1. What is being asked

Today the file embedded as Base64 in `<ELAR:Content>` is resolved to a `java.io.File` under the
family's `documentPath` and read from the local filesystem. The request is for the same executor to be
able to read it **directly from an IFS directory on the AS/400**, as `ifscopy` already does, instead of
requiring the documents to be copied down first.

The motive is worth stating because it bounds the design: a copy-down step already exists and works.
Reading in place removes one full transit of the document bytes and the local disk they land on - for a
feed of a hundred thousand scanned PDFs that is the difference between staging half a terabyte and
staging nothing.

## 2. The constraint that shapes everything

**The `elar` package is free of Spring and compiles standalone with `javac --release 8`.** That is not
an accident of layout: it is what has allowed every suite in this subsystem to run the real executor
against real files in a sandbox, and it is what caught the `.done` rename defect, the value-edge
breaks, the split start tag and the counter that counted twice. Several of those were invisible until
the code was executed.

`IfsSupport` is a Spring `@Component` in `com.legalarchive.orchestrator.ds`, constructed from a
`DataSourceDef` and using JTOpen (`com.ibm.as400.access.AS400`, `IFSFile`, `IFSFileInputStream`).
JTOpen is already a declared dependency - `net.sf.jt400:jt400:20.0.7` - so there is **no Nexus question
to settle**, which removes the usual Gate 0.

If `elar` were made to depend on `IfsSupport`, the package would stop compiling and running standalone
and the whole subsystem would become testable only against a live AS/400. That is too high a price.

**Therefore**: a minimal interface inside `elar`, with no dependency on anything outside the JDK, and
the IFS implementation living in the layer where Spring and JTOpen already are.

```
elar/ContentStore.java          interface, JDK only
elar/LocalContentStore.java     java.io.File - today's behaviour, unchanged
engine/IfsContentStore.java     JTOpen; constructed by runElarXml and passed in
```

`elar` stays standalone-testable in full. Only the thin IFS adapter is untestable in the sandbox, and
that will be declared as NOT VERIFIED rather than implied to be covered.

## 3. What the content file is used for

Four things, and each has to work over IFS:

| use | today | over IFS |
|---|---|---|
| existence check, in the pre-scan | `File.isFile()` per row | see section 4 |
| size, for the byte budget estimate | `File.length()` | from the directory listing |
| the DSAK value | extension of the resolved file **name** | unchanged, the name is in the listing |
| the payload | SHA-256 pass, then Base64 pass | see section 5 |

The interface is therefore small:

```java
public interface ContentStore extends Closeable {
    boolean exists(String fileName);
    long length(String fileName);
    long lastModified(String fileName);
    InputStream open(String fileName) throws IOException;
    String describe(String fileName);   // for messages: a path a human can act on
}
```

```java
public interface ContentStore extends Closeable {
    String resolve(String csvValue);          // the store's own reading of the column
    boolean exists(String resolved);
    long length(String resolved);
    long lastModified(String resolved);
    InputStream open(String resolved) throws IOException;
    String fileName(String resolved);         // last segment, for the DSAK
    String describe(String resolved);         // for messages: something a human can act on
}
```

**Resolution belongs to the store, and the two stores resolve differently.** Section 8a is answered:
the CSV column carries a **full IFS path**. So:

- `LocalContentStore` keeps today's rule exactly - trim, take the last path segment, join it to the
  family's `documentPath`. Unchanged, including that a traversal cannot escape the directory.
- `IfsContentStore` takes the value **as given**. An absolute path is used as it stands; a value with no
  leading `/` is joined to `contentIfsPath`, which is what makes that parameter worth having.

The asymmetry looks wrong until you see why it is right: **it is the same CSV serving two topologies.**
Under LOCAL the documents have been copied down by an `ifscopy` step, which flattens whatever tree they
came from into one directory - so only the file name can still be meaningful, and taking the last
segment of the IFS path is exactly correct. Under IFS the tree is still there and the path is the only
thing that finds the file. One column, two readings, both faithful to what is actually on disk.

Nothing outside the store sees either rule. `ElarPreScan` and `ElarRun` hold an opaque resolved handle.

## 4. The pre-scan: one listing, not one round trip per row

Today the pre-scan checks every referenced file before a single byte of output exists. Over IFS a
per-row `IFSFile.exists()` is a network round trip each; for a feed of thousands of rows that is the
transfer time of the whole batch spent on nothing but questions.

`IfsSupport.copyListToLocal` already reasons about exactly this and decides **against** a pre-scan,
and its javadoc says why: the destination there is a step working directory and the run stops at the
first problem. It also says why ELAR is different - *unlike the ELAR delivery, where a partial set
beside an unmarked input is unrecoverable*. So for this executor the pre-scan has to stay.

**Revised, now that 8a is answered.** Listing one base directory no longer works: with a full path per
row the documents may be spread over many directories, and a single listing would not cover them.

**The resolution: list the distinct parent directories, once each.** The parents are derivable from the
CSV itself - collect them while scanning the rows, then list each one and key the map by full path. If
the documents live in a handful of directories, that is a handful of round trips for a feed of
thousands of rows, and existence, length and the DSAK extension all become local lookups.

The degenerate case has to be handled rather than hoped away: if the paths are scattered one document
per directory, listing every parent is *worse* than asking per file, because each listing returns one
entry. So the store counts distinct parents first and **falls back to a per-file `exists()` when the
parents outnumber a set fraction of the rows** - proposed: more than half. Which strategy was chosen,
and how many round trips it cost, belongs in the step log; a run that silently took the slow path is a
run nobody can explain afterwards.

The memory bound still has to be declared rather than discovered: the map is held for the whole run, at
roughly a hundred bytes an entry. A hundred thousand documents is about ten megabytes; a million about a
hundred. A cap (`contentIfsMaxListing`, proposed default 500000) with a clear failure past it is better
than an OutOfMemoryError in the middle of a delivery, which is the failure mode this whole rewrite
exists to remove.

## 5. Two passes over a network file, and what to do about it

The template places the hash element **before** the content element, so the digest must be known before
the payload is written. Locally that means two sequential passes over the file, which is what the legacy
tool already did; the rewrite's contribution was that neither pass *holds* the file.

Over IFS two passes means the document crosses the network twice. Three options:

1. **Two network passes.** Simplest, correct, and doubles the transfer. For the volumes this executor
   exists for, not acceptable.
2. **One pass, buffering the payload** so the digest can be computed alongside it. This is precisely
   the `ByteArrayOutputStream` that produced the 147 MB batches and the `OutOfMemoryError`. Rejected -
   it would reintroduce the defect the rewrite was written to remove.
3. **Stage each document to a local temp file, once.** One network read; the digest and the Base64 pass
   then both read local disk. Peak local disk is **one document**, not one batch, because the temp file
   is deleted as soon as the document is written.

**Recommended: option 3.** It keeps the streaming invariant intact, costs one network transit instead
of two, and has a property the others do not: the size-and-modification stamp that guards against a
file changing between the digest and the encode becomes *meaningful*, because a local temp file cannot
change underneath us while an IFS file can.

The temp file goes in the step directory and is deleted in a `finally`. A leftover temp from a killed
run must be recognised and removed on the next run rather than reused, for the same reason the discards
file is not reused.

## 6. Configuration

Two new step parameters plus one attribute:

- `contentSource` - `LOCAL` (default) or `IFS`. **LOCAL is the default and no existing workflow changes
  behaviour**, which is the conservative-default rule and there is no reason to make an exception here.
- `contentIfsPath` - **optional**, and only a fallback: the base joined to a CSV value that has no
  leading `/`. Since the column carries a full path, a feed that always does needs it never. Refused
  when set with `contentSource=LOCAL`, rather than silently ignored.
- `contentIfsMaxListing` - the cap from section 4.

The **datasource is a `<step>` attribute, not a param.** `runIfsCopy` reads `step.datasource` and looks
it up in the same registry the `sql` steps use. Reusing that is right - one place where AS/400
credentials live - but it means:

- `WorkflowXmlParser` must carry `datasource` for an `elarxml` step, which today it does only for
  `sql`, `ifscopy` and their kin;
- **`buildXml` needs a change this time.** Every previous elarxml field was a `<param>`, which
  `buildXml` emits generically; a step attribute is not. This is the `reportQuery` case, and the
  earlier note that `buildXml` needed no change does not extend to it;
- `clientValidate` must require the datasource when `contentSource=IFS`, or the step fails at run time
  with a lookup miss instead of at save time with a message.

The family's `documentPath` from the properties file stays untouched and is simply not read under
`contentSource=IFS`. As with `output.max_index_docs` under `batchBy=BYTES`, the step log must **name the
ignored key with its value** rather than leave it looking effective.

## 7. Connection lifetime and failure

One `AS400` per run, opened before the pre-scan and disconnected in a `finally` - matching
`IfsSupport`, which calls `disconnectAllServices()` in a finally.

A network failure part-way through a batch must behave exactly as any other write failure already does:
the open batch is aborted, nothing is renamed to `.done`, no discards file is published, and the whole
input is reprocessed. That machinery already exists and needs nothing new; what it needs is a test that
proves an `IOException` from the store is not swallowed somewhere on the way out.

A row whose file is missing from the listing follows `onMissingFile` exactly as it does locally:
skipped by default and copied to the discards file. No new policy.

## 8. Decisions that are not mine to take

**8a. ANSWERED: the CSV column carries a full IFS path.** Sections 3, 4 and 6 are revised accordingly:
resolution moves into the store, the two stores read the column differently and for good reason, the
pre-scan lists distinct parent directories instead of one base directory, and `contentIfsPath` demotes
to an optional fallback for a value that is not absolute.

The answer also removes a trap that would have been silent: under the old shared rule an IFS run would
have reduced every full path to its file name, looked for it under a base directory it is not in, and
sent **every row** to the discards file with the documents sitting untouched on the IFS. The run would
have reported itself as a clean skip of everything.

**8b. One mode per step, or IFS as a fallback for what is not local?** A fallback is easy to write and
would be wrong here: with two possible sources, nothing in the delivered INDX or in the log says which
one served a given document, and for a legal archive that is a gap in the audit trail. Recommended: one
mode per step, chosen explicitly.

**8c. Is a per-document local temp file acceptable, and is the step directory the right place for it?**
Section 5 depends on it. Peak usage is one document, but it is disk write traffic on the delivery
machine that does not exist today.

**8d. Same AS/400 datasource as `ifscopy`?** The existing one reaches the IFS for the copy-down steps,
but whether the same host and user can read the document directory in place, with the same rights, is an
environment question.

## 9. Batch plan

- **Batch 0** - this specification. No code.
- **Batch 1** - `ContentStore` and `LocalContentStore` inside `elar`, and every use of `java.io.File`
  for the content file routed through the interface. **A pure refactor: byte-for-byte identical output,
  proved by running the existing suites unchanged and by comparing a delivered INDX before and after.**
  Nothing IFS in it, nothing new configurable. This is the no-op verification step, and it is where a
  mistake would be cheapest to find.
- **Batch 2** - `IfsContentStore` in the engine layer, the listing with its cap, the per-document
  staging, and the connection lifetime.
- **Batch 3** - the parameters, the parser's `datasource` for `elarxml`, `buildXml`, the designer panel
  and `clientValidate`.
- **Batch 4** - `USAGE.md`, and the field run.

## 10. What cannot be verified here, and must be said plainly

There is no AS/400 in the chat sandbox. `IfsContentStore` can be written and reviewed but **not
executed**, and no assertion in any batch will have touched a real IFS. Batch 1 and the `ContentStore`
seam are fully testable, and a fake store in the tests can prove the seam behaves correctly under
slow reads, missing files and mid-stream failures - but a fake store proves the seam, not JTOpen.

The first real evidence will be a field run, and the honest sequence is: batch 1 deployed and proved a
no-op on a real feed first, then IFS enabled on one family with a small input, then volume.

---

## Batch 1 — DELIVERED: the `ContentStore` seam

A pure refactor. `ContentStore` and `LocalContentStore` in `elar`, and every use of the content file
routed through the interface: the pre-scan's existence check, the byte-budget size, the DSAK name, the
digest pass, the encode pass and the change stamp. `ElarPreScan.resolveContentFile` is gone; its rule
now lives in `LocalContentStore.resolve` where the other store can differ from it.

**Proved a no-op the only way that means anything: byte for byte.** The same fixture — 50 documents,
full IFS paths in the column, accented metadata — delivered before and after, and the SHA-256 of the
delivered INDX and PULL compared, with formatting on and off:

```
format=true   INDX 41a586ac772c5524…  PULL f3e2f4e4ab9d5289…
format=false  INDX 4da3f4426296fc70…  PULL 1e35b4ef7af53ce3…
```

Identical on both sides. All five existing suites (19 + 31 + 15 + 36 + 33) pass unchanged.

**A fake store proves the seam**, which is what batch 2 depends on: 23 assertions covering that the
store's own resolution rule is the one used and a full path is kept whole; that `LocalContentStore`
still takes only the last segment, from a bare name, a full IFS path, a Windows path and a traversal;
that a document the store does not have is skipped and copied to the discards file; that length,
lastModified and two separate streams are all asked of the store; and that a read failing part-way
through — a dropped connection — aborts the batch with no INDX delivered, no temp file, no `.done`
rename and no discards file published.

`documentPath` in that fixture points at `/nowhere/at/all` on purpose: a run driven by another store
must never touch it.

**One defect found by the seam test rather than by review**: nothing closed the store. With a local
store that is harmless, with an `AS400` inside it is a connection leaked per run — the kind that only
surfaces after a week of retries. `ElarRun` now closes it in the `finally`, on every path including the
failing ones, and the interface documents that the executor owns the store for the duration of a run.

Batch 2 needs no further change to `elar`.
