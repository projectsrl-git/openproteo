# 2026-06-17 — disambiguate collapse vs move icons

User feedback: the collapse/expand icon was too small and looked identical to the step
move icons (collapse ▾ vs move ▲ / ▼ — all triangles), causing confusion. Also confirmed
the mask step collapse has no structural bug (div balance verified = 0 once subHead's
emitted divs are counted); the carets were just hard to notice.

Changes (designer):
* Step-card collapse toggle (.nc-toggle): now a bold +/- glyph (- expanded, + collapsed),
  bigger (18px), accent-colored; rotate removed. toggleNode swaps the glyph.
* Subsection carets (mask + anonymize, via subHead/toggleSub): now a boxed +/- glyph
  instead of a tiny ▾; toggleSub swaps +/-; rotate removed.
* Move buttons: ▲/▼ replaced with ↑/↓ (\u2191 / \u2193) so "move" reads clearly and is
  distinct from the +/- collapse controls.

The "Workflow files" section header keeps its own ▾/▸ caret (separate control, not near the
move buttons). designer JS OK; no \n/\r; Thymeleaf-safe; no Java change (76 classes).
