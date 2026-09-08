# 2026-09-08 — The street type word moves from the code into the pool (batch 2, final)

Implements the decision recorded in `.claude/MASK_ADDRESS_PREFIX.md` §10. No new parameter,
no designer change, nothing for the author to decide: the street pool carries the complete
street and the dropdown that already exists selects it.

## What changed

* `MaskGenerators.address()` — `"Via " + street + " " + num` becomes `street + " " + num`.
  **The two draws are untouched and in the same order**, which is the whole constraint.
* The same method's empty-pool fallback `"Roma"` becomes `"Via Roma"`. That branch is reached
  only when the pool file is missing or unreadable; without this it would have started
  emitting `Roma 47`.
* `streets_it.txt` — all 60 values gain `Via `.
* `streets_international.txt` — all 177 values gain their own type word in their own word
  order: `Oakwood Street`, `Bahnhofstraße`, `Rue Voltaire`, `Calle Serrano`, `Amstelstraat`,
  `Aarestrasse`. Four Dutch entries (`Damrak`, `Rokin`, `Spui`, `Overtoom`) are complete
  streets on their own and correctly carry no suffix.
* `runMask` logs the street pool in effect on every run, with its size and first value.
* Both file headers, the `USAGE.md` paragraph and the `CLAUDE.md` lines written by batch 1
  are inverted — they described the rule this batch reverses, one commit later.

## The proof that matters

Digest, not assertions. The mask package compiled from a clean clone of `3921bd5` and from
the patched tree, both run over 3 010 values with the default pools across `address`, `city`,
`fullName`, `company`, `customerDescription`:

```
pre   dd104f43c4d6f5b3cf3bfab6742fd115d20335dfe72c1673fd822538746da036   Via Petrarca 47
post  dd104f43c4d6f5b3cf3bfab6742fd115d20335dfe72c1673fd822538746da036   Via Petrarca 47
```

That is the same digest measured before batch 1, at `f437d43`. **The default output has not
moved once in this whole piece of work.** The empty-pool fallback is checked separately and
also matches: `Via Roma 142` on both sides.

Why it holds: `pool.length` is unchanged at 60, so `s.nextInt(pool.length)` draws the same
index from the same stream position, and the type word rides on the value rather than being
concatenated afterwards. Measured earlier (M2 in the spec) is the counter-case — a pool one
entry LONGER moves 1 962 of 2 000 addresses. Content may change freely; length may not.

## The failure mode this design has, stated rather than hidden

Nothing in a value can tell the executor whether the type word is present: `Damrak` is a
complete Dutch street and `Garibaldi` is not a street name at all. A heuristic would fire on
the legitimate Dutch entries and pass a broken Italian pool. So there is **no guard**, only
visibility: the step log names the pool, its size and its first value on every run, and both
file headers and `USAGE.md` say to keep the type word when replacing a pool.

## Verification

* **828 assertions green**, `--release 8`, against the real `MaskEngine`/`MaskPools`/
  `MaskGenerators`: raw-byte hygiene on all four pool files, values read back through the
  real reader, and two new invariants — every `streets_it` value starts with `Via `, and no
  `streets_international` value does.
* The digest proof above, plus the empty-pool fallback.
* **The log line was compiled and run, not just written.** `InternalSteps` needs the Spring
  tree and cannot be compiled here, so the added statements are **lifted verbatim** from the
  real file (asserted character-identical) into a stub compiled against the real `MaskPools`,
  and executed for three cases: the Italian pool, the international pool, and a missing file.
* Charset: every character in all four pool files is inside both ISO-8859-1 and
  windows-1252, `ß` included (`0xDF` in both), so a masked value survives a Latin-1 sink.

## A stale read of my own, caught by an assertion

The first attempt to edit `address()` failed its match assertion: I had the javadoc as
`"Via <n> <number>"` and the file says `"Via <name> <number>"`. Nothing was written — the
script asserts before it writes. Worth keeping: the exact text of a line I had already read
this session was still not what I remembered, and only the assertion said so.

## NOT verified

* `mvn clean package`. Maven Central is unreachable here. The mask package compiles
  standalone with `--release 8`; `InternalSteps` does not, and only its added statements were
  compiled, in a stub.
* A real feed run. The step log line and the international pool have not been through Tomcat.
* The designer is unchanged by this batch and was not re-tested.
