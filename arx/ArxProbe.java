// ARX resolution probe — Batch 1 §1.
// Scopo: dimostrare che la dipendenza ARX (org.deidentifier.arx) si risolve e LINKA.
// Da eseguire LATO UBS/Nexus (qui in sandbox Maven Central non e' raggiungibile).
//
// Uso (dopo aver reso disponibile il jar ARX, vedi README_ARX.md):
//   javac -cp libarx-<ver>.jar ArxProbe.java
//   java  -cp .;libarx-<ver>.jar ArxProbe      (Windows: separatore ';')
//   java  -cp .:libarx-<ver>.jar ArxProbe      (Linux:   separatore ':')
//
// Output atteso: "ARX OK: classe Data caricata, righe handle = 2".
import org.deidentifier.arx.Data;
import org.deidentifier.arx.DataHandle;

public class ArxProbe {
    public static void main(String[] args) {
        Data data = Data.create();
        data.add("NDG", "DT_EOR");
        data.add("64157069", "2025/01/13");
        data.add("71714992", "2025/02/20");
        DataHandle h = data.getHandle();
        System.out.println("ARX OK: classe Data caricata, righe handle = " + h.getNumRows());
        System.out.println("ARX version package: " + Data.class.getPackage().getName());
    }
}
