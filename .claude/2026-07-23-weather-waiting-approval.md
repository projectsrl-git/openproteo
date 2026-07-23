# Weather icon for WAITING_APPROVAL

Splitting WAITING_APPROVAL out of the "running" bucket left weatherFor() unaware of it: feeds
paused on a manual gate matched no branch and fell through to the generic "mixed" icon,
losing the information (before the split they showed as "N running").

Added a waiting branch: 🌥️ (sun behind large cloud, U+1F325) with the tooltip
"N waiting for approval", placed after the on-hold check and before running, so the priority
is: failed > all success > all not-run > done+to-run > on hold > waiting approval > running >
aborted > mixed. The icon is deliberately close to the running one (🌤️ small cloud) but more
covered, to read as "held back".

Verify: overview.html passes node --check (no literal \n/\r, no unsafe Thymeleaf) and the real
weatherFor was exercised in Node — waiting only, waiting+running, on-hold precedence, failed
precedence, running unchanged. Template -> rebuild the WAR, then Ctrl+F5.
