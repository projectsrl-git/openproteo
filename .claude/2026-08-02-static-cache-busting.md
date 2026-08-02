# Cache-busting of the static assets with the build id

Three times in a row a deploy "changed nothing" because the browser kept serving a cached
app.css / viewer.js / theme.js: the WAR was new, the assets were not. The static URLs now carry a
per-build token, so a new build is a new URL and the browser refetches on its own.

- `config/BuildInfo` is now the single source of the build identity: it loads the filtered
  build-info.properties once, blanks unsubstituted @placeholders@, and exposes `map()` (version,
  buildNumber, commit, shortCommit, buildTime, display, label) plus `id()`, the short token used on
  the asset URLs. ApiController.buildInfo() delegates to it, so /api/version and /api/env are
  unchanged and there is only one implementation left.
- `web/BuildIdAdvice` (@ControllerAdvice scoped to PageController, which renders all 16 templates)
  publishes `${buildId}`; the API is a @RestController and is deliberately not touched.
- Every CSS/JS include in the templates became `@{/css/app.css(v=${buildId})}` and friends -- 54
  includes across 16 templates. Images were left alone: they rarely change and are also referenced
  from JS.
- docs.html fetches USAGE.md from JS, so it gets the same treatment through a
  `<meta name="op-build" th:content="${buildId}">` read by the script -- no Thymeleaf inlining
  inside JS, keeping the `[[` rule safe.

`id()` is `<buildNumber>-<shortCommit>` (e.g. `73-ed576c9`); on a build without those properties it
falls back to the build-time digits, and finally to a per-JVM token so even a dev restart refreshes.

Verify: BuildInfo was compiled and executed in the three real scenarios -- full script build
(`73-ed576c9`), plain `mvn package` (falls back to the timestamp), completely unfiltered (still a
usable token, and the version badge stays hidden). All 16 templates pass node --check with no literal
\n/\r and no unsafe Thymeleaf; ApiController's brace balance is unchanged. Java not compiled as a
whole here -> Maven build on deploy.
