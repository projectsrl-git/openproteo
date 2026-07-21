# Wrap ';'-separated lists (with sum) in OUTPUT DATA and the run Variables

Long ';'-separated values (per-file row counts like csvRowCounts, or path lists like
csvFiles) were shown on one line and were unreadable. Now they wrap, in two places:

Operations overview OUTPUT DATA (odItemsHtml): each output variable on its own line;
a ';'-list of 2+ tokens becomes a block with the label, "Sigma <total>" (sum of the
numeric tokens, thousands-grouped) + "(N values)", and each value on its own line in a
scrollable box.

Run page Variables (run.html varValHtml): each ${var} whose value is a ';'-list of 2+
tokens renders the same way -- a "Sigma <total>" (numeric lists only) + "(N values)"
header and each value on its own line; scalars are unchanged. Path lists (csvFiles)
wrap without a sum.

Helpers fmtNum/isNumList shared conceptually in both templates. overview.html + run.html.

Verify: both pass node --check; no literal \n/\r; logic exercised in Node (numeric list ->
sum + lines; path list -> wraps no sum; scalar untouched). Templates -> rebuild the WAR.
