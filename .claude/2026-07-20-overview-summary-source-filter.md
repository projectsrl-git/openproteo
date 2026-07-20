# Operations overview: SOURCE filter on the summary/rollup

Adds a source filter directly on the summary (the "By source" panel), so the
dashboard can be limited to only some sources.

## Behaviour
- A "Sources: all v" checkbox dropdown in the rollup panel header (All sources +
  one entry per distinct source; empty selection = all).
- Selecting sources filters the WHOLE summary: the status tiles (Feeds, Success,
  Failed, On hold, ...) and the by-source table recount only the selected sources.
- For consistency it also drives the drill grid (dashFeeds() feeds both rollup and
  renderDrill), so drilling into a tile shows the same subset. The in-drill
  source/target filters still work as an additional narrowing (AND).
- Panel closes on outside click; the button shows "N selected".

## Implementation (overview.html only)
dashSrc {} (empty=all), dashFeeds() filters FEEDS by source; renderRollup uses
rollup(dashFeeds()); renderDrill bases on dashFeeds().filter(...). Reuses the .msf
dropdown styling. Verify: node --check clean; no literal \n/\r; dashFeeds logic
exercised in Node. overview.html is a template -> rebuild the WAR on deploy.
