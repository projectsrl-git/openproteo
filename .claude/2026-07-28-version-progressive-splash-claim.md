# Build version fix (commit was missing), automatic progressive, startup splash, new claim

## 1. Why the commit was missing
spring-boot-starter-parent 2.7.x sets `resource.delimiter` to `@` and configures
maven-resources-plugin with `useDefaultDelimiters=false` (verified in the spring-boot sources for
v2.7.18). So in a filtered resource ONLY `@token@` is substituted: `@project.version@` worked, while
`${git.commit}` and `${maven.build.timestamp}` were left verbatim and the endpoint (correctly)
blanked them. build-info.properties now uses `@...@` for every placeholder.

## 2. Automatic progressive
`build.number` = `git rev-list --count HEAD`, i.e. the commit count: monotonic, automatic, no state
file and no new Maven plugin. The deploy .bat passes `-Dbuild.number=<count>` together with
`-Dgit.commit=<short HEAD>` on the post-commit rebuild. The UI shows `v<version>.<build> · <commit>`,
e.g. `v1.0.0.148 · 32a1a78`; version, build, commit and build time are in the tooltip.
buildInfo() also falls back to the last-modified time of build-info.properties when the timestamp is
unavailable, and blanks anything left unfiltered, so a plain `mvn package` still shows `v1.0.0`.

## 3. Startup splash
theme.js mountSplash() shows a full-screen splash (logo, wordmark, claim, version) the FIRST time a
tab session hits the app: guarded by sessionStorage `op-splash`, it fades itself out after 2s and
closes on any click or key press, with a 6s hard safety net so it can never trap the UI. It honours
prefers-reduced-motion and is skipped entirely if storage is blocked. Styles (.op-splash*) in
app.css, including a light-theme variant. No literal \n/\r in the JS.

## 4. Claim
The dashboard topbar now reads "Pipeline Workflow Orchestrator" instead of
"Legal Archive · feed preparation & delivery"; the USAGE.md opening line was aligned.

## Note on <resources>
Our explicit <resources> block replaces the parent's, so application*.properties is no longer
filtered. Checked: application.properties contains no `@` placeholder, so nothing depends on it.

## Verify
buildInfo() was compiled and executed in three scenarios: filtered (label `1.0.0.148 · 32a1a78`),
plain build (version only, build time from the file mtime), fully unfiltered (everything blank, badge
hidden). pom.xml validated with an XML parser: well-formed, a single <properties>, and no `--` inside
comments (which is what broke the previous attempt). theme.js passes node --check with zero literal
\n/\r. Java not compiled as a whole here -> Maven build on deploy.
