# 2026-09-08 — Mask pools: international streets and cities (batch 1 of 2)

Batch 1 of two. This one is data plus the registration that makes the data reachable.
Batch 2 (making the `"Via "` prefix configurable) is specified separately and starts only
after this is approved; no code for it is in this patch.

## What

* `src/main/resources/maskdata/streets_international.txt` — 177 street names, UK 26 / US 26 /
  DE 27 / FR 25 / ES 25 / NL 24 / CH 24.
* `src/main/resources/maskdata/cities_international.txt` — 513 cities, UK 92 / US 91 / DE 90 /
  FR 80 / ES 70 / NL 50 / CH 40.
* `MaskPools.BUNDLED` 15 -> 17.
* `USAGE.md` "Masking pools" corrected.
* `CLAUDE.md` mask-pools section corrected in place (it still said `BUNDLED (15 nomi)` and
  listed only the company pools as having an international twin).

## Why the two files alone would have done nothing

`MaskPools.get()` finds a bundled pool on the classpath, so masking would have worked if a
step named the file by hand. The **catalogue** does not: `ApiController.poolList()` iterates
`MaskPools.BUNDLED` and then adds whatever it finds in the external `mask-pools-dir`. A file
bundled in the WAR and absent from `BUNDLED` is invisible in the designer dropdown for every
installation without an external pools directory — which is the normal installation. That is
the whole reason a data-only batch touches one `.java`.

Nothing else was needed. `designer.html` filters the Streets dropdown on the prefix `streets`
and the Cities dropdown on `cities`/`caps`, so both new files appear by name with no template
change.

## The street pool holds the name only, and why the comment says so

`streets_it.txt` holds `Garibaldi`, not `Via Garibaldi`: `MaskGenerators.address()` (86-91)
returns `"Via " + street + " " + num`, and the type word is a Java constant. The international
file follows the same rule, so today selecting it produces **`Via Oakwood 42`** — measured,
not predicted: the harness prints `Via Hazelmere 67`. The first line of each file states the
rule and the reason, because the next person to add a pool will otherwise add `Oakwood Street`
and get `Via Oakwood Street 42` with nothing failing.

## UTF-8 without a BOM is a correctness requirement here, not a style one

`MaskPools.get()` skips a line whose first character is `#` after `String.trim()`. `trim()`
removes characters `<= U+0020` and **`U+FEFF` is not one of them**. With a BOM the header line
would not start with `#` and the comment itself would be loaded as a masking value. Asserted on
the raw bytes rather than on the decoded text.

## Batch 1 does not change any existing feed, and it is proved rather than argued

The mask package was compiled from a clean clone of `f437d43` and from the patched tree, and
both were run over 3 010 values with the **default** (Italian) pools: `address`, `city`,
`fullName`, `company`, `customerDescription`. Same SHA-256
`dd104f43c4d6f5b3cf3bfab6742fd115d20335dfe72c1673fd822538746da036`, same sample
`Via Petrarca 47`. Adding entries to `BUNDLED` cannot move a feed that names no pool file.

## Fact for batch 2, recorded now because it decides the design

`MaskEngine.Stream.nextInt(bound)` is rejection-sampled **on the bound**, and the bound in
`pick()` is `pool.length`. Putting the prefix inside the pool file would change `pool.length`,
which changes the index mapping and can change how much of the stream a draw consumes — every
address already delivered would move. A prefix carried as a step parameter never touches the
stream. This is the measurement the batch-2 spec argues from; the spec is the next deliverable.

Also for batch 2: the constant sits in **prefix** position. Making it configurable therefore
buys `Rue`/`Calle`/`Strasse`, not `Oakwood Street` — an English or Dutch suffix needs a second
decision about position, not just a value.

## Corrections to the brief

* Six categories have the `_it`/`_international` pair, not seven: firstnames, lastnames,
  company_animals, company_colors, company_actions, company_suffixes. 6x2 + `caps_it` +
  `cities_it` + `streets_it` = 15, which is what `BUNDLED` held.
* `caps_it.txt` is unread by any generator, as stated — but it is not unreachable: the Cities
  dropdown filters on `['cities','caps']`, so it can be selected as the `cityFile` and `city()`
  would then return a postcode. Left alone, out of scope, noted so it is not rediscovered.

## Verification

* **754 assertions green** (`--release 8`, real `MaskEngine`/`MaskPools`/`MaskGenerators`):
  raw-byte checks (no BOM, no CR, strict UTF-8, no blank lines, no trailing spaces, one comment
  line, no duplicates including case), value counts through the real reader, accents surviving
  (`Zürich`), `BUNDLED` membership and every entry existing on disk, and the dropdown prefix
  filter yielding 2 street files and 3 city files.
* **Positive control**: the same suite fails 5 assertions against the tree before the `BUNDLED`
  edit. It can find something.
* **14 jsdom assertions** running `docs.html`'s own `render()` over the real `USAGE.md`: both
  new paragraphs render as a single `<p>` each, backticks become `<code>`, no raw Markdown
  leaks, and the wrapped-paragraph count goes 113 -> 111 rather than up.
* **An assertion of mine was wrong, not the code**: I expected the count to drop by 3 and it
  dropped by 2. The third source line ended in a full stop, so the checker — which keys on the
  line that ENDS, per the json2csv batch-4 lesson — never counted it as a wrap. Corrected in
  the harness with the reason written beside it.

## NOT verified

* `mvn clean package`. Maven Central is unreachable from the sandbox; the build on the deploy
  machine is the only final proof. `MaskPools.java` was compiled standalone with `--release 8`,
  which checks the API surface, but not against the Spring tree.
* The designer dropdown rendered in the UBS browser. The prefix filter was exercised as logic,
  not as a rendering; `designer.html` is unchanged by this patch.
* The pools page and any external `mask-pools-dir` override.
* Whether any real feed wants the international street pool before batch 2 lands. Until it
  does, an operator selecting it gets `Via Oakwood 42`, which the docs now say.
