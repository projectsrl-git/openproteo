# Apache POI — dependency resolution (xlsx2csv Batch 1, Step 0)

**Do this BEFORE writing/merging the executor.** Unlike the H2 used by `csvsql` (pure JDBC,
runtime-only), Apache POI is a **compile-time** dependency: the `xlsx/` reader imports
`org.apache.poi.*`, so the module will not build until POI **and all its transitives** resolve on
the corporate Nexus. Gate the whole batch on the `PoiProbe` link test below.

**Not verifiable in this sandbox**: Maven Central / Nexus are unreachable here, so the resolution
and the probe must run on the UBS/Nexus side. (In this repo the executor was developed and
compiled against small hand-written POI stubs, exactly as Spring/Jackson/jt400 are; the real build
uses the real jars.)

POI = groupId `org.apache.poi`. License **Apache-2.0** (internal license review may still apply).

## 1) Pin the version (POI is NOT managed by the Spring Boot BOM)
```
<dependency><groupId>org.apache.poi</groupId><artifactId>poi</artifactId><version>5.2.5</version></dependency>
<dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.2.5</version></dependency>
```
POI 5.2.x is Java-8-compatible (confirm the exact floor for the version you pin).

## 2) Confirm ALL transitives resolve on Nexus (POI's tree is large — the "anti-H2")
Run `mvn dependency:tree` and check at least:
`poi-ooxml-lite` (OOXML schemas, the heavy one), `org.apache.xmlbeans:xmlbeans`, `commons-codec`,
`org.apache.commons:commons-collections4`, `commons-io:commons-io`,
`org.apache.commons:commons-math3`, `org.apache.commons:commons-compress`,
`com.zaxxer:SparseBitSet`, `com.github.virtuald:curvesapi`, `org.apache.logging.log4j:log4j-api`.
Any that do not resolve must be vendored (no-CDN fallback, as for ARX):
```
mvn install:install-file -Dfile=<artifact>.jar -DgroupId=<g> -DartifactId=<a> -Dversion=<v> -Dpackaging=jar
```

## 3) WAR size
Keep `poi-ooxml-lite` (the default transitive); do **not** pull `poi-ooxml-full` (~15 MB) — it is
only needed for exotic features this step does not use. The lite schemas are enough to read common
workbooks but still add several MB to the otherwise-light WAR.

## 4) Link test (answers "is POI available?")
Compile and run `PoiProbe.java` with the resolved jars on the classpath:
```
javac -cp "poi-5.2.5.jar;poi-ooxml-5.2.5.jar;<transitives>" PoiProbe.java
java  -cp ".;poi-5.2.5.jar;poi-ooxml-5.2.5.jar;<transitives>" PoiProbe   &:: Windows
```
Expected: `POI OK: event rows = 3`.

## Notes
- `.xlsx` (OOXML) only in this batch; `.xls` (HSSF) is out of scope.
- The reader uses the streaming event API (`OPCPackage` + `XSSFReader` + SAX), not the in-memory
  `XSSFWorkbook`, so large workbooks read in constant memory.
