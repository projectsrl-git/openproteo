# Show the build version (pom version + git commit) in OpenProteo

Goal: see at a glance which build is running. The classic approach, Nexus-safe (no new Maven
plugin): Maven filters a tiny properties file with the pom version and a commit hash passed on the
command line; an endpoint reads it; the topbar shows a small badge.

## How the commit reaches the WAR
- `src/main/resources/build-info.properties` holds three placeholders:
  `build.version=@project.version@`, `build.commit=${git.commit}`, `build.time=${maven.build.timestamp}`.
  (`@...@` is used for the version so it does not clash with the `${...}` build properties.)
- pom.xml filters ONLY that file (an explicit <resources> block: one filtered resource limited to
  build-info.properties, one unfiltered for everything else, so no binary/static asset is touched).
  A default property `git.commit=unknown` and a build-timestamp format are declared.
- The deploy .bat builds once to verify, commits/pushes, then REBUILDS the WAR passing
  `-Dgit.commit=<short hash>` (the commit exists only after the commit step), so the filtered file
  inside the WAR carries the real hash. Without the .bat (plain `mvn package`) the version still
  shows; the commit is blank (property stays "unknown", which the endpoint blanks out).

## Serving it
- ApiController.buildInfo() loads /build-info.properties once (cached), blanking any raw
  placeholder left by an unfiltered/dev build, and exposes version/commit/shortCommit/buildTime.
  New endpoint GET /api/version; the same map is also folded into /api/env.
- theme.js mountVersion() fetches /api/version and adds a small `.ver-badge` to the topbar next to
  the clock: "v1.0.0 · <short>", with the full version/commit/build-time in the tooltip. app.css
  styles it (muted, with a light-theme override). No literal newlines in the JS.

## Verify
buildInfo() was compiled and executed against a sample build-info.properties -> it returns
{version, commit, shortCommit, buildTime} with a 7-char short commit. theme.js passes node --check
with no literal \n/\r; pom.xml is valid XML; the .bat stays CRLF and injects `git rev-parse --short
HEAD` via -Dgit.commit. Java not compiled as a whole here -> Maven build on deploy.
