// Apache POI resolution / link probe — xlsx2csv Batch 1, Step 0.
// Purpose: prove that POI AND its (large) transitive tree resolve and LINK on the corporate Nexus,
// and that the streaming event API works. POI is a COMPILE-TIME dependency of the xlsx2csv reader,
// so the module will NOT build until this passes. Run on the UBS/Nexus side.
//
// Usage (after the poi + poi-ooxml jars and transitives are available):
//   javac -cp "poi-5.2.5.jar;poi-ooxml-5.2.5.jar;<all transitives>" PoiProbe.java
//   java  -cp ".;poi-5.2.5.jar;poi-ooxml-5.2.5.jar;<all transitives>" PoiProbe   (Windows ';')
//   java  -cp ".:poi-5.2.5.jar:poi-ooxml-5.2.5.jar:<all transitives>" PoiProbe   (Linux  ':')
//
// Expected output: "POI OK: event rows = 3" (1 header + 2 data rows).
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Iterator;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;     // write side (usermodel) — probe only
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;

import org.apache.poi.openxml4j.opc.OPCPackage;          // read side (event API) — what xlsx2csv uses
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.XSSFReader;

import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.Attributes;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.SAXParser;

public class PoiProbe {
    public static void main(String[] args) throws Exception {
        // 1) write a tiny 2-data-row workbook with POI usermodel
        File f = File.createTempFile("poiprobe", ".xlsx");
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sh = wb.createSheet("Anagrafica");
        String[][] data = { {"NDG", "NOME"}, {"64157069", "Mario"}, {"71714992", "Anna"} };
        for (int r = 0; r < data.length; r++) {
            Row row = sh.createRow(r);
            for (int c = 0; c < data[r].length; c++) row.createCell(c).setCellValue(data[r][c]);
        }
        FileOutputStream os = new FileOutputStream(f);
        wb.write(os); os.close(); wb.close();

        // 2) read it back via the streaming event API (OPCPackage + XSSFReader + SAX)
        OPCPackage pkg = OPCPackage.open(f, PackageAccess.READ);
        XSSFReader reader = new XSSFReader(pkg);
        Iterator<InputStream> sheets = reader.getSheetsData();
        InputStream sheet = sheets.next();
        final int[] rows = {0};
        SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
        parser.parse(sheet, new DefaultHandler() {
            public void startElement(String uri, String ln, String qn, Attributes a) {
                if ("row".equals(qn)) rows[0]++;
            }
        });
        sheet.close(); pkg.close(); f.delete();

        System.out.println("POI OK: event rows = " + rows[0]);
        if (rows[0] != 3) { System.out.println("UNEXPECTED row count"); System.exit(1); }
    }
}
