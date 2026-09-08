# MASK_ADDRESS_PREFIX — making the address type word configurable

Batch 2 of the international-pools work. **Specification only. No implementation in this
commit.** Batch 1 (`1b08084`) shipped `streets_international.txt` and
`cities_international.txt`; this is the change that makes the international street pool
produce something other than `Via Oakwood 42`.

Base: `1b08084`. Every number below was measured against that tree, not inferred.

---

## 1. What is true today

`MaskGenerators.address()` (86-91):

```java
public String address(String value, String group) {
    MaskEngine.Stream s = engine.stream(group, norm(value));
    String street = pick(pools.get(streetFile), s, "Roma");
    int num = 1 + s.nextInt(250);
    return "Via " + street + " " + num;
}
```

Two facts follow from those five lines and matter for everything below.

* `"Via "` is a Java constant in **prefix position**. It is not in the pool, is not a
  parameter, and cannot be reached from any workflow.
* The stream is touched exactly twice, and **both draws happen before the string is built**.
  `pick()` calls `s.nextInt(pool.length)` — and only when the pool is non-empty; an empty
  pool returns the fallback without consuming anything, which is a third fact worth holding
  on to.

## 2. The constraints this design has to meet

Stated by Fabiano, restated here because the whole evaluation turns on them:

1. Without the new parameter the output must be **byte-identical** to today's, `"Via "`
   included.
2. **No additional call to the deterministic stream** before or between the existing draws.
   One extra `nextInt()` changes every address already produced, even at unchanged defaults,
   and breaks reproducibility against the datasets already delivered.
3. The report must **demonstrate** why the sequence is unchanged, not assert it.
4. Whether the prefix belongs to the step or to the pool is to be argued with trade-offs
   before any code is written.

## 3. Measurements

Nine assertions, `javac --release 8`, against the real `MaskEngine`, `MaskPools` and
`MaskGenerators`. 2 000 distinct input values per measurement.

### M1 — how much stream a draw consumes

`Stream.nextInt(bound)` gathers four bytes and **rejection-samples on the bound**: it retries
whenever the 32-bit value falls in the top `2^32 mod bound` window.

| bound | rejection window | probability |
|---|---|---|
| 60 (`streets_it`) | 16 of 2^32 | 3.7e-09 |
| 61 (one entry more) | 57 of 2^32 | 1.3e-08 |
| 250 (house number) | 46 of 2^32 | 1.1e-08 |

Measured: after `nextInt(60)` and after `nextInt(61)` on the same seed, the next byte was
identical in **2 000 of 2 000** cases — no rejection fired. So consumption is
bound-independent *in practice* but **not by construction**, and this specification does not
rely on it. Nothing proposed here changes a bound.

### M2 — what a pool-size change costs

With the pool one entry longer, the drawn index changes for **1 962 of 2 000** values. The
index, not the stream position, is what moves. Any design that changes `pool.length` moves
almost every address already delivered.

### M3 — the recommended design leaves the sequence alone

A prototype that draws the street and the number **in the same order** and then renders a
format template produces, at the default template, SHA-256
`284b372f7a589eb875c25faa9fd16a54683045793a40e9a6cd8ad1cc59c76f8d` over 2 000 addresses —
**the same digest as the shipped `address()`**. And the stream is probed one byte further
after each call: identical in 2 000 of 2 000, which is a direct measurement of the stream
POSITION and not an argument about it. Constraint 2 is met because the template is applied
after both draws and touches no stream API.

### M4 — the honest result for the pool option

Baking `Via ` into every value of `streets_it.txt` keeps `pool.length` at 60 and therefore
also reproduces the baseline digest **exactly**. Option B is not disqualified by
byte-identity. It is disqualified by what happens next: see §4.

## 4. The four options

### A1 — a bare prefix parameter, e.g. `addressPrefix=Via`

**Rejected, on a reading of the designer.** `designer.html:499-500`:

```js
for (var k = 0; k < n.params.length; k++)
    if (n.params[k].name === name) { if (value === '') n.params.splice(k, 1); else ... }
if (value !== '') n.params.push({ name: name, value: value });
```

An empty value **deletes the parameter and never creates one**. So from the designer,
"present and empty" cannot be expressed: `params.get("addressPrefix")` is `null` both when
the author never touched the field and when they deliberately cleared it. **"No prefix" is
unreachable** — the setting that cannot take effect, which this codebase has refused three
times already (`contentIfsPath` under LOCAL, a fixed `value` on `SOURCE_EXTENSION`,
`renameProcessed` on `Json2CsvRun`).

The escape hatch would be a magic token (`addressPrefix=none`), and a magic token in a field
whose legitimate values are arbitrary words is a collision waiting for the one feed that
wants a literal one.

A1 also cannot do what the international pool was shipped for. The constant is in **prefix**
position, so A1 buys `Rue`, `Calle`, `Strasse` and buys nothing at all for `Oakwood Street`,
`Bahnhofstrasse` or `Damrak`. That is UK, US, DE and NL — four of the seven provenances now
in the file.

### A2 — a format template, `addressFormat` — **RECOMMENDED**

Default `Via {street} {number}`. Two placeholders, everything else literal.

| template | output |
|---|---|
| `Via {street} {number}` (default) | `Via Botticelli 90` |
| `{street} {number}` | `Botticelli 90` |
| `Rue {street} {number}` | `Rue Botticelli 90` |
| `{street} Street {number}` | `Oakwood Street 90` |
| `{street}strasse {number}` | `Bahnhofstrasse 90` |

Solves both A1 problems at once. "No prefix" is a **non-empty** value (`{street} {number}`),
so `setNodeParam` keeps it and no magic token is needed; and the position is the author's,
so the pool's seven provenances are all reachable. Byte-identity at the default is measured
(M3), not argued.

Cost: a template is a small grammar and a new failure mode. That is answered in §5 by
refusing a bad template at step start rather than by rendering it.

### B — the type word baked into every pool value

`streets_it.txt` would hold `Via Garibaldi`; `streets_international.txt` would hold
`Rue Voltaire`, `Oakwood Street`, `Bahnhofstrasse`. `address()` drops its constant.

Byte-identical at the default (M4a), and it puts the word order inside the data, which is
genuinely attractive for a file spanning seven countries.

**Rejected on the failure mode.** The pools page exists so an operator can replace a pool
without a rebuild, and the format they will replace it with is the one documented — which
until yesterday, and in every other pool file, is the bare name. Upload a bare-name list and
every address silently becomes `Botticelli 90`: no error, no counter, a masked dataset that
is wrong in a way nothing downstream can see (M4b). It also inverts the rule Batch 1 just
wrote into the header of both street files and into `USAGE.md`, one commit after writing it.

Two further costs: the type word stops being mixable — you cannot combine the Italian pool
with a French prefix, which is the freedom the eight pool dropdowns exist to give — and
`streets_it.txt` has to be rewritten line by line, which turns a configuration change into a
data migration.

### C — a directive in the pool header, e.g. `#prefix=Via`

`MaskPools.get()` already skips any line starting with `#`, so a directive line would leave
`pool.length` untouched and the stream untouched. Tidy, and it keeps the type word beside the
names it belongs to.

**Rejected on the compatibility direction.** A `#` line is, by the file format's own
contract, *ignored*. A pool file carrying `#prefix=Rue` read by any build that does not know
the directive produces `Via Voltaire` — silently, because being ignored is exactly what the
format promises. External pool directories are shared across installations that are not
upgraded together, so this is a live shape, not a hypothetical one. It also inherits B's
loss of mixability, and it gives `#` a second meaning in a file whose only rule today is
"`#` means nothing".

## 5. The recommended design in detail

### Parameter

`addressFormat`, a step `<param>` on the `mask` executor. Default — used when the parameter
is absent or blank — is `Via {street} {number}`.

Free text, not an enum. The legitimate values are every language's word order; a dropdown
would either be wrong for the eighth language or would have to be maintained forever. No
`PARAM_OPTIONS` entry in `variables.html` for the same reason, which also sidesteps the
shared-name trap that kept `inputCharset` and `onMissingFile` out of that table.

### Rendering

Scan the template once. `{street}` and `{number}` substitute; every other character is
literal. Applied **after** both draws, so §3/M3 holds by construction.

`{number}` renders `1 + s.nextInt(250)` exactly as today. **The 1..250 range stays fixed and
is explicitly out of scope**: it is a stream *bound*, so making it configurable is the one
change in this area that genuinely cannot be byte-identical (M2 is the same arithmetic). If
it is ever wanted it needs its own batch and its own decision.

### Validation, at step start, before any row is written

Same principle as `ElarPreScan`: refuse before there is output to be inconsistent with.

* A template with no `{street}` **fails the step**. It would silently produce the same
  address for every customer, which is a masking defect that looks like data.
* An **unknown** `{placeholder}` fails the step, naming it. Rendering it literally would ship
  `{steet}` into a delivered dataset; a typo must not be deliverable.
* A template still containing `${` is reported as an **undefined variable**, not as a bad
  template — a different problem with a different fix, exactly as `fmtToJavaPattern` reports
  it. Parameters are already `${}`-resolved before the executor sees them, so
  `addressFormat=${addressFormatVar}` works with no extra machinery.
* `{number}` is **optional**: leaving it out has an effect (no house number), so it is a
  choice and not a mistake.

The step log prints the template in effect, always — including when it is the default. A line
that appears only when something is unusual trains people not to look for it, which is the
`ElarCounters` rule.

### Registration — read, not predicted

Per the batch-0 lesson that cost a wrong prediction on `buildXml`: the emission was read
before this was written.

* `WorkflowXmlParser:175-176` reads **every** `<param>` child generically.
* `WorkflowXmlWriter:204-207` emits **every** param generically.
* `designer.html:444` — `buildXml` iterates `n.params` generically.

So `addressFormat` needs **no** parser, writer, DTO or `buildXml` change. This is the
`elarxml` case (a `<param>`), not the `reportQuery` case (a new child element).

Touched: `MaskGenerators.java` (one field, one renderer, `address()` rewritten to use it),
`InternalSteps.runMask` (read + validate + log), `designer.html` (one field in the mask
panel's pool subsection + `clientValidate`), `USAGE.md`, and a `.claude/` note. Six files.

### `USAGE.md`

Batch 1 added a paragraph ending "Making it configurable is a separate change and is not in
this release." That sentence is part of this change, not decoration — the stale-text defect
recorded in `CLAUDE.md` was exactly a feature landing while the prose still said it did not
exist.

## 6. Gate 0 — answer before implementation

1. **Free text, or a preset dropdown?** Recommended free text (§5). A dropdown is defensible
   if the estate will only ever use two or three forms and you would rather not see a typo.
2. **Unknown `{placeholder}`: fail, or emit literally?** Recommended fail.
3. **`{number}` optional?** Recommended yes.
4. **Is `Via Oakwood 42` acceptable in the meantime**, or is the international street pool
   unusable until this ships? This decides whether batch 2 is urgent or routine — nothing in
   the code depends on the answer.
5. **Does any feed need this per-column rather than per-step?** The whole mask step is
   configured per step today; per-column would be a different design and is assumed out.

## 7. Test plan

* **The no-op proof is a digest, not assertions**: the mask package compiled from a clean
  clone of the base commit and from the patched tree, both run over the same values with no
  `addressFormat` set. Identical SHA-256, as done for batch 1.
* **The stream-position probe from M3** kept as a regression: one byte read past each
  rendered address, asserted equal to the byte read past the unmodified sequence.
* Template rendering: default, no-prefix, prefix, suffix, compound (`{street}strasse`),
  `{number}` absent, `{number}` twice, `{street}` twice, an unknown placeholder, an unclosed
  `{`, a `${` left unresolved.
* Refusals asserted to happen **before** the output file exists.
* `designer.html` through jsdom, driving the field through its own handler and never around
  it — the `contentSource` lesson. `clientValidate` refuses a template without `{street}`.
* Mutations to run: the default template changed; validation removed; the template applied
  before the draws instead of after; `{number}` computed before `{street}`.

## 8. Not in scope

The 1..250 house-number range (§5). `caps_it.txt`, which no generator reads. The `city`,
`company` and name strategies, which have no constant to configure. An
`application.properties` default for the template — the mask step carries its own choices and
a global default would let a deploy change a feed's output.

## 9. Recorded, because it was got wrong once here

The first run of the measurement harness reported M3 and M4 as FAILING. The harness was
wrong, not the code: it seeded the stream with the raw input value while `MaskGenerators`
seeds it with `norm(value)`, which under the default `trimUpper` is upper-cased. Two
digests that disagree because the probe and the product disagree about the seed would, taken
at face value, have argued against the recommended option on false evidence.
