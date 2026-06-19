# H2 — dependency resolution (csvsql Batch 1)

**Not verifiable in this sandbox**: Maven Central / Nexus are unreachable here, so the link test
must be run on the UBS/Nexus side. Steps below.

The `csvsql` executor uses H2 purely through `java.sql` and `jdbc:h2:` URLs (CSVREAD for input,
the shared CSV exporter for output). There are **no `org.h2.*` imports**, so the project compiles
and the app starts even without H2 on the classpath; only `csvsql` steps fail — with a clear
message — until the jar is present.

H2 = groupId `com.h2database`, artifact `h2`, license **EPL 1.0 / MPL 2.0** (check internal
license review if required).

## 1) Search Nexus
Search for `com.h2database:h2`. It is a very common artifact and is usually mirrored.

## 2) If present on Nexus
The version is managed by the Spring Boot 2.7 BOM, so no explicit version is needed in `pom.xml`:
```
<dependency>
  <groupId>com.h2database</groupId>
  <artifactId>h2</artifactId>
  <scope>runtime</scope>
</dependency>
```
Confirm the resolved version with `mvn dependency:tree` (expected **2.1.214**, Java-8-safe). H2 has
no transitive dependencies, so nothing else needs to resolve.

## 3) No-CDN fallback (vendoring)
Download `h2-2.1.214.jar` and install it into the local repo:
```
mvn install:install-file -Dfile=h2-2.1.214.jar ^
    -DgroupId=com.h2database -DartifactId=h2 -Dversion=2.1.214 -Dpackaging=jar
```

## 4) Link test (answers "is H2 available?")
Compile and run `H2Probe.java`:
```
javac H2Probe.java
java -cp .;h2-2.1.214.jar H2Probe      &:: Windows
java -cp .:h2-2.1.214.jar H2Probe      #   Linux
```
Expected: `H2 OK: CSVREAD rows = 2`.

## Notes
- Pin to a Java-8-compatible H2 (the 2.1.x line is). Do not jump to 2.2.x+ without checking the
  required Java level.
- The executor creates a temporary **file-mode** DB under `${stepDir}/_h2` and deletes it in a
  `finally` block; the preview uses an in-memory DB.
