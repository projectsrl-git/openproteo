package com.legalarchive.orchestrator.xlsx;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * Streaming reader for one sheet of an .xlsx workbook, using the POI event API
 * (OPCPackage + XSSFReader + SAX) so large workbooks read in constant memory.
 *
 * <p>This is the only class importing {@code org.apache.poi.*}. POI is a compile-time dependency:
 * see {@code xlsx/README_POI.md}. Cells are rendered to text deterministically (NOT as Excel
 * displays them) per the xlsx2csv contract:
 * <ul>
 *   <li>shared / inline / formula strings: verbatim text</li>
 *   <li>boolean: {@code TRUE}/{@code FALSE}; error: empty</li>
 *   <li>date-styled number: {@code DateUtil.getJavaDate} formatted with {@code dateFormat} (default {@code yyyyMMdd})</li>
 *   <li>plain number: {@code BigDecimal.stripTrailingZeros().toPlainString()} (no exponent, '.' decimal)</li>
 *   <li>empty/missing: empty field (column position preserved)</li>
 * </ul>
 */
public class XlsxSheetReader {

    /** Receives each sheet row in order. {@code cells} is 0-based by column, padded to the last present cell. */
    public interface RowSink {
        void row(int rowNum, List<String> cells) throws Exception;
    }

    /** Column projection plan: source indices to pull and the output header names. */
    public static class Plan {
        public final int[] idx;
        public final String[] out;
        public Plan(int[] idx, String[] out) { this.idx = idx; this.out = out; }
    }

    /**
     * Build a projection plan from the sheet's header row and the selected columns. When {@code srcs}
     * is null/empty, every column is kept using the header names. {@code selectBy="letter"} resolves
     * each {@code src} as a column letter (A, AB, ...); otherwise as a header name (exact, then
     * case-insensitive). Throws if a header-name column cannot be found.
     */
    public static Plan plan(List<String> header, List<String> srcs, List<String> ases, String selectBy) {
        boolean letter = "letter".equalsIgnoreCase(selectBy);
        if (srcs == null || srcs.isEmpty()) {
            int n = header.size();
            int[] idx = new int[n]; String[] out = new String[n];
            for (int i = 0; i < n; i++) { idx[i] = i; out[i] = header.get(i) == null ? "" : header.get(i); }
            return new Plan(idx, out);
        }
        int n = srcs.size();
        int[] idx = new int[n]; String[] out = new String[n];
        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            String src = srcs.get(i);
            String as = (ases != null && i < ases.size()) ? ases.get(i) : null;
            int ci;
            if (letter) ci = columnLetterToIndex(src);
            else { ci = headerIndex(header, src); if (ci < 0) missing.add(src); }
            idx[i] = ci;
            out[i] = (as != null && !as.trim().isEmpty()) ? as : src;
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("columns not found in header row: " + join(missing));
        return new Plan(idx, out);
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append(", "); sb.append(xs.get(i)); }
        return sb.toString();
    }

    private static int headerIndex(List<String> header, String name) {
        if (name == null) return -1;
        for (int i = 0; i < header.size(); i++) if (name.equals(header.get(i))) return i;
        String t = name.trim();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h != null && h.trim().equalsIgnoreCase(t)) return i;
        }
        return -1;
    }

    /** Column index (0-based) from a column letter like "A" -> 0, "AB" -> 27. */
    public static int columnLetterToIndex(String s) {
        if (s == null) return -1;
        int col = 0; boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c >= 'A' && c <= 'Z') { col = col * 26 + (c - 'A' + 1); any = true; }
            else break;
        }
        return any ? col - 1 : -1;
    }

    /** Sheet names in workbook order. */
    public static List<String> sheetNames(File xlsx) throws Exception {
        OPCPackage pkg = null;
        try {
            pkg = OPCPackage.open(xlsx, PackageAccess.READ);
            XSSFReader r = new XSSFReader(pkg);
            List<String> names = new ArrayList<String>();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) r.getSheetsData();
            while (it.hasNext()) {
                InputStream is = it.next();
                names.add(it.getSheetName());
                try { is.close(); } catch (Exception ignore) {}
            }
            return names;
        } finally {
            if (pkg != null) try { pkg.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * Stream the chosen sheet. Selection: by {@code sheetName} when non-blank, else by 0-based
     * {@code sheetIndex}. Throws if the sheet cannot be found.
     */
    public static void read(File xlsx, String sheetName, int sheetIndex,
                            String dateFormat, boolean rawValues, RowSink sink) throws Exception {
        OPCPackage pkg = null;
        InputStream sheet = null;
        try {
            pkg = OPCPackage.open(xlsx, PackageAccess.READ);
            ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);
            XSSFReader r = new XSSFReader(pkg);
            StylesTable styles = r.getStylesTable();
            boolean is1904 = readDate1904(r);

            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) r.getSheetsData();
            int idx = 0;
            while (it.hasNext()) {
                InputStream is = it.next();
                boolean match = (sheetName != null && !sheetName.trim().isEmpty())
                        ? sheetName.equals(it.getSheetName())
                        : idx == sheetIndex;
                if (match) { sheet = is; break; }
                try { is.close(); } catch (Exception ignore) {}
                idx++;
            }
            if (sheet == null) {
                throw new IllegalArgumentException("sheet not found: "
                        + ((sheetName != null && !sheetName.trim().isEmpty()) ? sheetName : ("#" + sheetIndex)));
            }

            String df = (dateFormat == null || dateFormat.trim().isEmpty()) ? "yyyyMMdd" : dateFormat.trim();
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            XMLReader xr = f.newSAXParser().getXMLReader();
            xr.setContentHandler(new SheetHandler(sst, styles, is1904, df, rawValues, sink));
            xr.parse(new InputSource(sheet));
        } finally {
            if (sheet != null) try { sheet.close(); } catch (Exception ignore) {}
            if (pkg != null) try { pkg.close(); } catch (Exception ignore) {}
        }
    }

    /** Read the date1904 flag from xl/workbook.xml (defaults to false). */
    private static boolean readDate1904(XSSFReader r) {
        InputStream wb = null;
        try {
            wb = r.getWorkbookData();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = wb.read(buf)) > 0) bo.write(buf, 0, n);
            String xml = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            int i = xml.indexOf("date1904");
            if (i < 0) return false;
            int eq = xml.indexOf('=', i);
            if (eq < 0) return false;
            String tail = xml.substring(eq + 1, Math.min(eq + 8, xml.length()));
            return tail.contains("1") || tail.toLowerCase(java.util.Locale.ROOT).contains("true");
        } catch (Exception e) {
            return false;
        } finally {
            if (wb != null) try { wb.close(); } catch (Exception ignore) {}
        }
    }

    /** SAX handler implementing the §3 cell contract. */
    private static class SheetHandler extends DefaultHandler {
        private final ReadOnlySharedStringsTable sst;
        private final StylesTable styles;
        private final boolean is1904;
        private final SimpleDateFormat sdf;
        private final boolean rawValues;
        private final RowSink sink;

        private List<String> row;
        private int rowNum;
        private int curCol = -1;
        private String cellType = "";
        private int styleIndex = -1;
        private boolean capture = false;
        private final StringBuilder val = new StringBuilder();
        private Exception failure;

        SheetHandler(ReadOnlySharedStringsTable sst, StylesTable styles, boolean is1904,
                     String dateFormat, boolean rawValues, RowSink sink) {
            this.sst = sst; this.styles = styles; this.is1904 = is1904; this.rawValues = rawValues; this.sink = sink;
            SimpleDateFormat s;
            try { s = new SimpleDateFormat(dateFormat); } catch (Exception e) { s = new SimpleDateFormat("yyyyMMdd"); }
            s.setTimeZone(TimeZone.getTimeZone("UTC"));
            this.sdf = s;
        }

        public void startElement(String uri, String ln, String qn, Attributes a) {
            if ("row".equals(qn)) {
                row = new ArrayList<String>();
                rowNum = parseInt(a.getValue("r"), -1);
            } else if ("c".equals(qn)) {
                curCol = colFromRef(a.getValue("r"));
                cellType = a.getValue("t"); if (cellType == null) cellType = "";
                styleIndex = parseInt(a.getValue("s"), -1);
                val.setLength(0);
            } else if ("v".equals(qn)) {
                capture = true; val.setLength(0);
            } else if ("t".equals(qn) && "inlineStr".equals(cellType)) {
                capture = true;   // do NOT reset: an inline string may have several <t> runs
            }
        }

        public void characters(char[] ch, int start, int length) {
            if (capture) val.append(ch, start, length);
        }

        public void endElement(String uri, String ln, String qn) {
            if (failure != null) return;
            if ("v".equals(qn)) {
                capture = false;
            } else if ("t".equals(qn) && "inlineStr".equals(cellType)) {
                capture = false;
            } else if ("c".equals(qn)) {
                String text = decode(val.toString());
                if (curCol >= 0) {
                    while (row.size() <= curCol) row.add("");
                    row.set(curCol, text);
                }
            } else if ("row".equals(qn)) {
                try { sink.row(rowNum, row); } catch (Exception e) { failure = e; }
            }
        }

        public void endDocument() throws org.xml.sax.SAXException {
            if (failure != null) throw new org.xml.sax.SAXException(failure);
        }

        private String decode(String raw) {
            if ("s".equals(cellType)) {
                try { return str(sst.getItemAt(Integer.parseInt(raw.trim()))); } catch (Exception e) { return ""; }
            }
            if ("inlineStr".equals(cellType)) return raw;
            if ("str".equals(cellType)) return raw;          // formula cached string
            if ("b".equals(cellType)) return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
            if ("e".equals(cellType)) return "";             // error -> empty
            if (raw == null || raw.isEmpty()) return "";
            if (rawValues) return raw;
            // number: date-styled or plain
            try {
                if (isDateStyle()) {
                    double serial = Double.parseDouble(raw);
                    Date d = DateUtil.getJavaDate(serial, is1904);
                    return sdf.format(d);
                }
                return BigDecimal.valueOf(Double.parseDouble(raw)).stripTrailingZeros().toPlainString();
            } catch (Exception e) {
                return raw;
            }
        }

        private boolean isDateStyle() {
            if (styleIndex < 0) return false;
            try {
                XSSFCellStyle cs = styles.getStyleAt(styleIndex);
                if (cs == null) return false;
                int fmtId = cs.getDataFormat();
                String fmt = cs.getDataFormatString();
                return DateUtil.isADateFormat(fmtId, fmt);
            } catch (Exception e) {
                return false;
            }
        }

        private static String str(Object rich) {
            if (rich == null) return "";
            if (rich instanceof org.apache.poi.ss.usermodel.RichTextString) {
                String s = ((org.apache.poi.ss.usermodel.RichTextString) rich).getString();
                return s == null ? "" : s;
            }
            return rich.toString();
        }

        private static int parseInt(String s, int def) {
            if (s == null) return def;
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }

        /** Column index (0-based) from a cell reference like "AB12" -> 27. */
        private static int colFromRef(String ref) {
            if (ref == null) return -1;
            int col = 0; boolean any = false;
            for (int i = 0; i < ref.length(); i++) {
                char c = ref.charAt(i);
                if (c >= 'A' && c <= 'Z') { col = col * 26 + (c - 'A' + 1); any = true; }
                else if (c >= 'a' && c <= 'z') { col = col * 26 + (c - 'a' + 1); any = true; }
                else break;
            }
            return any ? col - 1 : -1;
        }
    }
}
