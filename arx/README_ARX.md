# ARX — risoluzione dipendenza (Batch 1 §1)

**NON verificabile in sandbox**: Maven Central non e' raggiungibile da qui, quindi la
prova di link va eseguita sul lato UBS/Nexus. Sotto i passi esatti.

ARX core = groupId `org.deidentifier`, artifact tipicamente `libarx`, licenza **Apache 2.0**
(valutare se serve license review interna).

## 1) Cercare su Nexus
Cercare gli artifact: `deidentifier`, `arx`, `libarx`. Storicamente ARX non si risolve con
una coordinata Maven Central pulita.

## 2) Se presente su Nexus
Aggiungere la dipendenza al pom (coordinate da confermare con quanto trovato), es.:
```
<dependency>
  <groupId>org.deidentifier</groupId>
  <artifactId>libarx</artifactId>
  <version>3.9.1</version>
</dependency>
```
Verificare anche le transitive (almeno **Colt**, **HPPC**, ed eventuale json): devono
risolversi su Nexus, altrimenti vanno vendorizzate.

## 3) Fallback no-CDN (vendoring, come Papa.parse)
Scaricare il jar ARX (sito ufficiale arx.deidentifier.org / GitHub releases) e installarlo
nel repo locale:
```
mvn install:install-file -Dfile=libarx-3.9.1.jar ^
    -DgroupId=org.deidentifier -DartifactId=libarx -Dversion=3.9.1 -Dpackaging=jar
```
(idem per eventuali transitive non risolte).

## 4) Prova di link (rispondere a "ARX e' disponibile?")
Compilare ed eseguire `ArxProbe.java`:
```
javac -cp libarx-3.9.1.jar ArxProbe.java
java  -cp .;libarx-3.9.1.jar ArxProbe      &:: Windows
```
Atteso: `ARX OK: classe Data caricata, righe handle = 2`.

> Solo il **core (libarx) server-side**, nessun componente GUI.

Comunicare a Fabiano l'esito (versione trovata, coordinate, transitive) prima del Batch 2.
