# Operations overview: "On hold" column in the by-source rollup table

The by-source table showed Not run / Running / Success / Failed / Aborted / Other but
NOT On hold, while bucketFor maps ON_HOLD to the `onhold` bucket. So on-hold feeds were
counted in Total but not shown in any column -> the row columns didn't add up to Total.

Fix: added an "On hold" column (after Aborted, before Other), cell('onhold', ...),
clickable to drill into on-hold feeds for that source. Now
notRun+running+success+failed+aborted+onhold+other = total. The On hold rollup tile and
the per-source weather icon already existed; this makes the table reconcile.

overview.html only. Verified node --check clean; no literal \n/\r; template -> rebuild
the WAR on deploy.
