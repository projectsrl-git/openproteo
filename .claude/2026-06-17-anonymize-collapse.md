# 2026-06-17 — anonymize subsections collapsible (bug fix)

User report: the anonymization step's subsections were "not clickable" / could not be
collapsed. Cause: only the MASK step had been converted to the collapsible subHead()
helper; the ANONYMIZE step (exec="anonymize") still used the plain
`<div class="subsection"><div class="sub-label">...` markup, so there was no toggle to
click. (subHead/toggleSub are top-level globals and the collapsible CSS was already present
and correct, so the mask step worked; the anonymize step simply had no toggle.)

Fix: the two anonymize subsections ("Column roles" and "Free-text editing") now use
subHead(), so they collapse/expand on click like the mask step. No CSS/JS changes needed.

designer JS OK; no \n/\r; Thymeleaf-safe; 76 classes unchanged.
