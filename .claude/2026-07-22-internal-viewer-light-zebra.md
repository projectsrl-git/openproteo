# Internal CSV viewer: fix heavy dark rows in light theme

The internal viewer's odd rows used `.vgrid-row.odd { background: var(--bg-soft, #131a27); }`,
but --bg-soft is never defined, so the fallback #131a27 (dark navy) was always used -- fine
on dark, too heavy on light. The totals row was hardcoded #1d2738 (also dark).

Added light-theme overrides in app.css:
  :root[data-theme="light"] .vgrid-row.odd    { background: #f2f5f9; }
  :root[data-theme="light"] .vgrid-row:hover  { background: #fbeecb; }
  :root[data-theme="light"] .vgrid-row.totals { background: #eaf0fb; }

app.css only (static asset in the WAR) -> rebuild the WAR and hard-refresh (Ctrl+F5) to
bust the cached CSS.
