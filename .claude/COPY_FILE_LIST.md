# Copying the files listed in a CSV: `filecopy` and `safecopy`

Batch 0 — specification only, base commit `a43b648`, committed as `f900d99`.
Gate 0 answered on 2026-09-14; batch 1 delivered on `f900d99`. Corrections below are struck through
rather than rewritten, so an answer reads as a decision beside what it overruled.

## 1. What was asked, and the part of it that already exists

> The copy, safecopy and ifscopy steps must be able, as an additional option, to read a CSV of paths
> to copy (naming the column that holds the path) as well as the current input directory. It could
> also be a combination of the two: directory + file name from the CSV.

**`ifscopy` already does this**, delivered on 2026-08-21 as commit `554ed4f` and documented in
`.claude/2026-08-21-ifscopy-csv-file-list.md` and in the «ifscopy: copying the files listed in a CSV»
section of `USAGE.md`. Both halves of the request are there: the `listSource=csv` shape copies exactly
the files named in one column, and **the combination is the default behaviour of that shape** — when
`listPathPrefix` is empty the existing *IFS source path* field is used as the base, so a column of bare
file names is resolved under the directory already configured on the step. A value starting with `/` is
taken as a full path and the base is not applied, which is what lets one column mix the two.

So the work here is `filecopy` and `safecopy`, and `ifscopy` is touched only where a shared piece of
code moves underneath it. **Gate 0 Q1 exists to confirm that reading**, because if what is wanted for
`ifscopy` is a *union* — everything matching the pattern in the directory **plus** everything in the
CSV — that is a third shape and neither executor has it today.

## 2. Scope

* `filecopy` and `safecopy` gain the same option, with the same parameter names, the same defaults and
  the same failure modes as `ifscopy`.
* The reader that turns a CSV column into a list of paths is **extracted** and shared, rather than
  copied. Two implementations of the same rule that quietly disagree is the failure this project has
  already met twice (`FileMask` against elarcheck's matcher, the displayschema join in PowerShell).
* No new executor, so the four/five/six-place registration rule does not apply.
* Out of scope: the `encoding` executor's directory batch mode, which consumes a directory the same way
  and could take the same option later. Naming it here so that it is a decision then, not a discovery.

## 3. The shape of the option

One parameter selects the shape, exactly as on `ifscopy`:

| value | meaning |
|---|---|
| absent, or `pattern` | **today's behaviour, unchanged**, including which variables the step publishes |
| `csv` | copy exactly the files named in one column of a CSV |

**An unrecognised value FAILS the step.** Falling back to `pattern` would answer a typo (`cvs`,
`CSV_LIST`) with a directory copy whose pattern defaults to `*` — i.e. everything in the directory.
That is the one degradation a copy step must not have, and it is why `ifscopy` refuses too.

Parameters, all read **only** in the `csv` shape:

| param | default | notes |
|---|---|---|
| `listFile` | — | required; the CSV, typically `${dir.<step>}/something.csv` |
| `listColumn` | — | required; header name (case-insensitive) or 1-based index |
| `listPathPrefix` | the step's **Source directory** | the "dir + file name from the CSV" combination |
| `listDelimiter` | detected | passed to the shared reader, detection stays in `InternalSteps.detectDelimiter` |
| `listCharset` | `UTF-8` | a wrong charset corrupts a name into a file that does not exist |
| `hasHeader` | `true` | |
| `onMissingFile` | `fail` | `skip` counts them in `${missingFiles}` and names them |
| `onNameCollision` | `fail` | two listed files landing on the same destination name |

**The names and the defaults are `ifscopy`'s, deliberately.** `variables.html` holds `PARAM_OPTIONS`
keyed by parameter name **with no executor context**, and `listSource`, `hasHeader`, `onMissingFile`
and `onNameCollision` are already in it with these very defaults. Reusing a name with a *different*
default is the trap already recorded twice — `inputCharset` between the two ELAR executors,
`onMissingFile` between `ifscopy` and `elarxml` — and it costs a mass-edit page that prints the wrong
default. So: same names and same defaults, or different names. This specification takes the first.

## 4. Where the code lives

New `engine/CopyListSupport` — JDK only, no Spring, no JTOpen, no orchestrator types — holding column
resolution, RFC-4180 field splitting, BOM handling, dedup, blank accounting and collision accounting.
That is what makes the feature testable here rather than only on deploy, the reason `IfsListSupport`,
`SqlReportSupport` and the `elar` package are already shaped that way.

The **one** thing that genuinely differs between an IFS path and a local path is how a base and a name
are joined, and what the destination name of a joined path is. That becomes a two-valued flavour on the
reader (`IFS`, `LOCAL`); everything else is shared. A strategy interface for exactly two rules would be
ceremony, and the rules are short enough to read side by side, which is the point.

`IfsListSupport` is **absorbed** and its call site updated, rather than left as a delegating facade
carrying a name that would then lie about who uses it.

**The no-op claim will be proved differentially, not by assertion.** The pre-patch `IfsListSupport` and
the post-patch `CopyListSupport` will both be compiled in the sandbox and run over the same generated
corpus — headers and headerless files, names and indexes, quoting, BOM, blanks, duplicates, collisions,
absolute names, empty and degenerate files — with every field of `ListResult` compared. A reconstructed
list of the original 73 assertions would only prove that I transcribed them; comparing the two classes
proves they agree. The suites are not committed, as the `elar` suites are not.

## 5. Decisions

### 5.1 What counts as an absolute path locally

`IfsListSupport.join` treats a leading `/` as absolute and leaves backslashes alone, because a backslash
is legal in an IFS name. Locally neither half of that is right. The rule for `LOCAL` is: a name is taken
as already complete when `Paths.get(name).isAbsolute()` **or** its first character is `/` or `\`.

The `isAbsolute()` call alone is not enough, and the reason is worth writing down: on Windows
`Paths.get("/logs/x.pdf").isAbsolute()` is **false** — a leading slash there means *root of the current
drive*, which is relative to something. A list produced by a query against a Unix-flavoured system, or
by an earlier `ifscopy` step, will carry exactly that shape, and joining it under the source directory
would produce `D:\landing\in\/logs\x.pdf`. The explicit first-character test also covers the UNC form
`\\server\share\x.pdf`, which `isAbsolute()` does answer correctly but only after the drive-relative
case has already been handled separately.

Backslashes inside a name are left alone under `LOCAL` as they are under `IFS`, but for the opposite
reason: there they are legal in a name, here they are a separator the platform already understands.
That divergence is asserted directly — the same string gives `back\slash.pdf` as an IFS destination name
and `slash.pdf` as a local one — because if the two flavours ever agree on it, one of them is wrong.

**Q2 answered:** the column holds **bare names or complete paths**, and which one it is is decided per
value, not per step — exactly the shape `ifscopy` already reads. A relative path carrying subdirectories
is not a case the feeds produce; it is joined to the base rather than refused, because refusing it would
be a rule with nothing behind it.

### 5.2 The destination stays flat

Both executors copy into `dest` under `f.getFileName()` today, and `ifscopy` does the same with a list.
A list of paths spread over several directories therefore flattens, and two files with the same name
would land on each other — which is why `onNameCollision` exists and defaults to `fail`, **checked
before anything is copied**. Reporting a success with fewer files in the destination than were copied is
the silent short delivery this project keeps meeting.

Preserving the relative tree under `dest` is a real alternative and is **not** in this specification: it
would need a rule for what the paths are relative *to*, and under an absolute-path list there is no
honest answer to that. ~~Gate 0 Q3 asks whether the tree matters; if it does, it is its own parameter and
its own batch, not a default.~~ **Q3 answered: one flat destination directory, no tree.** The question is
closed, so `preserveTree` is not a deferred parameter — it does not exist.

### 5.3 A local list IS pre-scanned, and that diverges from `ifscopy` on purpose

`ifscopy` deliberately has no existence pre-scan: over IFS it would cost one round trip per listed file,
for a list of thousands the whole transfer twice over, to buy nothing the failure message does not
already say.

Locally the cost is a `Files.exists` per row — microseconds, on the same filesystem the copy is about to
read anyway — so the argument reverses. Under `onMissingFile=fail` the step will therefore refuse
**before copying anything**, instead of stopping half way through. That matters most on `safecopy`,
whose whole purpose is that a downstream process watching the landing zone never sees something
incomplete: a half-done `safecopy` leaves real, correctly renamed files in that zone and no way to tell
the delivery was short. Under `onMissingFile=skip` the pre-scan only counts and names them.

The divergence is recorded here so that the two executors reading differently is a decision rather than
something discovered later by whoever compares them.

### 5.4 `filecopy` modes — with a list, only `copy`

~~`mode` is `copy` / `move` / `list`, and all three keep meaning something with a list: `copy` the
obvious one; `move` removes the listed files from where they were, which is the same promise the mode
already makes and a list makes precise instead of pattern-shaped; `list` copies nothing and answers
which of these files are actually there, which falls out of the pre-scan for free.~~

**Q4 answered: with a list the step may only copy.** `mode=move` or `mode=list` together with
`listSource=csv` is **refused at run time** (`exitCode=2`) and by `clientValidate`, naming both settings
and saying which one to change. It is not silently downgraded to a copy and the mode is not silently
ignored: a CSV read as an instruction to *remove* files is precisely the outcome a refusal is cheap
insurance against, and a `mode` that can be set but has no effect is the thing this project keeps
recording as worse than one that is absent.

`safecopy` has no `mode`, so nothing changes there.

### 5.5 `safecopy` keeps everything that makes it `safecopy`

The `.on_fly_` temp plus atomic rename is applied per file, unchanged. The rule that a name ending in
`tmpSuffix` is never copied also stays, because its reason — never pick up someone else's in-flight
temp — does not depend on how the name arrived. `tmpSuffix` keeps its meaning in both shapes.

### 5.6 Settings that cannot take effect say so

`pattern` is ignored in the `csv` shape and the step **logs it with its value** when one is set,
following the rule already applied by `elarxml` and `ifscopy`: a setting that can be read but has no
effect is worse than one that is absent. For `safecopy` that includes the multi-pattern list.

`overwrite` semantics are **unchanged**: `filecopy` and `safecopy` replace an existing destination file
today and go on doing so in both shapes. `ifscopy`'s `overwrite` flag and its `skippedExisting` counter
are not imported — that would change what the two local executors do, and nothing asked for it.

### 5.7 Nothing is lost in silence

Before the first byte moves, the step logs: rows read, files to copy, duplicates collapsed, rows with no
file name, which column was used and how it was identified, which base was applied and **where that base
came from** (`listPathPrefix` or the source directory), and — new for the local shape — how many listed
files are missing. The first fifty offending line numbers are named; the counts themselves are uncapped.
A row too short to have the column is treated as an empty cell and counted; a physically empty line is
not a row at all.

## 6. What does NOT need to change, having read it rather than predicted it

* `buildXml` in `designer.html` emits every `<param>` generically (`n.params.forEach`), so it needs no
  change. This is the `<param>`-versus-child-element distinction: `reportQuery` needed five places
  because it was a new *element*; a param is free. Checked in the file, not inferred from the earlier
  case, which is the mistake recorded under the elarxml IFS batch 3.
* `WorkflowXmlParser`, `WorkflowXmlWriter`, `StepDef`, `NodeDto` and `toDto` — params round-trip
  already, as they did for `ifscopy`.
* `PARAM_OPTIONS` in `variables.html` already carries all four enums with the right defaults (§3).

One thing **does**: `runFileCopy` is currently dispatched as `runFileCopy(step, vars, res, line)` and
never receives `resolvedParams` at all — it is the only copy executor that does not. The dispatch line
in `InternalSteps.run()` has to pass them.

## 7. Step outputs

`pattern` shape: unchanged — `matchedCount`, `matchedFiles`, and `bytesCopied` except under
`mode=list`.

`csv` shape: those, plus `listRows`, `listedFiles`, `duplicatesInList`, `blankNames`, `missingFiles` —
the same names `ifscopy` publishes, so a gate written against one reads the same on the others.
`filesCopied` is **not** introduced here: these two executors have always called it `matchedCount`, and
renaming it would break every existing gate for the sake of symmetry with a third executor.

## 8. Designer

A **Files to copy** dropdown on both steps, wired through a helper that sets the param **and
re-renders** — `setNodeParam` alone is the defect recorded twice (`contentSource`, `batchBy`), and the
panel genuinely shows different fields in the two shapes, so it is the same class exactly.

In the `csv` shape the Source directory field is relabelled as the base for names that are not complete
paths rather than being hidden, so the "dir + file name from the CSV" combination is visible in the
place it operates. Pattern is hidden. `clientValidate` must stop requiring `source` unconditionally on
both executors — under a list of absolute paths there is nothing for it to mean — and must report
**both** missing CSV fields rather than the first, as the `ifscopy` branch already does.

The panel will be **executed under jsdom against the real template**, not inspected: rendering, the
`onchange` attribute evaluated as a browser would, the default option writing no param at all so that
switching back leaves the step byte-identical, and an unrecognised stored value not opening the CSV
panel.

## 9. Gate 0 — ANSWERED 2026-09-14, batch 1 unblocked

**Q1 — ANSWERED: `ifscopy` is finished.** No union. The work is `filecopy` and `safecopy`.

~~Q1. Is `ifscopy` finished, or is a union wanted?~~ §1 reads the request as: `ifscopy` already has both
halves, so the work is `filecopy` and `safecopy`. The alternative reading is a third shape — the
directory pattern **and** the CSV list together, copied as one set. *Recommendation: `ifscopy` is
finished. A union is easy to add later to all three at once, and nothing in the request asks for one
source to be topped up by another.*

**Q2 — ANSWERED: bare names or complete paths, decided per value.** See §5.1.

~~Q2. Does the column hold bare file names, full paths, or both — and produced by what?~~ The rule in
§5.1 is written for "both", which is what an earlier step in the same workflow tends to produce. If the
list comes from a query against a Windows system it will carry `D:\...` or UNC paths, which the rule
also handles; if it can carry a *relative* path with subdirectories (`2026/09/x.pdf`) then §5.2's
flattening becomes a live question rather than a theoretical one. *Recommendation: none needed if a real
sample CSV can be attached — one file answers this better than any answer.*

**Q3 — ANSWERED: no. One flat destination directory.** See §5.2.

~~Q3. Must the destination preserve the directory tree?~~ *Recommendation: no. Flat, with the collision
guard, matching what all three executors do today. Say so if a tree is needed and it becomes its own
parameter in its own batch.*

**Q4 — ANSWERED: no. With a list, copy only**, and the other two modes are refused rather than ignored. See §5.4 — this REVERSES the recommendation that stood there.

~~Q4. `filecopy` with `mode=move` and a list — wanted?~~ It turns the CSV into an instruction to remove
files from where they are. *Recommendation: yes, allow it; it is what the mode already means and a list
is more precise than a glob. Veto it and the shape refuses `move` explicitly instead of silently doing
something destructive.*

**Q5 — ANSWERED: keep the shape and declare the limit.** The limit is written into the class javadoc where someone changing the reader will meet it, not only here.

~~Q5. How many rows, at the top end?~~ The list is read whole before anything is copied, as `ifscopy`
does — a million-row CSV is a million strings held for the duration. *Recommendation: keep the shape and
state the limit; if lists of that size are real, the reader streams instead and the collision check
becomes a hash set, which is a different batch.*

## 10. Batches

1. **DELIVERED on `f900d99`.** `CopyListSupport` extracted, `IfsListSupport` absorbed and deleted,
   `ifscopy` moved onto it, proved a no-op differentially (§4) over 22 018 compared cases. The `LOCAL`
   flavour exists and is tested but nothing calls it yet: no new behaviour, nothing new reachable from
   the UI, and no existing feed can change.
2. `filecopy` and `safecopy` executors, including the local pre-scan, run against real files on disk.
3. Designer panels for both, under jsdom against the real template.
4. `USAGE.md`: the existing «ifscopy: copying the files listed in a CSV» section becomes one section
   covering all three, rendered through `docs.html`'s own `render()`. One section, not three, because
   three would drift — the same reason the reader is shared.

## 11. What will not be verified here, said now rather than discovered later

`mvn clean package` — Maven Central is unreachable from the sandbox, so the build on the target machine
stays the only final proof, and `InternalSteps.java` cannot be compiled here at all (it needs the Spring
tree from the internal Nexus). It will be checked structurally, as every batch that touches it has been:
brace balance ignoring strings and comments, every helper verified against its real declaration, imports
checked. The executors themselves will be exercised for real against files on disk, because everything
of substance lives outside `InternalSteps` — which is the whole reason for §4.
