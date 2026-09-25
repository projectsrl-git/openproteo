# objpack — the date-format trap, found in production

A feed failed with `line 2: record_business_date '20201009' does not parse with format 'YYYYMMDD'`.
The value is a perfectly good date. **The format string was the problem, and the message pointed at
the wrong one of the two.**

## What is actually going on

In `java.time`, `Y` is the **week-based year** and `D` is the **day of the year**; the ordinary year
is `y` and the day of the month is `d`. So `YYYYMMDD` is not a typo that gets rejected — it is a
*valid* pattern meaning week-based-year, month, day-of-year, which no data will ever satisfy in
combination. Measured rather than reasoned about:

```
YYYYMMDD   20201009   -> parse fails at index 0
YYYYMMdd   20201009   -> fails: WeekBasedYear=2020, MonthOfYear=10, DayOfMonth=9 cannot make a date
YYYY-MM-DD 2020-10-09 -> fails: WeekBasedYear, MonthOfYear, DayOfYear
yyyyMMdd   20201009   -> 2020-10-09
yyyyDDD    2020283    -> 2020-10-09      <- a LEGITIMATE use of D
```

That last line is why the pattern is **not** silently corrected. `D` genuinely means day-of-year and
someone may genuinely want it; rewriting a user's format string is how a plausible-looking wrong
date reaches a legal archive.

## What changed

* **`validateDateFormat` runs before a single row is read.** Failing at configuration beats failing
  at line 2 of a million, and it fails once rather than per row.
* **It names the letter.** "'Y' is the week-based year — write 'y'", "'D' is the day of the year —
  write 'd'", then the pattern to use for a date like 20201009, with dashes if the input had them,
  and the reminder that an already-`yyyyMMdd` column needs no format at all.
* **`D` is only reported when `M` is also present**, so `yyyyDDD` keeps working. A mutation asserting
  the looser rule is caught by a test that packages `2020283` and checks it becomes `20201009`.
* **A round-trip net** catches the general case: a reference date is rendered with the pattern and
  parsed back, so a pattern that cannot survive its own output is refused whatever letters it uses.
  This is what catches `qqqq` — a *valid* pattern (quarter of the year) that renders "4th quarter".
* **The per-row message no longer blames the data alone.** It names the configured format, and when
  the value is already eight digits it says the format can simply be dropped.
* **The designer warns as the field is typed**, with the same wording, so the run never starts.

## Verification

120 suite assertions and **31 mutations, all caught**, including five new ones: the validator not
being called, the uppercase check removed, `D` accepted next to `M`, the round trip removed, and the
over-strict variant that would break `yyyyDDD`. 12 assertions on the designer helper under node,
with four mutations of it.

Two suite assertions failed first and both were mine: `qqqq` is a valid pattern, so it is caught by
the round trip rather than as bad syntax, and one test still quoted the old wording of the per-row
message. One panel mutation also survived first — the test input `${recordBusinessDateFormat}`
contains neither an uppercase `Y` nor an `M`, so it could not exercise the variable guard at all.
Replaced with `${MY_DATE_MASK}`, which does.

## Not verified

The fix has not been run against the real feed. `mvn clean package` not run; the panel not opened in
a browser.
