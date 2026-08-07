package com.legalarchive.orchestrator.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a .docx from the Markdown our report generators already produce.
 *
 * <p>Deliberately hand-rolled OOXML over {@code java.util.zip} rather than POI/XWPF, even though POI
 * is already a dependency. Two reasons. First, no new dependency and no new transitive to clear on
 * the internal Nexus - {@code poi-ooxml-lite} carries only part of the wordprocessingml schemas and
 * a missing one surfaces as a NoClassDefFoundError at runtime, in production, not at build time.
 * Second, and decisively: this class is plain JDK, so it compiles and RUNS on its own and the file it
 * produces can be unzipped and checked. A POI-based version could not be verified before deploy.</p>
 *
 * <p>It is a renderer for OUR Markdown, not a general converter. It handles what the audit report and
 * the sqlreport emit: ATX headings, paragraphs, pipe tables, fenced code blocks, horizontal rules,
 * and inline {@code **bold**} and {@code `code`}. Anything else is written as plain text rather than
 * dropped - an evidence document must never silently lose a line.</p>
 */
public final class DocxWriter {

    private DocxWriter() { }

    private static final String LF = String.valueOf((char) 10);

    // ------------------------------------------------------------------ public API

    /** Renders Markdown to the bytes of a .docx package. */
    public static byte[] fromMarkdown(String markdown) throws IOException {
        String body = body(markdown == null ? "" : markdown);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", RELS);
            put(zip, "word/_rels/document.xml.rels", DOC_RELS);
            put(zip, "word/styles.xml", STYLES);
            put(zip, "word/document.xml", DOC_OPEN + body + DOC_CLOSE);
        } finally {
            zip.close();
        }
        return out.toByteArray();
    }

    /** {@code x/y/report.md} -> {@code x/y/report.docx}; a path with no extension just gains one. */
    public static String docxPathFor(String mdPath) {
        if (mdPath == null || mdPath.trim().isEmpty()) return "report.docx";
        String p = mdPath.trim();
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        int dot = p.lastIndexOf('.');
        if (dot > slash) return p.substring(0, dot) + ".docx";
        return p + ".docx";
    }

    // ------------------------------------------------------------------ markdown -> wordprocessingml

    static String body(String md) {
        StringBuilder sb = new StringBuilder();
        String[] lines = md.replace(String.valueOf((char) 13), "").split(String.valueOf((char) 10), -1);
        int i = 0;
        while (i < lines.length) {
            String ln = lines[i];
            String t = ln.trim();

            if (t.startsWith("```")) {                       // fenced code block
                i++;
                List<String> code = new ArrayList<String>();
                while (i < lines.length && !lines[i].trim().startsWith("```")) { code.add(lines[i]); i++; }
                if (i < lines.length) i++;                    // closing fence
                for (String c : code) sb.append(para("Code", runs(c, true)));
                if (code.isEmpty()) sb.append(para("Code", runs("", true)));
                continue;
            }
            if (t.isEmpty()) { i++; continue; }               // blank lines: Word spaces paragraphs itself
            if (t.matches("-{3,}|\\*{3,}|_{3,}")) { sb.append(rule()); i++; continue; }

            if (isTableRow(t) && i + 1 < lines.length && isTableSeparator(lines[i + 1].trim())) {
                List<String[]> rows = new ArrayList<String[]>();
                rows.add(cells(t));
                i += 2;                                       // header + separator
                while (i < lines.length && isTableRow(lines[i].trim())) { rows.add(cells(lines[i].trim())); i++; }
                sb.append(table(rows));
                sb.append(para(null, runs("", false)));       // Word needs a paragraph after a table
                continue;
            }

            int h = 0;
            while (h < t.length() && h < 6 && t.charAt(h) == '#') h++;
            if (h > 0 && h < t.length() && t.charAt(h) == ' ') {
                sb.append(para("Heading" + Math.min(h, 3), runs(t.substring(h + 1).trim(), false)));
                i++;
                continue;
            }
            sb.append(para(null, runs(t, false)));
            i++;
        }
        if (sb.length() == 0) sb.append(para(null, runs("", false)));
        return sb.toString();
    }

    static boolean isTableRow(String t) { return t.startsWith("|") && t.length() > 1; }

    static boolean isTableSeparator(String t) {
        if (!isTableRow(t)) return false;
        for (String c : cells(t)) {
            String x = c.trim();
            if (x.isEmpty()) continue;
            if (!x.matches(":?-{1,}:?")) return false;
        }
        return true;
    }

    /** Splits a pipe row, honouring the {@code \|} escape our generators emit for values. */
    static String[] cells(String row) {
        String t = row.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') { cur.append('|'); i++; continue; }
            if (c == '|') { out.add(cur.toString().trim()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    /** Inline {@code **bold**} and {@code `code`} become separate runs; everything else is literal. */
    static String runs(String text, boolean mono) {
        if (text == null) text = "";
        StringBuilder sb = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            if (!mono && i + 1 < n && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > 0) {
                    flush(sb, plain, mono, false, false);
                    sb.append(run(text.substring(i + 2, end), mono, true, false));
                    i = end + 2;
                    continue;
                }
            }
            if (!mono && text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > 0) {
                    flush(sb, plain, mono, false, false);
                    sb.append(run(text.substring(i + 1, end), true, false, false));
                    i = end + 1;
                    continue;
                }
            }
            plain.append(text.charAt(i));
            i++;
        }
        flush(sb, plain, mono, false, false);
        if (sb.length() == 0) sb.append(run("", mono, false, false));
        return sb.toString();
    }

    private static void flush(StringBuilder sb, StringBuilder plain, boolean mono, boolean bold, boolean unused) {
        if (plain.length() == 0) return;
        sb.append(run(plain.toString(), mono, bold, false));
        plain.setLength(0);
    }

    static String run(String text, boolean mono, boolean bold, boolean unused) {
        StringBuilder pr = new StringBuilder();
        if (bold) pr.append("<w:b/>");
        if (mono) pr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/><w:sz w:val=\"18\"/>");
        String rPr = pr.length() == 0 ? "" : ("<w:rPr>" + pr + "</w:rPr>");
        return "<w:r>" + rPr + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r>";
    }

    static String para(String style, String runsXml) {
        String pPr = style == null ? "" : ("<w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>");
        return "<w:p>" + pPr + runsXml + "</w:p>";
    }

    static String rule() {
        return "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr></w:pPr></w:p>";
    }

    static String table(List<String[]> rows) {
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        StringBuilder sb = new StringBuilder();
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>");
        for (String side : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
            sb.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:color=\"BBBBBB\"/>");
        }
        sb.append("</w:tblBorders></w:tblPr>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<w:tr>");
            String[] row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                String v = c < row.length ? row[c] : "";
                // a w:tc MUST contain at least one w:p, so an empty cell still gets an empty paragraph
                sb.append("<w:tc><w:tcPr/>").append(para(r == 0 ? "TableHead" : null, runs(v, false))).append("</w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c == '\'') sb.append("&apos;");
            else if (c == 9 || c == 10 || c == 13) sb.append(' ');
            else if (c < 0x20) continue;                      // control chars are not legal in XML 1.0
            else sb.append(c);
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ------------------------------------------------------------------ package parts

    private static final String XMLDECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" ;

    private static final String CONTENT_TYPES = XMLDECL + LF
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "</Types>";

    private static final String RELS = XMLDECL + LF
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";

    private static final String DOC_RELS = XMLDECL + LF
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final String STYLES = XMLDECL + LF
            + "<w:styles xmlns:w=\"" + W + "\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault></w:docDefaults>"
            + style("Heading1", "heading 1", "32", true)
            + style("Heading2", "heading 2", "26", true)
            + style("Heading3", "heading 3", "22", true)
            + "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/>"
            + "<w:pPr><w:spacing w:after=\"0\"/></w:pPr>"
            + "<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/><w:sz w:val=\"18\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"TableHead\"><w:name w:val=\"Table Head\"/>"
            + "<w:rPr><w:b/></w:rPr></w:style>"
            + "</w:styles>";

    private static String style(String id, String name, String halfPt, boolean bold) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"" + id + "\"><w:name w:val=\"" + name + "\"/>"
                + "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"80\"/></w:pPr>"
                + "<w:rPr>" + (bold ? "<w:b/>" : "") + "<w:sz w:val=\"" + halfPt + "\"/></w:rPr></w:style>";
    }

    private static final String DOC_OPEN = XMLDECL + LF + "<w:document xmlns:w=\"" + W + "\"><w:body>";
    private static final String DOC_CLOSE = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
            + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>";
}
