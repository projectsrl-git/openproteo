# Operations promoted to the topbar; build produces a standalone artifact too

## 1. Operations everywhere
It was a small button buried in the dashboard toolbar. It is now a filled button in the **topbar of
every page** (16 templates; overview itself excluded), so the most used view is one click away from
wherever you are.

It uses a dedicated `.btn.ops` class, NOT `.btn.primary`: that class already styles other buttons across
the app (Validate & save, ...) and redefining it would have silently restyled all of them.

## 2. One build, two artifacts
    target/openproteo.war              plain WAR - unchanged, this is what you deploy on Tomcat
    target/openproteo-standalone.war   executable, embedded Tomcat:  java -jar openproteo-standalone.war
The Spring Boot repackage now runs with `<classifier>standalone</classifier>`, so it writes a SECOND
file and never touches the artifact you deploy - the deployable WAR is byte-for-byte what it was.
`spring-boot-starter-tomcat` is already `provided`, so Boot puts it in `WEB-INF/lib-provided`: on the
classpath when the file is run directly, ignored when a container deploys it. `<attach>false</attach>`
keeps it out of the Maven repository on install.

Honest note on the extension: with `<packaging>war</packaging>` Maven cannot emit a `.jar`. The
standalone artifact is a `.war` file that runs exactly like a fat jar (`java -jar`); renaming it to
`.jar` also works, since the JVM only looks at the manifest. A true `.jar` artifact would need a second
Maven module, which is a bigger change - say the word if the extension itself is a requirement.

### Running the standalone artifact

Only a JVM (8 or newer) is required: every dependency is inside the file - Spring, Thymeleaf, the JTOpen
JDBC driver, POI and H2 - and the embedded Tomcat comes from `WEB-INF/lib-provided`. No Tomcat install,
no drivers to copy, no CATALINA_HOME.

    java -jar openproteo-standalone.war ^
      --server.port=8081 ^
      --orchestrator.default-base-dir=G:/Phoenix/openproteo/feeds ^
      --orchestrator.workflows-dir=G:/Phoenix/openproteo/workflows ^
      --server.servlet.context-path=/openproteo

(`^` continues the line on Windows cmd; use `\` on bash, or put it all on one line.)

**Always be explicit about the directories.** Without arguments the bundled application.properties applies
and every path is RELATIVE TO THE WORKING DIRECTORY, not to the file:

| setting | default | resolved against |
|---|---|---|
| `server.port` | 8080 | - |
| `orchestrator.workflows-dir` | `./workflows` | current directory |
| `orchestrator.default-base-dir` | `./feeds` | current directory |
| `orchestrator.scripts-dir` | `./scripts` | current directory |
| `orchestrator.shared-dir` | `./shared` | current directory |
| `orchestrator.datasources-file` | `./datasources.json` | current directory |
| `logging.file.name` | `./logs/openproteo.log` | current directory |

So launching it from an empty directory starts a perfectly working instance with NO feeds - it looks
fine, it is simply a different installation. The opposite is the real risk: launching it inside the
production directory makes that standalone instance operate on live data.

The tidy alternative to a long command line is an `application.properties` next to the artifact, in the
directory you launch from: Boot loads it and it takes precedence over the bundled one, so each
environment keeps its own configuration without rebuilding. It is also where
`orchestrator.masking-secret` belongs - never in the repository.

Two differences from the Tomcat deployment worth remembering: standalone starts on `/`, not on
`/openproteo` (hence `--server.servlet.context-path`), and there is no IIS in front, so the
certificate-based authentication planned for Phase 2 will need a different arrangement in this scenario.

### The build script checks both

`scripts/build_openproteo.sh` fails with an explicit message if the standalone artifact is missing (the
usual cause being the repackage configuration having been changed) and prints both paths with their
sizes, so a build that silently produced only the WAR cannot go unnoticed. While fixing that, a latent
bug surfaced: the summary still referenced `$REPO`, a variable the project-resolution fix had removed, so
under `set -u` the script would have aborted on its very last line.

## Verify
pom.xml validated with an XML parser (version 1.1.0, single <properties>, classifier standalone). All 17
templates pass the JS/Thymeleaf checks. The build itself could not be run here (Maven Central
unreachable from the chat sandbox): after `mvn clean package`, check that BOTH files exist in target/ and
that the plain WAR still deploys as before.
