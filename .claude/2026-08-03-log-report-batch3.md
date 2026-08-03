# Log report — Batch 3: activity timeline (SVG, brush-select, click-to-zoom)

`GET /api/logs/timeseries` plus a chart above the grid, sharing the filters and the source selector.

## Bucketing done in Java, on purpose
Counting per time bucket in SQL needs a dialect-specific date function (H2 `DATEDIFF`, SQLite
`strftime`, ...). Since H2 cannot be exercised in the chat sandbox, the query returns a bounded
`(ts, severity)` projection and the bucketing happens in Java: no dialect dependency, and the sizing
logic is unit-testable. The projection is capped at 400k rows and the response carries `truncated` so
the UI can say so rather than silently lie.

`auto` picks the smallest step keeping the chart under ~160 bars; `minute|hour|day|week` force one.
Measured: 1h -> 1m/60 bars, 6h -> 5m/72, 1d -> 15m/96, 1w -> 3h/56, 30d -> 6h/120, 90d -> 1d/90,
1y -> 1w/52. Run statuses are mapped onto the same severity palette, so the chart looks the same in
both sources.

## Chart
Stacked bars per bucket coloured by severity (FAIL at the bottom, so failures are always visible),
native `<title>` tooltips, no library and no CDN — same SVG-only approach as bpmn.js. Clicking a bar
zooms to that bucket; dragging selects a range; both write into the from/to filters and re-run the
search, so chart, grid and filters stay one state. "Full range" clears them. A drag shorter than four
units is treated as a click, so the two gestures never fight.

## Verify
`bucketMs`/`labelFor` were compiled and executed across seven ranges (see the numbers above), and the
explicit widths return exactly 1m/1h/1d/1w. logs.html passes node --check with zero literal \n/\r and
no unsafe Thymeleaf. The chart itself could not be rendered here (no browser): after deploying, open
/logs — an empty chart with a populated grid would point at the timeseries call, which the footer index
counter helps separate from an empty index.
