# Copy file list — batch 4: USAGE.md. The feature is complete.

Base commit: `afb7cfd`. Spec: `.claude/COPY_FILE_LIST.md`, whose four batches are now all delivered.

## What is in this batch

The section «ifscopy: copying the files listed in a CSV» becomes «Copying the files listed in a CSV
(ifscopy, filecopy, safecopy)» and covers all three executors. **One section, not three.** Three would
drift, which is the argument that made the reader shared in batch 1 and the panel builder shared in
batch 3; and most of what there is to say — the column, the base, the duplicates, the blanks, the
collisions — is identical, so three copies would mostly be three chances to contradict each other.

The three executor-list entries were updated too: `filecopy` and `safecopy` said nothing about the
option at all, and `ifscopy`'s cross-reference pointed at the old title.

## What the section says that the old one could not

Everything that is the same is written once. What differs is written as a difference, with its reason,
because a reader meeting two executors that behave differently deserves to know it is deliberate:

* **What counts as a name that already says where the file is.** On the IFS a leading `/`. Locally a
  leading `/` or `\`, a drive letter, or a UNC name — and the leading slash is in that list because on
  Windows it means the root of the *current drive*, so the platform itself does not call it absolute,
  while it is exactly the shape a list from an earlier `ifscopy` step carries.
* **The backslash, left alone in both, for opposite reasons**: a legal character in an IFS name, a
  separator the platform understands locally.
* **The pre-scan.** `ifscopy` has none and that is deliberate; `filecopy` and `safecopy` do, so the
  default policy copies nothing at all rather than stopping half way — which matters most on
  `safecopy`, whose whole point is that the landing zone never shows something incomplete.
* **`safecopy`'s refusal** of a listed name that already ends in the temp suffix, and why skipping it
  would be a delivery that silently never arrives.
* **`filecopy` may only copy** with a list; `move` and `list` are refused, not downgraded.
* **The destination is one flat directory**, and nothing rebuilds the source tree.
* **The outputs differ and are not renamed for symmetry**: `ifscopy` keeps `${filesCopied}`,
  `filecopy` and `safecopy` keep `${matchedCount}`, because renaming would break every existing gate.

## Rendered, not proof-read

`render()` is extracted from `docs.html` at build time and run over the real `USAGE.md`. **31
assertions; the same suite scores 9 passed, 20 failed against the pre-patch file.** Eight mutations,
all caught.

`docs.html` turns **every source line into its own paragraph** — no soft-wrap merging — so a paragraph
wrapped at 100 columns renders as five `<p>` blocks: invisible in an editor, obvious on the page. The
wrap check keys on the line that **ENDS**, not on what the next one starts with, because a continuation
beginning with a dash slipped past an earlier version of that check. **122 wrapped paragraphs already
exist elsewhere in the file and are left alone**: rewrapping hundreds of lines of unrelated prose inside
a patch about one section is the churn that makes a diff unreviewable. The assertion is scoped to what
this batch added, and the count elsewhere is reported so it cannot quietly grow.

Also asserted, and each with a positive control proving it can fire: no bold marker or backtick leaking
outside code, **no single asterisk** anywhere in the new section (the renderer's bold is
`\*\*([^*]+)\*\*` and it has no italics at all, so a lone `*` renders as itself), **no markdown table**
anywhere in the file (this renderer has no table support — a table comes out as raw pipes), no paragraph
in the section ending mid-sentence, and the TOC anchor resolving to a real element, which is worth
checking rather than assuming for a heading carrying parentheses.

## Two harness defects the run exposed

**The section chunk was the wrong one.** Splitting the rendered HTML on `<h2` and taking the first
chunk that mentions the title picked the **executor list**, because the two entries there cross-reference
the section by name. Every fact assertion was reading the wrong part of the document — and failing,
which is the lucky direction. The chunk is now the one that *begins* with the heading, and a second
assertion checks that other chunks do cross-reference it, so the two conditions cannot be confused
again.

**Two checks were unscoped** and reported the whole file's pre-existing noise: 13 single asterisks and
49 sentence fragments, none of them in the added section. Scoped, as the wrap check already was.

**One mutation came back green and it was a bad mutation, not a gap**: it edited prose no assertion
covers, which is not a defect a suite should catch. Replaced by two that remove facts the suite actually
names.

## Verified, and not

* 31 assertions through `docs.html`'s real `render()`; 20 failures against the pre-patch file; 8
  mutations all caught, each anchor asserted present and unique first.
* **NOT updated: `README.md`**, which CLAUDE.md already records as behind `USAGE.md` by several major
  features. Bringing it level is its own task and was not made worse here.
* **NOT rendered in the UBS browser**; the checks are against the renderer, not against the page.
* `mvn clean package` is untouched by this batch — `USAGE.md` is a static resource.

## The feature as a whole

Gate 0 answered, four batches, all four delivered: the reader extracted and proved a no-op over 22 018
compared cases; the executors run against real files with 141 assertions; the panels executed under
jsdom with 88; the documentation rendered with 31. Fifty mutations across the four, all caught — three
of which came back green first and were opened rather than filed, two exposing real gaps and one being
a genuinely bad mutation.

The gates that remain are the ones the sandbox cannot close: `mvn clean package`, a run on Windows, and
a first real list.
