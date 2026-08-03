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

Configure the standalone run with the usual Boot switches, e.g.
`java -jar openproteo-standalone.war --server.port=8081 --openproteo.base-dir=...`.

## Verify
pom.xml validated with an XML parser (version 1.1.0, single <properties>, classifier standalone). All 17
templates pass the JS/Thymeleaf checks. The build itself could not be run here (Maven Central
unreachable from the chat sandbox): after `mvn clean package`, check that BOTH files exist in target/ and
that the plain WAR still deploys as before.
