# diff CSV_KEY — substring L/R on match columns

## Goal
Batch 5 added `COL:L<n>` / `COL:R<n>` substring only on key columns. Extend the
exact same syntax to **match columns** (A and B sides), so an attribute can be
compared on a slice — e.g. an initial (`firstName:L1`) or the last digits of an
account (`iban:R4`).

## Change (runDiffKey)
Each match column token is now passed through the existing `keyColSql` (bare
column, or `LEFT(col,n)` / `RIGHT(col,n)`), exactly like key columns, and the
resulting expressions build the match `CONCAT_WS`. Invalid tokens are rejected
with the same clear message. The label default still uses the raw tokens (e.g.
`firstName:L1+lastName`) so the report stays audit-readable.

For `numeric` matches the substring is applied on the text first, then the
resulting value is compared numerically (BigDecimal) — confirmed behaviour
(`RIGHT(iban,4)` → compared as a number).

Designer: the matches hint now documents that the `:L<n>` / `:R<n>` suffix works
on match columns too. Fields stay free-text; no new widget.

## Verify
The actual patched runDiffKey was run against real H2 2.1.214: match
`firstName:L1` vs `initial` (text) and `iban:R4` vs `last4` (numeric) produced the
expected two mismatches (id2 initial J/X, id3 last4 9999/9998), keysCompared=3.
designer.html passes node --check; no literal \n/\r; no unsafe Thymeleaf. Full
Maven build to be confirmed on deploy.
