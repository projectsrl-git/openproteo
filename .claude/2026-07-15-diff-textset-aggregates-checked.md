# diff: TEXT_SET mode, match aggregates, attributes-checked total

Three additions to the diff executor. Applies on top of current main.

## 1) New mode TEXT_SET (line membership, order-independent)
Complements the ordered LCS `TEXT` mode. Treats each file as a set of lines and
reports the lines only in A (A->B: present in A, absent from B) and only in B
(B->A). runDiffTextSet uses hash sets (O(n+m), no LCS DP, scales). Report:
lines/distinct per file, common distinct, only-in-A / only-in-B counts.
Differences CSV: one row per differing line with category only_in_A / only_in_B
(both directions in one file). Output vars: linesA/linesB, distinctA/distinctB,
commonLines, onlyInA/onlyInB.

## 2) Match aggregates (CSV_KEY): value | sum | count | count_distinct
Each match gains a per-match `agg` (default `value` = current behaviour). For a
key group the aggregate is computed and compared A vs B:
- `sum` — SUM over the group of the (numeric) match value (empty cells ignored via
  NULLIF/TRIM; compared with BigDecimal so scale/leading zeros don't matter);
- `count` — COUNT(*) of rows in the group;
- `count_distinct` — COUNT(DISTINCT match value) in the group.
For aggregates the multi-occurrence "inconsistent_key" check is bypassed (that is
the point of aggregating) and the comparison is numeric. `value` keeps the
existing MAX + agreement-check semantics. H2 has no TRY_CAST (2.1.214), so `sum`
requires numeric columns; a non-numeric value surfaces a clear H2 error.
Designer: an Aggregate dropdown per match row; the report echoes agg per match.

## 3) Summary: total attributes checked
POSITIONAL and CSV_KEY reports now show, and expose as `attributesChecked`, the
sum over both files of (checked attributes x rows of that file) =
attributesCompared x (rowsA + rowsB). Rows in A / rows in B are also printed. (Not
applicable to the TEXT modes, which have no column attributes.)

## Verify
Real runDiffKey/runDiffTextSet executed against real H2 2.1.214 and standalone:
- aggregates: on 3-row/2-key sample, sum matched on the equal-sum key and differed
  on the other; count and distinct-count differed as expected (5 diffs);
  attributesChecked = 3 x (3+3) = 18 shown in the report.
- TEXT_SET: only_in_A=1, only_in_B=2, common=4 on the sample.
SUM/COUNT/COUNT(DISTINCT) per group and the DECIMAL cast were probed on H2 first.
designer.html passes node --check; no literal \n/\r; no unsafe Thymeleaf. Full
Maven build to be confirmed on deploy.
